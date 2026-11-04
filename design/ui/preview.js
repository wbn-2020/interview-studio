(() => {
  'use strict';

  const all = (selector) => [...document.querySelectorAll(selector)];
  const one = (selector) => document.querySelector(selector);
  const setText = (selector, value) => all(selector).forEach((element) => { element.textContent = value; });
  const views = ['home', 'interview', 'board'];
  const state = { view: 'home', paused: false, reviewItems: new Map(), zoom: 1, node: 'award', diagramSave: null, saveRevision: 0 };
  const nodeQuestions = {
    entry: '同一个扫码请求重复到达时，你会在哪一层识别它？',
    eligibility: '资格校验通过后到发奖前，资格发生变化如何处理？',
    budget: '预算预占成功，但发奖结果未知时，何时可以释放预算？',
    award: '发奖超时不等于失败。你会用哪个请求号查单，何时才能重试？',
    store: '查单结果与迟到回调同时写状态时，如何避免终态被旧结果覆盖？',
    query: '查单仍返回处理中时，谁负责下一次确认，怎样避免无限重试？',
  };

  function openView(view, focus = true) {
    if (!views.includes(view)) return;
    state.view = view;
    document.body.dataset.activeView = view;
    all('[data-view]').forEach((section) => { section.hidden = section.dataset.view !== view; });
    all('[data-open]').forEach((button) => {
      const selected = button.dataset.open === view;
      if (button.closest('nav, [data-view-switcher]')) {
        button.setAttribute('aria-pressed', String(selected));
        button.classList.toggle('active', selected);
      }
    });
    const heading = one(`[data-view="${view}"] [data-view-heading]`);
    if (focus && heading) heading.focus({ preventScroll: true });
    if (focus) window.scrollTo({ top: 0, behavior: 'auto' });
  }

  function selectNode(button) {
    state.node = button.dataset.node;
    const label = button.dataset.label || button.textContent.trim();
    all('[data-node]').forEach((node) => {
      const selected = node.dataset.node === state.node;
      node.setAttribute('aria-pressed', String(selected));
      node.classList.toggle('selected', selected);
    });
    setText('[data-node-name]', label);
    setText('[data-node-question]', nodeQuestions[state.node] || '请解释这个组件的职责、输入输出和失败边界。');
    const board = one('[data-view="board"]');
    board?.querySelectorAll('[data-followup-result]').forEach((result) => { result.hidden = true; });
    board?.querySelectorAll('[data-followup-question]').forEach((element) => { element.textContent = ''; });
    updateReviewButtons();
  }

  function reviewSource(button) {
    const view = button.closest('[data-view]')?.dataset.view;
    if (view === 'board') {
      const nodeLabel = one(`[data-node="${state.node}"]`)?.dataset.label || state.node;
      return { key: `diagram:v1:${state.node}`, label: `图 v1 / ${nodeLabel}` };
    }
    return { key: 'answer:R1:result-confirmation', label: '回答 R1 / 结果确认' };
  }

  function updateReviewButtons() {
    all('[data-add-review]').forEach((button) => {
      if (!button.dataset.originalLabel) button.dataset.originalLabel = button.textContent;
      const added = state.reviewItems.has(reviewSource(button).key);
      button.disabled = added;
      button.textContent = added ? '此来源已加入本页复练' : button.dataset.originalLabel;
    });
  }

  function showHistory() {
    const dialog = one('[data-history-dialog]');
    if (!dialog) return;
    if (typeof dialog.showModal === 'function') {
      if (!dialog.open) dialog.showModal();
    } else {
      dialog.setAttribute('open', '');
    }
  }

  all('[data-answer]').forEach((input) => {
    input.addEventListener('input', () => {
      setText('[data-draft-state]', '仅本页草稿 · 刷新会清空');
    });
  });
  all('[data-explanation]').forEach((input) => {
    input.addEventListener('input', () => {
      setText('[data-diagram-status]', '解释已修改 · 尚未本页暂存');
    });
  });

  document.addEventListener('click', (event) => {
    const target = event.target.closest('button, [data-open]');
    if (!target) return;

    if (target.matches('[data-open]')) openView(target.dataset.open);
    if (target.matches('[data-node]')) selectNode(target);
    if (target.matches('[data-pause]')) {
      state.paused = !state.paused;
      target.textContent = state.paused ? '继续演示' : '暂停演示';
      target.setAttribute('aria-pressed', String(state.paused));
      setText('[data-session-status]', state.paused ? '演示已暂停 · 输入仍保留' : '自由训练 · 不计时');
      all('[data-view="interview"] [data-submit-answer]').forEach((button) => { button.disabled = state.paused; });
    }
    if (target.matches('[data-submit-answer]')) {
      const section = target.closest('[data-view]') || document;
      const input = section.querySelector('[data-answer]');
      const result = section.querySelector('[data-answer-result]');
      const quote = section.querySelector('[data-answer-quote]');
      if (!input || !result || state.paused) return;
      if (!input.value.trim()) {
        input.setCustomValidity('先写下你的思路；这份演示不会把空回答当作答错。');
        input.reportValidity();
        input.setCustomValidity('');
        input.focus();
        return;
      }
      if (quote) quote.textContent = input.value;
      result.hidden = false;
      setText('[data-draft-state]', '已在本页展示回答 · 非服务端保存');
      const heading = result.querySelector('[tabindex="-1"]');
      if (heading) heading.focus({ preventScroll: true });
      result.scrollIntoView({ behavior: 'auto', block: 'nearest' });
    }
    if (target.matches('[data-add-review]')) {
      const source = reviewSource(target);
      if (!state.reviewItems.has(source.key)) {
        state.reviewItems.set(source.key, { source, action: '补充结果确认、查单与重试边界' });
      }
      const labels = [...state.reviewItems.values()].map((item) => item.source.label).join('；');
      setText('[data-review-status]', `本页复练 ${state.reviewItems.size} 项：${labels} · 按来源去重 · 刷新清空`);
      updateReviewButtons();
    }
    if (target.matches('[data-save-diagram]')) {
      const nodes = new Map();
      all('[data-node]').forEach((node) => {
        nodes.set(node.dataset.node, { id: node.dataset.node, label: node.dataset.label || node.textContent.trim() });
      });
      state.saveRevision += 1;
      state.diagramSave = {
        revision: state.saveRevision,
        nodes: [...nodes.values()],
        relations: [
          ['entry', 'eligibility', '校验'],
          ['eligibility', 'budget', '预占'],
          ['budget', 'award', '调用'],
          ['award', 'store', '结果写入'],
          ['query', 'award', '按请求号查单'],
          ['query', 'store', '回写状态'],
        ],
        constraints: ['同请求不能重复发奖', '超时不等于确定失败', '发奖接口幂等保证待核实'],
        explanation: one('[data-explanation]')?.value || '',
        selectedNode: state.node,
        questionBasis: 'v1',
      };
      setText('[data-diagram-status]', `已本页暂存 r${state.saveRevision} · 刷新清空 · 非服务端保存`);
    }
    if (target.matches('[data-demo-followup]')) {
      const section = target.closest('[data-view]') || document;
      section.querySelectorAll('[data-followup-result]').forEach((result) => { result.hidden = false; });
      const questionNode = section.dataset?.view === 'interview' ? 'award' : state.node;
      section.querySelectorAll('[data-followup-question]').forEach((element) => {
        element.textContent = nodeQuestions[questionNode] || nodeQuestions.award;
      });
    }
    if (target.matches('[data-show-history]')) showHistory();
    if (target.matches('[data-close-dialog]')) {
      const dialog = target.closest('dialog');
      if (dialog) {
        if (typeof dialog.close === 'function') dialog.close();
        else dialog.removeAttribute('open');
      }
    }
    if (target.matches('[data-zoom]')) {
      const action = target.dataset.zoom;
      state.zoom = action === 'fit' ? 1 : Math.min(1.4, Math.max(0.7, state.zoom + (action === 'in' ? 0.1 : -0.1)));
      all('[data-graph-layer]').forEach((layer) => {
        layer.style.transform = `scale(${state.zoom})`;
        layer.style.transformOrigin = 'center center';
      });
      setText('[data-zoom-label]', `${Math.round(state.zoom * 100)}%`);
    }
  });

  const initialNode = one('[data-node="award"]');
  if (initialNode) selectNode(initialNode);
  const initialView = window.location.hash.replace('#', '');
  openView(views.includes(initialView) ? initialView : 'home', false);
})();
