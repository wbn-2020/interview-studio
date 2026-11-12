'use strict';

(() => {
  const paths = {
    today: '<rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4m8-4v4M4 10h16m-11 4h2m3 0h2m-7 3h2"/>',
    prepare: '<rect x="3" y="7" width="18" height="14" rx="2"/><path d="M8 7V4h8v3M3 12h18m-11 0v3h4v-3"/>',
    knowledge: '<circle cx="12" cy="12" r="3"/><ellipse cx="12" cy="12" rx="10" ry="5" transform="rotate(-35 12 12)"/><circle cx="18" cy="5" r="1.5"/>',
    training: '<path d="M5 4h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-8l-6 3v-3H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z"/><path d="m10 8 5 3-5 3Z"/>',
    review: '<path d="M14 3H5v18h14V8Zm0 0v5h5M8 12h8m-8 4h5"/>',
    growth: '<path d="M4 3v17h17M7 15l4-4 4 2 5-7m-5 0h5v5"/>',
    search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    note: '<path d="M5 3h10l4 4v14H5Zm10 0v5h4M8 12h8m-8 4h6"/>',
    board: '<rect x="3" y="3" width="7" height="8" rx="1"/><rect x="14" y="5" width="7" height="14" rx="1"/><rect x="3" y="15" width="7" height="6" rx="1"/>',
    star: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2-5.5-2.9-5.5 2.9 1-6.2L3 9.6l6.2-.9Z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6l4 2"/>',
    link: '<path d="m9 15 6-6m-7 8-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2-1 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0"/>',
    book: '<path d="M12 6C8 3 5 3 2 4v15c4-1 7 0 10 2m0-15c4-3 7-3 10-2v15c-4-1-7 0-10 2Zm0 0v15"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
    refresh: '<path d="M20 7V3m0 4h-4M4 17v4m0-4h4M5 8a8 8 0 0 1 14-2M19 16A8 8 0 0 1 5 18"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
    target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
    layers: '<path d="m12 3 10 6-10 6L2 9Zm-10 11 10 6 10-6M2 18l10 6 10-6"/>',
    pause: '<path d="M8 5v14m8-14v14"/>',
    quote: '<path d="M3 12h6v7H3v-7c0-5 3-7 6-7m6 7h6v7h-6v-7c0-5 3-7 6-7"/>'
  };
  const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.note}</svg>`;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
  const topics = [
    {id:'java', name:'Java 并发', color:'#87b6d7', x:170, y:116},
    {id:'jvm', name:'JVM 与基础', color:'#b1b5df', x:416, y:57},
    {id:'storage', name:'数据与存储', color:'#c3aad5', x:650, y:85},
    {id:'distributed', name:'分布式系统', color:'#8bcdb7', x:642, y:365},
    {id:'project', name:'项目实践', color:'#d1b48b', x:380, y:687},
    {id:'expression', name:'表达与复盘', color:'#d2a7b6', x:120, y:650}
  ];
  const rawConcepts = [
    ['pool','java','线程池',154,208,'从任务进入，到执行与拒绝，梳理每个条件。'],
    ['future','java','异步任务',281,180,'沿着调用、等待与返回，讲清一次异步协作。'],
    ['locks','java','并发边界',216,293,'先说清共享对象，再讨论协作与边界。'],
    ['memory','jvm','运行时内存',393,123,'把抽象区域和一个具体对象的经历联系起来。'],
    ['gc','jvm','GC 条件',512,166,'把现象、运行条件与需要验证的证据分开。'],
    ['diagnosis','jvm','问题诊断',430,261,'保留不同假设，寻找能够区分它们的证据。'],
    ['indexes','storage','索引与查询',663,164,'从一个实际查询，解释选择与取舍。'],
    ['transaction','storage','事务边界',677,279,'明确事务覆盖了什么，也说明没有覆盖什么。'],
    ['isolation','storage','隔离与一致性',565,295,'把一致性要求放回具体的业务条件。'],
    ['cache','distributed','缓存一致性',494,402,'不止记住方案，还要解释失败时会发生什么。'],
    ['idempotency','distributed','幂等设计',671,456,'围绕一次业务意图，说明重复操作的处理。'],
    ['retry','distributed','重试与补偿',573,563,'重试是一个动作，终止条件和恢复依据也要讲清。'],
    ['chain','project','项目核心链路',350,493,'沿着真实业务流程，把自己的职责说具体。'],
    ['facts','project','项目事实',302,596,'区分本人负责、参与了解和仍待核实。'],
    ['outcome','project','结果与依据',427,628,'每个结果都带上口径、时间和可追查的依据。'],
    ['answer','expression','回答结构',118,428,'用条件、过程、取舍和验证组织一段表达。'],
    ['evidence','expression','原文证据',143,554,'每一条反馈，都能回到当时说过的话。'],
    ['repractice','expression','独立复练',216,625,'从空白回答开始，为改进留下新的证据。']
  ];
  const concepts = rawConcepts.map(([id,topic,title,x,y,description]) => ({id,topic,title,x,y,description}));
  const edges = [
    ['pool','future'],['pool','locks'],['future','memory'],['memory','gc'],['gc','diagnosis'],['locks','diagnosis'],
    ['diagnosis','cache'],['diagnosis','indexes'],['indexes','transaction'],['transaction','isolation'],['isolation','cache'],
    ['cache','retry'],['cache','idempotency'],['retry','idempotency'],['idempotency','transaction'],['cache','chain'],
    ['chain','facts'],['facts','outcome'],['outcome','retry'],['chain','answer'],['answer','evidence'],['evidence','repractice'],
    ['repractice','facts'],['evidence','diagnosis'],['locks','answer'],['future','cache']
  ];
  const byId = id => concepts.find(c => c.id === id) || concepts[9];
  const topicOf = id => topics.find(t => t.id === id) || topics[3];
  const neighbors = id => edges.flatMap(([a,b]) => a === id ? [b] : b === id ? [a] : []);
  const seedBody = {
    cache: '我想把缓存问题讲成一条完整链路：先交代数据怎样更新，再解释失败怎样被发现，最后说明如何恢复和验证。\n\n上次练习里，我只说了“失败就重试”。接下来需要补充：谁来重试、什么时候停止，以及如何知道问题已解决。\n\n待核对：方案的适用条件，以及它在具体项目里是否真的存在。',
    retry: '先明确是哪一步没有得到确定结果，再选择后续动作。\n\n整理时分别记录触发条件、执行者、次数边界、终止条件与验证依据。不要只写“加一个重试”。',
    facts: '示例项目：订单与营销平台。\n\n本人负责：订单状态查询接口与超时记录。\n参与了解：缓存更新方案讨论。\n待核实：补偿任务是否覆盖所有失败情况。\n\n以上为原型合成材料，不代表真实个人经历。',
    evidence: '反馈需要同时保留回答版本、原文片段和判断所需的条件。\n\n能定位原文，有助于核对反馈；但不等于已经证明技术判断正确。'
  };
  const seedAnswer = '在这个场景里，我会先更新数据库，更新成功以后再删除缓存。\n\n如果缓存删除失败，就记录一下，然后重试。\n\n我参与过缓存方案讨论，但没有负责补偿任务的实现。具体重试次数和终止规则还需要核实。';
  const state = {
    page:'knowledge', view:'galaxy', topic:'all', selected:'cache', noteId:'note-cache', favorites:new Set(['cache','idempotency']), favoritesOnly:false,
    focus:false, zoom:1, pan:{x:0,y:0}, trail:['cache'], exposure:new Set(), mode:'independent', session:null, attempts:[],
    notes:concepts.map(c => ({id:`note-${c.id}`,concept:c.id,title:c.id==='cache'?'缓存失败路径：我的理解':`${c.title} · 学习笔记`,body:seedBody[c.id] || `${c.description}\n\n我的整理方式：写出条件，使用一个具体例子说明过程，再列出还不能确定的部分。\n\n下一步：尝试不看笔记，独立解释这个概念。`,version:1,source:'个人整理 · 合成示例'})),
    report:{id:'sample-01',concept:'cache',title:'缓存删除失败，接下来呢？',answer:seedAnswer,assisted:false,sample:true,date:'11 月 2 日',exposure:'已读关联笔记'},
    reviewTab:'report', comparison:null, retryScheduled:true, actionResolved:false, noteTaskDone:false, recalls:[], recall:null,
    job:'Java 后端开发工程师', factRoles:['本人负责','参与了解','待核实'], board:{}, nextId:1, noteDrafts:{}, editStatus:'', searchQuery:''
  };
  const navigation = [['today','今日'],['prepare','准备'],['knowledge','知识'],['training','训练'],['review','复盘'],['growth','成长']];
  const main = document.querySelector('#main');
  const primaryNote = id => state.notes.find(n=>n.id===`note-${id}`) || state.notes.find(n=>n.concept===id);
  const dialog = document.querySelector('#dialog');
  let toastTimer;
  let dialogOpener;
  let pendingAction = null;
  const button = (label,action,kind='',extra='') => `<button type="button" class="button ${kind}" data-action="${action}" ${extra}>${label}</button>`;
  const pageHeading = (title,subtitle,actions='') => `<div class="page-heading"><div><h1>${title}</h1><p>${subtitle}</p></div><div class="heading-actions">${actions}</div></div>`;
  const isProtected = id => state.session && state.session.phase !== 'ended' && !state.session.assisted && state.session.questions.some(q => q.concept === id);
  function toast(message) { const el=document.querySelector('#toast'); el.textContent=message; el.classList.add('visible'); clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.classList.remove('visible'),3200); }
  function closeDialog() { if(dialog.open) dialog.close(); if(dialogOpener?.isConnected) dialogOpener.focus(); }
  function showDialog(title,body,{focus='input,textarea,select,button'}={}) {
    if(!dialog.open) dialogOpener=document.activeElement;
    document.querySelector('#dialog-content').innerHTML=`<div class="dialog-header"><h2 id="dialog-title">${title}</h2><button class="icon-button" data-action="close-dialog" aria-label="关闭对话框">×</button></div><div class="dialog-body">${body}</div>`;
    if(!dialog.open) dialog.showModal();
    requestAnimationFrame(()=>dialog.querySelector(focus)?.focus());
  }
  function navigate(page) {
    if(!navigation.some(([id])=>id===page)) page='knowledge';
    if(page==='review' && isProtected(state.report.concept)) { requestAssistance(()=>navigate(page)); return; }
    state.page=page;
    if(location.hash!==`#${page}`) history.pushState(null,'',`#${page}`);
    render(); window.scrollTo({top:0,behavior:'instant'});
  }
  function selectConcept(id) { state.selected=id; state.noteId=primaryNote(id)?.id || state.noteId; state.trail=[...state.trail.filter(x=>x!==id),id].slice(-4); render(); }
  function render() {
    document.querySelector('#main-nav').innerHTML=navigation.map(([id,label])=>`<button class="nav-item ${state.page===id?'active':''}" data-nav="${id}" aria-label="${label}" ${state.page===id?'aria-current="page"':''}>${icon(id)}<span class="nav-label">${label}</span>${id==='review'&&state.retryScheduled?'<span class="nav-number">1</span>':''}</button>`).join('');
    document.querySelector('#breadcrumb-current').textContent=navigation.find(([id])=>id===state.page)[1];
    document.querySelector('#search-icon').innerHTML=icon('search');
    const views={knowledge:knowledgePage,today:todayPage,prepare:preparePage,training:trainingPage,review:reviewPage,growth:growthPage};
    main.innerHTML=views[state.page]();
    document.title=`${navigation.find(([id])=>id===state.page)[1]} · 问阶 Interview Studio`;
  }
  function knowledgePage() {
    const header=pageHeading('知识空间','让零散的记录彼此连接，也让每一次理解有处可循。',button(`${icon('refresh')}回忆练习`,'recall','')+button(`${icon('plus')}新建笔记`,'new-note','primary'));
    const tabs=`<div class="tabs" role="tablist" aria-label="知识视图">${[['galaxy','knowledge','知识银河'],['notes','note','全部笔记'],['board','board','知识白板']].map(([id,ico,label])=>`<button role="tab" aria-selected="${state.view===id}" class="tab ${state.view===id?'active':''}" data-view="${id}">${icon(ico)}${label}${id==='notes'?`<span class="tab-count">${state.notes.length}</span>`:''}</button>`).join('')}<span class="tabs-meta">${concepts.length} 个知识点 · ${topics.length} 个主题</span></div>`;
    const compactPicker=`<div class="compact-picker"><label for="compact-concept">选择知识点</label><select id="compact-concept">${concepts.map(c=>`<option value="${c.id}" ${c.id===state.selected?'selected':''}>${c.title}</option>`).join('')}</select><label class="visually-hidden" for="compact-topic">聚焦主题</label><select id="compact-topic"><option value="all">全部主题</option>${topics.map(t=>`<option value="${t.id}" ${state.topic===t.id?'selected':''}>${t.name}</option>`).join('')}</select></div>`;
    return header+tabs+(state.view==='notes'?notesPage():state.view==='board'?boardPage():`${compactPicker}<div class="knowledge-workspace">${catalog()}${galaxy()}${inspector()}</div><div class="knowledge-footnote">${icon('link')}<span>探索路径</span>${state.trail.map(id=>`<button data-concept="${id}">${escape(byId(id).title)}</button>`).join('<span>›</span>')}<span>· 连接源于已建立的关系</span></div>`);
  }
  function catalog() {
    return `<aside class="catalog" aria-label="知识主题"><div class="catalog-label">我的知识</div><button class="catalog-item ${state.topic==='all'&&!state.favoritesOnly?'active':''}" data-topic="all">${icon('knowledge')}全部知识<span class="item-count">18</span></button>${topics.map(t=>`<button class="catalog-item ${state.topic===t.id?'active':''}" data-topic="${t.id}"><span class="topic-dot" style="--topic:${t.color}"></span>${t.name}<span class="item-count">3</span></button>`).join('')}<div class="catalog-divider"></div><button class="catalog-item ${state.favoritesOnly?'active':''}" data-action="filter-favorites">${icon('star')}我的收藏<span class="item-count">${state.favorites.size}</span></button><button class="catalog-item" data-action="unlinked-notes">${icon('note')}待整理笔记<span class="item-count">${state.notes.filter(n=>!n.concept).length}</span></button><div class="catalog-note">一处知识，多处连接。<br>主题聚焦不会重排星图。</div></aside>`;
  }
  function galaxy() {
    let marked=new Set(concepts.map(c=>c.id));
    if(state.topic!=='all') { const group=concepts.filter(c=>c.topic===state.topic).map(c=>c.id); marked=new Set(group.flatMap(id=>[id,...neighbors(id)])); }
    if(state.favoritesOnly) marked=new Set(state.favorites);
    if(state.focus) marked=new Set([state.selected,...neighbors(state.selected)]);
    const stars=Array.from({length:135},(_,i)=>{const x=(i*173.37+49)%800,y=(i*117.89+17)%730;return `<circle cx="${x.toFixed(2)}" cy="${y.toFixed(2)}" r="${i%7===0?1.15:.55}" fill="#d3e6e8" opacity="${i%4===0?.43:.19}"/>`;}).join('');
    const defs=topics.map(t=>`<radialGradient id="orb-${t.id}" cx="32%" cy="27%" r="75%"><stop stop-color="#e9f6ee"/><stop offset=".21" stop-color="${t.color}"/><stop offset=".66" stop-color="${t.color}" stop-opacity=".44"/><stop offset="1" stop-color="#142331"/></radialGradient>`).join('');
    return `<section class="map-panel" aria-label="连续知识图谱"><div class="map-toolbar"><div><strong>${state.topic==='all'?'我的知识银河':topicOf(state.topic).name}</strong><p>${state.focus?'聚焦当前知识及相邻连接':'每一颗星，都是一份逐渐清晰的理解'}</p></div><button data-action="focus-neighbors" class="${state.focus?'active':''}" aria-pressed="${state.focus}">聚焦关联</button></div><svg class="galaxy-svg" viewBox="0 0 800 730" aria-label="18 个知识点组成的可交互图谱"><defs>${defs}<radialGradient id="selected-glow"><stop stop-color="#8dd9bf" stop-opacity=".17"/><stop offset="1" stop-color="#8dd9bf" stop-opacity="0"/></radialGradient></defs>${stars}<g id="galaxy-world" transform="${worldTransform()}">${topics.map(t=>`<text class="cluster-label" x="${t.x}" y="${t.y}">${t.name}</text>`).join('')}${edges.map(([a,b])=>{const x=byId(a),y=byId(b);return `<line class="galaxy-edge ${a===state.selected||b===state.selected?'selected':''} ${!marked.has(a)||!marked.has(b)?'dimmed':''}" x1="${x.x}" y1="${x.y}" x2="${y.x}" y2="${y.y}" data-edge="${a}/${b}"/>`;}).join('')}${concepts.map(c=>`<g class="galaxy-node ${c.id===state.selected?'selected':''} ${marked.has(c.id)?'':'dimmed'}" transform="translate(${c.x} ${c.y})" data-concept="${c.id}" tabindex="0" role="button" aria-label="查看${c.title}" aria-pressed="${state.selected===c.id}">${c.id===state.selected?'<circle r="72" fill="url(#selected-glow)"/>':''}<circle class="node-ring" r="${c.id===state.selected?30:23}"/><circle r="${c.id===state.selected?21:13}" fill="url(#orb-${c.topic})"/><circle r="${c.id===state.selected?21:13}" fill="none" stroke="${topicOf(c.topic).color}" stroke-opacity=".3"/><text y="${c.id===state.selected?47:35}">${c.title}</text></g>`).join('')}</g></svg><div class="map-bottom"><span class="map-legend">${edges.length} 组知识关联</span><div class="map-zoom"><button data-action="zoom-out" aria-label="缩小图谱">−</button><output id="zoom-label">${Math.round(state.zoom*100)}%</output><button data-action="zoom-in" aria-label="放大图谱">＋</button><button data-action="reset-map" aria-label="复位图谱">⤢</button></div></div></section>`;
  }
  function worldTransform() { return `translate(${state.pan.x} ${state.pan.y}) translate(400 365) scale(${state.zoom}) translate(-400 -365)`; }
  function inspector() {
    const c=byId(state.selected), t=topicOf(c.topic), note=primaryNote(c.id);
    const locked=isProtected(c.id);
    if(!locked) state.exposure.add(c.id);
    return `<aside class="inspector" aria-label="知识详情"><div class="inspector-top"><span class="tag">${t.name}</span><button class="favorite" data-action="favorite" aria-label="${state.favorites.has(c.id)?'取消收藏':'收藏知识点'}" aria-pressed="${state.favorites.has(c.id)}">${icon('star')}</button></div><h2>${c.title}</h2><p class="concept-description">${c.description}</p><div class="inspector-section"><div class="section-caption"><strong>我的理解</strong><span>主笔记 · v${note?.version || 1}</span></div>${locked?`<div class="locked">${icon('lock')}<h3>正在独立作答</h3><p>本题关联笔记将在作答后开放。</p>${button('转为辅助并查看','assist-knowledge','small')}</div>`:`<div class="note-excerpt"><h3>${escape(note?.title || '还没有主笔记')}</h3><p>${escape(note?.body || '从自己的理解开始，写下第一条笔记。')}</p><button data-action="open-primary">打开完整笔记 ↗</button></div>`}</div><div class="inspector-section"><div class="section-caption"><strong>关联知识</strong><span>${neighbors(c.id).length} 个连接</span></div>${neighbors(c.id).slice(0,4).map(id=>`<button class="relation-row" data-concept="${id}"><span>${byId(id).title}</span><small>${topicOf(byId(id).topic).name}</small></button>`).join('')}</div><div class="inspector-section"><div class="section-caption"><strong>连接到练习</strong><span>原文可追溯</span></div><div class="trace-row">${icon('review')}<span>${c.id==='cache'?'11 月 2 日 · 缓存问题专项<br>待复练：讲清失败后的恢复路径':'从这个知识点开始一次独立表达，留下新的练习记录。'}</span></div></div><div class="inspector-actions">${button(`${icon('training')}围绕它练一题`,'practice-selected','primary full')}${button(`${icon('refresh')}先做一次主动回忆`,'recall','full')}</div></aside>`;
  }
  function notesPage() {
    const items=state.unlinkedOnly?state.notes.filter(n=>!n.concept):state.notes;
    const note=items.find(n=>n.id===state.noteId)||items[0];
    if(!note) return `<div class="empty-state"><h3>待整理区很清爽</h3><p>快速记录时可以先不选知识点，稍后再建立关联。</p>${button('写一条自由笔记','new-note','primary')}</div>`;
    state.noteId=note.id;
    const draft=state.noteDrafts[note.id] || note;
    const locked=note.concept&&isProtected(note.concept);
    if(note.concept&&!locked) state.exposure.add(note.concept);
    return `<section class="notes-workspace"><aside class="notes-list" aria-label="笔记列表">${items.map(n=>`<button class="note-list-item ${n.id===note.id?'active':''}" data-note="${n.id}"><h3>${escape(n.title)}</h3><p>${n.concept&&isProtected(n.concept)?'独立作答中，正文暂不展示':escape(n.body.replace(/\n/g,' '))}</p><small>${n.concept?byId(n.concept).title:'未关联知识点'} · v${n.version}</small></button>`).join('')}</aside><div class="note-editor">${locked?`<div class="locked">${icon('lock')}<h2>本题关联笔记暂不展示</h2><p>你可以结束当前作答后再阅读，或明确转为辅助练习。</p>${button('转为辅助并查看','assist-knowledge','primary')}</div>`:`<input id="note-title" aria-label="笔记标题" maxlength="100" value="${escape(draft.title)}"><div class="editor-meta"><span class="tag">${note.concept?byId(note.concept).title:'自由笔记'}</span><span>${escape(note.source)} · v${note.version}</span></div><textarea id="note-body" aria-label="笔记正文" spellcheck="false">${escape(draft.body)}</textarea><div class="editor-footer"><span id="note-save-state">${state.noteDrafts[note.id]?'有未暂存的修改':'当前版本在本页可用'}</span>${button(`${icon('check')}暂存笔记`,'save-note','primary')}</div>`}</div></section>`;
  }
  const boardSeeds=[['cache',410,220],['isolation',110,55],['retry',735,85],['idempotency',740,350],['chain',90,350]];
  function boardPage() {
    boardSeeds.forEach(([id,x,y])=>state.board[id] ||= {x,y});
    return `<div class="board"><div class="board-world"><svg class="board-lines" aria-hidden="true">${boardSeeds.slice(1).map(([id])=>boardLine(id)).join('')}</svg>${boardSeeds.map(([id])=>{const c=byId(id),p=state.board[id];return `<article class="board-card ${id==='cache'?'current':''}" data-board-card="${id}" style="left:${p.x}px;top:${p.y}px"><button class="board-handle" data-board-handle="${id}" aria-label="移动${c.title}，也可使用方向键"><span>${topicOf(c.topic).name}</span><span>⠿</span></button><h3>${c.title}</h3><p>${c.description}</p><button class="text-button" data-board-open="${id}">打开源笔记 ${icon('arrow')}</button></article>`;}).join('')}</div><div class="board-caption"><span>拖动卡片整理思路 · 卡片引用同一份知识</span><button data-action="reset-board">整理布局 ↻</button></div></div>`;
  }
  function boardLine(id) { const a=state.board.cache,b=state.board[id]; return `<line data-board-line="${id}" x1="${a.x+105}" y1="${a.y+65}" x2="${b.x+105}" y2="${b.y+65}"/>`; }
  function todayPage() {
    return pageHeading('今天，往前一步。','11 月 4 日，星期三 · 从一项具体的小练习开始。',button(`${icon('plus')}快速记录`,'new-note'))+`<div class="content-split"><section><div class="focus-task"><span class="tag green">${state.actionResolved?'已留下复练判断':'建议先做 · 来源复练'}</span><h2>再讲一次，<br>缓存删除失败以后。</h2><p>上一次回答停在了“失败就重试”。这次试着把触发、执行者和验证方式连成一条完整路径。</p><div class="button-row">${button(`${icon('training')}开始独立复练`,'retry','primary')}${button('回看上次回答','open-review')}</div></div><div class="agenda"><div class="section-title"><h2>今日安排</h2><span class="micro muted">每项安排，只记录一次执行</span></div><div class="agenda-row"><button class="check-circle ${state.recalls.length?'done':''}" data-action="recall" aria-label="进入主动回忆">${state.recalls.length?'✓':''}</button><div><h3>复习缓存一致性</h3><p>${state.recalls.length?'本页已有回忆记录':'先回忆，再打开笔记'}</p></div><span>约 3 分钟</span>${button(state.recalls.length?'再练一次':'开始回忆','recall','small')}</div><div class="agenda-row"><button class="check-circle ${state.noteTaskDone?'done':''}" data-action="toggle-note-task" aria-label="${state.noteTaskDone?'撤回笔记整理完成':'完成笔记整理'}">${state.noteTaskDone?'✓':''}</button><div><h3>整理一次失败路径</h3><p>补充自己的理解，保留不确定条件</p></div><span>约 5 分钟</span>${button('打开笔记','open-primary','small')}</div><div class="agenda-row"><span class="check-circle ${state.actionResolved?'done':''}">${state.actionResolved?'✓':''}</span><div><h3>${state.retryScheduled?'缓存题 · 独立复练':'安排下一次独立表达'}</h3><p>${state.actionResolved?'已记录个人比较结论':'阅读完成不自动关闭这项改进动作'}</p></div>${button('进入复练','retry','small')}</div></div></section><aside><div class="panel"><span class="micro muted">接下来的一场面试 · 示例</span><div class="event-date"><div class="date-tile"><small>11 月</small><strong>12</strong></div><div><p>Java 后端 · 技术一面</p><small>周四 14:30 · 上海时间</small></div></div><button class="list-link" data-nav="prepare">查看岗位与项目事实 ${icon('chevron')}</button><button class="list-link" data-nav="knowledge">打开知识空间 ${icon('chevron')}</button></div><div class="daily-note"><h3>你的积累，不止于这一次。</h3><p>知识可以在不同岗位和训练中复用。每次回答仍保留当时的条件与原文。</p></div>${state.session&&state.session.phase!=='ended'?`<div class="daily-note"><h3>还有一份作答在进行</h3><p>输入已在本页保留。</p>${button('继续作答','continue-session','small primary')}</div>`:''}</aside></div>`;
  }
  function preparePage() {
    return pageHeading('准备空间','把目标和真实经历整理清楚，再开始练习。',button(`${icon('target')}编辑目标`,'edit-job'))+`<div class="content-split"><section><div class="panel"><div class="job-summary"><span class="job-mark">${icon('prepare')}</span><div><span class="tag green">当前关注目标</span><h2>${escape(state.job)}</h2><p>订单与交易方向 · 中级岗位 · 合成 JD</p></div></div><div class="section-title" style="margin-top:24px"><h3>岗位希望看到的表现</h3><span class="micro muted">考察项，不是能力评分</span></div><button class="list-link" data-start="cache">解释缓存异常及恢复思路 ${icon('arrow')}</button><button class="list-link" data-start="chain">沿着一条项目链路讲清职责 ${icon('arrow')}</button><button class="list-link" data-start="pool">解释并发任务的边界条件 ${icon('arrow')}</button></div><div class="section-title" style="margin-top:28px"><h2>项目事实</h2><span class="micro muted">订单与营销平台 · 示例材料</span></div>${[['查询接口','负责订单状态查询接口及超时记录。'],['缓存方案','参加过缓存更新方案的讨论。'],['补偿范围','补偿任务是否覆盖所有失败情况，仍需核实。']].map(([title,body],i)=>`<div class="fact-row">${icon('note')}<div class="fact-body"><h3>${title}</h3><p>${body}</p></div><select data-fact="${i}" aria-label="${title}职责状态">${['本人负责','参与了解','待核实'].map(v=>`<option ${v===state.factRoles[i]?'selected':''}>${v}</option>`).join('')}</select></div>`).join('')}<p class="quiet-note">修改职责只改变本页示例材料；已经提交的回答保留原文。</p></section><aside><div class="panel"><h3>准备材料</h3><button class="list-link" data-action="project-story">自我介绍与项目故事 ${icon('chevron')}</button><button class="list-link" data-action="new-note">新建材料笔记 ${icon('chevron')}</button><button class="list-link" data-nav="growth">查看已有表现依据 ${icon('chevron')}</button><p class="quiet-note">不必先填完整简历，也可以直接开始基础练习。</p>${button('直接练一题','practice-selected','primary full')}</div></aside></div>`;
  }
  function makeQuestion(id) {
    const c=byId(id);
    const specific={cache:['缓存删除失败，接下来呢？','假设数据库更新已经成功，但缓存删除请求失败。请说明你会先确认哪些条件，以及后续如何发现、处理和验证这个问题。'],retry:['一次请求超时后，你会直接重试吗？','请先区分已知信息与未知结果，再说明你需要哪些契约或记录来决定后续动作。'],facts:['这条链路里，哪些工作是你负责的？','结合示例项目事实，分别说明你实际承担、参与了解和仍待核实的部分。']};
    const [title,prompt]=specific[id] || [`怎样讲清「${c.title}」？`,`请先给出必要条件，用一个具体例子解释${c.title}，再说明你还需要验证什么。`];
    return {concept:id,title,prompt};
  }
  function trainingPage() {
    const s=state.session;
    if(!s || s.phase==='ended') return trainingSetup();
    const q=s.questions[s.index], submitted=typeof s.answers[s.index]==='string', paused=s.phase==='paused';
    return pageHeading(s.retryOld?'独立复练':'专注这一道题',`${s.kind==='mock'?'文字模拟':'文字专项'} · 第 ${s.index+1} / ${s.questions.length} 题 · 本预览不计时`,button(`${icon(paused?'training':'pause')}${paused?'恢复作答':'暂停'}`,'toggle-pause')+button('结束本次练习','end-session'))+`<div class="training-layout"><section>${paused?'<div class="paused-banner">练习已暂停，输入暂为只读。恢复后可以继续作答。</div>':''}<div class="question-panel"><div class="question-body"><div class="question-meta"><span class="tag green">${s.assisted?'辅助练习':'独立模式'}</span><span class="tag">${topicOf(byId(q.concept).topic).name}</span><span class="tag">${s.exposure?'已打开关联笔记':'未记录相关资料使用'}</span></div><h2>${q.title}</h2><p>${q.prompt}</p></div><div class="answer-area"><div class="answer-label"><strong>你的回答</strong><span id="draft-status">${submitted?'原文已记录于本页':'输入仅在当前页面保留'}</span></div>${submitted?`<div class="read-only-answer">${escape(s.answers[s.index] || '本题已跳过，未记录回答。')}</div>`:`<textarea id="answer-input" aria-label="你的独立回答" placeholder="先用自己的话说清楚。不需要一开始就写得完美。" ${paused?'readonly':''}>${escape(s.draft)}</textarea>`}<div class="answer-footer"><small id="answer-count">${(submitted?s.answers[s.index]:s.draft).length} 字 · 文字练习</small><div class="button-row">${!submitted?button('跳过本题','skip-question','',paused?'disabled':''):''}${submitted?button(s.index<s.questions.length-1?'下一题':'查看示例复盘','next-question','primary',paused?'disabled':''):button(`${icon('check')}提交本题`,'submit-answer','primary',paused||!s.draft.trim()?'disabled':'')}</div></div></div></div><p class="training-status">${s.retryOld?'这是一份新的回答，旧答案默认不展开。':'提交与评价分开；本页没有连接模型。'}${submitted&&s.kind==='independent'&&s.questions.length===1?` <button class="text-button" data-action="followup" ${paused?'disabled':''}>继续一道示例追问 →</button>`:''}</p></section><aside><div class="panel"><h3>本次上下文</h3><div class="context-item">${escape(s.job)}<small>开始时固定的目标岗位</small></div><div class="context-item">${byId(q.concept).title}<small>自拟练习题 · 技术标准待核对</small></div><div class="context-item">${s.assisted?'已使用辅助':'先独立表达'}<small>${s.assisted?'报告会保留辅助使用标记。':'查看参考或填入示例将转为辅助练习。'}</small></div><div style="margin-top:18px">${button('查看学习参考','show-reference','full',paused?'disabled':'')}</div>${s.reference?`<div class="reference-box"><strong>自检提示 · 示例</strong><p>条件是什么？谁执行后续动作？如何判断结果？哪些部分仍然未知？</p></div>`:''}<button class="text-button" data-action="fill-sample" ${paused||submitted?'disabled':''}>填入示例回答，体验后续流程</button></div></aside></div>`;
  }
  function trainingSetup() {
    const q=makeQuestion(state.selected);
    return pageHeading('练到，能独立讲清。','从知识出发，用一次自己的回答检验理解。')+`<div class="training-layout"><section class="panel"><h3>选择这一次的练习方式</h3><div class="mode-options">${[['learning','边学边练','可查看笔记，梳理自己的理解。'],['independent','独立专项','先作答，再看参考与复盘。'],['mock','文字模拟','连续回答 3 题，结束后统一复盘。']].map(([id,title,desc])=>`<button class="mode-option ${state.mode===id?'active':''}" data-mode="${id}" aria-pressed="${state.mode===id}"><strong>${title}</strong><span>${desc}</span></button>`).join('')}</div><label class="micro muted" for="question-concept">围绕哪个知识点</label><select class="field" id="question-concept">${concepts.map(c=>`<option value="${c.id}" ${c.id===state.selected?'selected':''}>${c.title}</option>`).join('')}</select><div class="question-preview"><span class="tag">练习预览 · 自拟示例</span><h2>${q.title}</h2><p>${q.prompt}</p><div class="button-row" style="margin-top:26px">${button(`${icon('training')}开始${state.mode==='mock'?'文字模拟':'本次练习'}`,'start-session','primary')}<span class="micro muted">${state.mode==='mock'?'3 道主问':'1 道主问，可继续一次示例追问'} · 不计时</span></div></div></section><aside class="panel"><h3>这次会留下什么</h3><div class="context-item">一份自己的原始回答<small>输入与提交分开，不替你自动填答案。</small></div><div class="context-item">有来源的后续动作<small>从需要改进的具体地方开始。</small></div><div class="context-item">一次新的复练机会<small>保留新旧回答及使用参考的条件。</small></div><p class="quiet-note">本次使用合成内容。复盘页展示交互示例，不代表 AI 已评价你的回答。</p></aside></div>`;
  }
  function reviewPage() {
    const r=state.report;
    const header=pageHeading('让下一次回答，更进一步。','回到原文，找到一个具体、可以复练的改进点。',button(`${icon('note')}沉淀为笔记`,'candidate-note')+button(`${icon('refresh')}开始独立复练`,'retry','primary'));
    const tabs=`<div class="tabs"><button class="tab ${state.reviewTab==='report'?'active':''}" data-review-tab="report">${icon('review')}回答与复盘</button><button class="tab ${state.reviewTab==='compare'?'active':''}" data-review-tab="compare">${icon('layers')}新旧对照${state.comparison?'<span class="tab-count">1</span>':''}</button><span class="tabs-meta">${r.date} · ${r.assisted?'辅助练习':'独立模式'} · ${r.sample?'历史合成样例':'本页提交记录'}</span></div>`;
    if(state.reviewTab==='compare') return header+tabs+comparisonPage();
    const segments=r.answer.split(/\n\s*\n/).filter(Boolean);
    const draftView=(r.unsubmitted||[]).length?`<details class="draft-recovery"><summary>未提交的草稿 · 未用于复盘</summary>${r.unsubmitted.map(d=>`<h3>${escape(d.question)}</h3><p>${escape(d.text)}</p>`).join('')}</details>`:'';
    return header+tabs+`<div class="review-heading"><span class="tag amber">${r.sample?'示例复盘 · 标准待审':'交互示例 · 未调用 AI'}</span><span>${r.exposure} · 技术判断待核验</span></div><div class="review-columns"><article class="answer-document"><div class="section-caption"><strong>你的回答 · 原文</strong><span>${escape(r.id)}</span></div><h2 style="font-size:18px">${escape(r.title)}</h2>${segments.length?segments.map((p,i)=>`<p data-quote="${i}">${escape(p)}</p>`).join(''):'<p>本次未提交回答，不能形成表现判断。</p>'}${draftView}</article><section><div class="section-title"><h2>${r.sample?'从三个角度再看一次':'可以逐项核对的示例清单'}</h2><span class="micro muted">点击定位原文</span></div>${[
      ['条件是否讲清','区分已知结果与待核实条件，让后续推理有明确起点。',0],
      ['后续动作是否具体','试着说明谁来处理、何时停止，以及如何确认问题已经解决。',Math.min(1,segments.length-1)],
      ['职责是否有边界','将本人负责、参与了解和仍未知的部分分别说明。',Math.max(0,segments.length-1)]
    ].map(([title,body,i])=>`<button class="review-observation" data-evidence="${Math.max(0,i)}"><div class="observation-title">${icon('quote')}<strong>${title}</strong></div><p>${body}</p><small>${r.sample?'查看对应原话':'定位已提交原文 · 供自行核对'} ↗</small></button>`).join('')}<p class="quiet-note">${r.sample?'此报告用于演示证据定位，不是经审核的技术评价。':'这里保留了你的输入；右侧是固定自检提示，不是对输入的 AI 判断。'}</p></section></div><div class="action-bar"><div><h3>下一个动作：独立解释一条完整的处理路径</h3><p>补充条件与验证方式，再用一份新回答检查是否已经讲清。</p></div><div class="button-row">${button(state.retryScheduled?'已在今日 · 查看安排':'加入今日复练','schedule-retry',state.retryScheduled?'':'soft')}${button('立即复练','retry','primary')}</div></div>`;
  }
  function comparisonPage() {
    const c=state.comparison;
    if(!c) return `<div class="empty-state">${icon('layers')}<h3>让新的回答成为对照依据</h3><p>先完成一次独立复练，再把新旧原文放在一起看。</p>${button('开始独立复练','retry','primary')}</div>`;
    return `<div class="review-heading"><span class="tag green">同题重答</span><span>用户自行对照 · 不自动判定进步</span></div><div class="comparison">${[[c.old,'上一次回答'],[c.fresh,'这一次复练']].map(([r,label])=>`<article class="answer-document"><div class="section-caption"><strong>${label}</strong><span>${r.assisted?'使用过辅助':'独立模式'}</span></div><h3>${escape(r.title)}</h3><p>${escape(r.answer || '没有提交回答')}</p><p class="micro muted">${r.exposure}</p></article>`).join('')}</div><div class="comparison-result"><span>这次是否体现了你的改进目标？</span><select id="comparison-choice" aria-label="你的比较结论"><option value="">先核对两份原文</option><option value="yes" ${c.judgment==='yes'?'selected':''}>目标已体现</option><option value="again" ${c.judgment==='again'?'selected':''}>仍需继续练习</option><option value="unknown" ${c.judgment==='unknown'?'selected':''}>暂时无法判断</option></select>${button('记录我的判断','save-comparison','primary')}</div><p class="quiet-note">同题更熟练，不自动等于能应对新条件；结论作为个人判断保留。</p>`;
  }
  function growthPage() {
    return pageHeading('进步，有迹可循。','把回忆、作答与复练分开看，知道哪些地方还值得验证。',button('回到今日安排','go-today'))+`<div class="panel"><div class="section-title"><h2>当前目标的表现依据</h2><span class="tag">${escape(state.job)}</span></div><table class="capability-table"><thead><tr><th>考察项</th><th>当前记录</th><th>依据与限制</th><th>查看</th></tr></thead><tbody><tr><td>解释原理<small>缓存一致性</small></td><td><span class="tag ${state.recalls.length?'green':''}">${state.recalls.length?'已有回忆记录':'尚待回忆验证'}</span></td><td>个人自评，不等于技术审核</td><td><button class="text-button" data-action="recall">回忆</button></td></tr><tr><td>分析异常<small>失败后的处理路径</small></td><td><span class="tag green">${state.attempts.length?'已有练习记录':'已有示例原答'}</span></td><td>技术标准待核验</td><td><button class="text-button" data-action="open-review">原文</button></td></tr><tr><td>独立复练<small>是否体现改进目标</small></td><td><span class="tag ${state.comparison?'green':''}">${state.comparison?'已有新旧对照':'待补新的回答'}</span></td><td>${state.actionResolved?'已记录个人判断':'还需核对作答条件'}</td><td><button class="text-button" data-action="open-compare">对照</button></td></tr><tr><td>说明本人职责<small>项目实践</small></td><td><span class="tag">待验证</span></td><td>材料确认不能代替实际作答</td><td><button class="text-button" data-start="facts">练习</button></td></tr></tbody></table></div><div class="timeline"><div class="section-title"><h2>最近留下的记录</h2><span class="micro muted">不以阅读量或 AI 总分代表能力</span></div>${state.attempts.slice(-3).reverse().map(r=>`<div class="timeline-row"><span>本次预览</span><div><h3>${escape(r.title)}</h3><p>保存独立原文与本次辅助条件</p></div><span class="tag green">${r.assisted?'辅助练习':'作答记录'}</span></div>`).join('')}${state.recalls.length?`<div class="timeline-row"><span>本次预览</span><div><h3>完成 ${state.recalls.length} 次主动回忆</h3><p>自评、参考揭示与回忆输入分别保留</p></div><span class="tag green">回忆记录</span></div>`:''}<div class="timeline-row"><span>11 月 2 日</span><div><h3>从缓存题里，找到一个待复练的问题</h3><p>原回答、示例反馈与改进动作相互关联</p></div><span class="tag">合成历史</span></div></div>`;
  }

  function newNote(candidate=false) {
    const c=byId(candidate?state.report.concept:state.selected);
    const body=candidate?'我希望补全这次回答中的处理路径。\n\n需要核对：已知条件、后续动作的执行者、终止条件，以及验证结果的方式。\n\n这是一条待核对的整理，不代表技术结论已审核。':'';
    showDialog(candidate?'把值得保留的理解，留成笔记':'记下一个想法',`<p>${candidate?'这是一份整理候选，你可以修改后采用。采用不会改变原回答，也不会确认项目经历。':'先写下来，再慢慢建立连接。不必一开始就整理得很完美。'}</p><form id="note-form" data-candidate="${candidate}"><label for="new-note-title">标题</label><input class="field" id="new-note-title" name="title" required maxlength="100" placeholder="例如：缓存失败路径，我还想清楚了什么" value="${candidate?escape(c.title+' · 练后整理'):''}"><label for="new-note-body">自己的理解</label><textarea class="field" id="new-note-body" name="body" required maxlength="12000" placeholder="写下理解、疑问或一段想继续研究的内容。">${escape(body)}</textarea><label for="new-note-concept">关联知识点</label><select class="field" id="new-note-concept" name="concept"><option value="">先自由记录，稍后整理</option>${concepts.map(item=>`<option value="${item.id}" ${candidate&&item.id===c.id?'selected':''}>${item.title}</option>`).join('')}</select><div class="dialog-actions">${button('取消','close-dialog')}<button class="button primary" type="submit">${candidate?'采用为笔记':'暂存这条笔记'}</button></div></form>`,{focus:'#new-note-title'});
  }
  function searchDialog() {
    showDialog('搜索你的知识与笔记',`<div class="search-input-wrap" style="padding:0 0 15px"><input id="search-input" aria-label="搜索关键词" placeholder="试试缓存、项目、重试…" value="${escape(state.searchQuery)}"></div><div class="search-results" id="search-results" style="padding:0"></div>`,{focus:'#search-input'});
    updateSearch();
  }
  function updateSearch() {
    const q=state.searchQuery.trim().toLowerCase();
    const items=[...concepts.map(c=>({id:c.id,title:c.title,kind:'concept',type:topicOf(c.topic).name})),...state.notes.map(n=>({id:n.id,title:n.title,kind:'note',type:n.concept?byId(n.concept).title:'自由笔记'}))].filter(x=>!q||`${x.title} ${x.type}`.toLowerCase().includes(q)).slice(0,12);
    const el=document.querySelector('#search-results');
    if(el) el.innerHTML=items.length?items.map(x=>`<button class="search-result" data-search-kind="${x.kind}" data-search-id="${x.id}">${icon(x.kind==='concept'?'knowledge':'note')}<span>${escape(x.title)}<small>${x.kind==='concept'?'知识点':'笔记'} · ${escape(x.type)}</small></span>${icon('chevron')}</button>`).join(''):'<div class="empty-state"><h3>没有找到这条记录</h3><p>换个关键词，或先把想法记成一条笔记。</p></div>';
  }
  function openRecall(id=state.selected) {
    if(isProtected(id)) { requestAssistance(()=>openRecall(id)); return; }
    if(!state.recall || state.recall.concept!==id || state.recall.saved) state.recall={concept:id,draft:'',revealed:false,rating:'',saved:false};
    renderRecall();
  }
  function renderRecall() {
    const r=state.recall,c=byId(r.concept);
    showDialog('先回忆，再看参考',`<span class="tag green">${c.title} · 临时回忆</span><p style="color:var(--ink);font-size:15px;margin-top:17px">不看笔记，你会怎样解释${c.title}？</p><label for="recall-answer">你记得的内容</label><textarea class="field" id="recall-answer" placeholder="先写出自己的理解…">${escape(r.draft)}</textarea>${r.revealed?`<div class="recall-reference"><strong>我的笔记 · 示例参考，未经技术审核</strong><p style="margin-top:7px">${escape(primaryNote(c.id)?.body || c.description)}</p></div><span class="micro muted">这次回忆感觉如何？</span><div class="rating-options">${['还没想起来','需要一点提示','能够独立讲清'].map(v=>`<button data-rating="${v}" aria-pressed="${r.rating===v}" class="${r.rating===v?'active':''}">${v}</button>`).join('')}</div><div class="dialog-actions">${button('记录这次回忆','save-recall','primary',r.rating?'':'disabled')}</div>`:`<div class="dialog-actions">${button('揭示我的笔记','reveal-recall','primary')}</div>`}<p class="quiet-note" style="margin-bottom:0">本次只记录回忆和自评，不改变常规复习排程，也不自动完成复练动作。</p>`,{focus:r.revealed?'[data-rating]':'#recall-answer'});
  }
  function requestAssistance(callback) {
    if(!state.session || state.session.assisted || state.session.phase==='ended') { callback(); return; }
    pendingAction=callback;
    showDialog('转为辅助练习？',`<p>接下来会看到关联笔记、参考或示例回答。本次尝试将记录为“使用过辅助”，已经输入的内容会保留。</p><p>同一次尝试不会再切回独立模式；需要独立作答时，可以另开一次练习。</p><div class="dialog-actions">${button('继续独立作答','close-dialog')}${button('转为辅助并继续','confirm-assistance','primary')}</div>`,{focus:'[data-action="close-dialog"]'});
  }
  function startPractice(id=state.selected,retry=false) {
    const proceed=()=>{
      const questions=state.mode==='mock'&&!retry?[id,...['retry','facts','pool'].filter(x=>x!==id).slice(0,2)].map(makeQuestion):[makeQuestion(id)];
      state.session={id:`practice-${state.nextId++}`,phase:'active',kind:retry?'independent':state.mode,assisted:!retry&&state.mode==='learning',questions,index:0,answers:[],draft:'',exposure:state.exposure.has(id),job:state.job,reference:false,retryOld:retry?{...state.report}:null,factRoles:[...state.factRoles]};
      state.selected=id; closeDialog(); navigate('training');
      requestAnimationFrame(()=>document.querySelector('#answer-input')?.focus());
    };
    if(state.session&&state.session.phase!=='ended') {
      pendingAction=()=>{finishSession(false);proceed();};
      showDialog('还有一次练习正在进行',`<p>可以继续当前回答，或结束它再开始新尝试。已提交回答和未提交草稿都会保留在本页的历史状态中。</p><div class="dialog-actions">${button('继续当前练习','continue-session')}${button('结束并开始新的','confirm-new-session','primary')}</div>`);
    } else proceed();
  }
  function submitAnswer(skip=false) {
    const s=state.session;
    if(!s||s.phase!=='active'||typeof s.answers[s.index]==='string') return;
    if(!skip&&!s.draft.trim()) return;
    s.answers[s.index]=skip?'':s.draft.trim();
    s.skippedDrafts ||= {}; if(skip&&s.draft) s.skippedDrafts[s.index]=s.draft;
    s.draft=''; render(); toast(skip?'本题已跳过，未提交草稿仍保留在本页。':'回答原文已记录在本页。');
  }
  function finishSession(go=true) {
    const s=state.session; if(!s||s.phase==='ended') return;
    s.phase='ended';
    const report={id:s.id,concept:s.questions[0].concept,title:s.questions.length>1?`${s.kind==='mock'?'文字模拟':'文字专项'} · ${s.questions.length} 道问题`:s.questions[0].title,answer:s.questions.length>1?s.questions.map((q,i)=>`${q.title}\n${s.answers[i] || '未提交回答'}`).join('\n\n'):s.answers[0]||'',assisted:s.assisted,sample:false,date:'本次预览',exposure:s.exposure?'开始前已打开关联笔记':'未记录先前资料使用',draft:s.draft,answers:[...s.answers]};
    report.unsubmitted=Object.entries({...s.skippedDrafts,...(s.draft?{[s.index]:s.draft}:{})}).map(([index,text])=>({question:s.questions[index].title,text}));
    state.attempts.push(report); state.report=report; state.reviewTab='report';
    if(s.retryOld) {state.comparison={old:s.retryOld,fresh:report};state.reviewTab='compare';}
    closeDialog(); if(go) navigate('review');
  }
  const actions={
    'about':()=>showDialog('关于这份交互预览',`<p>这是「问阶 · Interview Studio」的整合 UI 提案，展示准备、知识、文字训练、复盘与复练之间的联系。</p><div class="context-item">全部内容为合成示例<small>没有接入真实模型；示例反馈不是对用户输入的 AI 评价。</small></div><div class="context-item">输入仅在当前页面暂存<small>切换页面可以继续，刷新或关闭会还原。请勿输入敏感材料。</small></div><div class="context-item">本版提出浅色工作台与深色银河<small>用于评审视觉与交互，不代表已决定全站主题，也不代表正式功能已经实现。</small></div><div class="dialog-actions">${button('开始探索','close-dialog','primary')}</div>`),
    'search':searchDialog,
    'close-dialog':closeDialog,
    'new-note':()=>newNote(false),
    'candidate-note':()=>newNote(true),
    'favorite':()=>{state.favorites.has(state.selected)?state.favorites.delete(state.selected):state.favorites.add(state.selected);render();toast(state.favorites.has(state.selected)?'已收藏这个知识点。':'已取消收藏。');},
    'filter-favorites':()=>{state.favoritesOnly=!state.favoritesOnly;state.topic='all';state.focus=false;render();},
    'unlinked-notes':()=>{state.unlinkedOnly=true;state.view='notes';render();},
    'focus-neighbors':()=>{state.focus=!state.focus;render();},
    'zoom-in':()=>{state.zoom=Math.min(1.5,Math.round((state.zoom+.1)*10)/10);updateMapTransform();},
    'zoom-out':()=>{state.zoom=Math.max(.6,Math.round((state.zoom-.1)*10)/10);updateMapTransform();},
    'reset-map':()=>{state.zoom=1;state.pan={x:0,y:0};state.topic='all';state.focus=false;state.favoritesOnly=false;render();},
    'open-primary':()=>{state.unlinkedOnly=false;state.view='notes';state.noteId=primaryNote(state.selected)?.id||state.notes[0].id;navigate('knowledge');},
    'save-note':()=>{
      const note=state.notes.find(n=>n.id===state.noteId),draft=state.noteDrafts[note?.id];if(!note||!draft) {toast('当前笔记没有新的修改。');return;}
      if(!draft.title.trim()||!draft.body.trim()) {toast('请保留笔记标题与正文，再暂存。');return;}
      note.history ||= [];note.history.push({title:note.title,body:note.body,version:note.version});Object.assign(note,{title:draft.title.trim(),body:draft.body.trim(),version:note.version+1});delete state.noteDrafts[note.id];render();toast('笔记已在本页暂存，各入口使用同一份正文。');
    },
    'reset-board':()=>{state.board={};render();toast('已恢复这张白板的示例布局。');},
    'practice-selected':()=>{navigate('training');if(state.session&&state.session.phase!=='ended')toast('已回到当前练习；输入仍在。');},
    'start-session':()=>startPractice(),
    'continue-session':()=>{closeDialog();navigate('training');},
    'confirm-new-session':()=>{const next=pendingAction;pendingAction=null;next?.();},
    'retry':()=>startPractice(state.report.concept,true),
    'toggle-pause':()=>{const s=state.session;if(!s||s.phase==='ended')return;s.phase=s.phase==='paused'?'active':'paused';render();toast(s.phase==='paused'?'已暂停，输入保留在本页。':'已恢复作答。');},
    'submit-answer':()=>submitAnswer(false),
    'skip-question':()=>submitAnswer(true),
    'next-question':()=>{const s=state.session;if(!s||s.phase!=='active'||typeof s.answers[s.index]!=='string')return;if(s.index<s.questions.length-1){s.index++;s.draft='';s.reference=false;render();}else finishSession();},
    'followup':()=>{const s=state.session;if(!s||s.phase!=='active'||s.questions.length!==1||typeof s.answers[0]!=='string')return;s.questions.push({concept:s.questions[0].concept,title:'继续追问：怎样知道问题已经解决？',prompt:'请补充你会观察什么结果，以及什么情况下还不能认定处理完成。此追问为预设示例。'});s.index=1;s.draft='';s.reference=false;render();},
    'end-session':()=>showDialog('结束这次练习？',`<p>已提交的回答将进入本页记录。未提交的草稿单独保留，不会被当作正式答案用于复盘。</p><div class="dialog-actions">${button('继续作答','close-dialog')}${button('结束并查看记录','confirm-end','primary')}</div>`),
    'confirm-end':()=>finishSession(),
    'show-reference':()=>requestAssistance(()=>{state.session.reference=true;render();}),
    'assist-knowledge':()=>requestAssistance(()=>render()),
    'confirm-assistance':()=>{if(state.session)state.session.assisted=true;const next=pendingAction;pendingAction=null;closeDialog();next?.();},
    'fill-sample':()=>requestAssistance(()=>{state.session.draft=seedAnswer;render();toast('已填入合成示例，本次标为辅助练习。');}),
    'recall':()=>openRecall(),
    'reveal-recall':()=>{state.recall.revealed=true;renderRecall();},
    'save-recall':()=>{const r=state.recall;if(!r||!r.rating||r.saved)return;r.saved=true;state.recalls.push({...r});closeDialog();render();toast('回忆和自评已记录；常规复习排程不变。');},
    'toggle-note-task':()=>{state.noteTaskDone=!state.noteTaskDone;render();toast(state.noteTaskDone?'笔记整理已完成，独立复练仍需另外验证。':'已撤回笔记整理的完成标记。');},
    'open-review':()=>{state.reviewTab='report';navigate('review');},
    'open-compare':()=>{state.reviewTab='compare';navigate('review');},
    'schedule-retry':()=>{if(state.retryScheduled)navigate('today');else{state.retryScheduled=true;render();toast('已加入今日，同一动作不会重复安排。');}},
    'go-today':()=>navigate('today'),
    'save-comparison':()=>{const choice=document.querySelector('#comparison-choice').value;if(!choice){toast('先选择一个比较结论。');return;}state.comparison.judgment=choice;state.actionResolved=choice==='yes';if(choice==='yes')state.retryScheduled=false;toast('已记录你的判断；它不会自动变为能力认证。');render();},
    'edit-job':()=>showDialog('调整当前关注目标',`<form id="job-form"><label for="job-title">目标岗位</label><input id="job-title" class="field" name="job" required maxlength="80" value="${escape(state.job)}"><p class="quiet-note">更新用于后续练习。已经开始的训练仍保留开始时的目标。</p><div class="dialog-actions">${button('取消','close-dialog')}<button class="button primary" type="submit">暂存目标</button></div></form>`,{focus:'#job-title'}),
    'project-story':()=>showDialog('把项目事实组织成一段故事',`<p>示例结构用于准备表达，不替你确认真实经历。</p><div class="context-item">背景与问题<small>订单查询出现超时，需要先确认发生范围与已知结果。</small></div><div class="context-item">我的职责<small>实现查询接口并记录超时；缓存方案为参与讨论。</small></div><div class="context-item">行动与验证<small>说明自己实际做过什么、如何核验结果；未知指标保持待核实。</small></div><div class="dialog-actions">${button('写成我的笔记','new-note','primary')}</div>`)
  };
  function updateMapTransform() {document.querySelector('#galaxy-world')?.setAttribute('transform',worldTransform());const label=document.querySelector('#zoom-label');if(label)label.textContent=`${Math.round(state.zoom*100)}%`;}
  document.addEventListener('click',event=>{
    if(event.target.closest('.skip-link')) {
      // 页内跳转只移动焦点，避免 #main 被当作页面路由。
      event.preventDefault();main.focus();return;
    }
    const el=event.target.closest('button,a[data-nav],g[data-concept]');if(!el||el.disabled)return;
    if(el.dataset.nav) {navigate(el.dataset.nav);return;}
    if(el.dataset.action) {actions[el.dataset.action]?.();return;}
    if(el.dataset.view) {state.view=el.dataset.view;state.unlinkedOnly=false;render();return;}
    if(el.dataset.topic) {state.topic=el.dataset.topic;state.focus=false;state.favoritesOnly=false;render();return;}
    if(el.dataset.concept) {selectConcept(el.dataset.concept);return;}
    if(el.dataset.note) {state.noteId=el.dataset.note;render();return;}
    if(el.dataset.boardOpen) {state.selected=el.dataset.boardOpen;actions['open-primary']();return;}
    if(el.dataset.mode) {state.mode=el.dataset.mode;render();return;}
    if(el.dataset.start) {state.selected=el.dataset.start;closeDialog();navigate('training');return;}
    if(el.dataset.reviewTab) {state.reviewTab=el.dataset.reviewTab;render();return;}
    if(el.dataset.rating) {state.recall.rating=el.dataset.rating;renderRecall();return;}
    if(el.dataset.evidence!==undefined) {document.querySelectorAll('[data-quote]').forEach(p=>p.classList.toggle('highlight',p.dataset.quote===el.dataset.evidence));document.querySelector(`[data-quote="${el.dataset.evidence}"]`)?.scrollIntoView({block:'nearest',behavior:'instant'});return;}
    if(el.dataset.searchId) {const {searchId:id,searchKind:kind}=el.dataset;closeDialog();state.unlinkedOnly=false;if(kind==='concept'){state.selected=id;state.view='galaxy';}else{state.noteId=id;state.view='notes';}navigate('knowledge');}
  });
  document.addEventListener('input',event=>{
    const el=event.target;
    if(el.id==='answer-input'&&state.session?.phase==='active') {state.session.draft=el.value;document.querySelector('#answer-count').textContent=`${el.value.length} 字 · 文字练习`;document.querySelector('#draft-status').textContent='输入已在本页保留';document.querySelector('[data-action="submit-answer"]').disabled=!el.value.trim();}
    if(el.id==='note-title'||el.id==='note-body') {const note=state.notes.find(n=>n.id===state.noteId);state.noteDrafts[note.id]={title:document.querySelector('#note-title').value,body:document.querySelector('#note-body').value};document.querySelector('#note-save-state').textContent='有未暂存的修改 · 切换页面仍保留';}
    if(el.id==='recall-answer'&&state.recall)state.recall.draft=el.value;
    if(el.id==='search-input'){state.searchQuery=el.value;updateSearch();}
  });
  document.addEventListener('change',event=>{
    const el=event.target;
    if(el.id==='question-concept'){state.selected=el.value;render();}
    if(el.id==='compact-concept')selectConcept(el.value);
    if(el.id==='compact-topic'){state.topic=el.value;state.focus=false;state.favoritesOnly=false;render();}
    if(el.dataset.fact!==undefined){state.factRoles[Number(el.dataset.fact)]=el.value;toast('示例事实状态已在本页更新。');}
  });
  document.addEventListener('submit',event=>{
    const form=event.target;if(!['note-form','job-form'].includes(form.id))return;event.preventDefault();
    const data=new FormData(form);
    if(form.id==='note-form') {
      const title=String(data.get('title')).trim(),body=String(data.get('body')).trim();if(!title||!body)return;
      const concept=String(data.get('concept'))||null;const note={id:`note-new-${state.nextId++}`,title,body,concept,version:1,source:form.dataset.candidate==='true'?`来自练后整理 · ${state.report.id}`:'自由记录 · 本页输入'};
      state.notes.unshift(note);state.noteId=note.id;state.view='notes';state.unlinkedOnly=false;closeDialog();navigate('knowledge');toast('笔记已在本页暂存，原回答保持不变。');
    } else {state.job=String(data.get('job')).trim();closeDialog();render();toast('后续练习将使用新的目标。');}
  });
  document.addEventListener('keydown',event=>{
    if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k') {event.preventDefault();searchDialog();return;}
    if((event.key==='Enter'||event.key===' ')&&event.target.matches('g[data-concept]')) {event.preventDefault();selectConcept(event.target.dataset.concept);return;}
    const id=event.target.dataset?.boardHandle;
    if(id&&['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();state.board[id].x=Math.max(0,Math.min(830,state.board[id].x+(event.key==='ArrowRight'?10:event.key==='ArrowLeft'?-10:0)));state.board[id].y=Math.max(0,Math.min(415,state.board[id].y+(event.key==='ArrowDown'?10:event.key==='ArrowUp'?-10:0)));updateBoardPosition(id);}
  });
  let drag=null;
  document.addEventListener('pointerdown',event=>{
    if(event.button!==0)return;
    const handle=event.target.closest('[data-board-handle]');
    if(handle){const id=handle.dataset.boardHandle;drag={type:'board',id,startX:event.clientX,startY:event.clientY,origin:{...state.board[id]}};handle.setPointerCapture(event.pointerId);event.preventDefault();return;}
    const map=event.target.closest('.galaxy-svg');
    if(map&&!event.target.closest('[data-concept]')&&event.pointerType!=='touch'){const box=map.getBoundingClientRect();drag={type:'map',startX:event.clientX,startY:event.clientY,origin:{...state.pan},ratio:Math.max(800/box.width,730/box.height)};map.classList.add('dragging');map.setPointerCapture(event.pointerId);}
  });
  document.addEventListener('pointermove',event=>{
    if(!drag)return;
    const dx=event.clientX-drag.startX,dy=event.clientY-drag.startY;
    if(drag.type==='map'){state.pan={x:Math.max(-350,Math.min(350,drag.origin.x+dx*drag.ratio)),y:Math.max(-350,Math.min(350,drag.origin.y+dy*drag.ratio))};updateMapTransform();}
    else{state.board[drag.id]={x:Math.max(0,Math.min(830,drag.origin.x+dx)),y:Math.max(0,Math.min(415,drag.origin.y+dy))};updateBoardPosition(drag.id);}
  });
  const endDrag=()=>{drag=null;document.querySelector('.galaxy-svg')?.classList.remove('dragging');};
  document.addEventListener('pointerup',endDrag);document.addEventListener('pointercancel',endDrag);
  function updateBoardPosition(id){const p=state.board[id],el=document.querySelector(`[data-board-card="${id}"]`);if(el){el.style.left=`${p.x}px`;el.style.top=`${p.y}px`;}document.querySelectorAll('[data-board-line]').forEach(line=>{const a=state.board.cache,b=state.board[line.dataset.boardLine];line.setAttribute('x1',a.x+105);line.setAttribute('y1',a.y+65);line.setAttribute('x2',b.x+105);line.setAttribute('y2',b.y+65);});}
  window.addEventListener('popstate',()=>{const route=location.hash.slice(1);navigate(navigation.some(([id])=>id===route)?route:'knowledge');});
  window.addEventListener('hashchange',()=>{if(location.hash.slice(1)!==state.page)navigate(location.hash.slice(1));});
  dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)closeDialog();}});
  const initial=location.hash.slice(1);state.page=navigation.some(([id])=>id===initial)?initial:'knowledge';render();
})();
