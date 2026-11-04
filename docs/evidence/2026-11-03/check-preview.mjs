import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createContext, runInContext, Script } from 'node:vm';

const filename = process.argv[2];
assert.ok(filename, 'Provide the absolute design preview path.');
const html = readFileSync(resolve(filename), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
assert.equal(scripts.length, 1);
const script = scripts[0];
new Script(script, { filename: 'whiteboard-preview.js' });
assert.ok(!/fetch\s*\(|XMLHttpRequest|WebSocket|localStorage|sessionStorage/.test(script));
assert.ok(!/<!doctype|<html[\s>]|<head[\s>]|<body[\s>]/i.test(html));
assert.ok(!/\\"/.test(html));
const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
assert.equal(new Set(ids).size, ids.length);

const context = createContext({});
const logic = script.slice(script.indexOf('const nodeIds'), script.indexOf('const root ='));
runInContext(`${logic}; globalThis.helpers = { normalizePreviewState, layoutPositions, connectionPath };`, context);
const { normalizePreviewState, layoutPositions, connectionPath } = context.helpers;
assert.equal(normalizePreviewState(null).editVersion, 'revised');
assert.equal(normalizePreviewState({ editVersion: 'initial', selected: 'query' }).selected, 'budget');
assert.equal(normalizePreviewState({ promptVersion: 'initial', promptNode: 'query' }).promptNode, 'budget');
assert.equal(normalizePreviewState({ selected: 'invalid', history: true }).history, false);

for (const width of [244, 288, 340, 390, 480, 700, 900, 1200]) {
  for (const version of ['initial', 'revised']) {
    const result = layoutPositions(width, version);
    const positions = Object.values(result.positions);
    assert.ok(result.height >= 330, 'SVG coordinates must match the minimum graph height.');
    assert.equal(positions.length, version === 'initial' ? 4 : 6);
    for (const position of positions) {
      assert.ok(position.width >= 100, `Node too narrow at ${width}.`);
      assert.ok(position.x >= 0 && position.x + position.width <= width, `Node outside ${width}.`);
      assert.ok(position.y + position.height <= result.height, `Node outside height at ${width}.`);
    }
    for (let i = 0; i < positions.length; i += 1) {
      for (let j = i + 1; j < positions.length; j += 1) {
        const a = positions[i], b = positions[j];
        const overlap = a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
        assert.equal(overlap, false, `Nodes overlap at ${width}.`);
      }
    }
    const edge = connectionPath(result.positions.budget, result.positions.award);
    assert.ok(edge.path.startsWith('M') && !edge.path.includes('NaN'));
  }
}

const fields = new Set([...html.matchAll(/data-field="([^"]+)"/g)].map(match => match[1]));
for (const match of script.matchAll(/field\(panel, '([^']+)'\)/g)) assert.ok(fields.has(match[1]), `Missing field ${match[1]}.`);
assert.match(script, /relations\.replaceChildren/);
assert.ok(script.includes("${state.labels ? ` · ${label}` : ''}"), 'Narrow-screen relation labels must respond to the control.');
assert.match(html, /\.wj-graph\.wj-linear \.wj-connections \{ display: none/);
assert.ok(!script.includes('state.labels && columns !== 4'));
assert.match(script, /button\.disabled = state\.history/);

console.log('PASS: preview syntax, fragment boundaries, no network/browser storage, unique IDs, state normalization, node geometry and field bindings.');
console.log('Scope: static checks only; not browser, DOM interaction or visual acceptance.');
