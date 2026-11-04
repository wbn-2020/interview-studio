(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const clone = value => JSON.parse(JSON.stringify(value));
  const points = text => Array.from(text);
  const now = () => new Date().toISOString();
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
  function button(text, action, disabled = false, className = 'button') {
    const element = node('button', text, className);
    element.type = 'button'; element.disabled = disabled;
    element.addEventListener('click', action);
    return element;
  }
  function hash(text) {
    let value = 0x811c9dc5;
    for (const point of points(text)) {
      value ^= point.codePointAt(0);
      value = Math.imul(value, 0x01000193) >>> 0;
    }
    return `demo-fnv32-${value.toString(16).padStart(8, '0')}`;
  }
  function legalUnicode(text) {
    for (let i = 0; i < text.length; i += 1) {
      const unit = text.charCodeAt(i);
      if (unit >= 0xd800 && unit <= 0xdbff) {
        const next = text.charCodeAt(++i);
        if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      } else if (unit >= 0xdc00 && unit <= 0xdfff) return false;
    }
    return true;
  }
  function quoteEvidence(revision, quote) {
    if (!quote || !legalUnicode(quote)) return null;
    const index = revision.body.indexOf(quote);
    if (index < 0 || revision.body.indexOf(quote, index + 1) >= 0) return null;
    const start = points(revision.body.slice(0, index)).length;
    const end = start + points(quote).length;
    if (points(revision.body).slice(start, end).join('') !== quote) return null;
    return frozen({ kind: 'QUOTE', revisionId: revision.id, contentHash: revision.hash,
      rangeUnit: 'UNICODE_CODE_POINT', start, end, quote });
  }
  const seeds = frozen({
    B01: {
      key: 'B01', title: '线程池怎样接收这7个任务？',
      body: 'Java 21普通ThreadPoolExecutor处于RUNNING，初始为空。core=2、max=4，ArrayBlockingQueue容量2，AbortPolicy。一个提交线程按顺序execute T1–T7，前一次返回才提交下一次；期间无任务完成／取消、工作线程不取队列，创建成功且不退出，无shutdown或配置改变。逐个说明接收路径，最后线程／队列情况与拒绝行为。',
      criterion: '自拟待审参考：低于core时创建线程；达到core后优先offer入队；入队失败再尝试扩到max；仍无法接收交给拒绝策略。不得把入队顺序当成全部任务启动／完成顺序；配置或并发条件改变需要重新分析。',
      hints: ['先澄清运行状态、队列容量、拒绝策略和提交期间是否有任务完成。', '把线程创建、入队和拒绝视为不同接收分支；达到core后关注队列。', '在题干假设下，T1／T2建线程，T3／T4入队，T5／T6扩线程，T7触发AbortPolicy；这份参考尚待独立技术审阅。'],
      oldSample: '先扩到4个线程，再入队。第7个任务直接丢弃，不抛异常。🧪',
      newSample: '在题设没有任务完成或取队列的条件下，T1和T2创建核心工作线程；T3和T4进入容量2的队列；T5和T6在入队失败后扩到最大线程数4；T7无法接收，由AbortPolicy抛出RejectedExecutionException。接收不等于开始或完成，若队列或并发条件变化须重新分析。',
      quote: '先扩到4个线程，再入队。', condition: '普通线程池、当前有限队列和静止工作线程假设下，区分达到core之后的入队与扩容分支。',
      action: '逐个说明T1–T7的接收路径与拒绝策略，不混同接收、启动和完成。', acceptance: '新答能指出前提、入队／扩容分支以及拒绝策略的行为。'
    },
    P01: {
      key: 'P01', title: '发奖超时，结果未知，怎样继续？',
      body: '合成营销项目：本人负责扫码资格和预算接口对接；按请求号查单仅参与了解。R-001发奖调用超时，远端可能未执行、执行中或已成功但响应丢失。幂等范围、查单可见性与终态、预算释放／延长规则和回调是否存在均待核实。说明要确认的条件、未知分支与重试／预算处理前提，不编造接口保证或本人职责。',
      criterion: '自拟待审参考：超时不是业务失败；有效去重、请求参数与查单／终态契约须确认。未知结果应有确认和升级路径；发奖幂等不自动覆盖预算和资格。回调只有明确新增假设时才能讨论；多条合理确认路径均可，不强制某组件。',
      hints: ['先把已知职责、接口未知条件和新增假设分开。', '关注原请求标识、结果确认、有效去重与预算生命周期，避免把超时当失败。', '解释确认终态／仍未知两类分支；只有可证明的去重与参数范围下考虑重试，预算释放、延长、对账或人工处理须说明契约。'],
      oldSample: '发奖超时就重试，预算先释放。🙂',
      newSample: '超时只能说明本次调用没拿到确定结果，不能当作发奖失败。我负责资格校验和预算接口对接，查单只是参与了解。应保留R-001并确认查单一致性、终态和幂等范围；只有同号同参且去重有效时才考虑重试。查不到仍可能未知，需要有界查询、对账或人工确认；预算是否延长或释放按已确认的终态及契约处理，不能因超时直接释放。回调目前未确认。',
      quote: '发奖超时就重试，预算先释放。', condition: '原请求结果未知，重试去重及预算生命周期契约尚待确认。',
      action: '说明原请求标识、确认路径、未知升级与重试／预算处理前提。', acceptance: '新答能区分确认终态和仍未知，并明确重试及预算处理的前提和职责边界。'
    },
    S01: {
      key: 'S01', title: '超时重试，为什么不能直接换请求号？',
      body: '合成外部命令接口：网络异常后原请求结果未知，客户端准备新建请求号再次提交。去重键、同号同参范围、保存期限以及查询终态规则尚未确认。分析要补充哪些契约，并提出有界处理方式，不假设接口已支持幂等。',
      criterion: '自拟待审参考：传输失败与业务失败分开；新标识可能避开去重，必须确认原命令结果及有效契约。允许查询、对账、人工处理等合理方案；无法确认时不宣称必然成功或必然失败。',
      hints: ['区分网络观察与业务状态。', '核对去重标识、参数、有效期和查询语义。', '保留原命令身份，在有效契约内决定重试；无法确认时明确未知与升级，不无限重试。'],
      oldSample: '遇到异常就创建新请求重试。',
      newSample: '异常不证明原命令失败。应保留原标识并核对去重键、同号同参范围、保存期限和查询终态。确认有效幂等后才在契约内重试；否则先有界查询、对账或人工确认，不能盲目换请求号，也不无限等待。',
      quote: '遇到异常就创建新请求重试。', condition: '原命令可能已生效，去重及查询契约未确认。',
      action: '区分网络失败与业务未知，交代原标识、去重前提和有界升级路径。', acceptance: '新答明确未知状态与契约，不靠换新号消除风险。'
    }
  });
  const noMaterial = frozen({ ...clone(seeds.P01), key: 'M01',
    title: '技术情景：远端命令超时，结果未知',
    body: '无个人材料的合成情景：R-001远端命令调用超时，可能未执行、执行中或已成功但响应丢失。幂等、查单一致性、终态和资源释放规则均未确认。说明确认路径、未知分支与重试前提；不需要声称真实项目经历。',
    criterion: '合成待审参考：超时与业务失败分开；保留原命令身份，核对去重、参数、期限与查询终态契约。允许有界查询、对账或人工确认等合理路径，不补造个人经历。',
    hints: ['区分网络观察与业务状态。', '保留原标识，明确去重与查询契约的未知项。', '只在有效契约内重试；仍未知时有界确认或升级处理，资源释放按终态和契约。'],
    condition: '原命令结果未知，去重、查询及资源释放规则未确认。',
    action: '说明原命令标识、确认路径、重试前提和未知升级。',
    acceptance: '新答能区分业务终态与仍未知，明确契约，不要求个人项目经历。',
    oldSample: '超时就是失败，换请求号重试。',
    newSample: '超时不证明业务失败。保留R-001，确认幂等的键、参数和有效期及查单终态语义。确认契约后决定重试；查不到仍可能未知，有界查询后可对账或人工处理，资源释放按终态和契约，不盲目换请求号。',
    quote: '超时就是失败，换请求号重试。' });
  function followup(topic, ordinal) {
    const project = ['P01', 'M01', 'S01'].some(key => topic === key || topic.startsWith(`${key}-`));
    const base = project ? seeds.P01 : seeds.B01;
    const first = ordinal === 0;
    return frozen({ key: `${topic}-F${ordinal + 1}`, title: project
      ? (first ? '查不到原请求，能否认定失败？' : '若新增回调假设，怎样处理并发通知？')
      : (first ? '换成无界队列，扩容分支怎样变化？' : '若使用CallerRunsPolicy，要补哪些条件？'),
      body: project ? (first
        ? '仅新增“提供按原请求标识查询”的假设。查询可见性、not-found语义、终态和保存期限尚未确认。查不到时应如何区分失败与未知？不假定查单强一致。'
        : '这轮明确新增“存在回调”的假设，不代表原项目事实。讨论回调与查询并发、重复通知和资源处理时需要确认的状态及去重契约。')
        : (first ? '其他条件仍需明确；本轮把队列改为容量上限Integer.MAX_VALUE的LinkedBlockingQueue。在没有队列满、状态改变或线程失败等条件下，说明达到core之后的路径和max的作用。'
          : '本轮拒绝策略改为CallerRunsPolicy。说明拒绝时的运行状态、调用线程执行和延迟风险；不要把它说成所有状态下都必然执行。'),
      criterion: '合成追问标准未审。新增假设须独立于原事实，说明条件与限制；不能继承种子标准的审阅标签。',
      hints: ['先列新增假设和仍未知的契约。', '分情况说明结果，避免把未观察到当确定失败。', '列验证／升级路径及限制；本页不作权威判题。'],
      oldSample: project ? (first ? '查不到就算失败，直接补发。' : '回调到了就更新，不需要去重。') : (first ? '达到core后直接扩到max。' : '被拒绝时总会在调用线程执行。'),
      newSample: project ? (first ? '查不到的含义取决于可见性、保存期限和终态契约，不能自动认定失败。保持原标识，确认契约并有界查询，仍未知时对账或人工处理；重试须有可证明的去重保障。' : '回调是本轮新增假设，不是原项目已知事实。先确认状态迁移、重复通知和查询竞争的契约，在同一命令身份下去重，资源处理只按有效终态，不凭任一迟到通知覆盖新状态。')
        : (first ? '在题设队列尚可入队且运行状态不变的条件下，达到core后优先排队，不因为设置了max就直接扩容；容量、完成和线程创建条件变化需另分析。' : '先确认运行状态。CallerRunsPolicy在未关闭时由调用execute的线程运行被拒绝任务，可能增加调用延迟；关闭时不会按这条路径执行，不能概括为永远执行。'),
      quote: '', condition: '本轮新增条件及剩余未知必须明示；技术依据待审。',
      action: '分清新增假设、未知契约与允许的处理路径。', acceptance: '新答能明确条件、分支和限制，不把假设伪作真实经历。', topic: base.key });
  }
  const state = { view: 'setup', attempts: [], activeAttemptId: null, reports: [],
    decisions: {}, decisionDrafts: {}, actions: [], comparisons: [], comparisonDrafts: {},
    actionId: null, compareAttemptId: null, comparisonCandidate: null, task: null,
    taskHistory: [], taskSequence: 0, finishPreview: null };
  const activeAttempt = () => state.attempts.find(item => item.id === state.activeAttemptId);
  const currentQuestion = attempt => attempt?.questions[attempt.activeIndex];
  const latest = question => question?.revisions.at(-1);
  const activeTask = task => task && ['RUNNING', 'VALIDATING'].includes(task.status);
  const actionById = id => state.actions.find(item => item.id === id);
  function announce(text) { $('notice').textContent = text; }
  function show(view) {
    state.view = view;
    document.querySelectorAll('[data-panel]').forEach(panel => { panel.hidden = panel.dataset.panel !== view; });
    document.querySelectorAll('.nav-item').forEach(item => {
      if (item.dataset.view === view) item.setAttribute('aria-current', 'page');
      else item.removeAttribute('aria-current');
    });
    render();
    document.querySelector(`[data-panel="${view}"] h1`)?.focus({ preventScroll: true });
  }
  function configuration() {
    return { mode: $('practice-mode').value, topic: $('practice-topic').value, factsUsed: $('fact-use').checked };
  }
  function planQuestions(config) {
    return config.mode === 'simulation' ? [seeds.B01, config.factsUsed ? seeds.P01 : noMaterial, seeds.S01] : [seeds[config.topic]];
  }
  function renderSetup() {
    const config = configuration();
    $('plan-summary').textContent = config.mode === 'simulation'
      ? `短模拟：基础判断 → ${config.factsUsed ? '合成项目深挖' : '无材料技术情景'} → 故障取舍。3主问，每主问最多1追问，总计≤6，不计时，结束后复盘。`
      : '专项：一个主问题，最多2追问。提交后可主动获取本轮评价；提示按需展开并记录。';
    $('send-scope').textContent = `本页演示范围：${config.mode === 'simulation' ? '三题Java短模拟' : seeds[config.topic].title}；${config.factsUsed ? '所选合成事实F1／F2／F3与未知约束U1' : '不使用个人事实'}。出题不含历史回答；评价与追问才加入明确选定的提交修订。`;
  }
  function permittedConfig(config) {
    if (config.mode === 'training' && config.topic === 'P01' && !config.factsUsed) {
      $('setup-error').textContent = '项目专项需先确认本页合成事实用途；也可选基础题或无材料短模拟。';
      return false;
    }
    $('setup-error').textContent = ''; return true;
  }
  function makeAttempt(config, questions, originActionId = null) {
    const id = `A${String(state.attempts.length + 1).padStart(2, '0')}`;
    const attempt = { id, mode: config.mode, factsUsed: config.factsUsed, state: 'ACTIVE',
      policyVersion: config.mode === 'simulation' ? 'demo-policy-S1' : 'demo-policy-T1',
      activeIndex: 0, generationEpoch: 0, questions: [], originActionId, endedAt: null, endManifest: null };
    questions.forEach((question, ordinal) => {
      const qid = `${id}/Q${ordinal + 1}`;
      attempt.questions.push({ id: qid, rootId: qid, mainOrdinal: ordinal, kind: 'MAIN',
        question: frozen(clone(question)), parentRevisionId: null, draft: '', editing: true,
        status: ordinal === 0 ? 'ANSWERING' : 'PENDING', revisions: [], hints: [], skipReason: '' });
    });
    state.attempts.push(attempt); state.activeAttemptId = id;
    return attempt;
  }
  function isLiveBasisCurrent(attempt, input) {
    return Boolean(attempt && attempt.state === 'ACTIVE' && attempt.generationEpoch === input.epoch
      && currentQuestion(attempt)?.id === input.questionId && latest(currentQuestion(attempt))?.id === input.revisionId);
  }
  function canPublish(task) {
    const attempt = state.attempts.find(item => item.id === task.input.attemptId);
    if (task.kind === 'QUESTIONS') return JSON.stringify(configuration()) === JSON.stringify(task.input.config);
    if (['FOLLOWUP', 'EVALUATE'].includes(task.kind)) return isLiveBasisCurrent(attempt, task.input);
    if (task.kind === 'REPORT') return attempt?.state === 'COMPLETED' && JSON.stringify(attempt.endManifest) === task.input.manifest;
    const pair = pairFor(task.input.actionId, task.input.newAttemptId);
    return Boolean(pair && pair.newRevision.id === task.input.newRevisionId && pair.action.state === 'PENDING');
  }
  function invalidateTask(task, reason) {
    if (!task || !(activeTask(task) || (task.status === 'SUCCEEDED' && task.kind === 'FOLLOWUP' && !task.adopted))) return;
    clearTimeout(task.timer); task.status = 'STALE'; task.visibleText = ''; task.reason = reason;
  }
  function touchAttempt(attempt, reason) {
    attempt.generationEpoch += 1;
    if (state.task?.input.attemptId === attempt.id && ['FOLLOWUP', 'EVALUATE'].includes(state.task.kind)) invalidateTask(state.task, reason);
  }
  function taskText(kind, input) {
    if (kind === 'QUESTIONS') return planQuestions(input.config).map((q, i) => `${i + 1}. ${q.title}\n${q.body}`).join('\n\n');
    if (kind === 'FOLLOWUP') return `${input.question.title}\n${input.question.body}`;
    return '';
  }
  function startTask(kind, input) {
    if (activeTask(state.task)) { announce('本页已有在途任务。可等待或显式取消，不并发追加。'); return; }
    if (state.task) state.taskHistory.push(state.task);
    const task = { id: `DEMO-T${++state.taskSequence}`, kind, input: frozen(clone(input)), status: 'RUNNING',
      fault: $('fault-mode').value, fullText: taskText(kind, input), cursor: 0, cacheStart: 0,
      visibleText: '', disconnected: false, adopted: false, tick: 0, result: null, reason: '' };
    state.task = task; $('fault-mode').value = 'normal';
    renderTask(); renderInterview();
    task.timer = setTimeout(() => advanceTask(task), 220);
  }
  function advanceTask(task) {
    if (!activeTask(task)) return;
    if (!canPublish(task)) { invalidateTask(task, '依据或配置已改变；未接纳旧生成。'); render(); return; }
    if (task.status === 'RUNNING') {
      const all = points(task.fullText);
      task.tick += 1; task.cursor = Math.min(all.length, task.cursor + 30);
      if (!task.disconnected) task.visibleText = all.slice(task.cacheStart, task.cursor).join('');
      if ((all.length && task.cursor === all.length) || (!all.length && task.tick >= 5)) task.status = 'VALIDATING';
      renderTask(); task.timer = setTimeout(() => advanceTask(task), task.status === 'VALIDATING' ? 450 : 220); return;
    }
    if (task.fault === 'invalid') { task.status = 'FAILED'; task.reason = '合成校验失败：没有正式候选，原答保留。'; }
    else if (task.fault === 'unknown') { task.status = 'UNKNOWN'; task.reason = '合成状态：发送／费用待确认，可能已计费；不记0，不自动重发。'; }
    else { task.status = 'SUCCEEDED'; publishTask(task); }
    render();
  }
  function publishTask(task) {
    if (task.kind === 'QUESTIONS') task.result = frozen(planQuestions(task.input.config).map(q => ({ ...clone(q), source: 'AI出题形态 · 合成预设' })));
    else if (task.kind === 'FOLLOWUP') task.result = task.input.question;
    else if (task.kind === 'COMPARE') {
      const pair = pairFor(task.input.actionId, task.input.newAttemptId);
      const known = pair && pair.newRevision.body === pair.newQuestion.question.newSample;
      task.result = frozen({ pairKey: `${task.input.actionId}/${task.input.newAttemptId}`, text: known
        ? '合成比较建议：新答交代了来源动作要求的前提、确认路径与限制；这不是实际AI评价，也不证明技术掌握。请对照新答证据，自行确认已处理／部分／未处理／无法判断。'
        : '本演示不分析自写内容；仅展示比较任务完成形态，不给预设优劣判断。请自行查看原动作和双方原文。' });
      state.comparisonCandidate = task.result;
    } else {
      const attempt = state.attempts.find(a => a.id === task.input.attemptId);
      const questions = task.kind === 'EVALUATE' ? attempt.questions.filter(q => q.id === task.input.questionId) : attempt.questions;
      const report = { id: `DEMO-REPORT-${state.reports.length + 1}`, taskId: task.id, attemptId: attempt.id,
        scope: task.kind === 'EVALUATE' ? '本轮评价形态' : '结束复盘形态', createdAt: now(), entries: [], notes: [] };
      questions.forEach(q => {
        const revision = latest(q);
        if (!revision) { report.notes.push(`${q.question.title}：${q.status === 'SKIPPED' ? '明确跳过' : '未答'}，不把未答认定为不会。`); return; }
        if (revision.body !== q.question.oldSample) {
          report.notes.push(`${q.question.title}／${revision.id}：${revision.body === q.question.newSample ? '匹配预设合成新答，示例说明已交代条件与限制，不凑缺口或自动结项。' : '自写文本仅保存，本演示不分析，不套用固定评价。'}`); return;
        }
        const evidence = q.question.quote ? quoteEvidence(revision, q.question.quote) : frozen({ kind: 'NOT_MENTIONED', revisionId: revision.id,
          contentHash: revision.hash, rangeUnit: 'UNICODE_CODE_POINT', start: 0, end: points(revision.body).length, needed: q.question.acceptance });
        if (!evidence) { report.notes.push('合成引文校验失败，没有新增反馈。'); return; }
        report.entries.push(frozen({ id: `${report.id}/F${report.entries.length + 1}`, sourceKind: 'AI_FEEDBACK_DEMO',
          attemptId: attempt.id, questionId: q.id, revisionId: revision.id, title: q.question.title,
          evidence, condition: q.question.condition, action: q.question.action, acceptance: q.question.acceptance,
          limitation: '预设合成检查方向；技术标准未审，不能据此给确定技术对错／等级、推断真实接口契约或个人能力。' }));
      });
      task.result = frozen(report); state.reports.push(task.result);
    }
  }
  function renderTask() {
    const task = state.task; $('task-panel').hidden = !task;
    if (!task) return;
    const names = { QUESTIONS: '出题候选', FOLLOWUP: '追问候选', EVALUATE: '本轮评价', REPORT: '结束复盘', COMPARE: '辅助对照' };
    const statuses = { RUNNING: '生成中 · 暂态', VALIDATING: '校验中', SUCCEEDED: '合成结果就绪', FAILED: '校验失败', UNKNOWN: '费用待确认 · 合成', CANCELLED: '已取消本页接纳', STALE: '旧依据失效' };
    $('task-heading').textContent = `${names[task.kind]} · 状态演示`;
    $('task-state').textContent = task.disconnected ? '展示断开' : statuses[task.status];
    $('task-meta').textContent = `${task.id} · ${task.input.revisionId ? `固定依据 ${task.input.revisionId}／代次${task.input.epoch}` : '固定合成清单'} · 没有实际API／账单`;
    $('task-progress').textContent = task.disconnected ? '展示断开，任务可能仍在本页后台运行。重连只读取同一任务，不重新生成。'
      : task.reason || (task.status === 'RUNNING' ? (task.fullText ? '分段呈现公开题干；尚不能采用／作答。' : '整理合成依据；评价不展示未经核对的暂态判断。')
        : task.status === 'VALIDATING' ? '输出结束，演示完整结构／引用核对。' : task.status === 'SUCCEEDED' ? '完整合成结果就绪；采用或用户决定仍是独立动作。' : '原回答与已提交修订保持不变。');
    $('task-output').textContent = task.adopted || task.discarded ? '' : task.disconnected ? task.visibleText : task.status === 'SUCCEEDED' ? task.fullText : activeTask(task) ? task.visibleText : '';
    $('candidate-detail').replaceChildren();
    if (!task.disconnected && task.status === 'SUCCEEDED' && !task.adopted && !task.discarded && ['QUESTIONS', 'FOLLOWUP'].includes(task.kind)) {
      const questions = task.kind === 'QUESTIONS' ? task.result : [task.result];
      const simulationActive = task.kind === 'FOLLOWUP' && state.attempts.some(a => a.id === task.input.attemptId && a.mode === 'simulation' && a.state === 'ACTIVE');
      questions.forEach(q => {
        const box = node('div'); box.append(node('p', `${q.title} · 题干／标准已同次固定 · 私有合成未审`));
        if (simulationActive) box.append(node('p', '短模拟进行中只预览题干和新增假设，不展示参考条件。'));
        else {
          const details = node('details'); details.append(node('summary', '查看合成参考条件（采用不等于专家审阅）'), node('p', q.criterion)); box.append(details);
        }
        $('candidate-detail').append(box);
      });
    }
    $('adopt-candidate').hidden = task.disconnected || task.status !== 'SUCCEEDED' || !['QUESTIONS', 'FOLLOWUP'].includes(task.kind) || task.adopted || task.discarded;
    $('discard-candidate').hidden = $('adopt-candidate').hidden;
    $('open-task-result').hidden = task.disconnected || task.status !== 'SUCCEEDED' || !['EVALUATE', 'REPORT', 'COMPARE'].includes(task.kind);
    $('disconnect-task').hidden = task.disconnected || !activeTask(task);
    $('reconnect-task').hidden = !task.disconnected;
    $('reset-stream').hidden = task.disconnected || !activeTask(task) || !task.fullText;
    $('cancel-task').hidden = !activeTask(task);
    $('retry-task').hidden = task.disconnected || !['FAILED', 'UNKNOWN', 'CANCELLED', 'STALE'].includes(task.status);
  }
  function adoptCandidate() {
    const task = state.task;
    if (!task || task.status !== 'SUCCEEDED' || task.adopted || task.discarded || !canPublish(task)) { announce('候选已失效、丢弃或依据改变，请重新准备。'); return; }
    if (task.kind === 'QUESTIONS') {
      task.adopted = true; makeAttempt(task.input.config, task.result); show('interview'); announce('采用合成题与标准，开始空答新尝试；不是实际AI出题或专家认证。');
    } else if (task.kind === 'FOLLOWUP') {
      const attempt = state.attempts.find(a => a.id === task.input.attemptId);
      const parent = currentQuestion(attempt);
      if (!allowsFollowup(attempt, parent)) { announce('已经达到该主问题的追问上限。'); return; }
      task.adopted = true; touchAttempt(attempt, '采用新轮次，旧实时生成失效。');
      attempt.questions.push({ id: `${attempt.id}/Q${attempt.questions.length + 1}`, rootId: parent.rootId,
        mainOrdinal: parent.mainOrdinal, kind: 'FOLLOWUP', question: task.result, parentRevisionId: task.input.revisionId,
        draft: '', editing: true, status: 'ANSWERING', revisions: [], hints: [], skipReason: '' });
      attempt.activeIndex = attempt.questions.length - 1; state.activeAttemptId = attempt.id; show('interview'); announce('采用合成追问；明确新增假设与父回答，未预填答案。');
    }
    renderTask();
  }
  function allowsFollowup(attempt, question) {
    if (!attempt || attempt.state !== 'ACTIVE' || question?.status === 'SKIPPED' || !latest(question)) return false;
    const count = attempt.questions.filter(q => q.kind === 'FOLLOWUP' && q.rootId === question.rootId).length;
    return count < (attempt.mode === 'simulation' ? 1 : 2) && attempt.questions.length < (attempt.mode === 'simulation' ? 6 : 3);
  }
  function renderInterview() {
    const attempt = activeAttempt();
    $('attempt-select').replaceChildren();
    state.attempts.forEach(a => { const option = node('option', `${a.id} · ${a.originActionId ? '来源复练' : a.mode === 'simulation' ? '短模拟' : '专项'} · ${a.state === 'COMPLETED' ? '已结束' : '进行中'}`); option.value = a.id; $('attempt-select').append(option); });
    $('attempt-select').value = attempt?.id || ''; $('attempt-select').disabled = !attempt;
    $('interview-empty').hidden = Boolean(attempt); $('interview-content').hidden = !attempt;
    $('attempt-summary').textContent = attempt ? `${attempt.id} · ${attempt.mode === 'simulation' ? '短模拟／结束后复盘' : '专项／提交后按需反馈'} · ${attempt.policyVersion} · 本页合成快照，不是服务端保存` : '先采用问题，再独立作答。';
    $('mode-badge').textContent = attempt ? `${attempt.mode === 'simulation' ? '文字短模拟' : '文字专项'} · 合成预设` : 'AI设计预览 · 未接模型';
    if (!attempt) return;
    const q = currentQuestion(attempt); const revision = latest(q); const active = attempt.state === 'ACTIVE';
    $('question-label').textContent = `阶段${q.mainOrdinal + 1} · ${q.kind === 'MAIN' ? '主问题' : '追问'} · ${q.id}`;
    $('question-title').textContent = q.question.title; $('question-body').textContent = q.question.body;
    $('basis-note').textContent = `${q.question.source || '自拟合成种子／追问'} · ${q.question.key}／demo-v1 · ${q.parentRevisionId ? `父回答 ${q.parentRevisionId}，新增假设不是真实事实。` : '题目与标准在答题前固定。'} · demo-fnv32仅演示，非SHA-256。`;
    $('criterion-details').hidden = attempt.mode === 'simulation' && active;
    if ($('criterion-details').hidden) $('criterion-details').open = false;
    $('criterion-copy').textContent = $('criterion-details').hidden ? '' : q.question.criterion;
    $('hint-tools').hidden = !active || attempt.mode === 'simulation';
    $('hint-copy').hidden = !q.hints.length || attempt.mode === 'simulation';
    $('hint-copy').textContent = !$('hint-copy').hidden ? q.question.hints[q.hints.at(-1).level] : '';
    if ($('answer-input').value !== q.draft) $('answer-input').value = q.draft;
    $('answer-input').disabled = !active || !q.editing;
    $('answer-status').textContent = !active ? '已结束，只读；未交草稿仍可查看，但未纳入正式回答。'
      : q.editing ? `${points(q.draft).length} code points · 本页未提交草稿，刷新清空${revision ? `；已有${revision.id}仍保留` : ''}` : `${revision?.id}已成为不可变本页快照；不是服务端保存。`;
    $('submit-answer').disabled = !active || !q.editing; $('edit-revision').hidden = !active || !revision || q.editing;
    $('fill-sample').hidden = !active || !q.editing; $('fill-sample').textContent = attempt.originActionId ? '填入合成新答' : '填入合成原答';
    $('evaluate-answer').hidden = !active || attempt.mode === 'simulation'; $('evaluate-answer').disabled = !revision || q.status === 'SKIPPED' || activeTask(state.task);
    $('generate-followup').hidden = !active; $('generate-followup').disabled = !allowsFollowup(attempt, q) || activeTask(state.task);
    $('next-question').hidden = !active || attempt.mode !== 'simulation';
    $('next-question').disabled = !attempt.questions.some(item => item.kind === 'MAIN' && item.mainOrdinal > q.mainOrdinal);
    $('finish-attempt').hidden = !active; $('skip-question').hidden = !active; $('skip-reason').disabled = !active;
    $('skip-reason').value = q.skipReason; $('simulation-note').hidden = !active || attempt.mode !== 'simulation';
    $('attempt-report').hidden = active; $('attempt-report').disabled = activeTask(state.task);
    $('revision-list').replaceChildren();
    q.revisions.forEach(r => { const box = node('section', '', 'revision-block'); box.append(node('h3', `${r.id} · ${r.codePointLength} code points`), node('p', `${r.hash} · 提示${r.hints.length}次 · 原文不随新修订移动`, 'small-print'), node('pre', r.body, 'answer-copy')); $('revision-list').append(box); });
    if (!q.revisions.length) $('revision-list').append(node('p', '尚无提交修订。', 'small-print'));
    $('round-limit').textContent = `已采用${attempt.questions.length}问／上限${attempt.mode === 'simulation' ? 6 : 3}；${attempt.mode === 'simulation' ? '每主问≤1追问' : '主问外≤2追问'}，无自动追加。`;
    $('round-list').replaceChildren();
    attempt.questions.forEach((item, index) => {
      const locked = active && attempt.mode === 'simulation' && item.kind === 'MAIN' && item.status === 'PENDING';
      const b = button(`${item.status === 'SKIPPED' && active ? '返回 · ' : ''}${item.kind === 'MAIN' ? '主问' : '追问'} · ${item.question.title}`, () => moveQuestion(attempt, index), locked, 'round-button');
      b.append(node('small', latest(item) ? `已交 ${latest(item).id}` : item.status === 'SKIPPED' ? `跳过：${item.skipReason}` : '尚未回答'));
      b.setAttribute('aria-current', String(index === attempt.activeIndex)); $('round-list').append(b);
    });
  }
  function validateBody(body) {
    if (!body.trim()) return '正文为空；可跳过并说明原因，不能保存空回答。';
    if (!legalUnicode(body)) return '含非法孤立Unicode代理项，请修正；没有截断或提交。';
    if (points(body).length > 20000) return '超过20,000 code points，保留输入，不截断。';
    return '';
  }
  function submitRevision(attempt, q) {
    if (attempt.state !== 'ACTIVE') return false;
    const error = validateBody(q.draft);
    if (error) { $('answer-error').textContent = error; return false; }
    if (latest(q)?.body === q.draft) { q.editing = false; return true; }
    const revision = frozen({ id: `${q.id}/R${q.revisions.length + 1}`, body: q.draft,
      hash: hash(q.draft), codePointLength: points(q.draft).length, submittedAt: now(), hints: clone(q.hints) });
    q.revisions.push(revision); q.editing = false; q.status = 'ANSWERED';
    touchAttempt(attempt, '已提交新修订，旧实时任务不再接纳。'); $('answer-error').textContent = ''; return true;
  }
  function moveQuestion(attempt, index) {
    const target = attempt.questions[index];
    if (attempt.activeIndex === index && !(attempt.state === 'ACTIVE' && target.status === 'SKIPPED')) return;
    if (attempt.state === 'ACTIVE') {
      if (['SKIPPED', 'PENDING'].includes(target.status)) target.status = latest(target) ? 'ANSWERED' : 'ANSWERING';
      touchAttempt(attempt, '当前轮次改变或显式返回，旧实时任务失效；每题草稿仍保在本页。');
    }
    attempt.activeIndex = index; $('answer-error').textContent = ''; render();
  }
  function generateFollowup() {
    const attempt = activeAttempt(), q = currentQuestion(attempt), revision = latest(q);
    if (!allowsFollowup(attempt, q)) { announce('需要先提交，且不能超过本题追问上限。'); return; }
    const root = attempt.questions.find(item => item.id === q.rootId);
    const ordinal = attempt.questions.filter(item => item.kind === 'FOLLOWUP' && item.rootId === q.rootId).length;
    startTask('FOLLOWUP', { attemptId: attempt.id, questionId: q.id, revisionId: revision.id, epoch: attempt.generationEpoch, question: followup(root.question.key, ordinal) });
  }
  function feedbackById(id) { return state.reports.flatMap(r => r.entries).find(f => f.id === id); }
  function feedbackRevision(feedback) {
    return state.attempts.find(a => a.id === feedback.attemptId)?.questions.find(q => q.id === feedback.questionId)?.revisions.find(r => r.id === feedback.revisionId);
  }
  function decide(feedback, decision) {
    const note = state.decisionDrafts[feedback.id] || '';
    if (decision === 'DISPUTE' && !note.trim()) { announce('争议请写下依据或限制；原候选不会被改写。'); return; }
    const previous = state.decisions[feedback.id]?.at(-1);
    if (previous?.decision === decision && previous.note === note) { announce('同一决定已保存，没有重复记录。'); return; }
    (state.decisions[feedback.id] ||= []).push(frozen({ decision, note, at: now() }));
    if (decision === 'ACCEPT' && !state.actions.some(a => a.feedbackId === feedback.id)) state.actions.push({ id: `ACTION-${state.actions.length + 1}`, feedbackId: feedback.id,
      attemptId: feedback.attemptId, questionId: feedback.questionId, revisionId: feedback.revisionId,
      text: feedback.action, acceptance: feedback.acceptance, version: 1, state: 'PENDING' });
    render(); announce('用户决定与原候选分开记录；不认证技术正确，不自动结项或清除已有行动。');
  }
  function repractice(action) {
    const origin = state.attempts.find(a => a.id === action.attemptId);
    if (!origin || origin.state !== 'COMPLETED' || action.state !== 'PENDING') { announce('先结束来源尝试；已关闭来源不会因重复操作重新激活。'); return; }
    const source = origin.questions.find(q => q.id === action.questionId);
    makeAttempt({ mode: 'training', factsUsed: origin.factsUsed }, [source.question], action.id);
    state.actionId = action.id; show('interview'); announce('新尝试为空答，不预填旧答案。来源原文仍保留；提示与模式变化在对照中列出。');
  }
  function renderReview() {
    $('report-list').replaceChildren(); $('action-list').replaceChildren();
    if (!state.reports.length) $('report-list').append(node('section', '尚无合成评价。专项先提交原答再主动获取；短模拟先结束再生成复盘。', 'paper padded'));
    state.reports.forEach(report => {
      const sheet = node('section', '', 'paper padded'); sheet.append(node('h2', `${report.attemptId} · ${report.scope}`), node('p', `${report.id} · ${report.taskId} · 固定合成未审标准／非真实AI`, 'small-print'));
      report.notes.forEach(note => sheet.append(node('p', note, 'inline-note')));
      report.entries.forEach(feedback => {
        const revision = feedbackRevision(feedback); const e = feedback.evidence; const card = node('article', '', 'feedback-card');
        card.append(node('span', '合成候选 · 条件待补 · 技术依据待审', 'tag warm'), node('h3', feedback.title),
          node('p', `${feedback.revisionId} · ${e.contentHash} · code point [${e.start},${e.end}) · ${e.kind}`, 'small-print'));
        card.append(node('blockquote', e.kind === 'QUOTE' ? e.quote : '未提及声明绑定完整原答，不伪造负向引文。'), node('p', `适用条件：${feedback.condition}`), node('p', `限制：${feedback.limitation}`),
          node('p', `动作：${feedback.action}`), node('p', `怎样判断：${feedback.acceptance}`));
        const details = node('details'); details.append(node('summary', '打开确切提交原文'), node('pre', revision?.body || '来源缺失', 'answer-copy')); card.append(details);
        const label = node('label', '处理说明（争议必填）'); label.htmlFor = `decision-${feedback.id}`;
        const input = node('input'); input.id = label.htmlFor; input.type = 'text'; input.value = state.decisionDrafts[feedback.id] || '';
        input.addEventListener('input', () => { state.decisionDrafts[feedback.id] = input.value; }); card.append(label, input);
        const row = node('div', '', 'button-row'); ['ACCEPT', 'DISPUTE', 'IGNORE'].forEach((decision, i) => row.append(button(['接受来源动作', '保留争议', '忽略这项'][i], () => decide(feedback, decision)))); card.append(row);
        const decisions = state.decisions[feedback.id] || []; if (decisions.length) card.append(node('p', `决定历史：${decisions.map(d => `${({ ACCEPT: '接受', DISPUTE: '争议', IGNORE: '忽略' })[d.decision]}${d.note ? `（${d.note}）` : ''}`).join(' → ')}`, 'small-print'));
        sheet.append(card);
      });
      $('report-list').append(sheet);
    });
    if (!state.actions.length) $('action-list').append(node('p', '接受一项候选后，来源动作才出现；不会自动接受。', 'small-print'));
    state.actions.forEach(action => {
      const ended = state.attempts.find(a => a.id === action.attemptId)?.state === 'COMPLETED'; const card = node('article', '', 'action-card');
      card.append(node('span', action.state === 'PENDING' ? '待复练／对照' : '本来源已处理', 'tag'), node('p', action.text), node('p', `${action.id}／v${action.version} · 来源 ${action.revisionId}`, 'small-print'), node('p', action.acceptance, 'small-print'),
        button(ended ? '空答开始独立复练' : '来源未结束，先结束尝试', () => repractice(action), !ended || action.state !== 'PENDING'));
      $('action-list').append(card);
    });
  }
  function pairFor(actionId, attemptId) {
    const action = actionById(actionId); if (!action) return null;
    const oldAttempt = state.attempts.find(a => a.id === action.attemptId), newAttempt = state.attempts.find(a => a.id === attemptId && a.originActionId === action.id);
    const oldQuestion = oldAttempt?.questions.find(q => q.id === action.questionId), newQuestion = newAttempt?.questions[0];
    const oldRevision = oldQuestion?.revisions.find(r => r.id === action.revisionId), newRevision = latest(newQuestion);
    if (!oldRevision || !newRevision || oldAttempt.state !== 'COMPLETED' || newAttempt.state !== 'COMPLETED') return null;
    return { action, oldAttempt, newAttempt, oldQuestion, newQuestion, oldRevision, newRevision };
  }
  function currentPair() { return pairFor(state.actionId, state.compareAttemptId); }
  function comparisonKey() { return `${state.actionId || ''}/${state.compareAttemptId || ''}`; }
  function saveComparisonDraft() {
    state.comparisonDrafts[comparisonKey()] = { outcome: $('comparison-outcome').value, evidence: $('comparison-evidence').value, reason: $('comparison-reason').value };
  }
  function renderComparison() {
    $('compare-action').replaceChildren(); $('compare-attempt').replaceChildren();
    state.actions.forEach(action => { const option = node('option', `${action.id} · ${action.text}`); option.value = action.id; $('compare-action').append(option); });
    if (!actionById(state.actionId)) state.actionId = state.actions[0]?.id || null;
    $('compare-action').value = state.actionId || '';
    const candidates = state.actionId ? state.attempts.filter(a => a.originActionId === state.actionId && a.state === 'COMPLETED' && latest(a.questions[0])) : [];
    const placeholder = node('option', '请选择已结束的独立新答'); placeholder.value = ''; $('compare-attempt').append(placeholder);
    candidates.forEach(a => { const option = node('option', `${a.id} · ${latest(a.questions[0]).id}`); option.value = a.id; $('compare-attempt').append(option); });
    if (!candidates.some(a => a.id === state.compareAttemptId)) state.compareAttemptId = null;
    $('compare-attempt').value = state.compareAttemptId || '';
    const pair = currentPair(); const draft = state.comparisonDrafts[comparisonKey()] || {};
    $('comparison-outcome').value = draft.outcome || ''; $('comparison-evidence').value = draft.evidence || ''; $('comparison-reason').value = draft.reason || '';
    $('old-answer').textContent = pair?.oldRevision.body || ''; $('new-answer').textContent = pair?.newRevision.body || '';
    $('old-meta').textContent = pair ? `${pair.oldRevision.id} · ${pair.oldRevision.hash} · 提示${pair.oldRevision.hints.length}次` : '';
    $('new-meta').textContent = pair ? `${pair.newRevision.id} · ${pair.newRevision.hash} · 提示${pair.newRevision.hints.length}次` : '';
    const different = pair && (pair.oldAttempt.mode !== pair.newAttempt.mode || pair.oldAttempt.factsUsed !== pair.newAttempt.factsUsed || pair.oldQuestion.question.body !== pair.newQuestion.question.body);
    $('comparison-context').textContent = pair ? `${pair.action.text}\n判断条件：${pair.action.acceptance}\n${different ? '模式／事实／题干有变化：不宜直接比较，需说明限制。' : '同一题干／标准与事实范围，仍须对照提示和回答；技术标准未审。'}\n旧答是反馈实际来源修订，不一定是旧尝试最后提交。` : '接受来源动作、结束来源和新尝试后，选择双方原文。新答提交不会自动结项。';
    const candidate = state.comparisonCandidate;
    $('comparison-suggestion').hidden = !candidate || candidate.pairKey !== comparisonKey();
    $('comparison-suggestion').textContent = !$('comparison-suggestion').hidden ? candidate.text : '';
    $('request-comparison').disabled = !pair || pair.action.state !== 'PENDING' || activeTask(state.task);
    $('save-comparison').disabled = !pair;
    $('comparison-records').replaceChildren();
    state.comparisons.forEach(record => { const box = node('section', '', 'comparison-record'); box.append(node('h3', `${record.actionId} · ${record.verdict}`), node('p', `${record.oldRevisionId} → ${record.newRevisionId} · 动作v${record.actionVersion}`, 'small-print'), node('p', record.reason)); if (record.evidence) box.append(node('p', `新答证据 [${record.evidence.start},${record.evidence.end})：${record.evidence.quote}`)); $('comparison-records').append(box); });
  }
  function saveComparison() {
    const pair = currentPair(); if (!pair) return;
    saveComparisonDraft(); const draft = state.comparisonDrafts[comparisonKey()];
    if (!draft.outcome || !draft.reason.trim()) { $('comparison-error').textContent = '请选择结论，并写判断理由／限制。'; return; }
    const evidence = quoteEvidence(pair.newRevision, draft.evidence);
    if (['ADDRESSED', 'PARTIAL'].includes(draft.outcome) && !evidence) { $('comparison-error').textContent = '已处理／部分处理需新答中完整且唯一的证据；重复片段请补上下文。'; return; }
    if (draft.evidence && !evidence) { $('comparison-error').textContent = '所填证据不是这份新答中完整唯一的片段。'; return; }
    const fingerprint = JSON.stringify([pair.action.id, pair.newRevision.id, draft]);
    if (state.comparisons.some(c => c.fingerprint === fingerprint)) { announce('同一对照已保存，没有重复记录。'); return; }
    if (pair.action.state !== 'PENDING') { $('comparison-error').textContent = '本来源已结项；重复操作不会复活或覆盖旧结论。'; return; }
    state.comparisons.push(frozen({ fingerprint, actionId: pair.action.id, actionVersion: pair.action.version,
      oldRevisionId: pair.oldRevision.id, newRevisionId: pair.newRevision.id, verdict: draft.outcome, reason: draft.reason, evidence, createdAt: now() }));
    if (draft.outcome === 'ADDRESSED') pair.action.state = 'ADDRESSED';
    if (state.task?.kind === 'COMPARE') invalidateTask(state.task, '用户已另行确认对照，旧在途建议失效。');
    $('comparison-error').textContent = ''; render(); announce(draft.outcome === 'ADDRESSED' ? '仅关闭本来源；不表示技术掌握，旧答保留。' : '记录对照，本来源继续待复练；未自动完成。');
  }
  function finishFingerprint(attempt) {
    return JSON.stringify({ epoch: attempt.generationEpoch, questions: attempt.questions.map(q => [q.id, latest(q)?.id, hash(q.draft), q.status]),
      task: state.task?.input.attemptId === attempt.id ? [state.task.id, state.task.status, state.task.adopted] : null });
  }
  function previewFinish() {
    const attempt = activeAttempt(); if (!attempt || attempt.state !== 'ACTIVE') return;
    state.finishPreview = { attemptId: attempt.id, fingerprint: finishFingerprint(attempt) };
    $('finish-manifest').replaceChildren();
    attempt.questions.forEach(q => {
      const r = latest(q), dirty = q.draft !== (r?.body || ''); const row = node('div', '', 'manifest-row');
      row.append(node('strong', `${q.id} · ${q.question.title}`), node('p', r ? `纳入 ${r.id}；旧修订仍保留。${q.status === 'SKIPPED' ? `当前跳过：${q.skipReason}` : ''}` : q.status === 'SKIPPED' ? `跳过：${q.skipReason}` : '未答，没有正式修订。'));
      if (dirty) row.append(node('p', `有${points(q.draft).length} code points未交草稿；仅纳入已交时此稿明确排除，仍可只读查看。`, 'warning'));
      $('finish-manifest').append(row);
    });
    if (state.task?.input.attemptId === attempt.id && (activeTask(state.task) || (state.task.kind === 'FOLLOWUP' && !state.task.adopted))) $('finish-manifest').append(node('p', `${state.task.id}／${state.task.status}不成为正式轮次；确认结束将停止本页接纳。`, 'inline-note'));
    $('finish-submit').disabled = !attempt.questions.some(q => q.editing && q.draft.trim() && q.draft !== latest(q)?.body);
    $('finish-error').textContent = ''; if (!$('finish-dialog').open) $('finish-dialog').showModal();
  }
  function finishAttempt(submitDrafts) {
    const preview = state.finishPreview, attempt = activeAttempt();
    if (!preview || !attempt || attempt.id !== preview.attemptId || attempt.state !== 'ACTIVE') return;
    if (finishFingerprint(attempt) !== preview.fingerprint) { previewFinish(); $('finish-error').textContent = '提交／任务清单已变化，请重新查看并确认。'; return; }
    const drafts = attempt.questions.filter(q => q.editing && q.draft.trim() && q.draft !== latest(q)?.body);
    if (submitDrafts) {
      const invalid = drafts.map(q => validateBody(q.draft)).find(Boolean);
      if (invalid) { $('finish-error').textContent = invalid; return; }
      drafts.forEach(q => submitRevision(attempt, q));
    }
    touchAttempt(attempt, '来源尝试已结束，停止旧实时结果接纳。');
    attempt.questions.forEach(q => { q.editing = false; if (!latest(q) && q.status !== 'SKIPPED') q.status = 'UNANSWERED'; });
    attempt.state = 'COMPLETED'; attempt.endedAt = now();
    attempt.endManifest = frozen(attempt.questions.map(q => ({ questionId: q.id, questionKey: q.question.key, latestRevisionId: latest(q)?.id || null,
      state: q.status, skipReason: q.skipReason, excludedDraft: q.draft !== (latest(q)?.body || '') })));
    $('finish-dialog').close(); state.finishPreview = null; render(); announce('结束清单固定，未自动调用复盘；可主动获取结束复盘或从来源行动独立复练。');
  }
  function render() { renderSetup(); renderTask(); renderInterview(); renderReview(); renderComparison(); }
  document.querySelectorAll('[data-view]').forEach(item => item.addEventListener('click', () => show(item.dataset.view)));
  ['practice-mode', 'practice-topic', 'fact-use'].forEach(id => $(id).addEventListener('change', () => {
    if (state.task?.kind === 'QUESTIONS') { invalidateTask(state.task, '准备配置改变，旧出题不再接纳。'); if (state.task.status === 'SUCCEEDED' && !state.task.adopted) { state.task.status = 'STALE'; state.task.reason = '准备配置已改变，请重新生成或直接选题。'; } }
    renderSetup(); renderTask();
  }));
  $('generate-questions').addEventListener('click', () => { const config = configuration(); if (permittedConfig(config)) startTask('QUESTIONS', { config }); });
  $('start-preset').addEventListener('click', () => {
    const config = configuration(); if (!permittedConfig(config)) return;
    if (activeTask(state.task)) { announce('请先取消或等待当前任务，再直接采用种子题。'); return; }
    if (state.task?.kind === 'QUESTIONS' && state.task.status === 'SUCCEEDED' && !state.task.adopted) state.task.discarded = true;
    makeAttempt(config, planQuestions(config)); show('interview'); announce('直接采用合成种子题；未采用的出题候选不再展开。空答开始，没有AI调用。');
  });
  $('adopt-candidate').addEventListener('click', adoptCandidate);
  $('open-task-result').addEventListener('click', () => { const task = state.task; if (!task || task.disconnected || task.status !== 'SUCCEEDED') return; show(task.kind === 'COMPARE' ? 'comparison' : 'review'); });
  $('discard-candidate').addEventListener('click', () => { const task = state.task; if (!task || task.status !== 'SUCCEEDED' || task.adopted) return; task.discarded = true; renderTask(); announce('候选已丢弃，不新增题目或轮次；生成已发生时不表示退款，本页没有实际费用。'); });
  $('disconnect-task').addEventListener('click', () => { if (activeTask(state.task)) { state.task.disconnected = true; renderTask(); } });
  $('reconnect-task').addEventListener('click', () => { const task = state.task; if (!task) return; task.disconnected = false; task.visibleText = points(task.fullText).slice(task.cacheStart, task.cursor).join(''); render(); announce(`读取同一${task.id}，没有新任务或重新生成；缓冲丢失则以完整合成结果为准。`); });
  $('reset-stream').addEventListener('click', () => { const task = state.task; if (!activeTask(task)) return; task.cacheStart = task.cursor; task.visibleText = ''; renderTask(); announce('演示reset：已清暂态缓存；完整结果就绪后再读取，不承诺重放全部片段。'); });
  $('cancel-task').addEventListener('click', () => { const task = state.task; if (!activeTask(task)) return; clearTimeout(task.timer); task.status = 'CANCELLED'; task.reason = '本页停止接纳；真实供应商停算／退款不作保证，已保存原答保留。'; task.visibleText = ''; render(); });
  $('retry-task').addEventListener('click', () => { if (state.task && ['FAILED', 'UNKNOWN', 'CANCELLED', 'STALE'].includes(state.task.status)) $('retry-dialog').showModal(); });
  $('retry-back').addEventListener('click', () => $('retry-dialog').close());
  $('retry-confirm').addEventListener('click', () => {
    const old = state.task; $('retry-dialog').close(); if (!old) return;
    if (!canPublish(old)) { announce('旧任务依据已变化，请在当前问题重新发起；不会拿旧R1清单生成R2。'); return; }
    const oldId = old.id; if (old.status === 'UNKNOWN') { old.status = 'STALE'; old.reason = '用户另开新任务；原未知费用占用仍待核对。'; }
    startTask(old.kind, old.input); announce(`${oldId}未复活；显式新建${state.task.id}。真实旧未知费用仍保留，新执行须另行预留；本页没有账单。`);
  });
  $('attempt-select').addEventListener('change', () => { state.activeAttemptId = $('attempt-select').value; $('answer-error').textContent = ''; render(); });
  $('answer-input').addEventListener('input', () => { const a = activeAttempt(), q = currentQuestion(a); if (a?.state === 'ACTIVE' && q.editing) { q.draft = $('answer-input').value.replace(/\r\n/g, '\n'); $('answer-status').textContent = `${points(q.draft).length} code points · 本页未提交草稿，刷新清空；生成依据仍为最近提交修订。`; } });
  $('submit-answer').addEventListener('click', () => { const a = activeAttempt(), q = currentQuestion(a); if (a && q.editing && submitRevision(a, q)) { render(); announce('追加不可变本页修订；没有自动评价、追问或结束。'); } });
  $('edit-revision').addEventListener('click', () => { const q = currentQuestion(activeAttempt()); if (q && activeAttempt().state === 'ACTIVE') { q.editing = true; renderInterview(); $('answer-input').focus(); } });
  $('fill-sample').addEventListener('click', () => {
    const a = activeAttempt(), q = currentQuestion(a); if (!q?.editing || a.state !== 'ACTIVE') return;
    if (q.draft.trim() && q.draft !== latest(q)?.body) { $('answer-error').textContent = '已有未交草稿，请先自行清空或提交；不覆盖当前输入。'; return; }
    q.draft = a.originActionId ? q.question.newSample : q.question.oldSample; renderInterview(); $('answer-error').textContent = ''; announce('显式填入固定合成示例；没有生成、提交或对自写文本判题。');
  });
  $('open-hint').addEventListener('click', () => { const a = activeAttempt(), q = currentQuestion(a); if (!q || a.mode !== 'training' || a.state !== 'ACTIVE') return; q.hints.push(frozen({ level: Number($('hint-level').value), phase: latest(q) ? 'AFTER_SUBMIT' : 'BEFORE_SUBMIT', at: now() })); renderInterview(); });
  $('evaluate-answer').addEventListener('click', () => { const a = activeAttempt(), q = currentQuestion(a), r = latest(q); if (a?.mode === 'training' && a.state === 'ACTIVE' && q.status !== 'SKIPPED' && r) startTask('EVALUATE', { attemptId: a.id, questionId: q.id, revisionId: r.id, epoch: a.generationEpoch }); });
  $('generate-followup').addEventListener('click', generateFollowup);
  $('next-question').addEventListener('click', () => { const a = activeAttempt(), q = currentQuestion(a); if (!a || a.state !== 'ACTIVE') return; if (!latest(q) && q.status !== 'SKIPPED') { $('answer-error').textContent = '先提交或按原因跳过，再推进主问题。'; return; } const index = a.questions.findIndex(item => item.kind === 'MAIN' && item.mainOrdinal > q.mainOrdinal); if (index >= 0) moveQuestion(a, index); });
  $('skip-reason').addEventListener('input', () => { const q = currentQuestion(activeAttempt()); if (q && activeAttempt().state === 'ACTIVE') q.skipReason = $('skip-reason').value; });
  $('skip-question').addEventListener('click', () => { const a = activeAttempt(), q = currentQuestion(a); if (!a || a.state !== 'ACTIVE') return; if (!q.skipReason.trim()) { $('answer-error').textContent = '跳过请说明原因；草稿和已提交修订仍保留。'; return; } q.status = 'SKIPPED'; touchAttempt(a, '跳过当前问题，旧实时任务失效。'); render(); announce('记录跳过原因，未清空草稿／旧提交；可从轮次列表返回。'); });
  $('finish-attempt').addEventListener('click', previewFinish);
  $('finish-back').addEventListener('click', () => { $('finish-dialog').close(); state.finishPreview = null; });
  $('finish-dialog').addEventListener('cancel', () => { state.finishPreview = null; });
  $('finish-committed').addEventListener('click', () => finishAttempt(false));
  $('finish-submit').addEventListener('click', () => finishAttempt(true));
  $('attempt-report').addEventListener('click', () => { const a = activeAttempt(); if (a?.state === 'COMPLETED') startTask('REPORT', { attemptId: a.id, manifest: JSON.stringify(a.endManifest) }); });
  $('compare-action').addEventListener('change', () => { saveComparisonDraft(); state.actionId = $('compare-action').value; state.compareAttemptId = null; $('comparison-error').textContent = ''; renderComparison(); });
  $('compare-attempt').addEventListener('change', () => { saveComparisonDraft(); state.compareAttemptId = $('compare-attempt').value || null; $('comparison-error').textContent = ''; renderComparison(); });
  ['comparison-outcome', 'comparison-evidence', 'comparison-reason'].forEach(id => $(id).addEventListener('input', saveComparisonDraft));
  $('request-comparison').addEventListener('click', () => { const pair = currentPair(); if (pair && pair.action.state === 'PENDING') startTask('COMPARE', { actionId: pair.action.id, newAttemptId: pair.newAttempt.id, newRevisionId: pair.newRevision.id }); });
  $('save-comparison').addEventListener('click', saveComparison);
  $('run-checks').addEventListener('click', () => {
    const results = []; const check = (name, condition) => { if (!condition) throw new Error(name); results.push(`通过：${name}`); };
    try {
      const b = { id: 'R1', body: '我先🧪重答。', hash: hash('我先🧪重答。') }, p = { id: 'R2', body: '我想👩‍💻先查单。', hash: hash('我想👩‍💻先查单。') };
      const be = quoteEvidence(b, '重答'), pe = quoteEvidence(p, '查单');
      check('Unicode code point引文', be?.start === 3 && be.end === 5 && pe?.start === 6 && pe.end === 8);
      check('孤立代理项拒绝', !legalUnicode('\ud800') && !legalUnicode('\udc00') && legalUnicode('🧪'));
      check('重复引文不猜位置', quoteEvidence({ id: 'R', body: '查单查单', hash: hash('查单查单') }, '查单') === null);
      const snapshot = frozen({ body: '旧答', nested: { revision: 1 } }); check('快照深层不可变', Object.isFrozen(snapshot) && Object.isFrozen(snapshot.nested));
      const fixture = { state: 'ACTIVE', generationEpoch: 2, activeIndex: 0, questions: [{ id: 'Q', revisions: [{ id: 'R2' }] }] };
      check('旧R1／代次不能提交实时结果', !isLiveBasisCurrent(fixture, { questionId: 'Q', revisionId: 'R1', epoch: 1 }) && isLiveBasisCurrent(fixture, { questionId: 'Q', revisionId: 'R2', epoch: 2 }));
      const q = { rootId: 'Q', revisions: [{ id: 'R' }] }; const capped = { state: 'ACTIVE', mode: 'training', questions: [{ id: 'Q' }, { rootId: 'Q', kind: 'FOLLOWUP' }, { rootId: 'Q', kind: 'FOLLOWUP' }] };
      check('专项追问达到2轮后拒绝追加', !allowsFollowup(capped, q));
      $('check-result').textContent = `${results.join('\n')}\n仅本页纯逻辑，不是GUI／服务端／AI语义验收。`;
    } catch (error) { $('check-result').textContent = `失败：${error.message}\n没有修改测试数据或隐藏失败。`; }
  });
  render();
})();
