(() => {
  'use strict';

  const VERSION = 'design-1';
  const STORAGE_KEY = 'interview-studio:design-1';
  const VIEW_LABELS = {
    home: '准备空间', targets: '目标岗位', evidence: '个人材料', practice: '题库与练习',
    interview: '模拟面试', whiteboard: '架构演练', review: '复盘与复练', settings: '模型与数据'
  };
  window.__prototype = { product: 'Interview Studio', version: VERSION, views: Object.keys(VIEW_LABELS) };

  const TARGETS = [
    {
      id: 'marketing', title: 'Java后端工程师 · 营销交易方向', short: 'Java后端 · 营销交易',
      team: '合成岗位 A · 业务中台', tags: ['Java', '事务一致性', '项目表达'],
      description: '围绕营销交易链路，理解业务状态与可靠交付。',
      points: ['说明 Java 服务的接口、事务边界与并发控制。', '用幂等、条件扣减与结果查单解释扫码领奖链路。', '讨论消息重试、补偿条件与业务状态一致性。', '基于已确认的项目事实，讲清个人职责与设计取舍。'],
      questions: ['idempotency', 'transaction', 'outbox']
    },
    {
      id: 'platform', title: 'Java后端工程师 · 平台服务方向', short: 'Java后端 · 平台服务',
      team: '合成岗位 B · 服务平台', tags: ['Java', '数据库', '可靠消息'],
      description: '围绕服务边界、数据库与异步协作练习基础设计。',
      points: ['解释服务接口、数据库事务与隔离级别的关系。', '说明本地事务如何可靠触发异步业务处理。', '为状态查单选择合适的索引与一致性策略。', '明确哪些是已参与的工作，哪些只是设计演练。'],
      questions: ['transaction', 'outbox', 'query']
    },
    {
      id: 'architecture', title: '系统设计工程师 · 业务架构方向', short: '系统设计 · 业务架构',
      team: '合成岗位 C · 业务架构', tags: ['系统设计', '事务一致性', '项目表达'],
      description: '从约束出发，练习说明架构决策与失败路径。',
      points: ['先界定业务不变量，再选择组件与交互流程。', '说明跨服务调用的未知结果与补偿边界。', '设计状态机、查单与可解释的故障恢复流程。', '用事实证据区分现有实现、设计建议与验证计划。'],
      questions: ['idempotency', 'outbox', 'query']
    }
  ];
  const STAGES = ['准备中', '已投递', '面试中', '已结束'];
  const EVIDENCE_SEED = {
    summary: '示例候选人：关注 Java 后端开发、数据库事务与业务系统设计。在合成的酒业数字化营销案例中，练习围绕扫码领奖链路描述接口、幂等控制与异常处理。此摘要不代表真实任职、工作年限或企业项目。',
    role: '合成职责：负责梳理扫码领奖接口的业务规则、预算扣减与领奖状态设计，并整理异常重试和结果查单的解释。真实表达时，应只保留自己实际参与且可核对的部分。',
    boundary: '合成技术事实：扫码请求经过资格校验后，以业务幂等号关联领奖记录。预算扣减采用带余额条件的原子更新。发奖为跨服务调用，超时只能说明结果未知；状态存储与查单是本次架构演练的修订方向，不代表已上线的真实实现。',
    result: '示例证据边界：可以引用接口流程、事务约束与状态机设计说明；没有真实流量、收入、节省金额或线上效果数据，不能将设计方案表述为真实业务成绩。',
    situation: '合成场景：酒业数字化营销活动通过扫码发放奖品，需要兼顾资格校验、预算约束与领奖体验。',
    task: '合成任务：明确重复扫码、预算不足和发奖超时的处理边界，让领奖结果可以查询与解释。',
    action: '合成行动：拆解扫码主链路，讨论业务幂等号与条件扣减；在白板修订中补充领奖状态存储和结果查单路径。',
    outcome: '合成结果：形成可讨论的流程与异常路径说明。实际效果尚未验证，不填造业绩数字。'
  };
  const CONFIRM_KEYS = ['summary', 'role', 'boundary', 'result', 'star'];
  const QUESTIONS = [
    {
      id: 'idempotency', title: '接口超时后再次扫码，怎样避免重复扣减预算？', difficulty: '进阶', tags: ['事务一致性'],
      description: '先界定一次领奖的业务身份，再讨论并发、事务和结果未知。',
      followup: '如果预算扣减已提交，但发奖接口没有返回，你会如何处理重试？',
      answer: ['业务幂等号应代表一次领奖意图，而不是每次 HTTP 请求；说明活动、用户与规则如何定义唯一身份。', '通过唯一约束与带余额条件的原子更新，守住不重复扣减和预算不为负的不变量。', '区分“确定失败”和“结果未知”。未知结果先查单，不直接重新扣减或补发。']
    },
    {
      id: 'transaction', title: '预算扣减与领奖记录，需要放在同一个事务里吗？', difficulty: '基础', tags: ['事务一致性', '数据库'],
      description: '说明哪些不变量可以在本地事务内保证，哪些需要跨服务协调。',
      followup: '本地事务已经提交，外部发奖失败，数据库回滚还能解决问题吗？',
      answer: ['当预算与领奖记录在同一个数据库内时，可把条件扣减和业务记录写入纳入本地事务。', '通过受影响行数区分余额不足与扣减成功；并发正确性不能只靠“先查余额再修改”。', '外部发奖不受本地数据库事务控制。需要清晰的状态、可靠触发与有条件的补偿，避免长事务调用外部服务。']
    },
    {
      id: 'outbox', title: '数据库提交后，如何可靠地触发发奖？', difficulty: '进阶', tags: ['可靠消息', '事务一致性'],
      description: '讨论本地消息表、重试与消费幂等，而不是承诺“绝不重复”。',
      followup: '同一条发奖消息被消费两次，怎样保证业务结果不会重复？',
      answer: ['可用事务消息表把业务状态与待发送事件纳入同一本地事务，再由独立投递流程发送。', '投递和消费都可能重试；以业务幂等号让消费端识别已处理的领奖意图。', '记录处理中、成功、失败或待人工核对的状态。超时后的补偿必须以确定结果为前提，不把未知结果当失败。']
    },
    {
      id: 'query', title: '领奖结果查单，应该返回哪些状态？', difficulty: '基础', tags: ['数据库', '事务一致性'],
      description: '把受理、处理中、成功与失败分开，让重试有可依据的状态。',
      followup: '用户看到“处理中”时，接口能承诺奖品一定到账吗？',
      answer: ['按业务幂等号查找领奖记录，明确未受理、处理中、成功和确定失败的业务含义。', '状态变迁应有合法前置条件，避免迟到回调把最终成功覆盖为失败。', '针对幂等号建立唯一索引并约束查询身份；查单返回可说明的状态，不承诺没有依据的到账结果。']
    },
    {
      id: 'project-scope', title: '如何区分你做过的工作、团队成果和本次设计建议？', difficulty: '基础', tags: ['项目表达'],
      description: '用已确认事实表达个人职责，不把合成方案或团队结果当成自己的履历。',
      followup: '状态存储与查单是已经实现的工作，还是这次演练提出的修订？',
      answer: ['先给出背景与个人任务，只列自己实际参与且能核对的工作。', '将项目事实、团队协作和本次架构演练分别描述；对尚未实现的方案使用建议或验证计划的措辞。', '结果只引用已有证据，不填写无法核对的流量、面试得分或业务收益。']
    }
  ];
  const PROJECT_QUESTIONS = [
    { id: 'project-role', practiceId: 'project-scope', title: '在扫码领奖项目中，你具体负责哪部分工作？', tag: '职责与事实', context: '请对照已确认的合成项目材料，区分个人职责、团队协作与演练建议。', followup: '哪些证据可以支持你的职责描述？哪些部分尚未验证？', feedback: '先界定个人参与的范围，再引用可核对的材料。对合成案例和未实现的方案，保留明确的示例或建议措辞。' },
    { id: 'project-boundary', practiceId: 'transaction', title: '从扫码到发奖，最关键的事务边界在哪里？', tag: '技术边界', context: '沿着资格校验、预算扣减与外部发奖，把本地事务和跨服务调用分开说明。', followup: '预算扣减成功，但外部发奖超时，为什么不能简单回滚一切？', feedback: '说明本地数据库能保证什么，外部系统又需要什么协调。将条件扣减、业务记录与未知结果的处理逐一分开。' },
    { id: 'project-evidence', practiceId: 'query', title: '你如何说明修订设计有效，又不把它当成真实业绩？', tag: '验证与证据', context: '围绕重复请求、超时、迟到回调和查单一致性，提出验证步骤。', followup: '你会先验证哪个失败路径？什么结果才算支持设计假设？', feedback: '列出可以执行的验证计划和判定条件。设计演练不是上线结果，不引用没有依据的实际流量或效果数字。' }
  ];
  const NODES = {
    request: { number: '01', title: '扫码请求', questionId: 'idempotency', constraints: ['确定一次领奖意图的业务身份。', '同一意图的重试应复用幂等号。', '请求参数必须经过业务校验。'], boundary: '请求到达不等于领奖成功；重复请求不应该触发新的预算扣减。', question: '同一用户连续扫码，两次请求怎样识别为同一次领奖？', guidance: '先定义活动、用户与规则下的业务唯一性，再描述后续处理。', feedback: '请自行核对：是否解释了业务幂等号的来源、重试复用和唯一约束？' },
    eligibility: { number: '02', title: '资格校验', questionId: 'idempotency', constraints: ['区分活动资格和一次领奖资格。', '校验结果不能替代并发控制。', '重复请求要返回已有业务结果。'], boundary: '先查询再判断的应用逻辑，不能单独保证并发下不重复领奖。', question: '两个请求同时通过资格校验，如何防止后续重复领奖？', guidance: '讨论数据库唯一约束、原子状态迁移和已有结果返回。', feedback: '请自行核对：资格校验之外，是否有可说明的并发唯一性保障？' },
    budget: { number: '03', title: '预算扣减', questionId: 'idempotency', constraints: ['预算不能被扣为负值。', '同一领奖意图不能重复扣减。', '扣减与本地业务记录应有明确原子边界。'], boundary: '条件扣减可守住余额约束，但跨服务发奖仍需要独立的状态与协调。', question: '扣减成功、发奖超时，如何说明不会重复扣减或发奖？', guidance: '把幂等号、条件更新、本地事务和结果未知逐一讲清。', feedback: '请自行核对：是否先说明业务幂等身份，再解释条件扣减与本地事务？发奖超时不能直接视为失败。' },
    award: { number: '04', title: '发奖服务', questionId: 'outbox', constraints: ['请求受理不代表发奖最终成功。', '超时可能意味着结果未知。', '重试必须有业务幂等与明确前置条件。'], boundary: '本地数据库提交后，外部发奖不受本地回滚控制。', question: '外部发奖超时后，先重试、查单还是补偿？依据是什么？', guidance: '说明确定失败与未知结果的区别，再选择查单或有条件的补偿。', feedback: '请自行核对：是否有可靠触发、消费幂等和未知结果的查单路径？不能承诺不会发生消息重复。' },
    status: { number: '05', title: '领奖状态存储', questionId: 'query', constraints: ['区分处理中、成功与确定失败。', '状态迁移需要合法前置条件。', '迟到回调不能覆盖最终成功状态。'], boundary: '状态记录让业务可解释，不等于已实现跨系统强一致。', question: '查单与发奖回调同时到达，怎样避免状态被错误覆盖？', guidance: '通过状态机与带前置状态的条件更新，说明合法变迁。', feedback: '请自行核对：是否明确状态含义、变迁条件与迟到结果的处理？' },
    query: { number: '06', title: '结果查单', questionId: 'query', constraints: ['按业务幂等号查询领奖结果。', '查询需要校验用户和记录归属。', '处理中应给出下一步而非虚假承诺。'], boundary: '查单返回有依据的当前状态，不承诺未经确认的最终到账结果。', question: '用户反复查单仍是“处理中”，系统如何给出可解释的后续路径？', guidance: '补充状态刷新、重试边界与异常核对策略，不造到账保证。', feedback: '请自行核对：是否区分了处理中和最终结果，并说明查单身份、合法状态与异常核对？' }
  };
  const QUESTION_IDS = QUESTIONS.map(question => question.id);
  const REVIEW_IDS = [
    ...Object.keys(NODES).map(id => `board-${id}`),
    ...QUESTION_IDS.map(id => `knowledge-${id}`),
    ...PROJECT_QUESTIONS.map(question => `project-${question.id}`)
  ];

  function blankWorkspace() {
    return {
      board: { version: 'initial', node: 'budget', explanations: { initial: '', revised: '' }, saved: { initial: false, revised: false }, ended: false },
      interview: { mode: 'knowledge', knowledge: { index: 0, answers: {}, ended: false }, project: { index: 0, answers: {}, ended: false } },
      practice: { favorites: [], retry: [], completed: [], notes: {} },
      reviewed: [], reviewSource: 'board'
    };
  }
  function defaultState() {
    return {
      version: VERSION, targetId: 'marketing', stages: Object.fromEntries(TARGETS.map(target => [target.id, '准备中'])),
      evidence: { fields: { ...EVIDENCE_SEED }, confirmed: Object.fromEntries(CONFIRM_KEYS.map(key => [key, false])), dirty: false, saved: false },
      workspaces: Object.fromEntries(TARGETS.map(target => [target.id, blankWorkspace()]))
    };
  }
  const boundedText = value => typeof value === 'string' ? value.slice(0, 14000) : '';
  const cleanList = (value, allowed) => Array.isArray(value) ? [...new Set(value.filter(id => allowed.includes(id)))] : [];
  function normalizeState(raw) {
    const result = defaultState();
    if (!raw || typeof raw !== 'object' || raw.version !== VERSION) return result;
    if (TARGETS.some(target => target.id === raw.targetId)) result.targetId = raw.targetId;
    TARGETS.forEach(target => {
      if (STAGES.includes(raw.stages?.[target.id])) result.stages[target.id] = raw.stages[target.id];
      const saved = raw.workspaces?.[target.id];
      if (!saved || typeof saved !== 'object') return;
      const workspace = result.workspaces[target.id];
      if (saved.board) {
        workspace.board.version = saved.board.version === 'revised' ? 'revised' : 'initial';
        if (Object.hasOwn(NODES, saved.board.node)) workspace.board.node = saved.board.node;
        ['initial', 'revised'].forEach(version => {
          workspace.board.explanations[version] = boundedText(saved.board.explanations?.[version]);
          workspace.board.saved[version] = saved.board.saved?.[version] === true;
        });
        workspace.board.ended = saved.board.ended === true;
        if (workspace.board.version === 'initial' && ['status', 'query'].includes(workspace.board.node)) workspace.board.node = 'budget';
      }
      workspace.interview.mode = saved.interview?.mode === 'project' ? 'project' : 'knowledge';
      ['knowledge', 'project'].forEach(mode => {
        const session = saved.interview?.[mode];
        if (!session) return;
        workspace.interview[mode].index = Number.isInteger(session.index) ? Math.max(0, Math.min(2, session.index)) : 0;
        const allowed = mode === 'knowledge' ? QUESTION_IDS : PROJECT_QUESTIONS.map(question => question.id);
        allowed.forEach(id => {
          const answer = boundedText(session.answers?.[id]);
          if (answer) workspace.interview[mode].answers[id] = answer;
        });
        workspace.interview[mode].ended = session.ended === true;
      });
      ['favorites', 'retry', 'completed'].forEach(key => { workspace.practice[key] = cleanList(saved.practice?.[key], QUESTION_IDS); });
      QUESTION_IDS.forEach(id => {
        const note = boundedText(saved.practice?.notes?.[id]);
        if (note) workspace.practice.notes[id] = note;
      });
      workspace.reviewed = cleanList(saved.reviewed, REVIEW_IDS);
      workspace.reviewSource = ['board', 'knowledge', 'project'].includes(saved.reviewSource) ? saved.reviewSource : 'board';
    });
    if (raw.evidence && typeof raw.evidence === 'object') {
      Object.keys(EVIDENCE_SEED).forEach(key => {
        if (typeof raw.evidence.fields?.[key] === 'string') result.evidence.fields[key] = boundedText(raw.evidence.fields[key]);
      });
      CONFIRM_KEYS.forEach(key => { result.evidence.confirmed[key] = raw.evidence.confirmed?.[key] === true; });
      result.evidence.dirty = raw.evidence.dirty === true;
      result.evidence.saved = raw.evidence.saved === true;
    }
    return result;
  }

  let storageAvailable = true;
  function loadState() {
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      return raw ? normalizeState(JSON.parse(raw)) : defaultState();
    } catch {
      storageAvailable = false;
      return defaultState();
    }
  }
  let state = loadState();
  const ui = { view: 'home', selectedTarget: state.targetId, evidenceTab: 'profile', practiceTab: 'all', practiceTag: 'all', difficulty: 'all', expanded: new Set(), reviewFocus: '' };
  let statusTimer;
  const $ = selector => document.querySelector(selector);
  const $$ = selector => Array.from(document.querySelectorAll(selector));
  const currentTarget = () => TARGETS.find(target => target.id === state.targetId);
  const workspace = () => state.workspaces[state.targetId];
  const pendingRetry = () => workspace().practice.retry.filter(id => !workspace().practice.completed.includes(id));
  const questionById = id => QUESTIONS.find(question => question.id === id);

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'icon');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.append(use);
    return svg;
  }
  function actionButton(text, action, data = {}, className = 'text-button', iconName = '') {
    const button = element('button', className, text);
    button.type = 'button';
    button.dataset.action = action;
    Object.assign(button.dataset, data);
    if (iconName) button.append(icon(iconName));
    return button;
  }
  function viewButton(text, view, data = {}, className = 'text-button') {
    const button = element('button', className, text);
    button.type = 'button';
    button.dataset.view = view;
    Object.assign(button.dataset, data);
    button.append(icon('arrow'));
    return button;
  }
  function tags(container, values) { container.replaceChildren(...values.map(value => element('span', 'tag', value))); }
  function setText(selector, text) { const node = $(selector); if (node) node.textContent = text; }
  function saveState() {
    try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); storageAvailable = true; }
    catch { storageAvailable = false; }
    renderStorageStatus();
  }
  function renderStorageStatus() {
    setText('#storage-status', storageAvailable ? '仅本机保存' : '仅本页暂存');
    setText('#settings-storage-description', storageAvailable ? '本原型状态通过浏览器 localStorage 保存在本机。导出文件可能含你输入的文本，请自行保管。' : '浏览器当前不允许本地持久保存，内容仅在本页面暂存；关闭前可导出 JSON 保留记录。');
  }
  function announce(text) {
    const live = $('#live-status');
    live.textContent = text;
    live.classList.add('is-visible');
    window.clearTimeout(statusTimer);
    statusTimer = window.setTimeout(() => { live.classList.remove('is-visible'); }, 3200);
  }
  function renderContext() {
    $$('[data-current-target]').forEach(node => { node.textContent = currentTarget().title; });
    $('#global-target').value = state.targetId;
    const count = pendingRetry().length;
    $$('[data-pending-count]').forEach(node => { node.hidden = !count; node.textContent = String(count); });
    renderStorageStatus();
  }

  function renderHome() {
    const ws = workspace();
    const pending = pendingRetry();
    const completed = ws.practice.retry.filter(id => ws.practice.completed.includes(id));
    const confirmations = CONFIRM_KEYS.filter(key => state.evidence.confirmed[key]).length;
    tags($('#home-target-tags'), currentTarget().tags);
    setText('#home-evidence-progress', `${confirmations} / 5 项已确认`);
    setText('#home-board-progress', `${ws.board.version === 'revised' ? '修订版' : '初版草图'} · ${ws.board.explanations[ws.board.version].trim() ? '已记录解释' : '待记录解释'}`);
    setText('#home-review-progress', pending.length ? `${pending.length} 项待复练 · ${completed.length} 项已复练` : completed.length ? `${completed.length} 项已复练 · 手动标记` : '复盘后选择待练问题');
    setText('#home-board-version', ws.board.version === 'revised' ? '修订版本' : '初版草图');
    $('#preview-status-node').classList.toggle('is-built', ws.board.version === 'revised');
    $('#preview-query-node').classList.toggle('is-built', ws.board.version === 'revised');
    setText('#preview-revision-label', ws.board.version === 'revised' ? '已加入修订' : '修订方向');
    const primary = $('#home-primary');
    delete primary.dataset.practiceMode;
    delete primary.dataset.interviewMode;
    if (pending.length) {
      setText('#home-next-kicker', '推荐下一步 · 复盘后的再练习');
      $('#home-next-title').replaceChildren(element('span', '', '把追问练透，'), element('span', '', '把表达再向前一步。'));
      setText('#home-next-description', `你为当前目标加入了 ${pending.length} 项待复练。回到问题，补充解释，再自行标记这一轮的完成。`);
      setText('#home-primary-label', '继续本轮复练');
      primary.dataset.view = 'practice'; primary.dataset.practiceMode = 'retry';
    } else if (completed.length) {
      setText('#home-next-kicker', '推荐下一步 · 再讲一遍你的思路');
      $('#home-next-title').replaceChildren(element('span', '', '让新的理解，'), element('span', '', '成为下一次好回答。'));
      setText('#home-next-description', `本轮 ${completed.length} 项复练已由你手动标记完成。带着补充的解释，做一次项目答辩，看看表达是否更清晰。`);
      setText('#home-primary-label', '再练一次项目表达');
      primary.dataset.view = 'interview'; primary.dataset.interviewMode = 'project';
    } else if (ws.board.ended) {
      setText('#home-next-kicker', '推荐下一步 · 回看刚才的设计');
      $('#home-next-title').replaceChildren(element('span', '', '把设计里的追问，'), element('span', '', '变成下一轮练习。'));
      setText('#home-next-description', '这一轮架构演练已结束。对照固定的示例反馈，选择尚未讲清的部分，加入复练清单。');
      setText('#home-primary-label', '查看本轮复盘'); primary.dataset.view = 'review';
    } else {
      setText('#home-next-kicker', '推荐下一步 · 架构演练');
      $('#home-next-title').replaceChildren(element('span', '', '让设计有依据，'), element('span', '', '让回答有底气。'));
      setText('#home-next-description', '围绕「扫码领奖」讲清预算扣减、发奖与查单。先画出思路，再把追问变成下一轮练习。');
      setText('#home-primary-label', '开始架构演练'); primary.dataset.view = 'whiteboard';
    }
    const answerCount = ['knowledge', 'project'].reduce((sum, mode) => sum + Object.values(ws.interview[mode].answers).filter(answer => answer.trim()).length, 0);
    setText('#home-interview-progress', answerCount ? `当前目标已本机记录 ${answerCount} 个回答 · 可继续模拟` : '知识问答或项目答辩，可随时结束');
    setText('#home-retry-description', pending.length ? `当前目标有 ${pending.length} 项待复练，完成后这里同步更新` : completed.length ? `本轮已有 ${completed.length} 项手动标记复练完成` : '定位图节点，选择需要再练的解释');
    const summaryState = $('#home-summary-state');
    summaryState.textContent = state.evidence.confirmed.summary ? '已确认' : '待确认';
    summaryState.classList.toggle('is-confirmed', state.evidence.confirmed.summary);
    const projectConfirmed = ['role', 'boundary', 'result'].filter(key => state.evidence.confirmed[key]).length;
    setText('#home-project-state', `${projectConfirmed} / 3 已确认`);
    $('#home-project-state').classList.toggle('is-confirmed', projectConfirmed === 3);
    setText('#home-retry-count', pending.length ? `${pending.length} 项待练` : completed.length ? `${completed.length} 项已复练` : '暂无事项');
    const list = $('#home-retry-list');
    if (pending.length) list.replaceChildren(...pending.slice(0, 3).map(id => element('p', 'home-retry-entry', questionById(id).title)));
    else if (completed.length) list.replaceChildren(element('p', 'home-retry-entry is-done', '本轮待练事项已完成 · 由你自行标记'));
    else list.replaceChildren(element('p', 'empty-copy', '把复盘里的一个追问，变成下一次可以练的回答。'));
    const link = $('#home-retry-link');
    link.dataset.view = ws.practice.retry.length ? 'practice' : 'review';
    if (ws.practice.retry.length) link.dataset.practiceMode = 'retry'; else delete link.dataset.practiceMode;
    link.replaceChildren(document.createTextNode(ws.practice.retry.length ? '打开复练清单' : '看看示例复盘'), icon('arrow'));
  }

  function renderTargets() {
    const selected = TARGETS.find(target => target.id === ui.selectedTarget) || currentTarget();
    $('#target-list').replaceChildren(...TARGETS.map(target => {
      const card = element('article', `target-card${target.id === selected.id ? ' is-selected' : ''}`);
      const header = element('div', 'target-card-header');
      header.append(element('span', 'target-card-label', `${target.team.split(' · ')[0]} / SYNTHETIC`));
      if (target.id === state.targetId) header.append(element('span', 'target-current', '当前训练目标'));
      card.append(header, element('h2', '', target.title), element('p', '', target.description));
      const tagList = element('div', 'tag-list'); tags(tagList, target.tags); card.append(tagList);
      const footer = element('div', 'target-card-footer');
      const stage = element('label', 'stage-field', '本地阶段');
      const select = element('select'); select.dataset.targetStage = target.id; select.setAttribute('aria-label', `${target.title}的本地阶段`);
      STAGES.forEach(value => { const option = element('option', '', value); option.value = value; select.append(option); });
      select.value = state.stages[target.id]; stage.append(select);
      const view = actionButton(target.id === selected.id ? '正在查看 JD' : '查看 JD 要点', 'select-target', { target: target.id }, 'target-select', 'chevron');
      view.setAttribute('aria-pressed', String(target.id === selected.id));
      footer.append(stage, view); card.append(footer); return card;
    }));
    setText('#jd-heading', selected.title); setText('#jd-team', selected.team);
    tags($('#jd-tags'), selected.tags);
    $('#jd-points').replaceChildren(...selected.points.map(point => element('li', '', point)));
    setText('#jd-current-label', selected.id === state.targetId ? '当前目标' : '待选为当前目标');
    const activate = $('#activate-target');
    activate.disabled = selected.id === state.targetId;
    activate.replaceChildren(document.createTextNode(activate.disabled ? '已是当前目标' : '设为当前目标'), icon(activate.disabled ? 'check' : 'arrow'));
  }

  function evidenceSaveText() {
    if (!storageAvailable) return state.evidence.dirty ? '修改仅在本页暂存' : '仅在本页暂存';
    return state.evidence.dirty ? '修改已本机暂存 · 可保存材料' : state.evidence.saved ? '材料已在本机保存' : '合成初始文本 · 可编辑';
  }
  function renderEvidenceConfirmations() {
    $$('[data-confirm-key]').forEach(input => { input.checked = state.evidence.confirmed[input.dataset.confirmKey]; });
    $$('[data-confirm-state]').forEach(node => {
      const confirmed = state.evidence.confirmed[node.dataset.confirmState];
      node.textContent = confirmed ? '已确认 · 本地标记' : '待确认'; node.classList.toggle('is-confirmed', confirmed);
    });
    $$('[data-evidence-dot]').forEach(node => {
      const tab = node.dataset.evidenceDot;
      const confirmed = tab === 'profile' ? state.evidence.confirmed.summary : tab === 'project' ? ['role', 'boundary', 'result'].every(key => state.evidence.confirmed[key]) : state.evidence.confirmed.star;
      node.classList.toggle('is-confirmed', confirmed);
    });
    setText('#evidence-confirmation-count', `${CONFIRM_KEYS.filter(key => state.evidence.confirmed[key]).length} / 5 项已确认`);
    setText('#evidence-save-status', evidenceSaveText());
  }
  function renderEvidence() {
    $$('[data-evidence-field]').forEach(input => { input.value = state.evidence.fields[input.dataset.evidenceField]; });
    $$('[data-action="evidence-tab"]').forEach(button => {
      const active = button.dataset.tab === ui.evidenceTab; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active));
    });
    $$('[data-evidence-panel]').forEach(panel => { panel.hidden = panel.dataset.evidencePanel !== ui.evidenceTab; });
    renderEvidenceConfirmations();
  }

  function queueQuestion(id) {
    if (!QUESTION_IDS.includes(id)) return;
    const practice = workspace().practice;
    if (!practice.retry.includes(id)) practice.retry.push(id);
    practice.completed = practice.completed.filter(value => value !== id);
  }
  function renderPractice() {
    const practice = workspace().practice;
    $$('[data-action="practice-tab"]').forEach(button => { const active = button.dataset.tab === ui.practiceTab; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active)); });
    $$('[data-action="practice-tag"]').forEach(button => { const active = button.dataset.tag === ui.practiceTag; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active)); });
    $('#practice-difficulty').value = ui.difficulty;
    setText('#practice-retry-count', String(pendingRetry().length)); setText('#practice-favorite-count', String(practice.favorites.length));
    const priority = [...currentTarget().questions, ...QUESTION_IDS.filter(id => !currentTarget().questions.includes(id))];
    let questions = priority.map(questionById);
    if (ui.practiceTab === 'retry') questions = questions.filter(question => practice.retry.includes(question.id));
    if (ui.practiceTab === 'favorites') questions = questions.filter(question => practice.favorites.includes(question.id));
    if (ui.practiceTag !== 'all') questions = questions.filter(question => question.tags.includes(ui.practiceTag));
    if (ui.difficulty !== 'all') questions = questions.filter(question => question.difficulty === ui.difficulty);
    setText('#practice-list-description', ui.practiceTab === 'retry' ? `当前目标的本地复练清单 · ${pendingRetry().length} 项待练` : ui.practiceTab === 'favorites' ? '当前目标收藏的自拟问题' : '精选自拟问题 · 优先排列与当前目标相关的题目');
    setText('#practice-result-count', `${questions.length} 道题目`);
    const list = $('#question-list');
    if (!questions.length) {
      const empty = element('div', 'empty-state');
      const filtered = ui.practiceTag !== 'all' || ui.difficulty !== 'all';
      empty.append(element('h2', '', filtered ? '这个筛选下还没有问题' : ui.practiceTab === 'retry' ? '还没有加入复练的事项' : '这里还没有收藏'));
      empty.append(element('p', '', filtered ? '可以清除筛选，回到当前列表。' : ui.practiceTab === 'retry' ? '在复盘中选择一个尚未讲清的追问，加入复练。这里会显示真正的本地状态。' : '在精选题目中收藏你想反复练习的问题。'));
      empty.append(filtered ? actionButton('清除筛选', 'clear-filters', {}, 'button secondary') : ui.practiceTab === 'retry' ? viewButton('回到复盘选择问题', 'review', {}, 'button secondary') : actionButton('查看精选题目', 'practice-tab', { tab: 'all' }, 'button secondary'));
      list.replaceChildren(empty); return;
    }
    list.replaceChildren(...questions.map((question, index) => {
      const queued = practice.retry.includes(question.id) && !practice.completed.includes(question.id);
      const done = practice.completed.includes(question.id);
      const favorite = practice.favorites.includes(question.id);
      const expanded = ui.expanded.has(question.id);
      const card = element('article', 'question-card'); card.dataset.questionId = question.id;
      const top = element('div', 'question-card-top');
      top.append(element('span', 'question-number', `Q${String(index + 1).padStart(2, '0')}`), element('span', 'difficulty-tag', question.difficulty));
      if (queued || done) top.append(element('span', 'question-state', done ? '已复练 · 自行标记' : '待复练 · 本地清单'));
      card.append(top, element('h2', '', question.title), element('p', 'question-card-description', question.description));
      const actions = element('div', 'question-card-actions');
      const tagList = element('div', 'tag-list'); tags(tagList, question.tags);
      const buttons = element('div', 'button-row');
      const expand = actionButton(expanded ? '收起参考思路' : '展开参考思路', 'expand-question', { question: question.id });
      expand.setAttribute('aria-expanded', String(expanded)); expand.setAttribute('aria-controls', `answer-${question.id}`);
      const favoriteButton = actionButton(favorite ? '已收藏' : '收藏', 'favorite-question', { question: question.id }, `text-button${favorite ? ' is-selected' : ''}`, 'star');
      favoriteButton.setAttribute('aria-pressed', String(favorite));
      buttons.append(expand, favoriteButton, actionButton(queued ? '移出复练' : done ? '再练一次' : '加入复练', 'toggle-retry', { question: question.id }));
      actions.append(tagList, buttons); card.append(actions);
      const answer = element('div', 'question-answer'); answer.id = `answer-${question.id}`; answer.hidden = !expanded;
      answer.append(element('span', 'reference-title', '参考思路 · 固定示例，不是唯一标准答案'));
      const points = element('ul'); points.append(...question.answer.map(point => element('li', '', point))); answer.append(points);
      const label = element('label', 'field-label', '你的复练解释'); label.htmlFor = `practice-note-${question.id}`;
      const textarea = element('textarea'); textarea.id = `practice-note-${question.id}`; textarea.dataset.practiceNote = question.id; textarea.rows = 3; textarea.maxLength = 14000; textarea.placeholder = '补充你自己的理解，之后自行标记这一轮完成。'; textarea.value = practice.notes[question.id] || '';
      const footer = element('div', 'question-answer-footer');
      footer.append(element('span', '', done ? '完成状态由你自行标记，不代表正确性评测' : '文字仅本机保存，没有 AI 自动评价'));
      const complete = actionButton(done ? '本轮已复练' : '标记本轮已复练', 'complete-practice', { question: question.id }, 'button secondary small', 'check'); complete.disabled = done;
      footer.append(complete); answer.append(label, textarea, footer); card.append(answer); return card;
    }));
  }

  function interviewQuestions(mode = workspace().interview.mode) {
    if (mode === 'project') return PROJECT_QUESTIONS;
    return currentTarget().questions.map(id => {
      const question = questionById(id);
      return { id: question.id, practiceId: question.id, title: question.title, tag: question.tags[0], context: question.description, followup: question.followup, feedback: question.answer.join(' ') };
    });
  }
  function renderSessionIndex() {
    const mode = workspace().interview.mode;
    const session = workspace().interview[mode];
    const questions = interviewQuestions(mode);
    const count = questions.filter(question => session.answers[question.id]?.trim()).length;
    setText('#interview-record-count', `已记录 ${count} / ${questions.length} 个回答`);
    $('#interview-progress').replaceChildren(...questions.map((question, index) => {
      const part = element('span', index === session.index ? 'is-current' : session.answers[question.id]?.trim() ? 'is-recorded' : '');
      part.setAttribute('aria-label', `第 ${index + 1} 题${index === session.index ? '，当前' : ''}`); return part;
    }));
    $('#interview-question-index').replaceChildren(...questions.map((question, index) => {
      const button = actionButton('', 'jump-question', { index: String(index) }, `session-index-entry${index === session.index ? ' is-current' : ''}`);
      button.append(element('span', '', String(index + 1).padStart(2, '0')), element('span', '', question.title));
      if (session.answers[question.id]?.trim()) button.append(icon('check'));
      button.setAttribute('aria-label', `转到第 ${index + 1} 题${session.answers[question.id]?.trim() ? '，已记录回答' : ''}`); return button;
    }));
  }
  function renderInterview() {
    const mode = workspace().interview.mode;
    const session = workspace().interview[mode];
    const questions = interviewQuestions(mode);
    const question = questions[session.index];
    $$('[data-action="interview-mode"]').forEach(button => { const active = button.dataset.mode === mode; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active)); });
    setText('#interview-question-position', `QUESTION ${String(session.index + 1).padStart(2, '0')} / ${String(questions.length).padStart(2, '0')}`);
    setText('#interview-question-tag', question.tag); setText('#interview-question', question.title);
    setText('#interview-question-context', question.context); setText('#interview-followup', question.followup);
    $('#interview-answer').value = session.answers[question.id] || '';
    setText('#interview-answer-status', session.answers[question.id]?.trim() ? storageAvailable ? '回答已在本机记录 · 可继续修改' : '回答仅在本页暂存' : '可跳过，不强制完成全部问题');
    $('#interview-previous').disabled = session.index === 0;
    $('#interview-next').replaceChildren(document.createTextNode(session.index === questions.length - 1 ? '结束并查看复盘' : '下一题'), icon('arrow'));
    renderSessionIndex();
  }

  function selectBoardNode(id) {
    if (!Object.hasOwn(NODES, id)) return;
    const board = workspace().board;
    if (['status', 'query'].includes(id)) board.version = 'revised';
    board.node = id;
  }
  function boardNoteText() {
    const board = workspace().board;
    if (!board.explanations[board.version].trim()) return '文字仅在本机记录，不会自动评价';
    if (!storageAvailable) return '设计解释仅在本页面暂存';
    return board.saved[board.version] ? '当前版本的设计解释已保存到本机' : '设计解释已本机暂存 · 可保存解释';
  }
  function renderWhiteboard() {
    const board = workspace().board;
    const revised = board.version === 'revised';
    if (!revised && ['status', 'query'].includes(board.node)) board.node = 'budget';
    $$('[data-action="board-version"]').forEach(button => { const active = button.dataset.version === board.version; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active)); });
    $$('[data-action="select-node"]').forEach(button => { const active = button.dataset.node === board.node; button.classList.toggle('is-selected', active); button.setAttribute('aria-pressed', String(active)); });
    $('#board-secondary-nodes').hidden = !revised; $('#board-upgrade-hint').hidden = revised;
    setText('#board-version-status', revised ? '修订版 · 预置结构' : '初版 · 本地版本');
    setText('#board-revision-description', revised ? '修订版增加状态存储与查单。结构是预置演练方案，解释由你记录。' : '初版聚焦主链路，尚未画出状态存储与查单。');
    const revise = $('#revise-board-button'); revise.disabled = revised;
    revise.replaceChildren(document.createTextNode(revised ? '已添加状态与查单' : '添加状态与查单'), icon(revised ? 'check' : 'arrow'));
    $('#board-explanation').value = board.explanations[board.version]; setText('#board-note-status', boardNoteText());
    const node = NODES[board.node];
    setText('#node-number', node.number); setText('#node-title', node.title);
    $('#node-constraints').replaceChildren(...node.constraints.map(constraint => element('li', '', constraint)));
    setText('#node-boundary', node.boundary); setText('#node-question', node.question); setText('#node-guidance', node.guidance);
  }

  function reviewItems() {
    const ws = workspace();
    if (ws.reviewSource === 'board') {
      const ids = [...new Set([...(ui.reviewFocus ? [ui.reviewFocus] : []), 'budget', 'award', 'query'])].filter(id => Object.hasOwn(NODES, id));
      return ids.map(id => {
        const node = NODES[id];
        const explanation = ws.board.explanations[ws.board.version].trim();
        return {
          id: `board-${id}`, questionId: node.questionId, title: node.question, node: id,
          quoteLabel: explanation ? `你的设计解释 / ${ws.board.version === 'revised' ? '修订版' : '初版'}` : '示例图引用 / 尚无文字解释',
          quote: explanation || (id === 'query' && ws.board.version === 'initial' ? '初版图尚未包含结果查单节点。点击“定位图节点”会打开修订版并选中查单。' : `${ws.board.version === 'revised' ? '修订版' : '初版'}中的「${node.title}」节点。此处引用的是预置图结构，不是你已经说过的回答。`),
          feedback: node.feedback
        };
      });
    }
    const mode = ws.reviewSource;
    const session = ws.interview[mode];
    return interviewQuestions(mode).map((question, index) => ({
      id: `${mode}-${question.id}`, questionId: question.practiceId, title: question.title, mode, index,
      quoteLabel: session.answers[question.id]?.trim() ? '你的本机回答 / 原文引用' : '未记录回答 / 不生成虚构引用',
      quote: session.answers[question.id]?.trim() || '这道问题尚未记录回答。你可以跳过，也可以回到模拟补充。',
      feedback: mode === 'project' ? question.feedback : `请自行核对以下参考要点，并不代表系统认为你已经答对或答错：${question.feedback}`
    }));
  }
  function isItemReviewed(item) { return workspace().reviewed.includes(item.id) || workspace().practice.completed.includes(item.questionId); }
  function renderReview() {
    const ws = workspace();
    const items = reviewItems();
    const pending = pendingRetry();
    $$('[data-action="review-source"]').forEach(button => { const active = button.dataset.source === ws.reviewSource; button.classList.toggle('is-active', active); button.setAttribute('aria-pressed', String(active)); });
    if (ws.reviewSource === 'board') {
      setText('#review-record-description', `${ws.board.version === 'revised' ? '修订版图' : '初版图'} · ${ws.board.explanations[ws.board.version].trim() ? '有本机设计解释' : '尚无文字记录'}`);
    } else {
      const mode = ws.reviewSource; const session = ws.interview[mode];
      const count = interviewQuestions(mode).filter(question => session.answers[question.id]?.trim()).length;
      setText('#review-record-description', `${count} / 3 个本机回答 · ${session.ended ? '已结束本轮' : '可继续记录'}`);
    }
    const unresolved = items.filter(item => !isItemReviewed(item));
    const unqueued = unresolved.filter(item => !pending.includes(item.questionId));
    setText('#review-unexplained-count', `${unresolved.length} 项待自行核对`);
    $('#review-primary').replaceChildren(document.createTextNode(unqueued.length ? '把待解释项加入复练' : '打开本轮复练清单'), icon('arrow'));
    $('#review-item-list').replaceChildren(...items.map((item, index) => {
      const reviewed = isItemReviewed(item);
      const queued = pending.includes(item.questionId);
      const card = element('article', `review-item${item.node === ui.reviewFocus ? ' is-focused' : ''}`); card.dataset.reviewItem = item.id;
      const header = element('div', 'review-item-header');
      header.append(element('span', '', `REVIEW ${String(index + 1).padStart(2, '0')}`), element('span', `review-local-state${reviewed ? ' is-done' : queued ? ' is-queued' : ''}`, reviewed ? '已自行核对 / 复练' : queued ? '已加入复练' : '待解释 · 模板清单'));
      const quote = element('blockquote', 'answer-quote');
      quote.append(element('span', 'quote-label', item.quoteLabel), document.createTextNode(item.quote));
      const feedbackHeading = element('p', 'feedback-heading'); feedbackHeading.append(icon('spark'), document.createTextNode('示例反馈 · 固定模板'));
      card.append(header, element('h3', '', item.title), quote, feedbackHeading, element('p', 'feedback-body', item.feedback));
      const footer = element('div', 'review-item-footer');
      footer.append(item.node ? viewButton('定位图节点', 'whiteboard', { node: item.node }) : viewButton('回到这道问题', 'interview', { interviewMode: item.mode, questionIndex: String(item.index) }));
      const buttons = element('div', 'button-row');
      const selfMarked = ws.reviewed.includes(item.id);
      const self = actionButton(selfMarked ? '撤销已解释' : '我已解释', 'self-review', { item: item.id }); self.setAttribute('aria-pressed', String(selfMarked));
      const add = actionButton(queued ? '已加入复练' : reviewed ? '再加入复练' : '加入复练', 'review-queue-one', { question: item.questionId }, 'button secondary small', queued ? 'check' : 'arrow'); add.disabled = queued;
      buttons.append(self, add); footer.append(buttons); card.append(footer); return card;
    }));
    setText('#review-queue-count', `${pending.length} 项待练`);
    const queue = $('#review-queue-list');
    queue.replaceChildren(...(pending.length ? pending.map(id => { const entry = element('div', 'queue-entry'); entry.append(icon('review'), element('span', '', questionById(id).title)); return entry; }) : [element('p', '', '尚无待复练事项。选择一个问题加入清单，或回看已完成的复练。')]));
    const back = $('#review-back-link');
    back.dataset.view = ws.reviewSource === 'board' ? 'whiteboard' : 'interview';
    if (ws.reviewSource !== 'board') back.dataset.interviewMode = ws.reviewSource; else delete back.dataset.interviewMode;
    back.replaceChildren(document.createTextNode(ws.reviewSource === 'board' ? '回到架构图' : '回到这一轮模拟'), icon('arrow'));
  }

  function renderSettings() {
    setText('#settings-practice-description', `当前目标：收藏 ${workspace().practice.favorites.length} 项 · 待复练 ${pendingRetry().length} 项`);
    renderStorageStatus();
  }
  const RENDERERS = { home: renderHome, targets: renderTargets, evidence: renderEvidence, practice: renderPractice, interview: renderInterview, whiteboard: renderWhiteboard, review: renderReview, settings: renderSettings };
  function renderCurrent() { renderContext(); RENDERERS[ui.view](); }
  function navigate(view, options = {}, focus = true, updateHash = true) {
    if (!Object.hasOwn(VIEW_LABELS, view)) return;
    if (options.evidenceTab && ['profile', 'project', 'star'].includes(options.evidenceTab)) ui.evidenceTab = options.evidenceTab;
    if (options.practiceMode === 'retry') { ui.practiceTab = 'retry'; ui.practiceTag = 'all'; ui.difficulty = 'all'; }
    if (options.interviewMode && ['knowledge', 'project'].includes(options.interviewMode)) workspace().interview.mode = options.interviewMode;
    if (options.questionIndex !== undefined) workspace().interview[workspace().interview.mode].index = Math.max(0, Math.min(2, Number(options.questionIndex) || 0));
    if (view === 'whiteboard' && options.node) selectBoardNode(options.node);
    ui.view = view;
    $('#main').dataset.currentView = view;
    $$('[data-view-panel]').forEach(panel => { panel.hidden = panel.dataset.viewPanel !== view; });
    $$('.nav-item[data-view]').forEach(button => {
      const active = button.dataset.view === view; button.classList.toggle('is-active', active);
      if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    });
    setText('#current-view-label', VIEW_LABELS[view]); document.title = `${VIEW_LABELS[view]} · 问阶 Interview Studio`;
    $('.sidebar').classList.remove('is-open'); $('#mobile-nav-toggle').setAttribute('aria-expanded', 'false');
    saveState(); renderCurrent();
    if (updateHash) { try { window.history.replaceState(null, '', `#${view}`); } catch { /* File previews can restrict History API. */ } }
    if (focus) { $(`#${view}-heading`).focus({ preventScroll: true }); window.scrollTo({ top: 0, behavior: 'auto' }); }
  }
  function switchTarget(id) {
    if (!TARGETS.some(target => target.id === id)) return;
    state.targetId = id; ui.selectedTarget = id; ui.reviewFocus = ''; ui.expanded.clear();
    ui.practiceTab = 'all'; ui.practiceTag = 'all'; ui.difficulty = 'all';
    saveState(); renderCurrent(); announce('当前目标已切换；材料共享，练习记录按目标保留。');
  }
  function finishInterview() {
    const mode = workspace().interview.mode;
    workspace().interview[mode].ended = true; workspace().reviewSource = mode;
    ui.reviewFocus = ''; saveState(); navigate('review');
  }
  function finishBoard(focused = false) {
    const board = workspace().board;
    if (board.explanations[board.version].trim()) board.saved[board.version] = true;
    board.ended = true; workspace().reviewSource = 'board'; ui.reviewFocus = focused ? board.node : '';
    saveState(); navigate('review');
  }
  function exportData() {
    try {
      const payload = { product: 'Interview Studio', version: VERSION, exportedAt: new Date().toISOString(), dataNotice: '合成示例与用户本地输入；不包含模型密钥或真实系统数据。', state };
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' });
      const url = window.URL.createObjectURL(blob);
      const link = element('a'); link.href = url; link.download = 'interview-studio-design-1.json'; document.body.append(link); link.click(); link.remove();
      window.setTimeout(() => window.URL.revokeObjectURL(url), 1000);
      announce('本地 JSON 已生成。导出可能包含你输入的文本，请自行保管。');
    } catch { announce('当前预览未能生成下载，请使用支持本地下载的浏览器打开。'); }
  }

  function handleAction(button) {
    const data = button.dataset;
    const ws = workspace();
    switch (data.action) {
      case 'toggle-nav': {
        const open = $('.sidebar').classList.toggle('is-open'); button.setAttribute('aria-expanded', String(open)); button.setAttribute('aria-label', open ? '收起导航' : '展开导航'); return;
      }
      case 'select-target': ui.selectedTarget = data.target; renderTargets(); return;
      case 'activate-target': switchTarget(ui.selectedTarget); return;
      case 'evidence-tab': ui.evidenceTab = data.tab; renderEvidence(); return;
      case 'save-evidence': state.evidence.dirty = false; state.evidence.saved = true; saveState(); renderEvidenceConfirmations(); announce(storageAvailable ? '材料已在本机保存，确认标记保持可核对。' : '材料仅在本页暂存，可导出 JSON 保留。'); return;
      case 'practice-tab': ui.practiceTab = data.tab; renderPractice(); return;
      case 'practice-tag': ui.practiceTag = data.tag; renderPractice(); return;
      case 'clear-filters': ui.practiceTag = 'all'; ui.difficulty = 'all'; renderPractice(); return;
      case 'expand-question': if (ui.expanded.has(data.question)) ui.expanded.delete(data.question); else ui.expanded.add(data.question); renderPractice(); return;
      case 'favorite-question': {
        if (!QUESTION_IDS.includes(data.question)) return;
        const favorites = ws.practice.favorites; ws.practice.favorites = favorites.includes(data.question) ? favorites.filter(id => id !== data.question) : [...favorites, data.question]; break;
      }
      case 'toggle-retry': {
        if (!QUESTION_IDS.includes(data.question)) return;
        if (pendingRetry().includes(data.question)) ws.practice.retry = ws.practice.retry.filter(id => id !== data.question);
        else queueQuestion(data.question);
        break;
      }
      case 'complete-practice': if (!QUESTION_IDS.includes(data.question)) return; if (!ws.practice.completed.includes(data.question)) ws.practice.completed.push(data.question); break;
      case 'interview-mode': if (!['knowledge', 'project'].includes(data.mode)) return; ws.interview.mode = data.mode; break;
      case 'jump-question': ws.interview[ws.interview.mode].index = Math.max(0, Math.min(2, Number(data.index) || 0)); break;
      case 'previous-question': ws.interview[ws.interview.mode].index = Math.max(0, ws.interview[ws.interview.mode].index - 1); break;
      case 'next-question': if (ws.interview[ws.interview.mode].index === 2) { finishInterview(); return; } ws.interview[ws.interview.mode].index += 1; break;
      case 'finish-interview': finishInterview(); return;
      case 'select-node': selectBoardNode(data.node); break;
      case 'board-version': if (!['initial', 'revised'].includes(data.version)) return; ws.board.version = data.version; if (data.version === 'initial' && ['status', 'query'].includes(ws.board.node)) ws.board.node = 'budget'; break;
      case 'revise-board': ws.board.version = 'revised'; ws.board.node = 'status'; ws.board.ended = false; break;
      case 'save-board': ws.board.saved[ws.board.version] = Boolean(ws.board.explanations[ws.board.version].trim()); saveState(); setText('#board-note-status', boardNoteText()); return;
      case 'finish-board': finishBoard(); return;
      case 'node-review': finishBoard(true); return;
      case 'review-source': if (!['board', 'knowledge', 'project'].includes(data.source)) return; ws.reviewSource = data.source; ui.reviewFocus = ''; break;
      case 'self-review': if (!REVIEW_IDS.includes(data.item)) return; ws.reviewed = ws.reviewed.includes(data.item) ? ws.reviewed.filter(id => id !== data.item) : [...ws.reviewed, data.item]; break;
      case 'review-queue-one': queueQuestion(data.question); break;
      case 'queue-review': {
        const items = reviewItems().filter(item => !isItemReviewed(item) && !pendingRetry().includes(item.questionId));
        if (!items.length) { navigate('practice', { practiceMode: 'retry' }); return; }
        items.forEach(item => queueQuestion(item.questionId)); saveState(); renderCurrent(); announce('待解释项已加入当前目标的复练清单。'); return;
      }
      case 'export-data': exportData(); return;
      case 'request-reset': $('#reset-confirmation').hidden = false; return;
      case 'cancel-reset': $('#reset-confirmation').hidden = true; return;
      case 'confirm-reset': state = defaultState(); ui.selectedTarget = state.targetId; ui.reviewFocus = ''; ui.expanded.clear(); $('#reset-confirmation').hidden = true; saveState(); renderCurrent(); announce('本原型已恢复合成示例，编辑与练习记录已重置。'); return;
      default: return;
    }
    saveState(); renderCurrent();
  }
  function handleInput(event) {
    const input = event.target;
    if (input.dataset.evidenceField) {
      const field = input.dataset.evidenceField;
      if (!Object.hasOwn(EVIDENCE_SEED, field)) return;
      state.evidence.fields[field] = input.value.slice(0, 14000); state.evidence.dirty = true;
      state.evidence.confirmed[CONFIRM_KEYS.includes(field) ? field : 'star'] = false;
      saveState(); renderEvidenceConfirmations();
    } else if (input.id === 'interview-answer') {
      const mode = workspace().interview.mode; const session = workspace().interview[mode]; const question = interviewQuestions(mode)[session.index];
      session.answers[question.id] = input.value.slice(0, 14000); session.ended = false;
      saveState(); setText('#interview-answer-status', storageAvailable ? '回答已在本机记录 · 没有自动评价' : '回答仅在本页暂存'); renderSessionIndex();
    } else if (input.id === 'board-explanation') {
      const board = workspace().board; board.explanations[board.version] = input.value.slice(0, 14000); board.saved[board.version] = false; board.ended = false;
      saveState(); setText('#board-note-status', boardNoteText());
    } else if (input.dataset.practiceNote && QUESTION_IDS.includes(input.dataset.practiceNote)) {
      workspace().practice.notes[input.dataset.practiceNote] = input.value.slice(0, 14000); saveState();
    }
  }
  function handleChange(event) {
    const input = event.target;
    if (input.id === 'global-target') { switchTarget(input.value); return; }
    if (input.dataset.targetStage && STAGES.includes(input.value)) { state.stages[input.dataset.targetStage] = input.value; saveState(); return; }
    if (input.dataset.confirmKey && CONFIRM_KEYS.includes(input.dataset.confirmKey)) {
      state.evidence.confirmed[input.dataset.confirmKey] = input.checked; saveState(); renderEvidenceConfirmations(); return;
    }
    if (input.id === 'practice-difficulty') { ui.difficulty = input.value; renderPractice(); }
  }
  function init() {
    document.addEventListener('click', event => {
      const button = event.target.closest('[data-action], [data-view]');
      if (!button || button.disabled) return;
      if (button.dataset.view) { event.preventDefault(); navigate(button.dataset.view, button.dataset); }
      else handleAction(button);
    });
    document.addEventListener('input', handleInput);
    document.addEventListener('change', handleChange);
    window.addEventListener('hashchange', () => {
      const view = window.location.hash.slice(1);
      if (Object.hasOwn(VIEW_LABELS, view) && view !== ui.view) navigate(view, {}, true, false);
    });
    const initial = window.location.hash.slice(1);
    navigate(Object.hasOwn(VIEW_LABELS, initial) ? initial : 'home', {}, false);
  }

  init();
})();
