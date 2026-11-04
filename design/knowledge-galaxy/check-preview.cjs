const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const runDir = path.join(__dirname, 'evidence', new Date().toISOString().replace(/[:.]/g, '-'));
  fs.mkdirSync(runDir, { recursive: true });
  const browser = await chromium.launchPersistentContext(path.join(runDir, 'browser-profile'), {
    executablePath: process.env.GALAXY_BROWSER,
    headless: true,
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce'
  });
  const page = await browser.newPage();
  const errors = [], remoteRequests = [], checks = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) remoteRequests.push(request.url()); });
  const check = (name, value) => { assert(value, name); checks.push(name); };
  try {
    await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
    await page.locator('.planet').first().waitFor();
    await page.screenshot({ path: path.join(runDir, 'desktop.png'), fullPage: true });
    check('six knowledge planets render', await page.locator('.planet').count() === 6);
    check('all knowledge notes are present in the overview', await page.locator('.graph-note').count() === 23);
    check('overview displays cross topic references', await page.locator('.knowledge-edge.cross-topic').count() > 0);
    check('displayed knowledge edges have actual source references', await page.evaluate(() => {
      const declared = new Set(GalaxyContent.notes.flatMap(note => note.relations.map(([target]) => [note.id, target].sort().join('/'))));
      const rendered = [...document.querySelectorAll('.knowledge-edge')].map(edge => [edge.dataset.from, edge.dataset.to].sort().join('/'));
      return rendered.length === declared.size && rendered.every(key => declared.has(key));
    }));
    check('theme membership uses a distinct dashed line', await page.locator('.membership-edge').count() === 23);
    check('existing sample references connect all 23 notes', await page.evaluate(() => {
      const seen = new Set([GalaxyContent.notes[0].id]); let changed = true;
      while (changed) { changed = false; GalaxyContent.notes.forEach(note => note.relations.forEach(([id]) => {
        if (seen.has(note.id) && !seen.has(id)) { seen.add(id); changed = true; }
        if (seen.has(id) && !seen.has(note.id)) { seen.add(note.id); changed = true; }
      })); }
      return seen.size === GalaxyContent.notes.length;
    }));
    check('initial note and source render', (await page.locator('#note-detail').innerText()).includes('P01'));
    check('desktop has no horizontal overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const initialPosition = await page.locator('[data-note="timeout"]').boundingBox();
    await page.locator('[data-planet="distributed"]').click();
    check('topic focus retains the whole knowledge network', await page.locator('.graph-note').count() === 23);
    check('explicit knowledge relations render', await page.locator('.knowledge-edge.active-edge').count() > 0);
    const topicPosition = await page.locator('[data-note="timeout"]').boundingBox();
    check('topic focus preserves node positions', initialPosition.x === topicPosition.x && initialPosition.y === topicPosition.y);
    check('linked project knowledge stays visible across topics', !((await page.locator('[data-note="budget"]').getAttribute('class')).includes('dimmed')));
    await page.locator('[data-related-note="budget"]').click();
    check('following a cross topic reference synchronizes map and inspector', await page.locator('[data-note="budget"]').getAttribute('aria-pressed') === 'true' && (await page.locator('.note-title').innerText()).includes('预算'));
    check('incoming references are shown in the inspector', (await page.locator('[data-related-note="award"]').innerText()).includes('被引用'));
    await page.locator('[data-related-note="timeout"]').click();
    await page.locator('#focus-neighbors').click();
    check('neighbor focus dims unrelated notes', (await page.locator('[data-note="gc"]').getAttribute('class')).includes('dimmed'));
    check('neighbor focus retains all adjacent notes', await page.evaluate(() => {
      const selected = document.querySelector('.graph-note.selected').dataset.note;
      const neighbors = new Set(GalaxyContent.notes.flatMap(note => note.relations.flatMap(([target]) => note.id === selected ? [target] : target === selected ? [note.id] : [])));
      return [...neighbors].every(id => !document.querySelector(`[data-note="${id}"]`).classList.contains('dimmed'));
    }));
    await page.locator('#focus-neighbors').click();
    check('focus button preserves keyboard access', await page.locator('#focus-neighbors').getAttribute('aria-pressed') === 'false');
    await page.screenshot({ path: path.join(runDir, 'topic.png'), fullPage: true });
    await page.locator('[data-note="idempotency"]').click();
    check('knowledge selection changes inspector', (await page.locator('.note-title').innerText()).includes('幂等'));
    check('exploration trail follows the current note', (await page.locator('#trail-context').innerText()).includes('幂等'));
    await page.locator('#zoom-in').click();
    check('zoom updates map', await page.locator('#zoom-value').innerText() === '110%');
    await page.locator('#reset-map').click();
    check('reset restores global overview', await page.locator('.planet').count() === 6 && await page.locator('#zoom-value').innerText() === '100%');
    await page.locator('#search-input').fill('这条知识不存在');
    check('search empty state is visible', await page.locator('#empty-map').isVisible());
    await page.locator('#clear-map-search').click();
    check('clearing search restores planets', await page.locator('.planet:not(.hidden-planet)').count() === 6);
    await page.locator('#search-input').fill('幂等');
    check('graph search highlights the matching knowledge itself', !((await page.locator('[data-note="idempotency"]').getAttribute('class')).includes('dimmed')));
    await page.locator('#search-input').fill('');
    await page.locator('#tab-list').click();
    await page.locator('#search-input').fill('幂等');
    check('list search finds relevant knowledge', await page.locator('.note-row').count() > 0 && await page.locator('.note-row').count() < 23);
    await page.locator('#search-input').fill('');
    await page.locator('#tab-list').focus();
    await page.keyboard.press('ArrowRight');
    check('tab arrow keyboard navigation works', await page.locator('#tab-recall').getAttribute('aria-selected') === 'true');
    await page.locator('#rail-galaxy').click();
    await page.locator('[data-planet="distributed"]').click();
    await page.locator('[data-note="timeout"]').click();
    await page.locator('#practice-note').click();
    check('reference is hidden before recall', !await page.locator('#reference-panel').isVisible());
    await page.locator('#practice-answer').fill('超时只表示未收到确定结果，先按原请求标识核对结果、幂等与预算契约。🙂');
    await page.keyboard.press('Escape');
    await page.locator('#practice-note').click();
    check('closing and reopening preserves answer draft', (await page.locator('#practice-answer').inputValue()).endsWith('🙂'));
    await page.locator('#reveal-reference').click();
    await page.locator('#save-recall').click();
    check('self assessment is required', (await page.locator('#practice-error').innerText()).includes('选择'));
    await page.locator('[data-rating="clear"]').click();
    await page.screenshot({ path: path.join(runDir, 'recall.png'), fullPage: true });
    await page.locator('#save-recall').click();
    check('saved recall is confirmed', await page.locator('#practice-saved').isVisible());
    check('saved recall cannot duplicate submission', await page.locator('#save-recall').isDisabled());
    check('due queue decreases after self selected interval', await page.locator('#due-count').innerText() === '2');
    await page.keyboard.press('Escape');
    await page.locator('#tab-recall').click();
    check('recall history is visible', await page.locator('.history-entry').count() === 1);
    await page.locator('#new-note').click();
    await page.locator('#note-title').fill('自己的理解 <img src=x onerror=alert(1)>');
    await page.locator('#note-body').fill('合成笔记：先解释条件，再说明例子。🙂');
    await page.locator('#note-form button[type="submit"]').click();
    check('new note appears in inspector', (await page.locator('.note-title').innerText()).includes('<img'));
    check('user content stays inert text', await page.locator('#note-detail img').count() === 0);
    check('new note updates knowledge count', await page.locator('#total-count').innerText() === '24');
    await page.locator('#toggle-bookmark').click();
    await page.locator('#sidebar-bookmarks').click();
    check('bookmarks view contains newly saved note', (await page.locator('#note-list').innerText()).includes('自己的理解'));
    await page.reload();
    check('reload restores synthetic demo only', await page.locator('#total-count').innerText() === '23' && await page.locator('#due-count').innerText() === '3');
    for (const [width, height, file] of [[1280,900,'desktop-1280.png'],[768,1024,'tablet.png'],[390,844,'mobile.png'],[360,800,'mobile-360.png']]) {
      await page.setViewportSize({ width, height });
      await page.screenshot({ path: path.join(runDir, file), fullPage: true });
      check(`${width}px viewport has no horizontal overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      check(`${width}px note centers stay inside the graph`, await page.locator('#universe').evaluate(universe => {
        const area = universe.getBoundingClientRect();
        return [...universe.querySelectorAll('.graph-note')].every(node => { const box = node.getBoundingClientRect(); const x = box.x + box.width / 2, y = box.y + box.height / 2; return x >= area.left && x <= area.right && y >= area.top && y <= area.bottom; });
      }));
    }
    await page.locator('[data-planet="distributed"]').click();
    await page.locator('[data-note="timeout"]').click();
    check('mobile note selection remains available', await page.locator('.note-title').innerText() === '超时 ≠ 失败');
    await page.locator('#practice-note').click();
    check('mobile recall dialog fits viewport', await page.locator('#practice-dialog').evaluate(node => node.getBoundingClientRect().width <= innerWidth));
    await page.keyboard.press('Escape');
    check('no runtime errors', errors.length === 0);
    check('preview makes no remote requests', remoteRequests.length === 0);
    fs.writeFileSync(path.join(runDir, 'results.json'), JSON.stringify({ passed: checks.length, checks, errors, remoteRequests }, null, 2));
    console.log(JSON.stringify({ runDir, passed: checks.length, errors, remoteRequests }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
