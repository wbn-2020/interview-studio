(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const now = () => new Date().toISOString();
  const clone = value => JSON.parse(JSON.stringify(value));
  function frozen(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) {
      Object.values(value).forEach(frozen);
      Object.freeze(value);
    }
    return value;
  }
  function node(tag, text = '', className = '') {
    const element = document.createElement(tag);
    if (text !== '') element.textContent = String(text);
    if (className) element.className = className;
    return element;
  }
  function button(text, action, className = 'button secondary', disabled = false) {
    const element = node('button', text, className);
    element.type = 'button';
    element.disabled = disabled;
    element.addEventListener('click', action);
    return element;
  }
  function tag(text, tone = '') { return node('span', text, `tag ${tone}`.trim()); }
  function options(select, rows, value) {
    select.replaceChildren(...rows.map(row => {
      const option = node('option', row.label);
      option.value = row.value;
      return option;
    }));
    if (rows.some(row => row.value === value)) select.value = value;
  }
  function error(id, text, fieldId) {
    $(id).textContent = text;
    if (fieldId) $(fieldId).setAttribute('aria-invalid', text ? 'true' : 'false');
  }
  function announce(text) { $('workspace-message').textContent = text; }
  function dateLabel(value) {
    return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }
  let serial = 0;
  const id = prefix => `${prefix}-${String(++serial).padStart(3, '0')}`;
  // 演示校验码，不是密码学摘要；原文定位还必须同时匹配 revisionId 和码点区间。
  function hash(text) {
    let value = 0x811c9dc5;
    for (const point of Array.from(text)) {
      value ^= point.codePointAt(0);
      value = Math.imul(value, 0x01000193) >>> 0;
    }
    return `demo-fnv32-${value.toString(16).padStart(8, '0')}`;
  }
  function isUnicodeBoundary(text, offset) {
    if (offset <= 0 || offset >= text.length) return true;
    const before = text.charCodeAt(offset - 1);
    const after = text.charCodeAt(offset);
    return !(before >= 0xd800 && before <= 0xdbff && after >= 0xdc00 && after <= 0xdfff);
  }
  function rangeFromSelection(text, startUtf16, endUtf16) {
    if (!isUnicodeBoundary(text, startUtf16) || !isUnicodeBoundary(text, endUtf16)) return null;
    const start = Array.from(text.slice(0, startUtf16)).length;
    const end = Array.from(text.slice(0, endUtf16)).length;
    return { start, end, quote: Array.from(text).slice(start, end).join('') };
  }
  const facts = frozen([
    { id: 'F1', text: '本人负责扫码入口与资格校验。', role: '本人负责', unknown: false },
    { id: 'F2', text: '本人负责预算预占接口对接。', role: '本人负责', unknown: false },
    { id: 'F3', text: '参与了解按发奖请求号查单。', role: '参与了解', unknown: false },
    { id: 'U1', text: '发奖接口是否提供幂等保证待核实。', role: '待核实', unknown: true }
  ]);
  const questions = frozen([
    {
      id: 'B01', version: 1, type: 'basic', typeLabel: '基础题', topic: 'Java 并发', title: 'ThreadPoolExecutor 的接纳顺序',
      prompt: 'Java 21 的 ThreadPoolExecutor 处于 RUNNING：corePoolSize=2，maximumPoolSize=4，ArrayBlockingQueue 容量=2，拒绝策略 AbortPolicy。任务 1—7 依次到达，期间没有任务结束，线程创建均成功。逐个说明接纳路径，并说明第 7 个任务会怎样。',
      context: '固定假设：从空线程池开始，单一提交者依次execute，worker持续被其首个任务占用、不取队列，无取消／关闭／其他队列操作或钩子，线程创建成功。若条件不同须重新判断。',
      sample: '前4个任务都开线程，满了再进队列。', reference: 'B01-reference-v1',
      dimensions: ['接纳顺序与核心线程', '队列、扩容与拒绝条件', '假设边界与反例澄清'],
      hints: [
        { title: '一层 · 澄清适用条件', purpose: '思考方向', text: '先圈出初始worker数、core、max、队列容量、拒绝策略、单一提交者与没有关闭／完成的前提。它们决定本题能否按固定顺序推演。' },
        { title: '二层 · 关键分支', purpose: '条件核对', text: 'worker少于core先建线程，达到core先尝试入队；队列无法接收才尝试扩到max，仍无容量才拒绝。分别说明任务3、5、7为什么走不同分支。' },
        { title: '三层 · 参考推演与限制', purpose: '参考讲解', text: '在本题条件下：1/2新worker，3/4入队，5/6队列满后新worker，7由AbortPolicy抛拒绝异常。队列FIFO不代表全池开始／完成顺序；改变队列、关闭或并发条件须重新判断，不能按关键词或字数评分。' }
      ], followups: []
    },
    {
      id: 'P01', version: 1, type: 'project', typeLabel: '项目题', topic: '交易可靠性', title: '发奖超时，结果未知',
      prompt: '合成请求 R-001 发奖超时，结果未知。结合本次确认的职责，说明结果确认、重试和预算对接的边界；区分既有事实、待核实契约和方案，不编造实现经历。',
      context: '预算已预占，查单可见性／终态、预算期限／释放与幂等契约待核实；回调存在性未知，不要求必答回调。题干角色仅为合成起点，本次事实以你选择的确切版本为准；未选／待核实内容不作已知事实。',
      sample: '发奖超时就重试，预算先释放。🙂', reference: 'P01-reference-v1',
      dimensions: ['结果未知与确定失败', '请求号、查询与回调竞争', '重试幂等、事务与预算边界', '职责表述与未知约束'],
      hints: [
        { title: '一层 · 先区分未知与失败', purpose: '思考方向', text: '超时只说明暂时没有拿到结果，不能据此确定发奖失败。先讲清你知道与不知道什么；本人负责接口对接，不等于本人独立设计了全部发奖服务。' },
        { title: '二层 · 找结果确认依据', purpose: '路径核对', text: '先按原请求号核对查单可见性和权威终态；查不到不代表未生效。回调只有核实存在时才采用，不说回调不是错误。查询不可用可在预算约定期限内协调对账或人工确认，不承诺无限冻结。' },
        { title: '三层 · 重试与预算的判断条件', purpose: '边界核对', text: '不能确定“超时就是失败”后直接补发或释放预算。若要重试，应先核实同一请求的幂等保证、重试窗口与事务/状态边界；结果仍未知时保留待核实状态或走人工确认。确定失败后的补偿、先可靠查单再决定、受约束的安全重试都是可讨论路径。U1 未确认，不能宣称接口一定幂等；参考不对你的文字自动下结论。' }
      ],
      followups: [
        { id: 'PQ1', title: '假设有回调，查询与回调冲突怎么处理？', prompt: '仅本预设追问新增“发奖方提供回调”的假设，不回写主问题事实。按同请求号关联、核权威终态，讨论避免预算重复处置的条件；规则不明则待确认并协调责任方，不按最后到达覆盖。' },
        { id: 'PQ2', title: '哪些条件确认后，才允许重试或释放预算？', prompt: '你主动选择了这个预设追问。区分结果未知、确定成功与确定失败；说明重试的幂等/事务条件与预算补偿依据。也可以给出等待、对账或人工核实等合理替代路径。' }
      ]
    }
  ]);
  const defaultTarget = frozen({ id: 'T-DEFAULT', version: 0, role: 'Java 后端', focus: '默认方向（尚未保存目标版本）' });
  const initialTarget = frozen({ id: 'T-SEED', version: 1, role: 'Java 后端', focus: '基础机制与项目表达', savedAt: now() });
  const initialMaterial = frozen({ id: 'M-SEED', version: 1, rows: clone(facts), savedAt: now(), seed: true });
  const state = {
    view: 'prepare', targets: [initialTarget], targetDraft: { role: initialTarget.role, focus: initialTarget.focus },
    materials: [initialMaterial], materialDraft: clone(facts), materialDirty: false,
    materialConfirmations: [frozen({ id: 'MC-SEED', versionId: initialMaterial.id, factIds: ['F1', 'F2', 'F3'], savedAt: now(), seed: true })],
    custom: null, customDraft: { topic: '我的表达练习', prompt: '', source: 'personal', sourceNote: '' },
    attempts: [], feedback: [], feedbackDecisions: {}, actions: [], comparisons: [], comparisonDrafts: {},
    currentAttemptId: null, compareAttemptId: null, compareRevisionId: null,
    faultMode: 'normal', conflict: null, pendingProject: null, pendingEnd: null, archiveActionId: null,
    exportPlan: null, deletionPlan: null, changeVersion: 0,
    ack: { body: 'v1：先确认未知结果。', version: 1, savedVersion: 0, pending: null }
  };
  const latestTarget = () => state.targets.at(-1) || defaultTarget;
  const latestMaterial = () => state.materials.at(-1) || null;
  const attemptById = attemptId => state.attempts.find(attempt => attempt.id === attemptId);
  const currentAttempt = () => attemptById(state.currentAttemptId);
  const latestRevision = attempt => attempt.revisions.at(-1) || null;
  const allAnswerRevisions = attempt => [...attempt.revisions, ...Object.values(attempt.followups).flatMap(item => item.revisions || [])];
  const reviewRevision = attempt => allAnswerRevisions(attempt).find(revision => revision.id === attempt.reviewRevisionId) || latestRevision(attempt);
  const questionById = questionId => questionId === 'C01' ? state.custom : questions.find(question => question.id === questionId) || state.attempts.find(attempt => attempt.question.id === questionId)?.question;
  const actionById = actionId => state.actions.find(action => action.id === actionId);
  const isConfirmable = row => !row.unknown && row.role !== '待核实';
  function confirmedFacts(material) {
    const confirmation = state.materialConfirmations.filter(item => item.versionId === material.id).at(-1);
    return (confirmation?.factIds || []).filter(factId => material.rows.some(row => row.id === factId && isConfirmable(row)));
  }
  function materialSnapshot(material, selectedIds) {
    const validIds = selectedIds.filter(factId => material.rows.some(row => row.id === factId && isConfirmable(row)));
    return frozen({ id: material.id, version: material.version, selectedFactIds: validIds, trainingUse: '本页项目训练与自检，非模型外发', rows: material.rows.map(row => ({ ...clone(row), confirmed: isConfirmable(row) && validIds.includes(row.id) })) });
  }
  function feedbackKey(value) {
    return JSON.stringify([value.attemptId, value.revisionId, value.evidence.kind, value.evidence.start, value.evidence.end, value.evidence.quote, value.dimension, value.type, value.applicable, value.limitation, value.gap, value.action, value.condition]);
  }
  const actionKey = feedback => JSON.stringify([feedback.id, feedback.action]);
  function dirtyAnswer(attempt) { return attempt.draft !== (latestRevision(attempt)?.body || ''); }
  function savedFollowups(attempt) { return Object.values(attempt.followups).filter(item => item.answer); }
  function makeAttempt(question, sourceActionId = null, material = null, seed = false) {
    const attempt = {
      id: id(seed ? 'SYN' : 'A'), seed, question: frozen(clone(question)), target: frozen(clone(latestTarget())), material,
      sourceActionId, createdAt: now(), status: 'active', endedAt: null, manifest: null,
      draft: '', draftVersion: 0, editing: true, revisions: [], reviewRevisionId: null, evidence: null,
      selfcheckDraft: { dimension: question.dimensions?.[0] || 'custom', custom: '', type: 'condition', applicable: '', limitation: '', gap: '', action: '', condition: '' },
      hints: [], followups: {}, noteDraft: '', noteSaved: null, excludedDraft: null, conflictPreserved: []
    };
    question.followups.forEach(item => { attempt.followups[item.id] = { preset: clone(item), status: 'unselected', draft: '', answer: null }; });
    return attempt;
  }
  function addRevision(attempt, body) {
    const revision = frozen({ id: `${attempt.id}/R${attempt.revisions.length + 1}`, label: `R${attempt.revisions.length + 1}`, body, hash: hash(body), codePointLength: Array.from(body).length, clientDraftVersion: attempt.draftVersion, submittedAt: now(), hintSnapshot: clone(attempt.hints) });
    attempt.revisions.push(revision);
    attempt.reviewRevisionId = revision.id;
    attempt.evidence = null;
    attempt.editing = false;
    return revision;
  }
  function freezeAttempt(attempt, reason) {
    if (attempt.status === 'ended') return;
    if (dirtyAnswer(attempt)) attempt.excludedDraft = frozen({ body: attempt.draft, reason: '用户明确选择仅用已提交版本结束；这份未交草稿未纳入提交清单。', keptAt: now() });
    attempt.status = 'ended';
    attempt.editing = false;
    attempt.endedAt = now();
    attempt.manifest = frozen({ reason, endedAt: attempt.endedAt, revisionIds: attempt.revisions.map(revision => revision.id), latestRevisionId: latestRevision(attempt)?.id || null, followupAnswerIds: savedFollowups(attempt).map(item => item.answer.id), feedbackIds: state.feedback.filter(item => item.attemptId === attempt.id).map(item => item.id), savedNoteId: attempt.noteSaved?.id || null, hintSnapshot: clone(attempt.hints) });
  }
  for (const question of questions) {
    const seedMaterial = question.type === 'project' ? materialSnapshot(initialMaterial, ['F1', 'F2', 'F3']) : null;
    const attempt = makeAttempt(question, null, seedMaterial, true);
    attempt.draft = question.sample;
    attempt.draftVersion = 1;
    addRevision(attempt, question.sample);
    freezeAttempt(attempt, '只读合成起点：尚无用户自检或自动评分。');
    state.attempts.push(attempt);
  }
  function changed() {
    state.changeVersion += 1;
    if (state.exportPlan) {
      state.exportPlan = null;
      $('export-section').hidden = true;
      $('export-preview').textContent = '';
      $('download-export').disabled = true;
      $('export-state').textContent = '本页记录已变化，请重新预览导出。';
    }
  }
  function renderFault() {
    $('fault-mode').value = state.faultMode;
    $('fault-banner').hidden = state.faultMode === 'normal';
    const text = state.faultMode === 'fail' ? '演示：下一次显式保存失败；输入会保留，随后回到正常。' : '演示：下一次回答提交发生合成冲突；无真实服务器，也不自动覆盖原稿。';
    $('fault-banner-text').textContent = text;
    $('fault-mode-help').textContent = state.faultMode === 'normal' ? '正常演示模式。没有真实请求；所有变化仅留在本页内存。' : text;
  }
  function allowSave(kind, errorId, attempt = null) {
    if (state.faultMode === 'fail') {
      state.faultMode = 'normal';
      error(errorId, '演示保存失败：没有创建或覆盖业务记录，输入仍保留。已回到正常模式，请再次保存。');
      renderFault();
      announce('演示保存失败，输入已保留。没有发出网络请求。');
      return false;
    }
    if (kind === 'answer' && state.faultMode === 'conflict') {
      state.faultMode = 'normal';
      renderFault();
      if ($('end-dialog').open) closeDialog('end-dialog');
      state.pendingEnd = null;
      openConflict(attempt);
      error(errorId, '演示冲突：本次提交被拒绝，原稿未覆盖。请在冲突对话框中明确选择处理方式。');
      return false;
    }
    return true;
  }
  const dialogReturn = new Map();
  function openDialog(dialogId) {
    dialogReturn.set(dialogId, document.activeElement);
    const dialog = $(dialogId);
    if (!dialog.open) dialog.showModal();
  }
  function closeDialog(dialogId) { if ($(dialogId).open) $(dialogId).close(); }
  document.querySelectorAll('dialog').forEach(dialog => {
    dialog.addEventListener('close', () => {
      const origin = dialogReturn.get(dialog.id);
      if (origin?.isConnected && !origin.disabled) origin.focus();
    });
    dialog.addEventListener('cancel', () => {
      if (dialog.id === 'project-dialog') state.pendingProject = null;
      if (dialog.id === 'end-dialog') state.pendingEnd = null;
      if (dialog.id === 'conflict-dialog') retainConflict('cancelled');
      if (dialog.id === 'archive-dialog') state.archiveActionId = null;
      if (dialog.id === 'delete-dialog') state.deletionPlan = null;
    });
  });
  function navigate(view, focus = true) {
    state.view = view;
    document.querySelectorAll('[data-panel]').forEach(panel => { panel.hidden = panel.dataset.panel !== view; });
    document.querySelectorAll('.nav-item').forEach(item => {
      if (item.dataset.view === view) item.setAttribute('aria-current', 'page');
      else item.removeAttribute('aria-current');
    });
    if (window.matchMedia('(max-width: 760px)').matches) $('nav-menu').open = false;
    render();
    if (focus) document.querySelector(`[data-panel="${view}"] h1`).focus();
  }
  function render() {
    const target = latestTarget();
    $('top-target').textContent = `${target.role} · ${target.version ? `目标 v${target.version}` : '默认方向（未保存）'}`;
    renderFault();
    const views = { prepare: renderPrepare, learning: renderLearning, practice: renderPractice, materials: renderMaterials, history: renderHistory, actions: renderActions, comparison: renderComparison, data: renderData };
    views[state.view]();
  }
  function renderPrepare() {
    const target = latestTarget();
    $('target-version-title').textContent = `${target.role} · ${target.version ? `v${target.version}` : '未保存目标'}`;
    $('target-role').value = state.targetDraft.role;
    $('target-focus').value = state.targetDraft.focus;
    const active = state.attempts.find(attempt => attempt.status === 'active');
    const nextAction = [...state.actions].reverse().find(action => action.status === 'pending');
    $('prepare-next').hidden = !active && !nextAction;
    $('basic-entry').className = active || nextAction ? 'paper padded' : 'paper feature-sheet';
    $('start-basic').className = active || nextAction ? 'button secondary' : 'button primary';
    if (active) {
      $('prepare-next-title').textContent = `继续 ${active.question.id} · ${active.id}`;
      $('prepare-next-reason').textContent = `优先恢复尚未结束的尝试；目标v${active.target.version}，原输入仍留本页，不自动切换上下文。`;
      $('prepare-next-button').onclick = () => openAttempt(active.id);
    } else if (nextAction) {
      $('prepare-next-title').textContent = nextAction.action;
      $('prepare-next-reason').textContent = `来自已接受来源 ${nextAction.source.feedbackId} / ${nextAction.source.revisionId}；目前待复练，不是系统能力评分。`;
      $('prepare-next-button').onclick = () => requestStart(nextAction.questionId, nextAction.id);
    }
    $('prepare-history').replaceChildren();
    const recent = [...state.attempts].reverse().slice(0, 3);
    if (!recent.length) $('prepare-history').append(node('p', '本页历史已清空。可以从一道题重新开始。', 'muted space-top'));
    recent.forEach(attempt => {
      const row = node('div', '', 'record-row');
      const copy = node('div');
      copy.append(node('h3', attempt.question.title), node('p', `${attempt.seed ? '合成起点' : attempt.id} · ${attempt.status === 'ended' ? '已结束' : '进行中'} · ${latestRevision(attempt)?.label || '未提交'} · 目标 v${attempt.target.version}`));
      row.append(node('span', attempt.question.id, 'record-symbol'), copy, button(attempt.status === 'ended' ? '看原答' : '回到草稿', () => openAttempt(attempt.id), 'text-button'));
      $('prepare-history').append(row);
    });
  }
  function saveTarget(event) {
    event.preventDefault();
    const role = state.targetDraft.role.trim();
    const focus = state.targetDraft.focus.trim();
    if (!role || !focus) { error('target-error', '请填写目标名称与关注点，只用合成内容。', !role ? 'target-role' : 'target-focus'); return; }
    const target = latestTarget();
    if (target.version && target.role === role && target.focus === focus) { error('target-error', ''); announce('目标文字没有变化，没有重复创建目标版本。'); return; }
    if (!allowSave('target', 'target-error')) return;
    state.targets.push(frozen({ id: id('T'), version: (state.targets.at(-1)?.version || 0) + 1, role, focus, savedAt: now() }));
    state.targetDraft = { role, focus };
    error('target-error', '');
    changed();
    renderPrepare();
    render();
    announce(`已保存目标 v${latestTarget().version} 到本页内存；旧尝试的目标与 context 不变。`);
  }
  function renderLearning() {
    $('custom-topic').value = state.customDraft.topic;
    $('custom-prompt').value = state.customDraft.prompt;
    $('custom-source').value = state.customDraft.source;
    $('custom-source-note').value = state.customDraft.sourceNote;
    $('save-custom').textContent = state.custom ? '保存新的自建题版本' : '保存这道自建题';
    $('custom-version').textContent = state.custom ? `C01 · 题目 v${state.custom.version} · 参考：空 · 仍只有一个自建题位置` : '参考：空。自建题无材料前置。';
    const topic = $('topic-filter').value;
    const type = $('type-filter').value;
    const visible = [...questions, ...(state.custom ? [state.custom] : [])].filter(question => (topic === 'all' || (topic === 'custom' ? question.type === 'custom' : question.topic === topic)) && (type === 'all' || question.type === type));
    $('filter-state').textContent = `当前筛选显示 ${visible.length} 道题（题目数量，不是训练统计）。`;
    $('question-list').replaceChildren();
    visible.forEach(question => {
      const card = node('article', '', 'paper padded question-card');
      const tags = node('div', '', 'tags');
      tags.append(tag(question.id), tag(question.topic), tag(question.typeLabel), tag(`题目 v${question.version}`));
      card.append(tags, node('h2', question.title), node('p', question.prompt, 'question-snippet'));
      const footer = node('div', '', 'sheet-footer');
      footer.append(node('p', question.reference ? '固定参考，按需查看三层提示；由你亲自自检。' : '参考为空。维度与判断条件均由你自行定义。', 'small-print'), button(question.type === 'project' ? '确认事实并开始' : '开始这道题 →', () => requestStart(question.id), 'button primary'));
      card.append(footer);
      $('question-list').append(card);
    });
    if (!visible.length) $('question-list').append(node('div', '这个筛选下没有题目。可以切回“全部”，或保存一个自建题。', 'paper padded muted'));
  }
  function saveCustom(event) {
    event.preventDefault();
    const topic = state.customDraft.topic.trim();
    const prompt = state.customDraft.prompt.trim();
    const source = state.customDraft.source;
    const sourceNote = state.customDraft.sourceNote.trim();
    if (!topic || !prompt) { error('custom-error', '请填写合成主题与题干；参考明确留空。', !topic ? 'custom-topic' : 'custom-prompt'); return; }
    if (state.custom?.topic === topic && state.custom.prompt === prompt && state.custom.source === source && state.custom.sourceNote === sourceNote) { error('custom-error', ''); announce('自建题与来源未变化，没有重复创建版本。'); return; }
    if (!allowSave('question', 'custom-error')) return;
    state.custom = frozen({ id: 'C01', version: (state.custom?.version || 0) + 1, type: 'custom', typeLabel: '自建题', topic, title: '我的自建题', prompt, source, sourceNote, context: `参考为空，来源${source === 'personal' ? '个人整理' : source === 'recollection' ? '面后回忆（不确定）' : '公开链接（仅记录）'}；${sourceNote || '无备注'}。由用户定义自检，不认证正确性，不抓取链接。`, sample: '', reference: null, dimensions: [], hints: [], followups: [] });
    state.customDraft = { topic, prompt, source, sourceNote };
    error('custom-error', '');
    changed();
    renderLearning();
    announce(`单个自建题已保存为 v${state.custom.version}；旧尝试的题干不变。参考仍为空。`);
  }
  function openAttempt(attemptId) {
    state.currentAttemptId = attemptId;
    navigate('practice');
  }
  function requestStart(questionId, sourceActionId = null) {
    const source = sourceActionId ? actionById(sourceActionId) : null;
    const question = source?.subjectQuestion?.id.includes('/') ? source.subjectQuestion : questionById(questionId);
    if (!question) { announce('题目已不在本页；请重新选题。'); return; }
    if (sourceActionId && (!source || source.status !== 'pending')) { announce('该来源行动已处理或归档，不创建新的复练尝试。'); return; }
    const active = state.attempts.find(attempt => attempt.status === 'active');
    if (active && ((sourceActionId && active.sourceActionId === sourceActionId) || (!sourceActionId && !active.sourceActionId && active.question.id === questionId))) {
      openAttempt(active.id);
      announce('已回到同一个进行中尝试，没有重复创建 Attempt。要重新作答，请先显式结束它。');
      return;
    }
    if (active) {
      state.currentAttemptId = active.id;
      navigate('practice');
      openEnd({ questionId, sourceActionId });
      return;
    }
    if (source) {
      const original = attemptById(source.source.attemptId);
      if (!original || original.status !== 'ended') { if (original) openAttempt(original.id); announce('必须先显式结束原尝试，再开始来源复练。'); return; }
    }
    if (question.type === 'project') openProject({ questionId, sourceActionId });
    else startAttempt(question, sourceActionId, null);
  }
  function startAttempt(question, sourceActionId, material) {
    if (state.attempts.some(attempt => attempt.status === 'active')) { announce('还有进行中尝试，请先结束；没有创建新的 Attempt。'); return; }
    const attempt = makeAttempt(question, sourceActionId, material);
    state.attempts.push(attempt);
    state.currentAttemptId = attempt.id;
    changed();
    navigate('practice');
    announce(`已开始 ${attempt.id} · ${question.id}。新回答从空白开始${sourceActionId ? '，保留确切来源，提交不会自动关闭行动' : ''}。`);
  }
  function openProject(pending) {
    const material = latestMaterial();
    if (!material) { navigate('materials'); announce('本页没有已保存材料。请手工载入合成起点并确认；没有自动补回已删除记录。'); return; }
    state.pendingProject = { ...pending, materialId: material.id };
    $('project-confirm-facts').replaceChildren();
    const selected = confirmedFacts(material);
    material.rows.forEach(row => {
      if (!isConfirmable(row)) {
        $('project-confirm-facts').append(node('p', `${row.id} · 未知约束：${row.text}（不作为已确认事实）`, 'unknown-fact'));
        return;
      }
      const block = node('div', '', 'fact-check');
      const label = node('label', '', 'checkbox-row');
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.value = row.id;
      check.className = 'project-fact-check';
      check.checked = selected.includes(row.id);
      label.append(check, node('span', `${row.id} · ${row.text}`));
      block.append(label, node('p', `责任标签：${row.role} · 材料 v${material.version} · ${selected.includes(row.id) ? '此前已确认，本次仍由你选用' : '新内容待确认'}`));
      $('project-confirm-facts').append(block);
    });
    $('project-boundary').checked = false;
    $('project-use').checked = false;
    error('project-error', '');
    openDialog('project-dialog');
  }
  function confirmProjectStart() {
    const pending = state.pendingProject;
    if (!pending) return;
    const material = state.materials.find(item => item.id === pending.materialId);
    const selected = [...document.querySelectorAll('.project-fact-check:checked')].map(check => check.value).filter(factId => material?.rows.some(row => row.id === factId && isConfirmable(row)));
    if (!material || !selected.length || !$('project-boundary').checked || !$('project-use').checked) { error('project-error', '请确认至少一条非待核实事实、责任边界及本次训练用途；不授权任何外发。'); return; }
    if (!allowSave('fact-confirmation', 'project-error')) return;
    const previous = confirmedFacts(material);
    if (JSON.stringify(previous) !== JSON.stringify(selected)) state.materialConfirmations.push(frozen({ id: id('MC'), versionId: material.id, factIds: selected, savedAt: now(), source: 'project-entry' }));
    const snapshot = materialSnapshot(material, selected);
    const sourceAction = pending.sourceActionId ? actionById(pending.sourceActionId) : null;
    const question = sourceAction?.subjectQuestion?.id.includes('/') ? sourceAction.subjectQuestion : questionById(pending.questionId);
    state.pendingProject = null;
    closeDialog('project-dialog');
    if (question) startAttempt(question, pending.sourceActionId, snapshot);
  }
  function submitAttempt(attempt, errorId = 'answer-error') {
    if (!attempt || attempt.status !== 'active') { error(errorId, '已结束的尝试只读；请开始独立新尝试。'); return false; }
    if (!attempt.draft.trim()) { error(errorId, '回答还是空的。请先写合成回答，或返回编辑。', 'answer-input'); return false; }
    if (Array.from(attempt.draft).length > 20000) { error(errorId, '回答超过20,000 Unicode码点，本页保留原文但不提交，请先缩短。'); return false; }
    if (latestRevision(attempt)?.body === attempt.draft) { error(errorId, ''); announce('草稿与最新提交完全相同，没有重复创建回答版本。'); return true; }
    if (!allowSave('answer', errorId, attempt)) return false;
    const revision = addRevision(attempt, attempt.draft);
    error(errorId, '');
    error('answer-error', '');
    changed();
    announce(`${attempt.id} 的 ${revision.label} 已成为不可变提交。下一步由你选择原文并自检；没有自动评价，也没有自动结项。`);
    return true;
  }
  function beginRevision() {
    const attempt = currentAttempt();
    if (!attempt || attempt.status !== 'active' || !latestRevision(attempt)) return;
    attempt.editing = true;
    // 同 Attempt 可在最新提交上补充；来源复练的 makeAttempt 始终从空白草稿开始。
    if (!dirtyAnswer(attempt)) attempt.draft = latestRevision(attempt).body;
    renderPractice();
    $('answer-input').focus();
    announce(`正在补充修订，下一次不同内容提交为 R${attempt.revisions.length + 1}。旧 R1 与自检来源仍只读。`);
  }
  function fillSample() {
    const attempt = currentAttempt();
    if (!attempt || attempt.status !== 'active' || !attempt.editing || !attempt.question.sample) return;
    if (attempt.draft.trim()) { error('answer-error', '为避免覆盖已有输入，合成例答只可填入空白草稿。请保留并自行编辑现有文字。'); return; }
    attempt.draft = attempt.question.sample;
    attempt.draftVersion += 1;
    $('answer-input').value = attempt.draft;
    updateDraftState();
    error('answer-error', '');
    $('answer-input').focus();
    announce('已主动填入固定合成例答。它不是标准答案，也未自动生成任何自检。');
  }
  function updateDraftState() {
    const attempt = currentAttempt();
    if (!attempt) return;
    $('answer-state').textContent = attempt.status === 'ended' ? '已结束 · 只读 · 新作答需新 Attempt' : dirtyAnswer(attempt) ? `本页草稿 v${attempt.draftVersion} · 未提交（dirty）` : latestRevision(attempt) ? `${latestRevision(attempt).label} 已提交 · 没有更新的草稿` : '空白草稿 · 未提交';
  }
  function renderPractice() {
    const attempt = currentAttempt();
    $('practice-empty').hidden = Boolean(attempt);
    $('practice-body').hidden = !attempt;
    $('practice-end').hidden = !attempt || attempt.status === 'ended';
    $('practice-new').hidden = !attempt || attempt.status !== 'ended';
    if (!attempt) { $('practice-subtitle').textContent = '还没有当前尝试 · 自由训练 · 不计时'; return; }
    const ended = attempt.status === 'ended';
    const latest = latestRevision(attempt);
    $('practice-subtitle').textContent = `${attempt.id} · ${attempt.question.id} · ${ended ? '已结束，冻结提交清单' : '进行中'} · ${attempt.seed ? '只读合成起点 · ' : ''}自由训练 / 不计时`;
    $('attempt-tags').replaceChildren(tag(attempt.question.typeLabel), tag(attempt.question.topic), tag(`题目 v${attempt.question.version}`), tag(`${attempt.id}${attempt.seed ? ' · 合成起点' : ''}`), tag(ended ? '已结束' : '进行中', ended ? 'closed' : ''));
    $('attempt-prompt').textContent = attempt.question.prompt;
    $('attempt-context').textContent = attempt.question.context;
    $('attempt-facts').hidden = !attempt.material;
    if (attempt.material) $('attempt-facts').textContent = `本次固定材料 v${attempt.material.version}：\n${attempt.material.rows.map(row => `${row.id} · ${row.unknown || row.role === '待核实' ? '未知约束，待核实' : row.confirmed ? row.role : '未确认，不当作事实'} · ${row.text}`).join('\n')}`;
    const action = attempt.sourceActionId ? actionById(attempt.sourceActionId) : null;
    $('repeat-source-note').hidden = !action;
    if (action) $('repeat-source-note').textContent = `来源 ${action.id} · ${action.source.feedbackId} · ${action.source.revisionId}\n原动作：${action.action}\n判断条件：${action.condition}\n新答案默认空白；提交只新增 revision，不自动改变这个行动。`;
    $('answer-label').textContent = ended ? attempt.excludedDraft ? '明确未交草稿（保留只读，不纳入冻结提交）' : '最新提交原文（只读）' : latest ? attempt.editing ? `补充修订草稿 → R${attempt.revisions.length + 1}` : `${latest.label} 提交原文（补充修订后才能编辑）` : '回答草稿 → R1';
    $('answer-input').value = ended && !attempt.excludedDraft ? latest?.body || '' : attempt.draft;
    $('answer-input').readOnly = ended || !attempt.editing;
    $('begin-revision').hidden = ended || !latest || attempt.editing;
    $('submit-answer').hidden = ended;
    $('submit-answer').disabled = !attempt.editing;
    $('submit-answer').textContent = `提交为 R${attempt.revisions.length + 1} →`;
    $('fill-sample').hidden = ended || !attempt.question.sample;
    $('fill-sample').disabled = !attempt.editing;
    updateDraftState();
    $('excluded-draft').hidden = !attempt.excludedDraft;
    if (attempt.excludedDraft) $('excluded-draft').textContent = `${attempt.excludedDraft.reason}\n冻结清单：${attempt.manifest.revisionIds.join('、') || '无已交回答'}。这份草稿可在历史中查看，但不是一次提交。`;
    $('attempt-version-note').textContent = `固定 context：目标 v${attempt.target.version || '默认'} · 题目 v${attempt.question.version} · ${attempt.material ? `材料 v${attempt.material.version}` : '无需材料'} · ${attempt.question.reference || '参考为空'}`;
    renderReview(attempt);
    renderHints(attempt);
    renderFollowups(attempt);
    $('attempt-note').value = attempt.noteDraft;
    $('attempt-note').readOnly = ended;
    $('save-note').disabled = ended;
    $('note-state').textContent = attempt.noteSaved ? `本页内存笔记 ${attempt.noteSaved.id} · ${attempt.noteDraft === attempt.noteSaved.body ? '当前稿已保存' : '当前稿仍未保存'}${ended ? ' · 结束后只读' : ''}` : ended ? '本次没有已保存笔记；已结束，不能补写到旧 Attempt。' : '尚未保存笔记 · 每 Attempt 仅一条，可在结束前显式更新';
    const unsavedNote = ended && attempt.noteDraft !== (attempt.noteSaved?.body || '');
    $('unsaved-note').hidden = !unsavedNote;
    if (unsavedNote) $('unsaved-note').textContent = `未保存笔记草稿保留只读，没有纳入结束清单。此前已保存笔记：${attempt.noteSaved?.body || '无'}。`;
    $('practice-comparison-entry').hidden = !attempt.sourceActionId;
    $('open-current-comparison').disabled = !latest;
    $('comparison-entry-help').textContent = latest ? `选择 ${latest.label} 进入用户对照；当前行动仍为 ${actionStatus(action)}。` : '先独立提交新回答，再打开对照。';
  }
  function renderReview(attempt) {
    const revision = reviewRevision(attempt);
    if (revision) attempt.reviewRevisionId = revision.id;
    options($('review-revision'), allAnswerRevisions(attempt).map(item => ({ value: item.id, label: `${item.label} · ${item.id} · 只读原文` })), attempt.reviewRevisionId);
    $('review-revision').disabled = !revision;
    $('original-answer').value = revision?.body || '';
    $('original-metadata').textContent = revision ? `${revision.id} · ${revision.hash} · ${revision.codePointLength} Unicode 码点 · 演示 hash 非密码学摘要` : '尚未提交。提交后才能创建原文绑定。';
    const cannotEdit = !revision || attempt.status === 'ended';
    $('use-selection').disabled = cannotEdit;
    $('bind-missing').disabled = cannotEdit;
    const rows = [...(attempt.question.dimensions || []).map(value => ({ value, label: value })), { value: 'custom', label: '由我自定义维度' }];
    options($('review-dimension'), rows, attempt.selfcheckDraft.dimension);
    $('dimension-choice').hidden = !attempt.question.reference;
    $('dimension-custom-field').hidden = Boolean(attempt.question.reference) && attempt.selfcheckDraft.dimension !== 'custom';
    $('review-dimension-custom').value = attempt.selfcheckDraft.custom;
    $('review-type').value = attempt.selfcheckDraft.type;
    $('review-applicable').value = attempt.selfcheckDraft.applicable;
    $('review-limit').value = attempt.selfcheckDraft.limitation;
    $('review-gap').value = attempt.selfcheckDraft.gap;
    $('review-action').value = attempt.selfcheckDraft.action;
    $('review-condition').value = attempt.selfcheckDraft.condition;
    $('selfcheck-form').querySelectorAll('input,textarea,select,button').forEach(field => { field.disabled = cannotEdit; });
    renderEvidence(attempt);
    $('feedback-list').replaceChildren();
    const list = state.feedback.filter(item => item.attemptId === attempt.id);
    if (!list.length) $('feedback-list').append(node('p', '尚无用户自检。提交不会自动生成“缺口”或行动。', 'muted'));
    list.forEach(item => $('feedback-list').append(feedbackCard(item)));
  }
  function renderEvidence(attempt) {
    const evidence = attempt.evidence;
    $('evidence-preview').textContent = evidence ? `${evidence.kind === 'missing' ? '用户声明“未提及” · 整回答锚定，不是系统推断' : '用户亲自选择的原文'}\n${evidence.revisionId} · Unicode 码点半开区间 [${evidence.start}, ${evidence.end})\n原答 ${evidence.answerHash} · 引文 ${evidence.quoteHash}\nquote：${evidence.quote}` : attempt.revisions.length ? '还未绑定证据。请选原文，或明确“未提及”并绑定整回答。' : '提交回答后，再亲自绑定证据。';
  }
  function bindEvidence(missing = false) {
    const attempt = currentAttempt();
    const revision = attempt && reviewRevision(attempt);
    if (!revision || attempt.status !== 'active') return;
    let selected;
    if (missing) selected = { start: 0, end: Array.from(revision.body).length, quote: revision.body };
    else {
      selected = rangeFromSelection(revision.body, $('original-answer').selectionStart, $('original-answer').selectionEnd);
      if (!selected || selected.start === selected.end) { error('selfcheck-error', '请在只读提交原文里选一段完整文字；选区不能切开 Unicode 字符，也不能为空。'); $('original-answer').focus(); return; }
    }
    attempt.evidence = frozen({ revisionId: revision.id, kind: missing ? 'missing' : 'quote', ...selected, answerHash: revision.hash, quoteHash: hash(selected.quote) });
    error('selfcheck-error', '');
    renderEvidence(attempt);
  }
  function saveSelfcheck(event) {
    event.preventDefault();
    const attempt = currentAttempt();
    const revision = attempt && reviewRevision(attempt);
    if (!revision || attempt.status !== 'active') { error('selfcheck-error', '没有可编辑的提交来源。已结束只能开始新尝试。'); return; }
    const evidence = attempt.evidence;
    if (!evidence || evidence.revisionId !== revision.id || evidence.answerHash !== revision.hash || Array.from(revision.body).slice(evidence.start, evidence.end).join('') !== evidence.quote) { error('selfcheck-error', '请先亲自绑定当前确切 revision 的原文，或明确声明“未提及”。'); return; }
    const draft = attempt.selfcheckDraft;
    const dimension = (draft.dimension === 'custom' || !attempt.question.reference ? draft.custom : draft.dimension).trim();
    const type = draft.type;
    const applicable = draft.applicable.trim();
    const limitation = draft.limitation.trim();
    const gap = draft.gap.trim();
    const action = draft.action.trim();
    const condition = draft.condition.trim();
    if (!dimension || !type || !applicable || !limitation || !gap || !action || !condition) { error('selfcheck-error', '请填写维度、判断类型、适用条件、限制、缺口、动作与判断条件；参考为空时由你自行定义。'); return; }
    const value = { attemptId: attempt.id, revisionId: revision.id, evidence: clone(evidence), dimension, type, applicable, limitation, gap, action, condition };
    const key = feedbackKey(value);
    const previous = state.feedback.find(item => item.key === key);
    if (previous) { error('selfcheck-error', ''); announce(`相同自检已保存为 ${previous.id}，没有创建重复业务记录。`); return; }
    if (!allowSave('selfcheck', 'selfcheck-error')) return;
    const subjectPreset = revision.presetId ? attempt.followups[revision.presetId].preset : null;
    const subjectQuestion = subjectPreset ? frozen({ ...clone(attempt.question), id: `${attempt.question.id}/${subjectPreset.id}`, title: subjectPreset.title, prompt: subjectPreset.prompt, context: `${attempt.question.context}\n本轮预设条件仅用于此追问，不改原主题事实。`, sample: '', followups: [] }) : attempt.question;
    const feedback = frozen({ id: id('FB'), ...value, key, questionId: attempt.question.id, subjectQuestion, questionVersion: attempt.question.version, kind: '用户自检（非AI）', savedAt: now() });
    state.feedback.push(feedback);
    changed();
    error('selfcheck-error', '');
    renderReview(attempt);
    announce(`已保存用户自检 ${feedback.id} 到本页内存，固定绑定 ${revision.id}。可主动接受动作，不会自动创建复练或评价。`);
  }
  function feedbackCard(feedback) {
    const fieldErrorId = `accept-error-${state.view}-${feedback.id}`;
    const card = node('article', '', 'feedback-card');
    card.append(node('p', `${feedback.id} · ${feedback.kind} · ${feedback.revisionId}`, 'small-print hash-line'), node('h4', feedback.dimension));
    card.append(node('p', feedback.evidence.kind === 'missing' ? '用户声明“未提及”，整回答绑定' : '用户选择的原文', 'small-print'), node('blockquote', feedback.evidence.quote, 'evidence-quote'));
    card.append(node('p', `Unicode [${feedback.evidence.start}, ${feedback.evidence.end}) · ${feedback.evidence.answerHash} · quote ${feedback.evidence.quoteHash}`, 'small-print hash-line'));
    const typeLabels = { condition: '条件未交代', 'not-observed': '未观察到', tradeoff: '开放取舍', expression: '表达建议', conflict: '给定条件下的明确冲突' };
    card.append(node('p', `类型：${typeLabels[feedback.type]}`), node('p', `适用条件：${feedback.applicable}`), node('p', `限制／缺证据：${feedback.limitation}`), node('p', `缺口：${feedback.gap}`), node('p', `动作：${feedback.action}`, 'space-top'), node('p', `判断条件：${feedback.condition}`, 'space-top'));
    const accepted = state.actions.find(action => action.key === actionKey(feedback));
    const row = node('div', '', 'button-row');
    row.append(button(accepted ? `已接受为 ${accepted.id}` : '接受这个动作，生成来源复练', () => acceptAction(feedback.id, fieldErrorId), 'button secondary', Boolean(accepted)));
    if (accepted) row.append(button('查看这个来源行动', () => navigate('actions'), 'text-button'));
    if (!accepted) {
      const decisionLabel = node('label', '争议或忽略的说明（争议必填）');
      const decisionInput = node('textarea', '', 'compact');
      decisionInput.id = `decision-${state.view}-${feedback.id}`;
      decisionLabel.htmlFor = decisionInput.id;
      decisionInput.value = state.feedbackDecisions[feedback.id]?.reason || '';
      row.append(button('标记争议', () => recordDecision(feedback.id, 'dispute', decisionInput.value, fieldErrorId), 'text-button'), button('忽略这项建议', () => recordDecision(feedback.id, 'ignore', decisionInput.value, fieldErrorId), 'text-button'));
      card.append(decisionLabel, decisionInput);
    }
    const decision = state.feedbackDecisions[feedback.id];
    if (decision) card.append(node('p', `用户决定：${{ dispute: '争议', ignore: '忽略', accept: '接受动作' }[decision.kind]} · ${decision.reason || '未填说明'}；原自检仍保留。`, 'small-print space-top'));
    card.append(row);
    const fieldError = node('p', '', 'field-error');
    fieldError.id = fieldErrorId;
    fieldError.setAttribute('aria-live', 'polite');
    card.append(fieldError);
    return card;
  }
  function recordDecision(feedbackId, kind, reason, errorId) {
    if (!state.feedback.some(item => item.id === feedbackId)) return;
    if (kind === 'dispute' && !reason.trim()) { error(errorId, '请说明争议原因，原判断与来源不会被删除。'); return; }
    if (!allowSave('decision', errorId)) return;
    state.feedbackDecisions[feedbackId] = frozen({ kind, reason: reason.trim(), savedAt: now(), previous: state.feedbackDecisions[feedbackId] || null });
    changed();
    render();
    announce('用户决定已单独记录，未改原自检、未自动生成行动。');
  }
  function acceptAction(feedbackId, errorId) {
    const feedback = state.feedback.find(item => item.id === feedbackId);
    if (!feedback) return;
    const key = actionKey(feedback);
    const previous = state.actions.find(action => action.key === key);
    if (previous) { announce(`同一来源与动作已经是 ${previous.id}，没有重复创建复练记录。`); return; }
    if (!allowSave('action', errorId)) return;
    state.feedbackDecisions[feedbackId] = frozen({ kind: 'accept', reason: '用户明确接受动作', savedAt: now(), previous: state.feedbackDecisions[feedbackId] || null });
    state.actions.push({ id: id('ACT'), key, source: frozen({ feedbackId: feedback.id, attemptId: feedback.attemptId, revisionId: feedback.revisionId, evidence: clone(feedback.evidence) }), questionId: feedback.questionId, subjectQuestion: feedback.subjectQuestion, action: feedback.action, condition: feedback.condition, dimension: feedback.dimension, status: 'pending', acceptedAt: now(), archiveReason: null, events: [] });
    changed();
    render();
    announce('已按 feedbackId + 动作创建来源复练。题号相同但来源不同的行动会各自保留；开始前要先结束原尝试。');
  }
  function phaseFor(attempt) { return attempt.editing && attempt.draft.trim() ? '作答中' : attempt.revisions.length ? '提交后' : '作答前'; }
  function hintSummary(attempt, snapshot = attempt.hints) {
    if (!attempt.question.reference) return '参考为空；没有提示或伪标准。';
    return snapshot.length ? snapshot.map(item => `第${item.level}层 / ${item.phase} / ${item.purpose}`).join('；') : '未使用固定参考提示。';
  }
  function renderHints(attempt) {
    $('hint-list').replaceChildren();
    if (!attempt.question.reference) $('hint-list').append(node('p', '参考：空。请自行定义维度与判断条件；没有可展开的标准提示。', 'small-print'));
    attempt.question.hints.forEach((hint, index) => {
      const detail = node('details', '', 'hint');
      detail.append(node('summary', hint.title, 'hint-summary-label'), node('p', hint.text));
      let wasOpen = false;
      detail.addEventListener('toggle', () => {
        if (detail.open && !wasOpen && detail.isConnected && attempt.status === 'active') {
          attempt.hints.push(frozen({ id: id('HINT'), level: index + 1, phase: phaseFor(attempt), purpose: hint.purpose, viewedAt: now(), latestRevisionId: latestRevision(attempt)?.id || null }));
          changed();
          $('hint-log').textContent = `本次提示展开记录：${hintSummary(attempt)}`;
        }
        wasOpen = detail.open;
      });
      $('hint-list').append(detail);
    });
    $('hint-log').textContent = `${attempt.status === 'ended' ? '已结束，重新查看不改写原提示记录。' : '本页记录：'}${hintSummary(attempt)}`;
  }
  function renderFollowups(attempt) {
    $('followup-section').hidden = !attempt.question.followups.length;
    $('followup-list').replaceChildren();
    attempt.question.followups.forEach(preset => {
      const item = attempt.followups[preset.id];
      const card = node('article', '', 'followup-card');
      const ended = attempt.status === 'ended';
      const selected = item.status !== 'unselected';
      const skipped = item.status === 'skipped';
      card.append(node('p', `${preset.id} · 预设题 v1 · ${!selected ? '尚未主动追加' : skipped ? `已跳过：${item.skipReason}` : '用户主动追加'}`, 'small-print'), node('h3', preset.title));
      const fieldError = node('p', '', 'field-error');
      fieldError.id = `followup-error-${preset.id}`;
      fieldError.setAttribute('aria-live', 'polite');
      if (!selected) {
        card.append(button('主动追加这道预设追问', () => chooseFollowup(attempt, preset.id, false), 'button secondary space-top', ended || !attempt.revisions.length));
        if (!attempt.revisions.length && !ended) card.append(node('p', '先提交主回答，再选择相关追问；不自动追加。', 'small-print'));
      } else {
        card.append(node('p', preset.prompt, 'muted space-top'));
        if (item.answer) card.append(node('pre', item.answer.body, 'answer-copy'), node('p', `${item.answer.id} · 不可变 · ${item.answer.hash}`, 'small-print hash-line'));
        if (skipped) card.append(button('返回这道已跳过题', () => chooseFollowup(attempt, preset.id, false), 'button secondary space-top', ended));
        else {
          if (!item.answer || item.editing) {
            const label = node('label', '这道预设追问的合成回答');
            const textarea = node('textarea', '', 'compact');
            textarea.id = `followup-input-${attempt.id}-${preset.id}`;
            textarea.value = item.draft;
            textarea.readOnly = ended;
            label.htmlFor = textarea.id;
            textarea.addEventListener('input', () => { item.draft = textarea.value; });
            textarea.setAttribute('aria-describedby', fieldError.id);
            card.append(label, textarea, button(`提交追问 R${(item.revisions?.length || 0) + 1}`, () => submitFollowup(attempt, preset.id), 'button secondary space-top', ended));
          } else card.append(button('补充追问修订', () => { item.editing = true; renderFollowups(attempt); }, 'button secondary space-top', ended));
          if (ended && item.draft !== (item.answer?.body || '') && item.draft.trim()) card.append(node('p', '未交追问草稿保留只读，不纳入结束清单。', 'deleted-draft space-top'));
        }
        if (!ended && !skipped) {
          const reasonLabel = node('label', '跳过原因（只跳过本轮，不删除提交或草稿）');
          const reasonInput = node('input');
          reasonInput.id = `followup-reason-${preset.id}`;
          reasonInput.value = item.skipReason || '';
          reasonLabel.htmlFor = reasonInput.id;
          reasonInput.addEventListener('input', () => { item.skipReason = reasonInput.value; });
          card.append(reasonLabel, reasonInput, button('按原因跳过本轮', () => chooseFollowup(attempt, preset.id, true), 'text-button'));
        }
      }
      card.append(fieldError);
      $('followup-list').append(card);
    });
  }
  function chooseFollowup(attempt, presetId, skip) {
    const item = attempt.followups[presetId];
    if (attempt.status !== 'active' || !attempt.revisions.length) return;
    if (skip && !item.skipReason?.trim()) { error(`followup-error-${presetId}`, '请先填写跳过原因；草稿与旧提交会保留。'); return; }
    if (skip) item.status = 'skipped';
    else if (item.status === 'unselected' || item.status === 'skipped') item.status = 'chosen';
    else return;
    item.chosenAt = now();
    changed();
    renderFollowups(attempt);
    announce(skip ? '按原因跳过预设轮次，草稿与修订保留；结束前可以返回。' : '用户主动追加／返回预设轮次，固定题干与条件，不是自动追问。');
  }
  function submitFollowup(attempt, presetId) {
    const item = attempt.followups[presetId];
    if (attempt.status !== 'active' || item.status !== 'chosen') return;
    if (!item.draft.trim()) { error(`followup-error-${presetId}`, '请填写合成回答，或按原因跳过；空白不当已答。'); return; }
    if (Array.from(item.draft).length > 20000) { error(`followup-error-${presetId}`, '追问回答超过20,000码点，保留输入但不提交。'); return; }
    if (item.answer?.body === item.draft) { announce('相同追问提交已存在，没有重复创建修订。'); return; }
    if (!allowSave('followup', `followup-error-${presetId}`)) return;
    item.revisions = item.revisions || [];
    item.answer = frozen({ id: `${attempt.id}/${presetId}/R${item.revisions.length + 1}`, label: `${presetId} R${item.revisions.length + 1}`, body: item.draft, hash: hash(item.draft), codePointLength: Array.from(item.draft).length, submittedAt: now(), presetId, questionVersion: 1, hintSnapshot: clone(attempt.hints) });
    item.revisions.push(item.answer);
    item.editing = false;
    attempt.reviewRevisionId = item.answer.id;
    attempt.evidence = null;
    changed();
    renderFollowups(attempt);
    renderReview(attempt);
    announce('预设追问新增不可变修订，可在自检版本中选择；不覆盖主回答或旧追问。');
  }
  function saveNote(event) {
    event.preventDefault();
    const attempt = currentAttempt();
    if (!attempt || attempt.status !== 'active') { error('note-error', '已结束的 Attempt 不能补写笔记；请开始新尝试。'); return; }
    if (!attempt.noteDraft.trim()) { error('note-error', '笔记可不填写；如果保存，请先写一条合成笔记。'); return; }
    if (attempt.noteSaved?.body === attempt.noteDraft) { error('note-error', ''); announce('这条练后笔记未变化，没有重复创建记录。'); return; }
    if (!allowSave('note', 'note-error')) return;
    attempt.noteSaved = frozen({ id: attempt.noteSaved?.id || id('NOTE'), attemptId: attempt.id, body: attempt.noteDraft, savedAt: now() });
    changed();
    error('note-error', '');
    renderPractice();
    announce('练后笔记已显式保存到本页内存，每个 Attempt 仅一条。不是服务端保存。');
  }
  function openEnd(next = null) {
    const attempt = currentAttempt();
    if (!attempt || attempt.status !== 'active') return;
    state.pendingEnd = { attemptId: attempt.id, next };
    const dirty = dirtyAnswer(attempt);
    const unsentFollowups = Object.values(attempt.followups).filter(item => item.draft.trim() && !item.answer).length;
    const unsentNote = attempt.noteDraft !== (attempt.noteSaved?.body || '');
    $('end-dialog-help').textContent = `${attempt.id}：${next ? '开始另一尝试前，请显式结束当前来源。' : '结束后主回答、用户自检与笔记都只读。'}后续只能新建 Attempt，旧来源不会移到新版本。`;
    const manifest = node('div', '', 'confirmation-summary');
    manifest.append(node('p', `将冻结的已提交清单：${attempt.revisions.map(revision => `${revision.label} (${revision.id})`).join('；') || '空清单（目前没有已提交主回答）'}`), node('p', `最新提交：${latestRevision(attempt)?.id || '无'}；已交预设追问：${savedFollowups(attempt).length} 条；已保存笔记：${attempt.noteSaved ? '1 条' : '无'}。`));
    $('end-manifest').replaceChildren(manifest);
    $('end-draft-warning').hidden = !(dirty || unsentFollowups || unsentNote);
    $('end-draft-warning').textContent = `${dirty ? '警告：有未交的新回答草稿。仅用已提交版本结束，会明确保留这份草稿为只读排除记录，不纳入冻结提交清单。\n' : ''}${unsentFollowups ? `${unsentFollowups} 条追问草稿尚未提交，会保留只读但不纳入清单。\n` : ''}${unsentNote ? '笔记当前稿未保存，会保留只读但不纳入已保存笔记。' : ''}`;
    $('end-submitted-only').textContent = attempt.revisions.length ? dirty ? '仅用旧提交结束（新草稿不纳入）' : '仅用已提交版本结束' : '不提交，明确结束空清单';
    $('submit-and-end').hidden = !dirty;
    $('submit-and-end').disabled = !attempt.draft.trim();
    error('end-error', '');
    openDialog('end-dialog');
  }
  function finishEnd(includeDraft) {
    const pending = state.pendingEnd;
    const attempt = pending && attemptById(pending.attemptId);
    if (!attempt || attempt.status !== 'active') return;
    if (includeDraft) {
      if (!submitAttempt(attempt, 'end-error')) return;
    } else if (!allowSave('end', 'end-error')) return;
    freezeAttempt(attempt, includeDraft ? '用户选择提交新草稿后结束。' : '用户明确选择仅用已提交清单结束，未交草稿不纳入。');
    changed();
    state.pendingEnd = null;
    closeDialog('end-dialog');
    render();
    announce(`${attempt.id} 已显式结束，最新提交清单冻结${attempt.excludedDraft ? '；未交草稿明确保留但未纳入' : ''}。`);
    if (pending.next) requestStart(pending.next.questionId, pending.next.sourceActionId);
  }

  state.materialConfirmDrafts = {};
  function renderMaterials() {
    const material = latestMaterial();
    const confirmed = material ? confirmedFacts(material) : [];
    $('material-version-title').textContent = material ? `扫码领奖 · 合成材料 v${material.version}` : '合成材料 · 本页记录为空';
    $('material-version-state').textContent = !material ? '未载入' : state.materialDirty ? '有未保存新文字' : confirmed.length ? `${confirmed.length} 条 F 事实已确认 · U1 仍未知` : '新内容待确认';
    $('material-empty').hidden = Boolean(material);
    $('material-form').hidden = !material;
    $('material-start-project').disabled = !material;
    $('material-editor').replaceChildren();
    if (material) {
      const chosen = state.materialConfirmDrafts[material.id] || confirmed;
      state.materialDraft.forEach((row, index) => {
        const block = node('div', '', 'material-row');
        block.append(node('h3', `${row.id} · ${row.unknown ? '未知约束，不能升级为事实' : '合成事实与责任边界'}`));
        const label = node('label', '手工编辑的合成文字');
        const textarea = node('textarea', '', 'compact');
        textarea.id = `material-text-${row.id}`;
        textarea.value = row.text;
        textarea.maxLength = 2000;
        label.htmlFor = textarea.id;
        textarea.addEventListener('input', () => {
          state.materialDraft[index].text = textarea.value;
          state.materialDirty = JSON.stringify(state.materialDraft) !== JSON.stringify(material.rows);
          $('material-version-state').textContent = state.materialDirty ? '有未保存新文字 · 保存后待确认' : '当前稿与保存版本一致';
        });
        block.append(label, textarea);
        const roleLabel = node('label', '责任标签');
        const select = node('select');
        select.id = `material-role-${row.id}`;
        roleLabel.htmlFor = select.id;
        options(select, ['本人负责', '参与了解', '待核实'].map(value => ({ value, label: value })), row.role);
        select.disabled = row.unknown;
        select.addEventListener('change', () => {
          state.materialDraft[index].role = select.value;
          state.materialDirty = JSON.stringify(state.materialDraft) !== JSON.stringify(material.rows);
          $('material-version-state').textContent = state.materialDirty ? '责任标签有变化 · 保存后待确认' : '当前稿与保存版本一致';
        });
        block.append(roleLabel, select);
        if (isConfirmable(row)) {
          const checkLabel = node('label', '', 'checkbox-row');
          const check = document.createElement('input');
          check.type = 'checkbox';
          check.value = row.id;
          check.checked = chosen.includes(row.id);
          check.className = 'material-confirm-check';
          check.addEventListener('change', () => { state.materialConfirmDrafts[material.id] = [...document.querySelectorAll('.material-confirm-check:checked')].map(input => input.value); });
          checkLabel.append(check, node('span', `确认 ${row.id} 在当前已保存 v${material.version} 中的文字与责任标签`));
          block.append(checkLabel);
        }
        block.append(node('p', row.unknown || row.role === '待核实' ? '待核实条件，不提供事实确认入口。' : '修改后先保存新版本，再重新确认；未确认的新文字不进入可引用事实。', 'small-print'));
        $('material-editor').append(block);
      });
    }
    $('material-history').replaceChildren();
    if (!state.materials.length) $('material-history').append(node('p', '没有已保存材料版本。旧记录不会自动恢复。', 'muted'));
    [...state.materials].reverse().forEach(version => {
      const block = node('div', '', 'material-version');
      const ids = confirmedFacts(version);
      block.append(node('h3', `v${version.version} · ${version.id}${version.seed ? ' · 合成起点' : ''}`), node('p', `确认范围：${ids.join(' / ') || '尚无确认事实'}；U1 始终待核实。`, 'small-print'));
      const list = node('ul');
      version.rows.forEach(row => list.append(node('li', `${row.id} · ${row.role} · ${row.text}`)));
      block.append(list);
      $('material-history').append(block);
    });
  }
  function saveMaterial(event) {
    event.preventDefault();
    const material = latestMaterial();
    if (!material) return;
    const rows = state.materialDraft.map(row => ({ ...row, text: row.text.trim(), role: row.unknown ? '待核实' : row.role }));
    if (rows.some(row => !row.text)) { error('material-error', '请保留每条合成文字的内容；未知也要写明，不能用空白冒充已核实。'); return; }
    if (JSON.stringify(rows) === JSON.stringify(material.rows)) { error('material-error', ''); announce('材料内容没有变化，没有重复创建版本。可以单独确认已有版本。'); return; }
    if (!allowSave('material', 'material-error')) return;
    const version = frozen({ id: id('M'), version: material.version + 1, rows: clone(rows), savedAt: now(), seed: false });
    state.materials.push(version);
    state.materialDraft = clone(rows);
    state.materialDirty = false;
    state.materialConfirmDrafts[version.id] = [];
    changed();
    error('material-error', '');
    renderMaterials();
    announce(`新合成材料 v${version.version} 已保存到本页内存，全部新版本 F 内容待重新确认。旧尝试的材料快照不变。`);
  }
  function confirmMaterial() {
    const material = latestMaterial();
    if (!material) return;
    if (state.materialDirty) { error('material-error', '当前文字或责任标签还未保存。请先保存新版本，再确认新内容；不把当前稿套入旧版本。'); return; }
    const selected = [...document.querySelectorAll('.material-confirm-check:checked')].map(check => check.value).filter(factId => material.rows.some(row => row.id === factId && isConfirmable(row)));
    if (!selected.length) { error('material-error', '至少主动确认一条 F 事实。U1 不是确认对象；不选的 F 内容不会作为可引用事实。'); return; }
    if (JSON.stringify(selected) === JSON.stringify(confirmedFacts(material))) { error('material-error', ''); announce('同一版本与确认范围已经保存，没有重复创建确认记录。'); return; }
    if (!allowSave('fact-confirmation', 'material-error')) return;
    state.materialConfirmations.push(frozen({ id: id('MC'), versionId: material.id, factIds: selected, savedAt: now(), source: 'material-page' }));
    changed();
    error('material-error', '');
    renderMaterials();
    announce(`已确认材料 v${material.version} 的 ${selected.join(' / ')}，仅本页内存。U1 仍为未知约束。`);
  }
  function loadMaterial() {
    if (latestMaterial()) return;
    const version = frozen({ id: id('M'), version: 1, rows: clone(facts), savedAt: now(), seed: true });
    state.materials.push(version);
    state.materialDraft = clone(facts);
    state.materialDirty = false;
    state.materialConfirmDrafts[version.id] = [];
    changed();
    renderMaterials();
    announce('已由你点击载入新的合成起点；F 事实尚待确认，U1 仍未知。没有读取真实材料。');
  }
  function fixedContext(attempt, revision) {
    return `目标 v${attempt.target.version || '默认'}：${attempt.target.role} / ${attempt.target.focus}\n题目 ${attempt.question.id} v${attempt.question.version} · 参考 ${attempt.question.reference || '空（用户自定义条件）'}\n${attempt.material ? `材料 v${attempt.material.version} · 已确认 F：${attempt.material.selectedFactIds.join(' / ')} · U1 待核实` : '材料：无需材料'}\n提交时提示用途：${hintSummary(attempt, revision?.hintSnapshot || [])}\n${attempt.status === 'ended' ? `结束时提示记录：${hintSummary(attempt, attempt.manifest.hintSnapshot)}` : '尚未结束；提交后提示可能继续产生新记录，但不会回填旧提交快照。'}`;
  }
  function renderHistory() {
    $('history-list').replaceChildren();
    if (!state.attempts.length) {
      const empty = node('article', '', 'paper padded empty-state');
      empty.append(node('h2', '本页没有历史记录。'), node('p', '删除后的合成起点不会悄悄恢复。可以从基础题新建尝试。'), button('选择题目', () => navigate('learning')));
      $('history-list').append(empty);
      return;
    }
    [...state.attempts].reverse().forEach(attempt => {
      const card = node('article', '', 'paper padded history-sheet');
      const header = node('div', '', 'history-header');
      const title = node('div');
      title.append(node('p', `${attempt.id} · ${attempt.seed ? '只读合成起点' : '本页用户训练记录'} · ${dateLabel(attempt.createdAt)}`, 'kicker'), node('h2', `${attempt.question.id} · ${attempt.question.title}`));
      const tags = node('div', '', 'tags');
      tags.append(tag(attempt.status === 'ended' ? '已结束' : '进行中', attempt.status === 'ended' ? 'closed' : ''), tag(`目标 v${attempt.target.version || '默认'}`), tag(`题目 v${attempt.question.version}`), tag(attempt.material ? `材料 v${attempt.material.version}` : '无需材料'));
      title.append(tags);
      header.append(title, button(attempt.status === 'ended' ? '打开只读原答' : '回到当前草稿', () => openAttempt(attempt.id)));
      card.append(header, node('p', fixedContext(attempt, latestRevision(attempt)), 'frozen-note'));
      if (attempt.sourceActionId) card.append(node('p', `复练来源：${attempt.sourceActionId}。同一题的独立新 Attempt，不是补充 R2。`, 'small-print space-top'));
      const revisions = node('details');
      revisions.append(node('summary', `提交原文与冻结清单 · ${attempt.revisions.length} 个 revision`));
      const bodies = node('div');
      if (!attempt.revisions.length) bodies.append(node('p', '尚无已提交主回答。进行中的草稿不是提交记录。', 'muted space-top'));
      attempt.revisions.forEach(revision => {
        const block = node('div', '', 'revision-block');
        block.append(node('h3', revision.id), node('p', `${revision.hash} · Unicode 码点 ${revision.codePointLength} · 不可变原文`, 'small-print hash-line'), node('pre', revision.body, 'answer-copy'), node('p', `提交时提示：${hintSummary(attempt, revision.hintSnapshot)}`, 'small-print'));
        bodies.append(block);
      });
      if (attempt.manifest) bodies.append(node('p', `结束时冻结：${attempt.manifest.revisionIds.join(' / ') || '空清单'}；最新 ${attempt.manifest.latestRevisionId || '无'}。${attempt.manifest.reason}`, 'frozen-note'));
      if (attempt.excludedDraft) bodies.append(node('p', attempt.excludedDraft.reason, 'deleted-draft space-top'), node('pre', attempt.excludedDraft.body, 'answer-copy'));
      savedFollowups(attempt).forEach(item => (item.revisions || [item.answer]).forEach(revision => bodies.append(node('h3', `${revision.id} · 主动选择的预设追问修订`), node('pre', revision.body, 'answer-copy'))));
      Object.values(attempt.followups).filter(item => item.draft.trim() && !item.answer && attempt.status === 'ended').forEach(item => bodies.append(node('p', `${item.preset.id} 未交草稿，不在冻结清单内：${item.draft}`, 'deleted-draft space-top')));
      if (attempt.noteSaved) bodies.append(node('h3', '唯一练后笔记 · 已显式保存'), node('p', attempt.noteSaved.body, 'frozen-note'));
      if (attempt.status === 'ended' && attempt.noteDraft !== (attempt.noteSaved?.body || '')) bodies.append(node('p', `未保存笔记草稿，保留但不纳入清单：${attempt.noteDraft}`, 'deleted-draft space-top'));
      attempt.conflictPreserved.forEach(record => {
        bodies.append(node('h3', `${record.id} · 合成冲突保留稿（非提交）`), node('p', record.resolution, 'small-print space-top'), node('pre', record.body, 'answer-copy'));
        if (record.mergeDraft.trim()) bodies.append(node('p', '保留的手工合并稿：', 'small-print'), node('pre', record.mergeDraft, 'answer-copy'));
      });
      revisions.append(bodies);
      card.append(revisions);
      const selfchecks = state.feedback.filter(item => item.attemptId === attempt.id);
      if (selfchecks.length) {
        const detail = node('details');
        detail.append(node('summary', `用户自检来源 · ${selfchecks.length} 条（不是自动评价）`));
        const list = node('div', '', 'stack space-top');
        selfchecks.forEach(item => list.append(feedbackCard(item)));
        detail.append(list);
        card.append(detail);
      } else card.append(node('p', '没有用户自检，没有自动生成缺口或行动。', 'small-print space-top'));
      if (attempt.sourceActionId && attempt.revisions.length) card.append(button('打开这次来源对照', () => openComparison(attempt.id), 'text-button space-top'));
      $('history-list').append(card);
    });
  }
  function actionStatus(action) {
    if (!action) return '来源不存在';
    return { pending: '待复练', closed: '已处理', archived: '已归档' }[action.status];
  }
  function renderActions() {
    const filter = $('action-filter').value;
    const list = state.actions.filter(action => filter === 'all' || action.status === filter);
    $('action-list').replaceChildren();
    if (!list.length) {
      const empty = node('article', '', 'paper padded empty-state');
      empty.append(node('h2', filter === 'pending' ? '现在没有待复练行动。' : '这个状态下没有行动。'), node('p', '行动只来自你显式保存并接受的自检，不会按题号自动生成或去重。'), button('去当前训练留下自检', () => navigate('practice')));
      $('action-list').append(empty);
    }
    [...list].reverse().forEach(action => {
      const original = attemptById(action.source.attemptId);
      const card = node('article', '', 'paper padded action-card');
      const tags = node('div', '', 'tags');
      tags.append(tag(action.id), tag(action.questionId), tag(actionStatus(action), action.status === 'pending' ? 'warm' : action.status));
      card.append(tags, node('h2', action.action), node('p', `判断条件：${action.condition}`, 'action-detail'), node('p', `确切来源：${action.source.feedbackId} / ${action.source.revisionId} / ${action.dimension}`, 'small-print hash-line'), node('p', `${action.source.evidence.kind === 'missing' ? '用户声明未提及，整回答锚定' : '用户原文选区'} [${action.source.evidence.start}, ${action.source.evidence.end}) · ${action.source.evidence.answerHash}`, 'small-print hash-line'), node('pre', action.source.evidence.quote, 'answer-copy'));
      const row = node('div', '', 'button-row');
      if (original) row.append(button('查看原回答与自检', () => openAttempt(original.id)));
      if (action.status === 'pending') {
        row.append(button(original?.status === 'active' ? '先显式结束来源，再开始复练 →' : '开始来源复练 →', () => requestStart(action.questionId, action.id), 'button primary'), button('归档并写原因', () => openArchive(action.id), 'text-button'));
      }
      card.append(row);
      const repeats = state.attempts.filter(attempt => attempt.sourceActionId === action.id);
      repeats.forEach(attempt => {
        const line = node('div', '', 'action-history');
        line.append(node('p', `复练 ${attempt.id} · ${attempt.status === 'ended' ? '已结束' : '进行中'} · ${latestRevision(attempt)?.label || '未交新答'} · 提交本身不结项`));
        line.append(button(attempt.revisions.length ? '查看新答与来源对照' : '回到空白/当前新答', () => attempt.revisions.length ? openComparison(attempt.id) : openAttempt(attempt.id), 'text-button'));
        card.append(line);
      });
      if (action.archiveReason) card.append(node('p', `用户归档原因：${action.archiveReason}`, 'action-history'));
      action.events.forEach(event => card.append(node('p', `${dateLabel(event.at)} · ${event.description}`, 'action-history')));
      $('action-list').append(card);
    });
  }
  function openArchive(actionId) {
    const action = actionById(actionId);
    if (!action || action.status !== 'pending') return;
    state.archiveActionId = action.id;
    $('archive-dialog-help').textContent = `${action.id} · ${action.action}。归档只改变这个来源行动的待复练状态，不删除原回答、自检或对照记录。`;
    $('archive-reason').value = '';
    error('archive-error', '');
    openDialog('archive-dialog');
  }
  function confirmArchive() {
    const action = actionById(state.archiveActionId);
    if (!action || action.status !== 'pending') return;
    const reason = $('archive-reason').value.trim();
    if (!reason) { error('archive-error', '请写明确的用户归档原因；不把归档冒充“已处理”。', 'archive-reason'); return; }
    if (!allowSave('archive', 'archive-error')) return;
    action.status = 'archived';
    action.archiveReason = reason;
    action.events.push(frozen({ id: id('AE'), at: now(), description: `用户显式归档：${reason}` }));
    changed();
    state.archiveActionId = null;
    closeDialog('archive-dialog');
    renderActions();
    announce('已保存这个来源行动的归档原因，其他来源与旧对照记录不变。');
  }
  function eligibleComparisons() {
    return state.attempts.filter(attempt => attempt.sourceActionId && attempt.revisions.length && actionById(attempt.sourceActionId));
  }
  function openComparison(attemptId) {
    const attempt = attemptById(attemptId);
    if (!attempt || !attempt.sourceActionId || !attempt.revisions.length) { announce('请先完成来源复练的新回答提交，再打开对照。'); return; }
    state.compareAttemptId = attempt.id;
    state.compareRevisionId = latestRevision(attempt).id;
    navigate('comparison');
  }
  function comparisonSelection() {
    const eligible = eligibleComparisons();
    const attempt = eligible.find(item => item.id === state.compareAttemptId) || eligible.at(-1);
    if (!attempt) return null;
    state.compareAttemptId = attempt.id;
    const revision = attempt.revisions.find(item => item.id === state.compareRevisionId) || latestRevision(attempt);
    state.compareRevisionId = revision.id;
    const action = actionById(attempt.sourceActionId);
    const original = attemptById(action.source.attemptId);
    const oldRevision = original && allAnswerRevisions(original).find(item => item.id === action.source.revisionId);
    if (!oldRevision) return null;
    const key = JSON.stringify([action.id, attempt.id, revision.id]);
    if (!state.comparisonDrafts[key]) state.comparisonDrafts[key] = { change: '', outcome: '', evidence: '', note: '', disposition: 'pending', reason: '' };
    return { attempt, revision, action, original, oldRevision, key, draft: state.comparisonDrafts[key] };
  }
  function renderComparison() {
    const selected = comparisonSelection();
    $('comparison-empty').hidden = Boolean(selected);
    $('comparison-body').hidden = !selected;
    if (!selected) return;
    const { attempt, revision, action, original, oldRevision, draft } = selected;
    options($('compare-attempt'), [...eligibleComparisons()].reverse().map(item => ({ value: item.id, label: `${item.id} · 来源 ${item.sourceActionId} · ${item.status === 'ended' ? '已结束' : '尚未结束'}` })), attempt.id);
    options($('compare-revision'), attempt.revisions.map(item => ({ value: item.id, label: `${item.label} · ${item.id} · 独立新原答` })), revision.id);
    $('comparison-source').replaceChildren(node('p', `只处理当前选定来源 ${action.id} · ${action.source.feedbackId} · ${action.source.revisionId}`, 'kicker'), node('h2', `原动作：${action.action}`), node('p', `原判断条件：${action.condition}`), node('p', `来源维度：${action.dimension} · 当前状态：${actionStatus(action)}。新答提交未自动结项。`, 'small-print'));
    $('old-answer-title').textContent = `${original.id} · ${oldRevision.label}${original.seed ? ' · 合成起点' : ''}`;
    $('old-answer-meta').textContent = `${oldRevision.id} · ${oldRevision.hash} · Unicode 码点 ${oldRevision.codePointLength}`;
    $('old-answer-copy').textContent = oldRevision.body;
    $('old-context').textContent = fixedContext(original, oldRevision);
    $('new-answer-title').textContent = `${attempt.id} · ${revision.label} · 独立新回答`;
    $('new-answer-meta').textContent = `${revision.id} · ${revision.hash} · Unicode 码点 ${revision.codePointLength}`;
    $('new-answer-copy').textContent = revision.body;
    $('new-context').textContent = fixedContext(attempt, revision);
    const oldQuestion = action.subjectQuestion || original.question;
    const contextChanged = JSON.stringify(original.target) !== JSON.stringify(attempt.target) || oldQuestion.id !== attempt.question.id || oldQuestion.version !== attempt.question.version || JSON.stringify(original.material) !== JSON.stringify(attempt.material);
    const differences = [`目标 v${original.target.version || '默认'} → v${attempt.target.version || '默认'}；题目 v${original.question.version} → v${attempt.question.version}；材料 ${original.material ? `v${original.material.version}` : '无需'} → ${attempt.material ? `v${attempt.material.version}` : '无需'}。`, contextChanged ? '不宜直接比较：目标、题目或事实条件已变化；这里只能对选定动作作有条件的用户判断，不能认定能力提升。' : '上下文版本未变化；提示使用仍须核对，不自动认定提升。'];
    $('version-difference').replaceChildren(node('p', differences.join('\n')));
    if (attempt.status !== 'ended') {
      $('version-difference').append(node('p', '可以先预览对照，但保存用户处理结果前，必须显式结束新 Attempt。此时尚不冻结最新提交。', 'space-top'), button('显式结束这次新尝试', () => { state.currentAttemptId = attempt.id; openEnd(); }, 'button secondary space-top'));
    }
    $('condition-change').value = draft.change;
    $('comparison-note').value = draft.note;
    $('comparison-evidence').value = draft.evidence;
    $('action-disposition').value = draft.disposition;
    $('comparison-archive-reason').value = draft.reason;
    document.querySelectorAll('input[name="outcome"]').forEach(input => { input.checked = input.value === draft.outcome; });
    const readOnly = attempt.status !== 'ended' || action.status !== 'pending';
    $('comparison-form').querySelectorAll('input,textarea,select,button').forEach(field => { field.disabled = readOnly; });
    $('comparison-disposition').hidden = draft.outcome === 'handled';
    $('comparison-archive-field').hidden = draft.disposition !== 'archived';
    error('comparison-error', action.status !== 'pending' ? `这个来源已${actionStatus(action)}，保留只读对照与历史；不会用另一个结论悄悄覆盖行动状态。` : attempt.status !== 'ended' ? '新 Attempt 尚未结束。先显式结束，再保存处理结果；提交新答不自动结项。' : '');
    renderComparisonRecords(action.id);
  }
  function renderComparisonRecords(actionId) {
    $('comparison-records').replaceChildren();
    const records = state.comparisons.filter(item => item.actionId === actionId);
    if (!records.length) $('comparison-records').append(node('p', '没有用户对照结论。新答提交不会自动产生“已处理”记录。', 'muted'));
    const labels = { handled: '已处理', partial: '部分处理', unhandled: '未处理', unknown: '无法判断' };
    [...records].reverse().forEach(record => {
      const block = node('article', '', 'comparison-record');
      block.append(node('h3', `${record.id} · 用户判断：${labels[record.outcome]}`), node('p', `${record.oldRevisionId} ↔ ${record.newRevisionId}`, 'small-print hash-line'), node('p', `条件变化：${record.conditionChange}`), node('p', `对照理由：${record.note}`), node('p', `新答证据：${record.newEvidence ? `${record.newEvidence.revisionId} [${record.newEvidence.start},${record.newEvidence.end}) · ${record.newEvidence.quote}` : '无法观察到可引用片段；以用户理由保留限制'}`), node('p', `后续：${record.disposition === 'closed' ? '关闭选定原行动' : record.disposition === 'archived' ? `显式归档：${record.archiveReason}` : '继续待复练'} · ${dateLabel(record.savedAt)}`, 'small-print'));
      $('comparison-records').append(block);
    });
  }
  function updateComparisonDraft(field, value) {
    const selected = comparisonSelection();
    if (!selected) return;
    selected.draft[field] = value;
    if (field === 'outcome') $('comparison-disposition').hidden = value === 'handled';
    if (field === 'disposition') $('comparison-archive-field').hidden = value !== 'archived';
  }
  function saveComparison(event) {
    event.preventDefault();
    const selected = comparisonSelection();
    if (!selected) return;
    const { attempt, revision, action, original, oldRevision, draft } = selected;
    if (attempt.status !== 'ended' || original.status !== 'ended') { error('comparison-error', '新旧 Attempt 都必须显式结束后，再保存用户处理结果。'); return; }
    const conditionChange = draft.change.trim();
    const note = draft.note.trim();
    const evidence = draft.evidence;
    const evidenceIndex = evidence ? revision.body.indexOf(evidence) : -1;
    if (evidence && (evidenceIndex < 0 || revision.body.indexOf(evidence, evidenceIndex + 1) >= 0)) { error('comparison-error', '证据需与新原答完全匹配且唯一；重复片段请复制更长的上下文，不能猜定位。'); return; }
    const needsEvidence = draft.outcome === 'handled' || draft.outcome === 'partial';
    if (!note || (needsEvidence && (!evidence.trim() || evidenceIndex < 0))) { error('comparison-error', '请填写对照理由；已处理／部分处理必须提供与选定新原答完全匹配的证据片段。'); return; }
    const newEvidence = evidenceIndex >= 0 ? rangeFromSelection(revision.body, evidenceIndex, evidenceIndex + evidence.length) : null;
    if (needsEvidence && !newEvidence) { error('comparison-error', '新答证据不能切开Unicode字符，请复制完整片段。'); return; }
    const archiveReason = draft.reason.trim();
    const disposition = draft.outcome === 'handled' ? 'closed' : draft.disposition;
    if (!conditionChange || !draft.outcome || (disposition === 'archived' && !archiveReason)) { error('comparison-error', '请明确条件变化（无变化也写明）、选择处理结果；若归档，必须写原因。'); return; }
    const key = JSON.stringify([action.id, oldRevision.id, revision.id, conditionChange, draft.outcome, evidence, note, disposition, disposition === 'archived' ? archiveReason : '']);
    if (state.comparisons.some(item => item.key === key)) { error('comparison-error', ''); announce('相同用户对照已保存，不会重复写入或重复关闭行动。'); return; }
    if (action.status !== 'pending') { error('comparison-error', '原行动已经处理或归档，不能用新结论自动回写它。原对照历史保持只读。'); return; }
    if (!allowSave('comparison', 'comparison-error')) return;
    const record = frozen({ id: id('CMP'), key, actionId: action.id, feedbackId: action.source.feedbackId, oldAttemptId: original.id, oldRevisionId: oldRevision.id, newAttemptId: attempt.id, newRevisionId: revision.id, newEvidence: newEvidence ? { ...newEvidence, revisionId: revision.id, hash: revision.hash } : null, conditionChange, outcome: draft.outcome, note, disposition, archiveReason: disposition === 'archived' ? archiveReason : null, oldHintSnapshot: clone(oldRevision.hintSnapshot), newHintSnapshot: clone(revision.hintSnapshot), savedAt: now(), kind: '用户对照判断（非AI）' });
    state.comparisons.push(record);
    action.status = disposition === 'closed' ? 'closed' : disposition === 'archived' ? 'archived' : 'pending';
    if (disposition === 'archived') action.archiveReason = archiveReason;
    action.events.push(frozen({ id: id('AE'), at: now(), comparisonId: record.id, description: `${record.id} 用户对照：${{ handled: '已处理', partial: '部分处理', unhandled: '未处理', unknown: '无法判断' }[draft.outcome]}；${actionStatus(action)}。` }));
    changed();
    error('comparison-error', '');
    renderComparison();
    announce(`用户对照 ${record.id} 已保存。只有当前来源行动变为“${actionStatus(action)}”，原答、旧反馈与其他来源没有覆盖。`);
  }
  function openConflict(attempt) {
    state.conflict = { attemptId: attempt.id, local: attempt.draft, localVersion: attempt.draftVersion, remote: attempt.question.id === 'B01' ? '【合成服务器差异稿】在固定条件下，任务1、2创建核心线程；3、4入队；5、6扩容；7由AbortPolicy拒绝。' : '【合成服务器差异稿】超时仍是结果未知；先核实请求号、查单与回调依据，幂等保证待核实时不直接补发或释放预算。', remoteVersion: 'SERVER-DEMO-v2' };
    $('conflict-local').value = state.conflict.local;
    $('conflict-remote').value = state.conflict.remote;
    $('conflict-copy').value = '';
    $('conflict-merge').value = '';
    $('conflict-state').textContent = `演示：本地草稿 v${state.conflict.localVersion} 与 ${state.conflict.remoteVersion} 不一致；提交已拒绝。没有真实异步请求。`;
    error('conflict-error', '');
    openDialog('conflict-dialog');
  }
  function retainConflict(resolution) {
    const conflict = state.conflict;
    if (!conflict) return;
    const attempt = attemptById(conflict.attemptId);
    if (attempt && (resolution !== 'cancelled' || $('conflict-merge').value.trim())) {
      attempt.conflictPreserved.push(frozen({ id: id('CONFLICT'), kind: '合成冲突保留稿（非提交）', body: conflict.local, mergeDraft: $('conflict-merge').value, remote: conflict.remote, resolution, preservedAt: now() }));
      changed();
    }
    state.conflict = null;
  }
  function preserveLocalConflict() {
    const conflict = state.conflict;
    const attempt = conflict && attemptById(conflict.attemptId);
    if (!attempt || conflict.preservedId) return;
    const record = frozen({ id: id('CONFLICT'), kind: '合成冲突保留稿（非提交）', body: conflict.local, mergeDraft: '', remote: conflict.remote, resolution: '用户点击复制到保留区并全选；原稿同时保留为非提交历史。', preservedAt: now() });
    attempt.conflictPreserved.push(record);
    conflict.preservedId = record.id;
    changed();
  }
  function adoptRemote() {
    const conflict = state.conflict;
    const attempt = conflict && attemptById(conflict.attemptId);
    if (!attempt || attempt.status !== 'active') return;
    const remote = conflict.remote;
    retainConflict('用户主动采纳对端为新草稿，未自动提交');
    attempt.draft = remote;
    attempt.draftVersion += 1;
    attempt.editing = true;
    closeDialog('conflict-dialog');
    if (state.view !== 'practice') navigate('practice');
    else renderPractice();
    announce('已由你主动采纳合成对端为待提交草稿；本地差异稿保留在历史，旧提交原文未覆盖。仍需显式提交并再次结束（若需要）。');
  }
  function retryMerged() {
    const conflict = state.conflict;
    const attempt = conflict && attemptById(conflict.attemptId);
    if (!attempt || attempt.status !== 'active') return;
    const merged = $('conflict-merge').value;
    if (!merged.trim()) { error('conflict-error', '请手工填写合并稿。系统不会自动合并或自动覆盖你的原文。', 'conflict-merge'); return; }
    retainConflict('用户手工合并后重试');
    attempt.draft = merged;
    attempt.draftVersion += 1;
    attempt.editing = true;
    closeDialog('conflict-dialog');
    if (state.view !== 'practice') navigate('practice');
    if (submitAttempt(attempt)) renderPractice();
    announce('手工合并稿已按你的操作重试提交；冲突原稿已保留。没有自动结束 Attempt，也没有修改旧 revision。');
  }
  function renderData() {
    renderFault();
    $('ack-draft').value = state.ack.body;
    renderAck();
  }
  function renderAck(message = '') {
    const ack = state.ack;
    $('ack-deliver').disabled = !ack.pending;
    $('ack-state').textContent = `${message ? `${message}\n` : ''}演示稿 v${ack.version} · 已确认 v${ack.savedVersion} · ${ack.version === ack.savedVersion ? '当前版本 clean' : '当前版本 dirty（保留新输入）'}${ack.pending ? ` · 待手动交付 ack v${ack.pending.version}` : ''}`;
  }
  function captureAck() {
    state.ack.pending = frozen({ version: state.ack.version, body: state.ack.body });
    renderAck('已捕获当前版本的演示回执。现在可继续编辑，再手动交付旧 ack。');
  }
  function deliverAck() {
    const ack = state.ack;
    if (!ack.pending) return;
    const captured = ack.pending;
    ack.savedVersion = Math.max(ack.savedVersion, captured.version);
    ack.pending = null;
    renderAck(captured.version < ack.version ? `旧 ack v${captured.version} 不能清除新稿 v${ack.version} 的 dirty，也未覆盖新稿。` : `ack v${captured.version} 与当前稿相同，可将这一版标为 clean。`);
  }
  function exportSnapshot() {
    return {
      label: '问阶交互设计预览 / 用户声明的合成记录 / 本页内存 / 非服务端保存 / 非AI', schema: 'core-preview-v1', exportedAt: now(),
      scope: '仅显式保存记录。无真实材料、密钥字段或外部数据；正在编辑的草稿不导出。下载后不可召回。',
      targets: clone(state.targets), materials: clone(state.materials), materialConfirmations: clone(state.materialConfirmations), customQuestion: clone(state.custom),
      attempts: state.attempts.map(attempt => ({ id: attempt.id, seed: attempt.seed, status: attempt.status, question: clone(attempt.question), target: clone(attempt.target), material: clone(attempt.material), sourceActionId: attempt.sourceActionId, createdAt: attempt.createdAt, endedAt: attempt.endedAt, revisions: clone(attempt.revisions), frozenManifest: clone(attempt.manifest), followupAnswers: savedFollowups(attempt).map(item => ({ preset: clone(item.preset), answer: clone(item.answer), revisions: clone(item.revisions || [item.answer]), status: item.status, skipReason: item.skipReason || null })), savedNote: clone(attempt.noteSaved), hints: clone(attempt.hints) })),
      feedback: clone(state.feedback), feedbackDecisions: clone(state.feedbackDecisions), actions: clone(state.actions), comparisons: clone(state.comparisons)
    };
  }
  function markdownSnapshot(snapshot) {
    const parts = ['# 问阶 · 合成训练记录', '', '> 交互设计预览 · 本页内存 · 非服务端保存 / 非AI · 下载后不可召回', '', `导出时间：${snapshot.exportedAt}`, '', '## 目标与合成材料版本', ''];
    snapshot.targets.forEach(target => parts.push(`- 目标 v${target.version}：${target.role} / ${target.focus}`));
    snapshot.materials.forEach(material => {
      parts.push('', `### 材料 v${material.version} · ${material.id}`);
      material.rows.forEach(row => parts.push(`- ${row.id} / ${row.role}：${row.text}`));
    });
    parts.push('', '## 不可变回答与固定 context', '');
    snapshot.attempts.forEach(attempt => {
      parts.push(`### ${attempt.id} · ${attempt.question.id} · ${attempt.status === 'ended' ? '已结束' : '进行中'}`, `目标 v${attempt.target.version} · 题目 v${attempt.question.version} · 材料 ${attempt.material ? `v${attempt.material.version}` : '无需'}`, `题干：${attempt.question.prompt}`, `来源行动：${attempt.sourceActionId || '无'}`, '');
      attempt.revisions.forEach(revision => parts.push(`#### ${revision.id} · ${revision.hash}`, '', revision.body, '', `提示快照：${JSON.stringify(revision.hintSnapshot)}`, ''));
      parts.push(`冻结提交清单：${attempt.frozenManifest?.revisionIds.join(' / ') || '无'}`, `练后笔记：${attempt.savedNote?.body || '无已保存笔记'}`, '');
      attempt.followupAnswers.forEach(item => (item.revisions || [item.answer]).forEach(revision => parts.push(`预设追问 ${revision.id}：${revision.body}`, '')));
    });
    parts.push('## 用户自检来源（非AI）', '');
    snapshot.feedback.forEach(feedback => parts.push(`### ${feedback.id} / ${feedback.revisionId}`, `维度：${feedback.dimension}`, `原文类型：${feedback.evidence.kind} · Unicode [${feedback.evidence.start}, ${feedback.evidence.end}) · ${feedback.evidence.answerHash}`, `quote：${feedback.evidence.quote}`, `类型：${feedback.type}；适用条件：${feedback.applicable}；限制：${feedback.limitation}`, `用户决定：${snapshot.feedbackDecisions[feedback.id]?.kind || '未决定'}`, `缺口：${feedback.gap}`, `动作：${feedback.action}`, `条件：${feedback.condition}`, ''));
    parts.push('## 来源行动', '');
    snapshot.actions.forEach(action => parts.push(`- ${action.id} / ${action.source.feedbackId} / ${action.status}：${action.action}`, `  条件：${action.condition}`, `  归档原因：${action.archiveReason || '无'}`));
    parts.push('', '## 用户对照判断（不覆盖历史）', '');
    snapshot.comparisons.forEach(record => parts.push(`### ${record.id} / ${record.actionId}`, `${record.oldRevisionId} ↔ ${record.newRevisionId}`, `用户结果：${record.outcome}；后续：${record.disposition}`, `条件变化：${record.conditionChange}`, `说明：${record.note || '无'}`, `归档原因：${record.archiveReason || '无'}`, ''));
    return parts.join('\n');
  }
  function previewExport() {
    const snapshot = exportSnapshot();
    const serialized = JSON.stringify(snapshot, null, 2);
    // 检测常见密钥格式时拒绝导出；页面仍要求用户只输入合成内容。
    if (/(?:\bsk[-_][A-Za-z0-9_-]{16,}|\bAKIA[A-Z0-9]{16}\b|\bBearer\s+[A-Za-z0-9._-]{16,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)/.test(serialized)) {
      state.exportPlan = null;
      $('export-section').hidden = true;
      error('export-error', '检测到疑似密钥格式，已拒绝导出。请不要输入真实密钥；此格式检查不能替代合成内容约定。');
      return;
    }
    const format = $('export-format').value;
    state.exportPlan = { version: state.changeVersion, format, content: format === 'json' ? serialized : markdownSnapshot(snapshot) };
    $('export-preview').textContent = state.exportPlan.content;
    $('export-section').hidden = false;
    $('export-understood').checked = false;
    $('download-export').disabled = true;
    $('download-export').textContent = format === 'json' ? '下载预览中的 JSON' : '下载预览中的 Markdown';
    $('export-state').textContent = '仅预览，尚未下载。当前正在编辑的草稿不包含在导出中。';
    error('export-error', '');
  }
  function downloadExport() {
    const plan = state.exportPlan;
    if (!plan || plan.version !== state.changeVersion || !$('export-understood').checked) { error('export-error', '请重新预览当前记录，并确认下载后不能召回。'); return; }
    const extension = plan.format === 'json' ? 'json' : 'md';
    const blob = new Blob([plan.content], { type: plan.format === 'json' ? 'application/json;charset=utf-8' : 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `wenjie-synthetic-preview.${extension}`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    $('export-state').textContent = '已按你的点击发起 Blob 下载。浏览器是否完成下载由浏览器决定；已下载文件无法由本页召回。释放临时链接不等于删除文件。';
  }
  function deletionImpact(scope) {
    const impact = [
      `${state.attempts.length} 个 Attempt（含 ${state.attempts.filter(attempt => attempt.seed).length} 个明确标注的合成起点）`,
      `${state.attempts.reduce((total, attempt) => total + attempt.revisions.length, 0)} 个不可变主回答 revision`,
      `${state.feedback.length} 条用户自检、${state.actions.length} 个来源行动、${state.comparisons.length} 条用户对照`,
      `${state.attempts.filter(attempt => dirtyAnswer(attempt)).length} 份当前/排除回答草稿；${state.attempts.filter(attempt => Object.values(attempt.selfcheckDraft).some(value => value && value !== 'custom' && !attempt.question.dimensions.includes(value))).length} 份可能未保存自检输入`,
      `${state.attempts.filter(attempt => attempt.noteSaved).length} 条已保存笔记及所有未保存笔记/追问草稿；${state.attempts.reduce((total, attempt) => total + savedFollowups(attempt).length, 0)} 条追问提交`,
      `${state.attempts.reduce((total, attempt) => total + attempt.conflictPreserved.length, 0)} 份合成冲突保留记录；全部训练提示记录与未保存对照输入`
    ];
    if (scope === 'all') impact.push(`${state.targets.length} 个目标版本及目标草稿（清空后只有未保存的 Java 后端默认方向）`, `${state.materials.length} 个合成材料版本、${state.materialConfirmations.length} 条确认与当前材料稿`, `${state.custom ? '1 个' : '0 个'}自建题位置的已保存版本与当前题干草稿`, '独立 ack 演示稿与捕获回执；导出预览快照');
    else impact.push('保留目标版本/草稿、材料版本/确认/草稿、单个自建题和隔离的 ack 演示稿');
    impact.push('不会删除用户文件、已下载导出文件、旧浏览器数据或固定 B01/P01 题目。没有服务器删除请求。');
    return impact;
  }
  function previewDelete() {
    const scope = $('delete-scope').value;
    state.deletionPlan = frozen({ scope, version: state.changeVersion, impact: deletionImpact(scope) });
    const list = node('ul', '', 'impact-list');
    state.deletionPlan.impact.forEach(text => list.append(node('li', text)));
    $('delete-impact').replaceChildren(list);
    $('delete-confirmation').value = '';
    $('confirm-delete').disabled = true;
    error('delete-dialog-error', '');
    openDialog('delete-dialog');
  }
  function confirmDelete() {
    const plan = state.deletionPlan;
    if (!plan || plan.version !== state.changeVersion || $('delete-confirmation').value !== '删除本页合成记录') { error('delete-dialog-error', '影响范围已变化，或确认文字不一致。请取消后重新预览。'); return; }
    if (!allowSave('delete', 'delete-dialog-error')) return;
    state.attempts = [];
    state.feedback = [];
    state.feedbackDecisions = {};
    state.actions = [];
    state.comparisons = [];
    state.comparisonDrafts = {};
    state.currentAttemptId = null;
    state.compareAttemptId = null;
    state.compareRevisionId = null;
    state.pendingEnd = null;
    state.pendingProject = null;
    state.conflict = null;
    if (plan.scope === 'all') {
      state.targets = [];
      state.targetDraft = { role: 'Java 后端', focus: '基础机制与项目表达' };
      state.materials = [];
      state.materialConfirmations = [];
      state.materialConfirmDrafts = {};
      state.materialDraft = [];
      state.materialDirty = false;
      state.custom = null;
      state.customDraft = { topic: '我的表达练习', prompt: '', source: 'personal', sourceNote: '' };
      state.ack = { body: '', version: 0, savedVersion: 0, pending: null };
    }
    state.faultMode = 'normal';
    state.deletionPlan = null;
    ['prepare-history', 'history-list', 'action-list', 'feedback-list', 'hint-list', 'followup-list', 'comparison-records', 'comparison-source', 'old-answer-copy', 'new-answer-copy', 'old-context', 'new-context', 'version-difference', 'prepare-next-title', 'prepare-next-reason', 'archive-dialog-help', 'export-preview', 'delete-impact'].forEach(elementId => $(elementId).replaceChildren());
    ['answer-input', 'original-answer', 'attempt-note', 'review-dimension-custom', 'review-applicable', 'review-limit', 'review-gap', 'review-action', 'review-condition', 'condition-change', 'comparison-evidence', 'comparison-note', 'comparison-archive-reason', 'archive-reason', 'conflict-local', 'conflict-remote', 'conflict-copy', 'conflict-merge'].forEach(elementId => { $(elementId).value = ''; });
    ['review-revision', 'compare-attempt', 'compare-revision'].forEach(elementId => $(elementId).replaceChildren());
    ['evidence-preview', 'attempt-facts', 'repeat-source-note', 'excluded-draft', 'unsaved-note', 'old-answer-title', 'old-answer-meta', 'new-answer-title', 'new-answer-meta', 'export-state', 'end-manifest', 'end-draft-warning'].forEach(elementId => { $(elementId).textContent = ''; });
    $('export-section').hidden = true;
    $('prepare-next').hidden = true;
    $('prepare-next-button').onclick = null;
    dialogReturn.clear();
    if (plan.scope === 'all') {
      ['material-editor', 'material-history', 'question-list', 'project-confirm-facts'].forEach(elementId => $(elementId).replaceChildren());
      $('top-target').textContent = 'Java 后端 · 默认方向（未保存）';
      $('target-version-title').textContent = 'Java 后端 · 未保存目标';
      ['target-role', 'target-focus', 'custom-topic', 'custom-prompt', 'custom-source-note', 'ack-draft'].forEach(elementId => { $(elementId).value = ''; });
    }
    changed();
    closeDialog('delete-dialog');
    renderData();
    announce(`已按确认清空${plan.scope === 'all' ? '全部本页可变合成记录' : '本页训练、自检、行动与对照记录'}。固定题目保留；没有删除文件、下载或旧浏览器数据。`);
  }
  document.querySelectorAll('[data-view]').forEach(element => element.addEventListener('click', () => navigate(element.dataset.view)));
  $('start-basic').addEventListener('click', () => requestStart('B01'));
  $('start-project').addEventListener('click', () => requestStart('P01'));
  $('material-start-project').addEventListener('click', () => requestStart('P01'));
  $('target-form').addEventListener('submit', saveTarget);
  $('target-role').addEventListener('input', () => { state.targetDraft.role = $('target-role').value; error('target-error', '', 'target-role'); });
  $('target-focus').addEventListener('input', () => { state.targetDraft.focus = $('target-focus').value; error('target-error', '', 'target-focus'); });
  $('custom-question-form').addEventListener('submit', saveCustom);
  $('custom-topic').addEventListener('input', () => { state.customDraft.topic = $('custom-topic').value; });
  $('custom-prompt').addEventListener('input', () => { state.customDraft.prompt = $('custom-prompt').value; });
  $('custom-source').addEventListener('change', () => { state.customDraft.source = $('custom-source').value; });
  $('custom-source-note').addEventListener('input', () => { state.customDraft.sourceNote = $('custom-source-note').value; });
  $('topic-filter').addEventListener('change', renderLearning);
  $('type-filter').addEventListener('change', renderLearning);
  $('confirm-project-start').addEventListener('click', confirmProjectStart);
  $('cancel-project').addEventListener('click', () => { state.pendingProject = null; closeDialog('project-dialog'); });
  $('answer-input').addEventListener('input', () => {
    const attempt = currentAttempt();
    if (!attempt || attempt.status !== 'active' || !attempt.editing) return;
    attempt.draft = $('answer-input').value;
    attempt.draftVersion += 1;
    updateDraftState();
    error('answer-error', '', 'answer-input');
  });
  $('fill-sample').addEventListener('click', fillSample);
  $('begin-revision').addEventListener('click', beginRevision);
  $('submit-answer').addEventListener('click', () => { const attempt = currentAttempt(); if (submitAttempt(attempt)) renderPractice(); });
  $('review-revision').addEventListener('change', () => {
    const attempt = currentAttempt();
    if (!attempt) return;
    attempt.reviewRevisionId = $('review-revision').value;
    attempt.evidence = null;
    renderReview(attempt);
    error('selfcheck-error', '');
  });
  $('use-selection').addEventListener('click', () => bindEvidence(false));
  $('bind-missing').addEventListener('click', () => bindEvidence(true));
  $('review-dimension').addEventListener('change', () => {
    const attempt = currentAttempt();
    if (!attempt) return;
    attempt.selfcheckDraft.dimension = $('review-dimension').value;
    $('dimension-custom-field').hidden = attempt.selfcheckDraft.dimension !== 'custom';
  });
  [['review-dimension-custom', 'custom'], ['review-applicable', 'applicable'], ['review-limit', 'limitation'], ['review-gap', 'gap'], ['review-action', 'action'], ['review-condition', 'condition']].forEach(([fieldId, key]) => {
    $(fieldId).addEventListener('input', () => { const attempt = currentAttempt(); if (attempt?.status === 'active') attempt.selfcheckDraft[key] = $(fieldId).value; });
  });
  $('review-type').addEventListener('change', () => { const attempt = currentAttempt(); if (attempt?.status === 'active') attempt.selfcheckDraft.type = $('review-type').value; });
  $('selfcheck-form').addEventListener('submit', saveSelfcheck);
  $('attempt-note').addEventListener('input', () => {
    const attempt = currentAttempt();
    if (attempt?.status !== 'active') return;
    attempt.noteDraft = $('attempt-note').value;
    $('note-state').textContent = attempt.noteDraft === (attempt.noteSaved?.body || '') ? '当前笔记稿与已保存内容一致。' : '当前笔记稿未保存 · 仅本页内存编辑';
    error('note-error', '');
  });
  $('note-form').addEventListener('submit', saveNote);
  $('practice-end').addEventListener('click', () => openEnd());
  $('practice-new').addEventListener('click', () => { const attempt = currentAttempt(); if (attempt) requestStart(attempt.question.id); });
  $('cancel-end').addEventListener('click', () => { state.pendingEnd = null; closeDialog('end-dialog'); });
  $('end-submitted-only').addEventListener('click', () => finishEnd(false));
  $('submit-and-end').addEventListener('click', () => finishEnd(true));
  $('open-current-comparison').addEventListener('click', () => openComparison(state.currentAttemptId));
  $('material-form').addEventListener('submit', saveMaterial);
  $('confirm-material').addEventListener('click', confirmMaterial);
  $('load-material').addEventListener('click', loadMaterial);
  $('action-filter').addEventListener('change', renderActions);
  $('cancel-archive').addEventListener('click', () => { state.archiveActionId = null; closeDialog('archive-dialog'); });
  $('confirm-archive').addEventListener('click', confirmArchive);
  $('compare-attempt').addEventListener('change', () => { state.compareAttemptId = $('compare-attempt').value; state.compareRevisionId = null; renderComparison(); });
  $('compare-revision').addEventListener('change', () => { state.compareRevisionId = $('compare-revision').value; renderComparison(); });
  [['condition-change', 'change'], ['comparison-evidence', 'evidence'], ['comparison-note', 'note'], ['comparison-archive-reason', 'reason']].forEach(([fieldId, key]) => $(fieldId).addEventListener('input', () => updateComparisonDraft(key, $(fieldId).value)));
  document.querySelectorAll('input[name="outcome"]').forEach(input => input.addEventListener('change', () => updateComparisonDraft('outcome', input.value)));
  $('action-disposition').addEventListener('change', () => updateComparisonDraft('disposition', $('action-disposition').value));
  $('comparison-form').addEventListener('submit', saveComparison);
  $('fault-mode').addEventListener('change', () => { state.faultMode = $('fault-mode').value; renderFault(); });
  function normalMode() { state.faultMode = 'normal'; renderFault(); announce('已回到正常演示模式。输入与原记录保留，没有真实服务器操作。'); }
  $('restore-normal').addEventListener('click', normalMode);
  $('restore-normal-top').addEventListener('click', normalMode);
  $('copy-conflict-local').addEventListener('click', () => {
    if (!state.conflict) return;
    $('conflict-copy').value = state.conflict.local;
    preserveLocalConflict();
    $('conflict-copy').focus();
    $('conflict-copy').select();
    $('conflict-state').textContent = '本地稿已复制到只读保留区并全选。可按 Ctrl/Cmd+C 手工复制；本页没有自动读取或写入系统剪贴板。';
  });
  $('adopt-conflict-remote').addEventListener('click', adoptRemote);
  $('retry-conflict-merge').addEventListener('click', retryMerged);
  $('cancel-conflict').addEventListener('click', () => { retainConflict('cancelled'); closeDialog('conflict-dialog'); announce('已取消合成冲突处理，原回答草稿保留。合并区若有新字也已保留为非提交稿。'); });
  $('ack-draft').addEventListener('input', () => { state.ack.body = $('ack-draft').value; state.ack.version += 1; renderAck(); });
  $('ack-capture').addEventListener('click', captureAck);
  $('ack-deliver').addEventListener('click', deliverAck);
  $('preview-export').addEventListener('click', previewExport);
  $('export-format').addEventListener('change', () => { state.exportPlan = null; $('export-section').hidden = true; $('export-state').textContent = '格式已变化，请重新预览后再下载。'; });
  $('export-understood').addEventListener('change', () => { $('download-export').disabled = !state.exportPlan || !$('export-understood').checked; });
  $('download-export').addEventListener('click', downloadExport);
  $('preview-delete').addEventListener('click', previewDelete);
  $('delete-confirmation').addEventListener('input', () => { $('confirm-delete').disabled = $('delete-confirmation').value !== '删除本页合成记录'; });
  $('confirm-delete').addEventListener('click', confirmDelete);
  $('cancel-delete').addEventListener('click', () => { state.deletionPlan = null; closeDialog('delete-dialog'); });
  // 小型纯函数约束检查；不创建训练记录、不调度控制台，也不代表浏览器验收。
  const unicodeCheck = rangeFromSelection('A🙂B', 1, 3);
  if (!unicodeCheck || unicodeCheck.start !== 1 || unicodeCheck.end !== 2 || unicodeCheck.quote !== '🙂' || rangeFromSelection('A🙂B', 1, 2) !== null) {
    announce('本页 Unicode 选区约束检查异常，请停止使用并交给设计验收；尚无用户训练操作。');
  }
  navigate('prepare', false);
})();
