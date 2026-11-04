(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const { topics, notes } = window.GalaxyContent;
  const state = { mode: 'galaxy', topic: null, selected: 'timeout', focused: false, search: '', bookmarks: new Set(['timeout', 'thread-pool']), onlyBookmarks: false, recent: ['timeout', 'thread-pool', 'award'], recalls: [], drafts: new Map(), practice: null, zoom: 1, panX: 0, panY: 0, serial: 0 };
  const noteById = id => notes.find(note => note.id === id);
  const topicById = id => topics.find(topic => topic.id === id);
  const activeNote = () => noteById(state.selected);
  const dueNotes = () => notes.filter(note => note.due);
  const atlas = {
    concurrency: [17, 29], storage: [82, 27], distributed: [70, 60], project: [24, 71], jvm: [49, 19], expression: [54, 86],
    'thread-pool': [8, 11, '线程池接纳'], queue: [6, 40, '队列容量'], rejection: [29, 39, '拒绝策略'], 'concurrency-boundary': [29, 16, '并发假设'],
    'jvm-memory': [42, 5, '对象生命周期'], gc: [62, 9, 'GC 的运行条件'], diagnosis: [57, 29, '排障与证据'],
    transaction: [69, 38, '事务边界'], cas: [91, 40, '版本冲突'], revision: [83, 8, '草稿与提交'], index: [94, 22, '查询与索引'],
    timeout: [48, 50, '超时 ≠ 失败'], idempotency: [88, 49, '幂等契约'], query: [89, 73, '查不到 ≠ 没发生'], compensation: [65, 76, '补偿边界'],
    award: [31, 54, '发奖链路'], budget: [41, 70, '预算预占'], qualification: [9, 60, '资格校验'], 'project-story': [10, 85, '项目中的异常'],
    ownership: [32, 90, '负责 · 参与 · 了解'], evidence: [73, 91, '原答证据'], clarify: [42, 34, '澄清条件'], repractice: [91, 87, '独立复练']
  };
  function graphEdges() {
    const pairs = new Map();
    notes.forEach(note => note.relations.forEach(([target, label]) => {
      if (!noteById(target)) return;
      const key = [note.id, target].sort().join('/');
      if (!pairs.has(key)) pairs.set(key, { from: note.id, to: target, references: [] });
      pairs.get(key).references.push({ from: note.id, to: target, label });
    }));
    return [...pairs.values()];
  }
  function connections(id) {
    return graphEdges().filter(edge => edge.from === id || edge.to === id).map(edge => {
      const outgoing = edge.references.find(ref => ref.from === id);
      const incoming = edge.references.find(ref => ref.to === id);
      return { note: noteById(edge.from === id ? edge.to : edge.from), outgoing, incoming, label: outgoing?.label || incoming.label };
    });
  }
  function positionFor(note) {
    if (atlas[note.id]) return atlas[note.id];
    const [x, y] = atlas[note.topic];
    const index = notes.filter(item => item.topic === note.topic && !atlas[item.id]).findIndex(item => item.id === note.id);
    const angle = index * 2.4;
    return [Math.max(6, Math.min(94, x + Math.cos(angle) * 13)), Math.max(6, Math.min(93, y + Math.sin(angle) * 16)), note.title];
  }
  const el = (tag, text = '', className = '') => { const node = document.createElement(tag); if (text) node.textContent = text; if (className) node.className = className; return node; };
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`); svg.setAttribute('aria-hidden', 'true'); svg.append(use); return svg;
  }
  function action(text, handler, className = '') { const node = el('button', text, className); node.type = 'button'; node.addEventListener('click', handler); return node; }
  let toastTimer;
  function toast(message) { $('toast').textContent = message; $('toast').classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.remove('show'), 3400); }
  function matches(note) {
    const query = state.search.trim().toLocaleLowerCase();
    return (!query || [note.title, note.summary, note.source, ...note.tags, topicById(note.topic).name].join(' ').toLocaleLowerCase().includes(query)) && (!state.onlyBookmarks || state.bookmarks.has(note.id));
  }
  function filteredNotes() { return notes.filter(note => (!state.topic || note.topic === state.topic) && matches(note)); }
  function refreshCounts() {
    $('total-count').textContent = notes.length;
    $('collection-count').textContent = `${topics.length} 个星域 · ${notes.length} 篇知识笔记`;
    $('due-count').textContent = dueNotes().length;
    $('tab-due-count').textContent = dueNotes().length;
    $('bookmark-count').textContent = state.bookmarks.size;
  }
  function renderLibrary() {
    $('topic-nav').replaceChildren(...topics.map(topic => {
      const button = action('', () => selectTopic(topic.id), `topic-button${state.topic === topic.id ? ' selected' : ''}`);
      button.style.setProperty('--topic-color', topic.color);
      button.setAttribute('aria-pressed', String(state.topic === topic.id));
      button.dataset.topic = topic.id;
      button.append(el('i', '', 'topic-dot'), el('span', topic.name), el('span', String(notes.filter(note => note.topic === topic.id).length), 'count'));
      return button;
    }));
    $('recent-nav').replaceChildren(...state.recent.slice(0, 3).map(id => {
      const note = noteById(id);
      const button = action('', () => selectNote(id), 'recent-item');
      button.append(icon('note'), el('span', note.title)); return button;
    }));
    refreshCounts();
  }
  function setMode(mode, focus = false) {
    state.mode = mode;
    document.querySelectorAll('[data-mode]').forEach(button => {
      const selected = button.dataset.mode === mode;
      button.setAttribute('aria-selected', String(selected)); button.tabIndex = selected ? 0 : -1;
      if (focus && selected) button.focus();
    });
    ['galaxy', 'list', 'recall'].forEach(name => { $(`${name}-view`).hidden = mode !== name; });
    if (mode === 'galaxy') { renderMap(); requestAnimationFrame(drawGalaxy); }
    if (mode === 'list') renderList();
    if (mode === 'recall') renderRecall();
  }
  function resetMap() {
    state.topic = null; state.focused = false; state.onlyBookmarks = false; resetTransform(); renderLibrary(); setMode('galaxy');
  }
  function selectTopic(id) {
    state.topic = id; state.focused = false; state.onlyBookmarks = false; renderLibrary();
    const first = notes.find(note => note.topic === id && matches(note));
    if (first) selectNote(first.id, false);
    setMode('galaxy');
  }
  function selectNote(id, scroll = true) {
    if (!noteById(id)) return;
    if (state.topic && noteById(id).topic !== state.topic) state.topic = noteById(id).topic;
    state.selected = id; state.recent = [id, ...state.recent.filter(recent => recent !== id)];
    renderDetail(); renderLibrary(); renderTrail();
    if (state.mode === 'galaxy') renderMap();
    if (state.mode === 'list') renderList();
    if (scroll && window.innerWidth <= 760) $('inspector').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' });
  }
  function detailSection(title, iconName) {
    const section = el('section', '', 'detail-section');
    const heading = el('h3'); heading.append(icon(iconName), document.createTextNode(title)); section.append(heading); return section;
  }
  function renderDetail() {
    const note = activeNote(); const topic = topicById(note.topic);
    const panel = $('note-detail'); panel.replaceChildren();
    panel.append(el('span', topic.name, 'note-tag'), el('h2', note.title, 'note-title'), el('p', note.summary, 'note-summary'));
    const tags = el('div', '', 'note-tags'); note.tags.forEach(tag => tags.append(el('span', `# ${tag}`))); panel.append(tags);
    const linkedNotes = connections(note.id);
    const relations = detailSection(`与它相连 · ${linkedNotes.length}`, 'link');
    if (!linkedNotes.length) relations.append(el('p', '这篇笔记尚无知识引用，可先独立阅读与回忆。'));
    linkedNotes.forEach(({ note: linked, outgoing, incoming, label }) => {
      const button = action('', () => selectNote(linked.id, false), 'relation-chip');
      button.dataset.relatedNote = linked.id;
      button.title = [outgoing ? `引用：${outgoing.label}` : '', incoming ? `被引用：${incoming.label}` : ''].filter(Boolean).join('；');
      const text = el('span', '', 'relation-copy'); text.append(el('strong', linked.title), el('small', `${topicById(linked.topic).name} · ${outgoing ? '引用' : '被引用'}：${label}`));
      button.append(icon(outgoing ? 'link' : 'repeat'), text, icon('chevron')); relations.append(button);
    });
    panel.append(relations);
    const source = detailSection('知识来源', 'book'); source.append(el('p', note.source), el('p', note.sourceDetail || '合成设计内容 · 待独立审阅', 'source-note')); panel.append(source);
    if (note.evidence) {
      const evidence = detailSection('一次练习留下的线索', 'chat'); evidence.append(el('blockquote', note.evidence, 'evidence-quote'), el('div', note.evidenceLabel, 'evidence-caption'), el('p', note.evidenceHint, 'source-note')); panel.append(evidence);
    }
    $('toggle-bookmark').setAttribute('aria-pressed', String(state.bookmarks.has(note.id)));
    $('toggle-bookmark').setAttribute('aria-label', state.bookmarks.has(note.id) ? '取消收藏当前知识' : '收藏当前知识');
  }
  function renderMap() {
    const previousFocus = document.activeElement?.dataset.note;
    const layer = $('planet-layer'); layer.replaceChildren();
    const svg = $('relation-lines'); svg.replaceChildren();
    const edges = graphEdges();
    const neighbors = new Set([state.selected, ...connections(state.selected).map(item => item.note.id)]);
    const matchIds = new Set(notes.filter(matches).map(note => note.id));
    const searching = !!state.search.trim() || state.onlyBookmarks;
    const topicIds = new Set(notes.filter(note => note.topic === state.topic).map(note => note.id));
    const lit = state.focused ? neighbors : state.topic ? new Set([...topicIds, ...edges.filter(edge => topicIds.has(edge.from) || topicIds.has(edge.to)).flatMap(edge => [edge.from, edge.to])]) : null;
    const bright = id => (!lit || lit.has(id)) && (!searching || matchIds.has(id));
    const points = new Map(notes.map(note => [note.id, positionFor(note)]));
    const breadcrumb = $('map-breadcrumb'); breadcrumb.replaceChildren();
    breadcrumb.append(action('全部主题', resetMap), el('span', '／'), el('span', state.topic ? topicById(state.topic).name : `${notes.length} 个知识点 · ${edges.length} 条关联`));
    $('focus-neighbors').setAttribute('aria-pressed', String(state.focused));
    $('galaxy-view').classList.toggle('is-focused', state.focused);
    function curve(from, to, className, color, bend = 0) {
      const [x1, y1] = from, [x2, y2] = to;
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M${x1 * 10},${y1 * 6.5} Q${(x1 + x2) * 5 + bend},${(y1 + y2) * 3.25 - bend * .65} ${x2 * 10},${y2 * 6.5}`);
      path.setAttribute('class', className); path.setAttribute('stroke', color); path.setAttribute('vector-effect', 'non-scaling-stroke'); svg.append(path); return path;
    }
    notes.forEach(note => curve(atlas[note.topic], points.get(note.id), `membership-edge${bright(note.id) ? '' : ' dimmed'}`, topicById(note.topic).color));
    // One visible curve groups the explicitly recorded references between two notes.
    edges.sort((a, b) => Number(a.from === state.selected || a.to === state.selected) - Number(b.from === state.selected || b.to === state.selected)).forEach(edge => {
      const selected = edge.from === state.selected || edge.to === state.selected;
      const cross = noteById(edge.from).topic !== noteById(edge.to).topic;
      const visible = bright(edge.from) && bright(edge.to);
      const path = curve(points.get(edge.from), points.get(edge.to), `knowledge-edge${cross ? ' cross-topic' : ''}${selected && visible ? ' active-edge' : ''}${visible ? '' : ' dimmed'}`, selected ? '#b5ecd8' : cross ? '#b6a4e1' : topicById(noteById(edge.from).topic).color, cross ? 30 : -15);
      path.dataset.from = edge.from; path.dataset.to = edge.to;
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = edge.references.map(ref => `${noteById(ref.from).title} → ${noteById(ref.to).title}：${ref.label}`).join('\n'); path.append(title);
      if (selected && visible && noteById(edge.from).topic !== noteById(edge.to).topic) {
        const a = points.get(edge.from), b = points.get(edge.to);
        const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        label.setAttribute('x', (a[0] + b[0]) * 5 + (cross ? 15 : -7.5)); label.setAttribute('y', (a[1] + b[1]) * 3.25 - (cross ? 9.75 : -4.875) - 7);
        label.setAttribute('text-anchor', 'middle'); label.setAttribute('class', 'edge-caption');
        label.textContent = (edge.references.find(ref => ref.from === state.selected) || edge.references[0]).label; svg.append(label);
      }
    });
    topics.forEach(topic => {
      const count = notes.filter(note => note.topic === topic.id && matches(note)).length;
      const dim = !notes.some(note => note.topic === topic.id && bright(note.id));
      const button = action('', () => selectTopic(topic.id), `planet topic-hub${topic.ring ? ' ringed' : ''}${!count ? ' hidden-planet' : ''}${dim ? ' dimmed' : ''}`);
      const [x, y] = atlas[topic.id]; button.style.left = `${x}%`; button.style.top = `${y}%`;
      button.style.setProperty('--planet-color', topic.color); button.style.setProperty('--size', `${Math.max(30, topic.size * .74)}px`);
      button.setAttribute('aria-label', `聚焦${topic.name}，${count}篇知识笔记`); button.setAttribute('aria-pressed', String(state.topic === topic.id));
      button.dataset.planet = topic.id; button.disabled = !count;
      button.append(el('span', '', 'orb')); if (topic.ring) button.append(el('i', '', 'planet-ring'));
      button.append(el('span', topic.name, 'planet-name')); layer.append(button);
    });
    notes.forEach(note => {
      const [x, y, title] = points.get(note.id);
      const button = action('', () => selectNote(note.id), `graph-note${note.id === state.selected ? ' selected' : neighbors.has(note.id) ? ' connected' : ''}${bright(note.id) ? '' : ' dimmed'}${x > 78 ? ' label-left' : ''}`);
      button.dataset.note = note.id; button.style.left = `${x}%`; button.style.top = `${y}%`; button.style.setProperty('--planet-color', topicById(note.topic).color);
      button.setAttribute('aria-label', `${note.title}，${topicById(note.topic).name}，${connections(note.id).length}条关联`); button.setAttribute('aria-pressed', String(note.id === state.selected));
      button.title = `${note.title} · ${topicById(note.topic).name}`;
      button.append(el('i', '', 'note-star'), el('span', title, 'graph-note-label')); layer.append(button);
      if (previousFocus === note.id) button.focus({ preventScroll: true });
    });
    $('map-hint').textContent = searching ? `${matchIds.size} 个匹配知识点` : `${activeNote().title} · ${neighbors.size - 1} 条相邻关联`;
    $('empty-map').hidden = matchIds.size > 0;
    drawGalaxy();
  }
  function makeNoteRow(note) {
    const button = action('', () => selectNote(note.id), `note-row${note.id === state.selected ? ' selected-row' : ''}`);
    button.style.setProperty('--topic-color', topicById(note.topic).color);
    const mark = el('span', '', 'row-icon'); mark.append(icon('note'));
    const copy = el('span', '', 'row-copy'); copy.append(el('strong', note.title), el('small', note.summary.replaceAll('\n', ' ')));
    button.append(mark, copy, el('span', note.due ? '待回忆' : topicById(note.topic).name, 'row-meta'), icon('chevron')); return button;
  }
  function renderList() {
    const list = filteredNotes();
    $('list-title').textContent = state.onlyBookmarks ? '星标笔记' : state.topic ? topicById(state.topic).name : '所有知识笔记';
    $('list-count').textContent = `${list.length} 篇`;
    $('note-list').replaceChildren(...list.map(makeNoteRow));
    if (!list.length) $('note-list').append(el('p', '没有匹配的笔记，试试其他关键词。', 'empty-list'));
  }
  function renderRecall() {
    const list = dueNotes().filter(matches);
    $('recall-list').replaceChildren(...list.map(note => {
      const row = el('article', '', 'recall-card');
      const mark = el('span', '', 'row-icon'); mark.style.setProperty('--topic-color', topicById(note.topic).color); mark.append(icon('repeat'));
      const copy = el('div', '', 'row-copy'); copy.append(el('strong', note.title), el('small', `${topicById(note.topic).name} · ${note.prompt}`));
      row.append(mark, copy, action('开始回忆 →', () => openPractice(note.id), 'button secondary')); return row;
    }));
    if (!list.length) $('recall-list').append(el('p', state.search ? '没有匹配的待回忆内容。' : '今天的回忆已记录。也可以从星图选择一个知识点，随时再练。', 'empty-list'));
    const history = $('recall-history'); history.replaceChildren();
    if (state.recalls.length) {
      history.className = 'recall-history'; history.append(el('h3', '这一次，留下了什么'));
      state.recalls.slice().reverse().forEach(record => { const item = el('div', `${record.title} · ${record.ratingLabel}`, 'history-entry'); item.append(el('span', `${record.answer ? '已保留独立回答' : '本次没有填写回答'} · ${record.nextLabel} · ${record.hadReference ? '已展开参考' : '未展开参考'}`)); history.append(item); });
    }
  }
  function renderTrail() {
    const linked = connections(state.selected);
    $('trail-title').textContent = '沿着关联，继续探索';
    $('trail-context').textContent = `从「${activeNote().title}」出发 · ${linked.length} 个相邻知识点`;
    $('trail-list').replaceChildren(...linked.map(({ note, outgoing, label }) => {
      const button = action('', () => selectNote(note.id), 'trail-item'); button.dataset.trailNote = note.id;
      const overline = el('span'); overline.append(icon(outgoing ? 'link' : 'repeat'), document.createTextNode(`${outgoing ? '引用' : '被引用'} · ${label}`));
      button.append(overline, el('strong', note.title), el('small', topicById(note.topic).name)); return button;
    }));
    if (!linked.length) $('trail-list').append(el('p', '这篇笔记还没有知识关联。', 'empty-list'));
  }
  function openPractice(id) {
    const note = noteById(id); if (!note) return;
    state.practice = { noteId: id, rating: null, saved: false, hadReference: false };
    $('practice-topic').textContent = topicById(note.topic).name; $('practice-title').textContent = note.title; $('practice-prompt').textContent = note.prompt;
    $('practice-answer').value = state.drafts.get(id) || ''; $('practice-answer').readOnly = false;
    $('reference-copy').textContent = note.reference || note.summary;
    $('reference-status').textContent = note.id.startsWith('custom-') ? '个人笔记对照 · 未经技术审核' : '合成参考要点 · 未经独立专家审核';
    $('reference-panel').hidden = true; $('reveal-reference').hidden = false; $('practice-saved').hidden = true; $('practice-error').textContent = '';
    $('save-recall').disabled = false;
    document.querySelectorAll('[data-rating]').forEach(button => { button.setAttribute('aria-pressed', 'false'); button.disabled = false; });
    $('practice-dialog').showModal(); requestAnimationFrame(() => $('practice-answer').focus());
  }
  function saveRecall() {
    const practice = state.practice; if (!practice || practice.saved) return;
    if (!practice.rating) { $('practice-error').textContent = '先选择这次回忆的感受。'; return; }
    const answer = $('practice-answer').value.trim();
    if (!answer && practice.rating !== 'again') { $('practice-error').textContent = '请先留下自己的回答；暂时想不起来，也可以选择“还需要看看”。'; return; }
    const note = noteById(practice.noteId);
    const choices = { again: ['还需要看看', '留在今日回忆', 0], effort: ['想起来了一部分', '明天再回忆', 1], clear: ['能独立解释', '3 天后再回忆', 3] };
    const [ratingLabel, nextLabel, intervalDays] = choices[practice.rating];
    state.recalls.push(Object.freeze({ id: `recall-${++state.serial}`, noteId: note.id, title: note.title, answer: $('practice-answer').value, rating: practice.rating, ratingLabel, nextLabel, intervalDays, hadReference: practice.hadReference, at: new Date().toISOString() }));
    note.due = intervalDays === 0; practice.saved = true; state.drafts.delete(note.id);
    $('practice-answer').readOnly = true; $('save-recall').disabled = true; $('practice-error').textContent = '';
    document.querySelectorAll('[data-rating]').forEach(button => { button.disabled = true; });
    $('practice-saved').textContent = `已记录：${ratingLabel}。${nextLabel}。本页暂存，刷新清空。`; $('practice-saved').hidden = false;
    refreshCounts(); renderRecall(); if (state.mode === 'galaxy') renderMap();
    toast('这次回忆已留在本页记录中。');
  }
  function resetTransform() { state.zoom = 1; state.panX = 0; state.panY = 0; applyTransform(); }
  function applyTransform() { $('space-world').style.transform = `translate(${state.panX}px,${state.panY}px) scale(${state.zoom})`; $('zoom-value').textContent = `${Math.round(state.zoom * 100)}%`; $('zoom-out').disabled = state.zoom <= .8; $('zoom-in').disabled = state.zoom >= 1.6; }
  function zoomBy(amount) { state.zoom = Math.max(.8, Math.min(1.6, Math.round((state.zoom + amount) * 100) / 100)); applyTransform(); }
  function drawGalaxy() {
    const canvas = $('galaxy-canvas'); const rect = $('universe').getBoundingClientRect(); if (!rect.width || !rect.height) return;
    const ratio = Math.min(devicePixelRatio || 1, 2); canvas.width = Math.round(rect.width * ratio); canvas.height = Math.round(rect.height * ratio);
    const ctx = canvas.getContext('2d'); ctx.scale(ratio, ratio);
    const w = rect.width, h = rect.height; let seed = 3087;
    const random = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
    const cx = w * .49, cy = h * .50;
    for (let i = 0; i < 250; i++) {
      const x = random() * w, y = random() * h, r = random() < .95 ? random() * .8 + .2 : 1.3;
      ctx.fillStyle = `rgba(210,211,245,${.12 + random() * .45})`; ctx.beginPath(); ctx.arc(x,y,r,0,Math.PI*2); ctx.fill();
      if (r > 1) { ctx.strokeStyle = '#d4bdfb38'; ctx.lineWidth = .5; ctx.beginPath(); ctx.moveTo(x-4,y);ctx.lineTo(x+4,y);ctx.moveTo(x,y-4);ctx.lineTo(x,y+4);ctx.stroke(); }
    }
    ctx.save(); ctx.translate(cx,cy); ctx.rotate(-.22); ctx.scale(1,.54);
    const radius = w * .43;
    const haze = ctx.createRadialGradient(0,0,0,0,0,radius);
    haze.addColorStop(0,'#c5a3e62a'); haze.addColorStop(.12,'#ad7cbe20'); haze.addColorStop(.5,'#8e78c012'); haze.addColorStop(1,'#7d68b000');
    ctx.fillStyle = haze;ctx.fillRect(-radius,-radius,radius*2,radius*2);
    {
      ctx.globalAlpha = .55;
      ctx.globalCompositeOperation = 'screen';
      for(let i=0;i<2300;i++) {
        const progress = Math.pow(random(), .64); const arm = i % 3;
        const angle = arm * Math.PI*2/3 + progress*4.8 + (random()-.5)*(.7 + progress*.35);
        const radiusAt = progress*radius; const spread=(random()-.5)*radius*.09;
        const x = Math.cos(angle)*radiusAt+spread, y=Math.sin(angle)*radiusAt+spread;
        const dot=random()*.95+.2; const alpha=(1-progress)*.35+.05;
        ctx.fillStyle = i%5===0?`rgba(224,185,209,${alpha})`:`rgba(170,156,221,${alpha})`;
        ctx.beginPath();ctx.arc(x,y,dot,0,Math.PI*2);ctx.fill();
      }
      const core=ctx.createRadialGradient(0,0,0,0,0,radius*.23);
      core.addColorStop(0,'#e2d0dd42');core.addColorStop(.15,'#c4a3dc2e');core.addColorStop(1,'#b798cf00');ctx.fillStyle=core;ctx.fillRect(-radius*.23,-radius*.23,radius*.46,radius*.46);
      ctx.globalCompositeOperation='source-over';
      [0.65,.95,1.28].forEach((scale,index)=>{ctx.strokeStyle=index===1?'#bcb0e21c':'#bcb0e210';ctx.lineWidth=.8;ctx.beginPath();ctx.ellipse(0,0,radius*scale,radius*scale,0,0,Math.PI*2);ctx.stroke();});
    }
    ctx.restore();
  }
  document.querySelectorAll('[data-mode]').forEach(button => button.addEventListener('click', () => { state.onlyBookmarks = false; setMode(button.dataset.mode); }));
  document.querySelector('.view-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    const modes = ['galaxy','list','recall']; let index = modes.indexOf(event.target.dataset.mode || state.mode);
    index = event.key==='Home'?0:event.key==='End'?2:(index+(event.key==='ArrowRight'?1:2))%3; event.preventDefault(); setMode(modes[index],true);
  });
  $('all-space').addEventListener('click', resetMap); $('reset-map').addEventListener('click', resetMap); $('rail-galaxy').addEventListener('click', resetMap);
  $('focus-neighbors').addEventListener('click', () => { state.focused = !state.focused; renderMap(); });
  $('rail-list').addEventListener('click', () => { state.topic = null; state.onlyBookmarks = false; renderLibrary(); setMode('list'); });
  [$('rail-recall'),$('sidebar-recall')].forEach(button => button.addEventListener('click', () => { state.onlyBookmarks = false; setMode('recall'); }));
  $('sidebar-bookmarks').addEventListener('click', () => { state.onlyBookmarks = true; state.topic = null; renderLibrary(); setMode('list'); });
  $('show-all').addEventListener('click', () => { state.topic = null; state.onlyBookmarks = false; renderLibrary(); setMode('list'); });
  $('toggle-bookmark').addEventListener('click', () => { const id=state.selected;if(state.bookmarks.has(id))state.bookmarks.delete(id);else state.bookmarks.add(id);renderDetail();refreshCounts();if(state.mode==='list')renderList();toast(state.bookmarks.has(id)?'已加入星标笔记。':'已取消星标。'); });
  $('search-input').addEventListener('input', event => { state.search=event.target.value; if(state.mode==='galaxy')renderMap();if(state.mode==='list')renderList();if(state.mode==='recall')renderRecall(); });
  $('clear-map-search').addEventListener('click', () => { state.search='';$('search-input').value='';renderMap(); });
  $('practice-note').addEventListener('click', () => openPractice(state.selected));
  $('practice-answer').addEventListener('input', () => { if(state.practice&&!state.practice.saved)state.drafts.set(state.practice.noteId,$('practice-answer').value); });
  $('reveal-reference').addEventListener('click', () => { state.practice.hadReference=true;$('reference-panel').hidden=false;$('reveal-reference').hidden=true; });
  document.querySelectorAll('[data-rating]').forEach(button => button.addEventListener('click', () => { state.practice.rating=button.dataset.rating;document.querySelectorAll('[data-rating]').forEach(item=>item.setAttribute('aria-pressed',String(item===button)));$('practice-error').textContent=''; }));
  $('save-recall').addEventListener('click', saveRecall);
  document.querySelectorAll('[data-close]').forEach(button=>button.addEventListener('click',() => $(button.dataset.close).close()));
  $('open-about').addEventListener('click', () => $('about-dialog').showModal());
  $('note-topic').replaceChildren(...topics.map(topic => { const option=el('option',topic.name);option.value=topic.id;return option; }));
  $('new-note').addEventListener('click', () => { $('note-topic').value=state.topic||activeNote().topic;$('note-dialog').showModal();$('note-title').focus(); });
  $('note-form').addEventListener('submit', event => {
    event.preventDefault();const title=$('note-title').value.trim(),body=$('note-body').value.trim();
    if(!title||!body){toast('标题和理解都需要填写。');return;}
    const id=`custom-${++state.serial}`;const topic=$('note-topic').value;
    notes.push({id,topic,title,summary:body,tags:['个人整理'],prompt:`不看笔记，用自己的话解释“${title}”，说说适用条件与例子。`,reference:body,source:$('note-source').value.trim()||'个人整理 · 本页新笔记',sourceDetail:'用户输入 · 待核对',relations:[],due:false});
    state.topic=topic;state.search='';state.onlyBookmarks=false;$('search-input').value='';$('note-dialog').close();$('note-form').reset();selectNote(id,false);setMode('list');toast('新笔记已加入本页知识银河。刷新会清空。');
  });
  $('zoom-in').addEventListener('click',()=>zoomBy(.1));$('zoom-out').addEventListener('click',()=>zoomBy(-.1));
  $('universe').addEventListener('wheel',event=>{if(event.ctrlKey||Math.abs(event.deltaY)>0){event.preventDefault();zoomBy(event.deltaY<0?.05:-.05);}},{passive:false});
  let drag=null;
  $('universe').addEventListener('pointerdown',event=>{if(event.target.closest('button')||event.pointerType==='touch')return;drag={x:event.clientX,y:event.clientY,panX:state.panX,panY:state.panY};$('universe').setPointerCapture(event.pointerId);$('universe').classList.add('dragging');});
  $('universe').addEventListener('pointermove',event=>{if(!drag)return;state.panX=Math.max(-180,Math.min(180,drag.panX+event.clientX-drag.x));state.panY=Math.max(-130,Math.min(130,drag.panY+event.clientY-drag.y));applyTransform();});
  ['pointerup','pointercancel','lostpointercapture'].forEach(type=>$('universe').addEventListener(type,()=>{drag=null;$('universe').classList.remove('dragging');}));
  document.addEventListener('keydown',event=>{const editing=event.target.closest('input,textarea,select');if(event.key==='/'&&!editing&&!document.querySelector('dialog[open]')){event.preventDefault();$('search-input').focus();}if(event.key==='Escape'&&!document.querySelector('dialog[open]')&&!editing&&state.mode==='galaxy')resetMap();});
  new ResizeObserver(()=>requestAnimationFrame(drawGalaxy)).observe($('universe'));
  renderLibrary();renderDetail();renderTrail();renderMap();applyTransform();
})();
