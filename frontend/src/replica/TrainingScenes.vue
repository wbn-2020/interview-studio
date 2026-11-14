<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import RIcon from './RIcon.vue'

const props = defineProps<{ scene: number }>()
const emit = defineEmits<{ navigate: [scene: number]; notify: [message: string] }>()
const pending = (subject: string) => emit('notify', `${subject}待确认`)
const go = (scene: number) => emit('navigate', scene)

const questions = [
  { title: '缓存删除失败后如何恢复？', topic: '分布式', standard: '已审', source: '自建', recent: '今天' },
  { title: 'ThreadLocal 为什么会泄漏？', topic: 'Java 并发', standard: '待审', source: '导入', recent: '昨天' },
  { title: '发奖接口如何保证幂等？', topic: '项目取舍', standard: '已审', source: '自建', recent: '前天' },
  { title: '如何设计对账补偿任务？', topic: '系统设计', standard: '未审', source: 'AI 候选', recent: '3 天前' },
  { title: '解释一次 GC 停顿的排查过程', topic: 'JVM', standard: '已审', source: '自建', recent: '3 天前' },
]
const filterGroups = [
  { key: 'topic', title: '主题', options: [{ label: 'Java 并发', count: 12 }, { label: '分布式', count: 8 }, { label: '系统设计', count: 6 }] },
  { key: 'point', title: '考察项', options: [{ label: '解释' }, { label: '排错' }, { label: '取舍' }, { label: '职责' }] },
  { key: 'standard', title: '标准状态', options: [{ label: '已审' }, { label: '待审' }, { label: '未审' }] },
  { key: 'source', title: '来源', options: [{ label: '自建' }, { label: '导入' }, { label: 'AI 候选' }] },
] as const
type FilterKey = (typeof filterGroups)[number]['key']
const filters = reactive<Record<FilterKey, string>>({ topic: '', point: '', standard: '', source: '' })
const collapsedFilters = reactive<Record<FilterKey, boolean>>({ topic: false, point: false, standard: false, source: false })
const query = ref('')
const showFilters = ref(true)
const reverseQuestions = ref(false)
const selectedQuestion = ref(0)
const question = computed(() => questions[selectedQuestion.value] ?? questions[0]!)
const visibleQuestions = computed(() => {
  const matches = questions.map((item, index) => ({ ...item, index })).filter((item) => {
    return `${item.title} ${item.topic} ${item.source}`.toLowerCase().includes(query.value.trim().toLowerCase())
      && Object.entries(filters).every(([key, value]) => key === 'point' || !value || item[key as Exclude<FilterKey, 'point'>] === value)
  })
  return reverseQuestions.value ? matches.reverse() : matches
})
function toggleFilter(key: FilterKey, value: string) {
  filters[key] = filters[key] === value ? '' : value
  if (key === 'point') pending('考察项与题目的关联')
}
function resetFilters() {
  query.value = ''
  Object.keys(filters).forEach((key) => { filters[key as FilterKey] = '' })
}
function selectQuestion(index: number) {
  selectedQuestion.value = index
  if (index !== 0) pending('该题的完整题干与标准要求')
}

const routeTab = ref('路线')
const selectedRoute = ref(0)
const selectedStage = ref(1)
const routes = [
  { title: 'Java 后端 · 三周', meta: '12 题 · 4 检查点 · 3 天前', icon: 'layers' },
  { title: '分布式故障排查', meta: '12 题 · 3 检查点 · 1 周前', icon: 'box' },
  { title: '项目表达冲刺', meta: '12 题 · 3 检查点 · 2 周前', icon: 'chart-no-axes-column' },
]
const stages = [
  { title: '基础回顾', meta: '2 题 · 已完成', icon: 'check', state: 'done' },
  { title: '核心应用', meta: '3 题 · 进行中', icon: 'code', state: 'current' },
  { title: '故障排查', meta: '3 题 · 未开始', icon: 'wrench', state: 'waiting' },
  { title: '项目表达', meta: '4 题 · 未开始', icon: 'file-text', state: 'waiting' },
  { title: '系统设计', meta: '3 题 · 未开始', icon: 'monitor', state: 'waiting' },
]
function selectRoute(index: number) {
  if (index === 0) selectedRoute.value = index
  else pending(`${routes[index]?.title}路线详情`)
}
function selectStage(index: number) {
  selectedStage.value = index
  if (index !== 1) pending(`${stages[index]?.title}检查点详情`)
}

const recallTab = ref('全部')
const recallDraft = ref('')
const browseOnly = ref(false)
const recallCards = [
  { title: '缓存一致性', color: 'orange', badge: '当前' },
  { title: '分布式事务', color: 'green', badge: '' },
  { title: 'JVM 内存模型', color: 'gray', badge: '' },
  { title: '索引优化', color: 'orange', badge: '已延期' },
  { title: '消息队列', color: 'gray', badge: '' },
]
const trainingDraft = ref('')
const trainingPaused = ref(false)
const conditionsVisible = ref(true)
function submitTraining() {
  if (!trainingDraft.value.trim()) {
    emit('notify', '请先填写本题回答')
    return
  }
  go(18)
}

const candidateState = ref<'候选' | '已采用' | '已忽略'>('候选')
const locateEvidence = ref(false)
const firstOriginal = '在缓存一致性场景中，发生超时后需要结合业务语义判断是否继续重试。如果是幂等操作，可以安全重试；如果涉及副作用，则需要谨慎评估。'
function decideCandidate(state: '已采用' | '已忽略') {
  candidateState.value = state
  emit('notify', state === '已采用' ? '已采用此追问；后续作答流程待确认' : '已忽略此追问')
}
const expandedReport = ref<number | null>(null)
const reportQuestions = [
  { title: '缓存删除失败后如何恢复？', status: '已提交 / 2 条观察', submitted: true },
  { title: '发奖接口如何保证幂等？', status: '已提交 / 1 条观察', submitted: true },
  { title: '系统设计取舍', status: '跳过', submitted: false },
]
function toggleReport(index: number) {
  expandedReport.value = expandedReport.value === index ? null : index
  if (expandedReport.value !== null) pending(`题目 ${String(index + 1).padStart(2, '0')} 的详细观察`)
}

type ArchitectureNode = { id: string; label: string; icon: string; x: number; y: number; width: number; tone: string }
const initialNodes: ArchitectureNode[] = [
  { id: 'entry', label: '入口', icon: 'user', x: 137, y: 63, width: 175, tone: 'blue' },
  { id: 'inventory', label: '库存服务', icon: 'database', x: 120, y: 225, width: 179, tone: 'teal' },
  { id: 'order', label: '订单服务', icon: 'shopping-cart', x: 356, y: 225, width: 177, tone: 'teal' },
  { id: 'reward', label: '奖励服务', icon: 'gift', x: 589, y: 225, width: 173, tone: 'teal' },
  { id: 'config', label: '配置中心', icon: 'settings', x: 46, y: 401, width: 173, tone: 'purple' },
  { id: 'queue', label: '消息队列', icon: 'bar-chart-3', x: 356, y: 401, width: 177, tone: 'teal' },
  { id: 'reconcile', label: '对账任务', icon: 'calendar-check', x: 356, y: 550, width: 177, tone: 'teal' },
  { id: 'monitor', label: '监控', icon: 'monitor', x: 629, y: 550, width: 152, tone: 'steel' },
]
const nodes = ref<ArchitectureNode[]>(initialNodes.map((node) => ({ ...node })))
const diagramSvg = ref<SVGSVGElement | null>(null)
const designTab = ref('架构图')
const designAsideTab = ref('约束')
const designNote = ref('')
const selectedNode = ref('')
const diagramDirty = ref(false)
let dragState: { id: string; pointerId: number; offsetX: number; offsetY: number; element: SVGGElement } | null = null
const nodeById = (id: string) => nodes.value.find((node) => node.id === id)!
const connector = (fromId: string, toId: string, vertical = false) => {
  const from = nodeById(fromId)
  const to = nodeById(toId)
  if (fromId === 'entry') return `M ${from.x + 85} ${from.y + 70} L ${to.x + 102} ${to.y - 8}`
  return vertical
    ? `M ${from.x + from.width / 2} ${from.y + 70} L ${to.x + to.width / 2} ${to.y - 8}`
    : `M ${from.x + from.width} ${from.y + 35} L ${to.x - 8} ${to.y + 35}`
}
const retryPath = computed(() => {
  const reward = nodeById('reward')
  const inventory = nodeById('inventory')
  return `M ${reward.x + 64} ${reward.y - 5} V ${reward.y - 38} C ${reward.x + 64} ${reward.y - 99}, ${inventory.x + 255} ${inventory.y - 97}, ${inventory.x + 108} ${inventory.y - 37}`
})
const recoveryPath = computed(() => {
  const reward = nodeById('reward')
  const queue = nodeById('queue')
  const x = reward.x + 74
  const y = queue.y + 35
  return `M ${x} ${reward.y + 76} V ${y - 33} Q ${x} ${y}, ${x - 34} ${y} H ${queue.x + queue.width + 8}`
})
function svgPoint(event: PointerEvent) {
  const svg = diagramSvg.value
  const matrix = svg?.getScreenCTM()
  if (!svg || !matrix) return null
  const point = svg.createSVGPoint()
  point.x = event.clientX
  point.y = event.clientY
  return point.matrixTransform(matrix.inverse())
}
function startDrag(event: PointerEvent, node: ArchitectureNode) {
  if (event.button !== 0) return
  const point = svgPoint(event)
  if (!point) return
  const element = event.currentTarget as SVGGElement
  element.setPointerCapture(event.pointerId)
  selectedNode.value = node.id
  dragState = { id: node.id, pointerId: event.pointerId, offsetX: point.x - node.x, offsetY: point.y - node.y, element }
}
function moveDrag(event: PointerEvent) {
  if (!dragState || dragState.pointerId !== event.pointerId) return
  const point = svgPoint(event)
  if (!point) return
  const node = nodeById(dragState.id)
  // 使用 SVG 坐标转换，避免外层画布缩放时拖动距离与指针错位。
  node.x = Math.max(12, Math.min(853 - node.width, point.x - dragState.offsetX))
  node.y = Math.max(12, Math.min(617, point.y - dragState.offsetY))
  diagramDirty.value = true
}
function stopDrag(event?: PointerEvent) {
  if (!dragState || (event && event.pointerId !== dragState.pointerId)) return
  if (dragState.element.hasPointerCapture(dragState.pointerId)) dragState.element.releasePointerCapture(dragState.pointerId)
  dragState = null
}
function moveNode(event: KeyboardEvent, node: ArchitectureNode) {
  const steps: Record<string, [number, number]> = { ArrowLeft: [-8, 0], ArrowRight: [8, 0], ArrowUp: [0, -8], ArrowDown: [0, 8] }
  const step = steps[event.key]
  if (!step) return
  event.preventDefault()
  node.x = Math.max(12, Math.min(853 - node.width, node.x + step[0]))
  node.y = Math.max(12, Math.min(617, node.y + step[1]))
  selectedNode.value = node.id
  diagramDirty.value = true
}
function saveDiagram() {
  diagramDirty.value = false
  emit('notify', '图与说明已在本次页面内保留；持久保存待确认')
}

// 场景切换与刷新均回到参考初态；不读写存储，也不提交业务数据。
watch(() => props.scene, () => {
  resetFilters()
  Object.keys(collapsedFilters).forEach((key) => { collapsedFilters[key as FilterKey] = false })
  showFilters.value = true
  reverseQuestions.value = false
  selectedQuestion.value = 0
  routeTab.value = '路线'
  selectedRoute.value = 0
  selectedStage.value = 1
  recallTab.value = '全部'
  recallDraft.value = ''
  browseOnly.value = false
  trainingDraft.value = ''
  trainingPaused.value = false
  conditionsVisible.value = true
  candidateState.value = '候选'
  locateEvidence.value = false
  expandedReport.value = null
  stopDrag()
  nodes.value = initialNodes.map((node) => ({ ...node }))
  designTab.value = '架构图'
  designAsideTab.value = '约束'
  designNote.value = ''
  selectedNode.value = ''
  diagramDirty.value = false
})
</script>

<template>
  <section class="training-scenes" :class="`scene-${scene}`">
    <template v-if="scene === 14">
      <div class="bank-title"><h1>题库</h1></div>
      <nav class="bank-tabs" aria-label="题库视图">
        <button type="button" class="active">题目</button>
        <button type="button" @click="go(15)">题单</button>
        <button type="button" @click="go(15)">路线</button>
      </nav>
      <div class="bank-tools">
        <label class="bank-search"><RIcon name="search" :size="27" /><input v-model="query" placeholder="搜索题目、主题或标签..." aria-label="搜索题目" /></label>
        <button type="button" class="ts-button" :aria-expanded="showFilters" @click="showFilters = !showFilters"><RIcon name="filter" :size="24" />筛选<RIcon name="chevron-down" :size="22" /></button>
        <button type="button" class="ts-button primary" @click="pending('新建题目流程')">新建题目</button>
      </div>
      <aside v-if="showFilters" class="ts-panel bank-filters">
        <section v-for="group in filterGroups" :key="group.key" class="bank-filter-group">
          <button type="button" class="filter-heading" :aria-expanded="!collapsedFilters[group.key]" @click="collapsedFilters[group.key] = !collapsedFilters[group.key]">
            {{ group.title }}<RIcon :name="collapsedFilters[group.key] ? 'chevron-down' : 'chevron-up'" :size="17" />
          </button>
          <div v-if="!collapsedFilters[group.key]" class="filter-options">
            <button v-for="option in group.options" :key="option.label" type="button" :class="{ chosen: filters[group.key] === option.label }" :aria-pressed="filters[group.key] === option.label" @click="toggleFilter(group.key, option.label)">
              <span v-if="group.key === 'standard'" class="filter-state-dot" :class="option.label === '已审' ? 'green' : option.label === '待审' ? 'orange' : 'gray'"></span>
              <span v-else class="filter-radio" :class="{ checked: filters[group.key] === option.label }"></span>
              <span>{{ option.label }}</span><small v-if="'count' in option">{{ option.count }}</small>
            </button>
          </div>
        </section>
        <button type="button" class="ts-button reset-filters" @click="resetFilters"><RIcon name="refresh-cw" :size="20" />重置</button>
      </aside>
      <section class="ts-panel bank-list" :class="{ expanded: !showFilters }" aria-label="题目列表">
        <table>
          <colgroup><col class="question-column" /><col class="topic-column" /><col class="standard-column" /><col class="source-column" /><col /></colgroup>
          <thead><tr><th>题目</th><th>主题</th><th>标准</th><th>来源</th><th><button type="button" :aria-pressed="reverseQuestions" @click="reverseQuestions = !reverseQuestions">最近<RIcon name="arrow-up-down" variant="bank" :size="15" /></button></th></tr></thead>
          <tbody>
            <tr v-for="item in visibleQuestions" :key="item.title" :class="{ selected: item.index === selectedQuestion }" tabindex="0" :aria-selected="item.index === selectedQuestion" @click="selectQuestion(item.index)" @keydown.enter="selectQuestion(item.index)" @keydown.space.prevent="selectQuestion(item.index)">
              <td>{{ item.title }}</td><td>{{ item.topic }}</td><td><span class="standard-badge" :class="item.standard === '已审' ? 'reviewed' : item.standard === '待审' ? 'pending' : 'unreviewed'">{{ item.standard }}</span></td><td>{{ item.source }}</td><td>{{ item.recent }}</td>
            </tr>
            <tr v-if="!visibleQuestions.length"><td colspan="5" class="empty-row">没有匹配的题目</td></tr>
          </tbody>
        </table>
      </section>
      <aside class="ts-panel bank-detail">
        <h2><template v-if="selectedQuestion === 0">缓存删除失败后<br>如何恢复？</template><template v-else>{{ question.title }}</template></h2>
        <div class="bank-tags"><span>{{ question.topic }}</span><span v-if="selectedQuestion === 0">缓存</span><span v-if="selectedQuestion === 0">故障恢复</span></div>
        <template v-if="selectedQuestion === 0">
          <h3>题干</h3><p>在分布式系统中，缓存删除失败可能导致数据不一致，请分析可能的原因，并给出恢复方案。</p>
          <h3>标准要求</h3><p>能够从一致性、可用性、可观测性等角度，给出完整的分析与解决思路。</p>
        </template>
        <div v-else class="bank-unknown"><RIcon name="file-text" /><span>题干与标准要求待确认</span></div>
        <button type="button" class="ts-button bank-condition" @click="pending('条件说明')"><RIcon name="play" :size="14" />条件说明<RIcon name="chevron-right" :size="19" /></button>
        <button type="button" class="ts-button bank-hidden-answer" @click="pending('参考答案开放条件')"><RIcon name="lock" :size="17" /><span><RIcon name="lock" :size="16" />参考答案已隐藏</span></button>
        <button type="button" class="ts-button primary bank-start" @click="selectedQuestion === 0 ? go(17) : pending('该题独立作答')"><RIcon name="play" :size="19" />开始独立作答</button>
        <button type="button" class="ts-button bank-add" @click="pending('加入题单')"><RIcon name="plus" :size="22" />加入题单</button>
      </aside>
      <aside class="ts-panel bank-route">
        <button type="button" class="bank-route-head" @click="go(15)"><strong>学习路线</strong><span>Java 后端 · 三周</span><RIcon name="chevron-right" :size="20" /></button>
        <ol><li>分布式缓存原理</li><li>缓存一致性方案</li><li>故障排查与恢复</li></ol>
      </aside>
    </template>

    <template v-else-if="scene === 15">
      <div class="route-title"><h1>题单与路线</h1></div>
      <nav class="route-tabs" aria-label="题单与路线视图">
        <button type="button" @click="go(14)">题目</button>
        <button type="button" :class="{ active: routeTab === '题单' }" @click="routeTab = '题单'; pending('题单列表')">题单</button>
        <button type="button" :class="{ active: routeTab === '路线' }" @click="routeTab = '路线'">路线</button>
      </nav>
      <div class="route-actions"><button type="button" class="ts-button" @click="pending('新建题单')"><RIcon name="plus" :size="24" />新建题单</button><button type="button" class="ts-button primary" @click="pending('新建路线')"><RIcon name="plus" :size="24" />新建路线</button></div>
      <aside class="ts-panel route-list">
        <div class="route-list-heading"><h3>路线列表</h3><button type="button" class="ts-icon-button" aria-label="调整路线筛选" @click="pending('路线筛选')"><RIcon name="sliders-horizontal" :size="23" /></button></div>
        <button v-for="(item, index) in routes" :key="item.title" type="button" class="route-list-item" :class="{ selected: selectedRoute === index }" @click="selectRoute(index)">
          <span class="route-small-icon"><RIcon :name="item.icon" :size="28" /></span><span><strong>{{ item.title }}</strong><small>{{ item.meta }}</small></span><RIcon name="chevron-right" :size="22" />
        </button>
      </aside>
      <section class="ts-panel route-map" aria-label="Java 后端路线阶段">
        <div class="route-track"></div>
        <button v-for="(stage, index) in stages" :key="stage.title" type="button" class="route-stage" :class="[stage.state, { selected: selectedStage === index }]" :style="{ top: `${[29, 127, 224, 320, 415][index]}px` }" @click="selectStage(index)">
          <span class="stage-orbit"><span><RIcon :name="stage.icon" :size="28" /></span></span>
          <span class="stage-copy"><strong>{{ stage.title }}</strong><small>{{ stage.meta }}</small></span>
          <RIcon v-if="index === 0" name="check" :size="24" class="stage-end done" />
          <RIcon v-else-if="selectedStage === index" name="chevron-right" :size="24" class="stage-end" />
          <span v-else class="stage-end stage-empty"></span>
        </button>
        <div v-if="selectedStage === 1" class="route-completion"><strong>完成条件：</strong><p>独立作答 2 题 + 复练 1 次</p></div>
      </section>
      <section class="ts-panel route-overview">
        <h3>检查点总览</h3>
        <table><thead><tr><th></th><th>名称</th><th>题单</th><th>检查点</th><th>最近</th></tr></thead>
          <tbody>
            <tr tabindex="0" @click="selectStage(0)" @keydown.enter="selectStage(0)"><td><i class="orange"></i></td><td>基础回顾</td><td>Java 基础高频题</td><td>2 个检查点</td><td>3 天前</td></tr>
            <tr tabindex="0" @click="selectStage(1)" @keydown.enter="selectStage(1)"><td><i class="green"></i></td><td>核心应用</td><td>Java 核心应用题</td><td>1 个检查点</td><td>今天 14:32</td></tr>
            <tr tabindex="0" @click="selectStage(2)" @keydown.enter="selectStage(2)"><td><i class="gray"></i></td><td>故障排查</td><td>分布式与中间件</td><td>1 个检查点</td><td>—</td></tr>
          </tbody>
        </table>
      </section>
      <aside class="ts-panel route-details">
        <div class="route-details-inner">
          <h3>路线详情</h3><div class="route-detail-title"><span><RIcon name="layers" :size="29" /></span><h2>Java 后端 · 三周</h2></div>
          <div class="route-meta"><span class="route-creator">林</span>创建者：<span>林</span></div>
          <div class="route-meta"><RIcon name="clock" :size="27" />预计用时：<span>6 小时</span></div>
          <div class="route-detail-rule"></div><h3>关联题单</h3>
          <div class="linked-question-lists"><button v-for="name in ['Java 核心基础', '并发编程实战', 'Spring Boot 应用']" :key="name" type="button" @click="pending(`${name}题单详情`)"><RIcon name="file-text" :size="25" />{{ name }}<RIcon name="chevron-right" :size="22" /></button></div>
          <button type="button" class="ts-button route-reorder" @click="pending('题单顺序调整')"><RIcon name="sliders-horizontal" :size="24" />调整顺序</button>
        </div>
      </aside>
    </template>

    <template v-else-if="scene === 16">
      <div class="recall-horizon" aria-hidden="true"></div>
      <div class="recall-title"><h1>回忆卡</h1><p><RIcon name="calendar" :size="27" />今日 4 / 12 · 预计 9 分钟</p></div>
      <div class="recall-tools"><button type="button" class="ts-button" :aria-pressed="browseOnly" @click="browseOnly = !browseOnly"><RIcon name="eye" :size="26" />{{ browseOnly ? '返回回忆' : '仅浏览' }}</button><span></span><button type="button" class="ts-icon-button" aria-label="回忆设置" @click="pending('回忆设置')"><RIcon name="settings" :size="29" /></button></div>
      <aside class="ts-panel recall-queue">
        <nav class="recall-tabs" aria-label="回忆队列"><button v-for="tab in ['全部', '到期', '新卡']" :key="tab" type="button" :class="{ active: recallTab === tab }" @click="recallTab = tab">{{ tab }}</button></nav>
        <template v-if="recallTab !== '新卡'">
          <button v-for="(card, index) in recallCards" :key="card.title" type="button" class="recall-queue-item" :class="{ current: index === 0 }" @click="index === 0 ? undefined : pending(`${card.title}回忆卡正文`)">
            <i :class="card.color"></i><span><strong>{{ card.title }}</strong><small>今天 · 到期</small></span><span v-if="card.badge" class="recall-card-badge">{{ card.badge }}</span>
          </button>
        </template>
        <p v-else class="recall-empty">新卡队列待确认</p>
      </aside>
      <section class="ts-panel recall-workspace">
        <header><span><RIcon name="file-text" :size="26" />缓存一致性 / 笔记 v3</span><span>第 4 / 12 张</span></header>
        <h2>缓存删除失败后，如何确认系统<br />已经恢复？</h2>
        <textarea v-model="recallDraft" :readonly="browseOnly" placeholder="先写下你的回忆，再提示答案" aria-label="回忆内容"></textarea>
        <footer><button type="button" class="ts-button primary" @click="pending('提示答案内容')"><RIcon name="lightbulb" :size="29" />提示答案</button><button type="button" class="ts-button" @click="pending('下一张回忆卡')"><RIcon name="skip-forward" :size="28" />跳过</button></footer>
      </section>
      <aside class="ts-panel recall-card-details">
        <h3>这张卡</h3>
        <section class="recall-source"><div><RIcon name="file-text" :size="29" /><span>来源</span></div><button type="button" @click="pending('来源笔记入口')">缓存一致性 / 笔记 v3</button></section>
        <section class="recall-schedule"><div><RIcon name="calendar" :size="29" /><span>计划复习<strong>11.13</strong></span></div><div><RIcon name="clock" :size="29" /><span>上次复习<strong>11.08 · <em>困难</em></strong></span></div></section>
        <section class="recall-next"><div><RIcon name="refresh-cw" :size="29" /><span>下次复习<strong>11.20</strong></span></div><button type="button" class="ts-button" @click="pending('记忆历史')"><RIcon name="history" :size="23" />查看记忆历史</button></section>
        <section class="recall-note"><div><RIcon name="file-text" :size="29" /><span>备注</span></div><p>自评不会成为能力结论。</p></section>
      </aside>
    </template>

    <template v-else-if="scene === 17">
      <div class="training-frame"></div>
      <header class="writing-header">
        <button type="button" class="writing-back ts-icon-button" aria-label="返回题库" @click="go(14)"><RIcon name="chevron-left" :size="29" /></button>
        <span class="writing-crumb">训练 / 独立专项 / 第 1 题</span>
        <button type="button" class="writing-search" @click="pending('训练中搜索权限')"><RIcon name="search" :size="22" /><span>⌘K 搜索题目、知识点...</span></button>
        <span class="writing-timer"><RIcon name="clock" :size="27" />18:42</span>
        <span class="writing-header-divider"></span><span class="writing-saved"><RIcon name="circle-check" :size="21" />已保存 14:32</span>
        <button type="button" class="ts-button writing-pause" :aria-pressed="trainingPaused" @click="trainingPaused = !trainingPaused"><RIcon :name="trainingPaused ? 'play' : 'pause'" :size="19" />{{ trainingPaused ? '继续' : '暂停' }}</button>
        <button type="button" class="ts-button writing-end" @click="go(19)">结束本次</button>
        <button type="button" class="writing-bell ts-icon-button" aria-label="通知" @click="pending('通知列表')"><RIcon name="bell" :size="26" /></button><button type="button" class="writing-avatar" aria-label="个人菜单" @click="pending('个人菜单')">林</button>
      </header>
      <div class="writing-topic"><span><RIcon name="layers" :size="23" />分布式系统 · 异常恢复</span></div>
      <div class="writing-question"><h1>缓存删除失败后，你会先确认哪些条件？</h1><p>请先明确问题背景和相关约束。</p></div>
      <div class="writing-editor">
        <textarea v-model="trainingDraft" :disabled="trainingPaused" placeholder="从空白开始作答..." aria-label="本题回答"></textarea>
        <span v-if="!trainingDraft && !trainingPaused" class="fixed-caret" aria-hidden="true"></span>
        <div v-if="trainingPaused" class="writing-paused"><RIcon name="pause" :size="32" /><span>训练已暂停</span><button type="button" class="ts-button" @click="trainingPaused = false"><RIcon name="play" :size="20" />继续作答</button></div>
      </div>
      <aside v-if="conditionsVisible" class="ts-panel writing-conditions">
        <h3>本次条件</h3><dl>
          <div><dt><RIcon name="target" :size="24" />目标</dt><dd>Java 后端</dd></div><div><dt><RIcon name="shield-check" :size="24" />标准</dt><dd>未审</dd></div><div><dt><RIcon name="wrench" :size="24" />辅助</dt><dd>未开启</dd></div><div><dt><RIcon name="clock" :size="24" />允许用时</dt><dd>30 分钟</dd></div>
          <div class="condition-warning"><dt><RIcon name="file-text" :size="24" />注意</dt><dd>作答中不可打开参考</dd></div>
        </dl>
      </aside>
      <footer class="writing-footer"><div class="writing-count">{{ trainingDraft.length }} 字<span></span><RIcon name="circle-check" :size="23" /><strong>自动保存已开启</strong></div><div><button type="button" class="ts-button writing-condition-button" :aria-expanded="conditionsVisible" @click="conditionsVisible = !conditionsVisible"><RIcon name="file-text" :size="25" />查看本次条件</button><button type="button" class="ts-button primary" :disabled="trainingPaused" @click="submitTraining"><RIcon name="send" :size="25" />提交本题</button></div></footer>
    </template>

    <template v-else-if="scene === 18">
      <div class="follow-title"><h1>追问 1 / 2 <span>· 原答已提交 14:32</span></h1></div>
      <button type="button" class="ts-button follow-end" @click="go(19)"><RIcon name="flag" :size="29" />结束本次</button>
      <section class="ts-panel follow-original">
        <h2><RIcon name="file-text" :size="37" />你的原答</h2><div class="follow-rule"></div><p>{{ firstOriginal }}</p>
        <p class="original-second" :class="{ located: locateEvidence }">补偿机制可以作为兜底方案，但也要考虑<span>补偿的幂等性和成本</span>，避免因重复补偿带来数据不一致。</p>
        <button type="button" class="original-citation" @click="locateEvidence = !locateEvidence">引用 · 第 2 段</button>
      </section>
      <aside class="ts-panel follow-candidate" :class="{ ignored: candidateState === '已忽略' }">
        <h2><RIcon name="sparkles" :size="36" /><span>AI 追问</span><span class="candidate-badge">{{ candidateState }}</span></h2><div class="follow-rule"></div>
        <h3>如果补偿请求也超时，你如何判断<br />是否应该继续重试？</h3>
        <button type="button" class="candidate-evidence" @click="locateEvidence = true"><RIcon name="file-text" :size="31" /><span>依据：原答第 2 段 — 补偿失败后再次确认...</span></button>
        <p class="candidate-notice"><RIcon name="info" :size="30" />这个问题不会改写原答</p>
        <div class="candidate-actions"><button type="button" class="ts-button primary" :disabled="candidateState !== '候选'" @click="decideCandidate('已采用')">作为正式追问</button><button type="button" class="ts-button" :disabled="candidateState !== '候选'" @click="decideCandidate('已忽略')">忽略</button></div>
        <p class="candidate-disclaimer"><RIcon name="shield-check" :size="31" />AI 未审 · 仅使用已提交答案 · 不改变本次条件</p>
      </aside>
    </template>

    <template v-else-if="scene === 19">
      <div class="report-title"><h1>本次训练</h1><p><RIcon name="clock" :size="27" />独立专项 · 11.10 14:32–14:50</p></div>
      <div class="report-top-actions"><button type="button" class="ts-button" @click="pending('返回今日入口')"><RIcon name="arrow-left" :size="25" />返回今日</button><button type="button" class="ts-button primary" @click="pending('复盘入口')"><RIcon name="play" :size="24" />开始复盘</button></div>
      <section class="ts-panel report-summary">
        <div class="report-stat"><span class="stat-emblem submitted"><RIcon name="check" :size="42" /></span><span>已提交<strong>2 <small>题</small></strong></span></div>
        <div class="report-stat"><span class="stat-emblem skipped"><RIcon name="eye-off" :size="38" /></span><span>跳过<strong>1 <small>题</small></strong></span></div>
        <div class="report-stat"><span class="stat-emblem draft"><RIcon name="file-text" :size="37" /></span><span>未提交草稿<strong>1 <small>题</small></strong></span></div>
        <p><RIcon name="info" :size="24" />不把缺答算作失败</p>
      </section>
      <section class="ts-panel report-question-panel">
        <h2><RIcon name="list" :size="29" />题目报告</h2>
        <div class="report-question-list">
          <section v-for="(item, index) in reportQuestions" :key="item.title" class="report-question-row" :class="{ expanded: expandedReport === index }">
            <button type="button" :aria-expanded="expandedReport === index" @click="toggleReport(index)"><span class="report-question-number">题目 {{ String(index + 1).padStart(2, '0') }}</span><span class="report-question-copy"><strong>{{ item.title }}</strong><small><RIcon :name="item.submitted ? 'circle-check' : 'circle'" :size="27" />{{ item.status }}</small></span><RIcon :name="expandedReport === index ? 'chevron-up' : 'chevron-down'" :size="24" /></button>
            <div v-if="expandedReport === index" class="report-expanded-copy"><p v-if="index === 0">{{ firstOriginal }}</p><p v-else>{{ item.status }}</p><button type="button" @click="pending('详细观察')">查看观察<RIcon name="chevron-right" :size="18" /></button></div>
          </section>
        </div>
        <button type="button" class="report-draft" @click="go(17)"><span class="report-draft-icon"><RIcon name="file-text" :size="29" /></span><span>未提交草稿</span><span>草稿仍可查看与另存</span><RIcon name="chevron-right" :size="24" /></button>
      </section>
      <aside class="ts-panel report-end-conditions"><h2><RIcon name="flag" :size="30" />结束条件</h2><dl><div><dt><RIcon name="clock" :size="29" />用时</dt><dd>18 分钟</dd></div><div><dt><RIcon name="circle" :size="29" />暂停</dt><dd>1 次</dd></div><div><dt><RIcon name="eye" :size="29" />参考暴露</dt><dd>无</dd></div><div class="report-evaluation"><dt><RIcon name="bookmark" :size="29" />评价状态</dt><dd><span>部分完成</span></dd></div></dl></aside>
      <aside class="ts-panel report-ai"><h3><RIcon name="robot" :size="29" />AI 评价</h3><button type="button" @click="pending('AI 评价重试')"><RIcon name="circle-alert" :size="29" />失败提示：可重试，原答不受影响</button></aside>
      <div class="report-bottom-actions"><button type="button" class="ts-button" @click="pending('逐题报告入口')"><RIcon name="file-text" :size="27" />查看逐题报告</button><button type="button" class="ts-button primary" @click="pending('导出摘要格式与内容')"><RIcon name="download" :size="26" />导出摘要</button></div>
    </template>

    <template v-else-if="scene === 20">
      <div class="design-title"><h1>秒杀发奖</h1><span>图 v2 · 未提交<i></i></span></div>
      <div class="design-actions"><button type="button" class="ts-button" :aria-label="diagramDirty ? '保存修改后的架构图和说明' : '保存草稿'" @click="saveDiagram">保存草稿</button><button type="button" class="ts-button primary" @click="pending('图文答案提交')"><RIcon name="send" :size="26" />提交图文答案</button></div>
      <nav class="design-tabs" aria-label="系统设计视图"><button v-for="tab in ['架构图', '说明', '版本']" :key="tab" type="button" :class="{ active: designTab === tab }" @click="designTab = tab">{{ tab }}</button></nav>
      <section class="ts-panel design-canvas">
        <svg v-if="designTab === '架构图'" ref="diagramSvg" class="architecture-svg" viewBox="0 0 865 699" width="865" height="699" role="group" aria-label="秒杀发奖架构图，可拖动节点" @pointermove="moveDrag" @pointerup="stopDrag" @pointercancel="stopDrag">
          <defs>
            <pattern id="training-design-grid" width="29.5" height="33" patternUnits="userSpaceOnUse"><path d="M29.5 0H0V33" fill="none" stroke="#565149" stroke-opacity=".16" stroke-width="1" /></pattern>
            <marker id="training-arrow-white" markerWidth="12" markerHeight="12" refX="8" refY="6" orient="auto" markerUnits="userSpaceOnUse"><path d="m1 1 7 5-7 5" fill="none" stroke="#d2d3ce" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round" /></marker>
            <marker id="training-arrow-amber" markerWidth="14" markerHeight="14" refX="9" refY="7" orient="auto" markerUnits="userSpaceOnUse"><path d="m1 1 8 6-8 6" fill="none" stroke="#efb850" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round" /></marker>
            <marker id="training-arrow-red" markerWidth="15" markerHeight="15" refX="10" refY="7.5" orient="auto-start-reverse" markerUnits="userSpaceOnUse"><path d="M1 1 10 7.5 1 14 4 7.5Z" fill="#ee5743" stroke="#ee5743" stroke-linejoin="round" /></marker>
            <linearGradient id="training-node-blue" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#284052" stop-opacity=".7" /><stop offset="1" stop-color="#1e2837" stop-opacity=".9" /></linearGradient>
            <linearGradient id="training-node-teal" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#214a47" stop-opacity=".85" /><stop offset="1" stop-color="#1e343a" stop-opacity=".88" /></linearGradient>
            <linearGradient id="training-node-purple" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#3c3046" stop-opacity=".8" /><stop offset="1" stop-color="#252a36" stop-opacity=".9" /></linearGradient>
            <linearGradient id="training-node-steel" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#365057" stop-opacity=".9" /><stop offset="1" stop-color="#26343c" stop-opacity=".9" /></linearGradient>
          </defs>
          <rect width="865" height="699" fill="url(#training-design-grid)" />
          <g class="architecture-links" fill="none" stroke="#d2d3ce" stroke-width="3.5" marker-end="url(#training-arrow-white)">
            <path :d="connector('entry', 'inventory', true)" /><path :d="connector('inventory', 'order')" /><path :d="connector('order', 'reward')" /><path :d="connector('order', 'queue', true)" /><path :d="connector('config', 'queue')" /><path :d="connector('queue', 'reconcile', true)" /><path :d="connector('reconcile', 'monitor')" />
          </g>
          <path :d="retryPath" fill="none" stroke="#efb850" stroke-width="3.8" stroke-dasharray="10 7" marker-end="url(#training-arrow-amber)" />
          <text :x="nodeById('inventory').x + 324" :y="nodeById('inventory').y - 98" class="architecture-amber">重试 / 补偿</text>
          <path :d="recoveryPath" fill="none" stroke="#ee5743" stroke-width="4" stroke-linecap="round" stroke-dasharray="9 10" marker-start="url(#training-arrow-red)" marker-end="url(#training-arrow-red)" />
          <text :x="nodeById('order').x + 100" :y="nodeById('order').y + 129" class="architecture-label">异步事件</text>
          <g :transform="`translate(${nodeById('reward').x + 108}, ${nodeById('reward').y + 152})`" class="architecture-error"><circle r="15" fill="#ee5943" /><text text-anchor="middle" y="7" fill="#2e0a05" font-size="23" font-weight="700">!</text><text x="24" y="5">预算扣减成功，</text><text x="24" y="35">发奖结果未知</text></g>
          <g v-for="node in nodes" :key="node.id" class="architecture-node" :class="[node.tone, { selected: selectedNode === node.id }]" :transform="`translate(${node.x}, ${node.y})`" tabindex="0" role="button" :aria-label="`${node.label}，可拖动或用方向键移动`" @pointerdown.prevent="startDrag($event, node)" @lostpointercapture="stopDrag()" @keydown="moveNode($event, node)">
            <rect :width="node.width" height="70" rx="16" :fill="`url(#training-node-${node.tone})`" stroke-width="2.3" />
            <RIcon :name="node.icon" :size="36" :x="node.id === 'entry' ? 39 : 23" :y="17" :stroke-width="2.3" />
            <text :x="node.id === 'entry' ? 93 : 70" y="44" :text-anchor="node.id === 'entry' ? 'start' : undefined">{{ node.label }}</text>
          </g>
        </svg>
        <div v-else-if="designTab === '说明'" class="design-note-editor"><h2>说明</h2><textarea v-model="designNote" aria-label="系统设计说明"></textarea><button type="button" class="ts-button" @click="saveDiagram"><RIcon name="file-text" :size="23" />保存说明</button></div>
        <div v-else class="design-version-list"><h2>版本</h2><button type="button" class="design-version" @click="pending('图 v2 版本详情')"><RIcon name="history" :size="27" /><span>图 v2 · 未提交</span><RIcon name="chevron-right" :size="22" /></button></div>
      </section>
      <aside class="ts-panel design-details">
        <nav class="design-aside-tabs" aria-label="设计条件视图"><button v-for="tab in ['约束', '说明', '版本']" :key="tab" type="button" :class="{ active: designAsideTab === tab }" @click="designAsideTab = tab">{{ tab }}</button></nav>
        <template v-if="designAsideTab === '约束'">
          <section class="design-constraints"><h3><RIcon name="list" :size="27" />约束条件</h3><ul><li>峰值 5k QPS</li><li>不重复发奖</li><li>允许最终一致</li><li>必须可验证恢复</li></ul></section>
          <section class="design-description"><h3><RIcon name="lightbulb" :size="28" />题目说明</h3><p>设计一个秒杀发奖系统的整体架构，需考虑高并发下的发奖一致性、容错恢复以及可观测性。请画出核心服务、数据流和关键异步链路，并说明设计思路。</p></section>
          <button type="button" class="design-fixed" @click="pending('图与说明固定规则')"><RIcon name="file-text" :size="26" />图与说明一起固定</button>
        </template>
        <section v-else-if="designAsideTab === '说明'" class="design-aside-note"><h3>说明</h3><textarea v-model="designNote" aria-label="图文说明"></textarea><button type="button" class="ts-button" @click="saveDiagram">保存说明</button></section>
        <section v-else class="design-aside-note"><h3>版本</h3><button type="button" class="design-version" @click="pending('版本记录')"><RIcon name="history" :size="25" />图 v2 · 未提交</button></section>
      </aside>
    </template>
  </section>
</template>

<style scoped>
.training-scenes { position: absolute; inset: 0; font-size: 20px; color: #f4f0e9; }
.training-scenes button, .training-scenes input, .training-scenes textarea { font: inherit; }
.training-scenes button { color: inherit; }
.training-scenes button:hover { filter: brightness(1.12); }
.training-scenes button:disabled { filter: none; }
.ts-panel { position: absolute; overflow: hidden; border: 1px solid #3b352e; border-radius: 18px; background: linear-gradient(125deg, #24211cb0, #1a1814a8 78%); }
.ts-button { display: inline-flex; align-items: center; justify-content: center; gap: 10px; min-height: 48px; padding: 8px 20px; border: 1px solid #453c32 !important; border-radius: 17px; background: linear-gradient(125deg, #29251f9c, #211d18b5) !important; white-space: nowrap; line-height: 1.3; }
.ts-button.primary { color: #241407; background: linear-gradient(135deg, #f3c28d, #dfaa73 45%, #b77d4d) !important; border-color: #edbd89 !important; font-weight: 600; }
.ts-icon-button { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 50%; }
.bank-title { position: absolute; left: 24px; top: 99px; }
.bank-title h1 { font-size: 44px; line-height: 1.25; }
.bank-tabs { position: absolute; top: 162px; left: 20px; display: flex; width: 310px; height: 47px; border-radius: 13px; background: #18161382; }
.bank-tabs button { flex: 1; color: #ded9d3; font-size: 21px; border-radius: 13px; }
.bank-tabs button.active { position: relative; color: #fff3e4; font-weight: 600; background: linear-gradient(#3a3128a1, #332b2380); }
.bank-tabs button.active { flex: 0 0 100px; }
.bank-tabs button.active::after { content: ''; position: absolute; bottom: 0; left: 6px; right: 6px; height: 2px; background: #e6ab72; box-shadow: 0 0 7px #d7965266; }
.bank-tools { position: absolute; top: 112px; right: 30px; display: flex; gap: 13px; align-items: center; }
.bank-search { display: flex; align-items: center; gap: 14px; height: 54px; width: 324px; padding: 0 17px; border: 1px solid #39342d; border-radius: 16px; background: #211e19b0; }
.bank-search input { min-width: 0; width: 100%; background: transparent; border: 0; outline: none; font-size: 18px; }
.bank-search input::placeholder { color: #948e86; }
.bank-tools .ts-button { height: 54px; min-width: 0; width: 128px; padding-inline: 14px; gap: 9px; font-size: 20px; border-radius: 16px; }
.bank-tools .primary { width: 116px; font-weight: 500; }
.bank-filters { left: 8px; top: 230px; width: 204px; height: 750px; border-radius: 14px; padding: 32px 23px; background: linear-gradient(160deg, #1d1a17, #1b1815 80%); }
.bank-filter-group { margin-bottom: 30px; }
.bank-filter-group:nth-child(2) { margin-bottom: 23px; }
.bank-filter-group:nth-child(3) { padding-top: 10px; margin-top: -11px; margin-bottom: 15px; border-top: 1px solid #ffffff08; }
.bank-filter-group:nth-child(4) { margin-bottom: 0; }
.filter-heading { display: flex; justify-content: space-between; align-items: center; width: 100%; font-size: 19px !important; font-weight: 600; line-height: 28px; }
.filter-heading .r-icon { color: #8a8279; }
.filter-options { display: flex; flex-direction: column; gap: 7px; margin-top: 6px; }
.bank-filter-group:nth-child(3) .filter-options, .bank-filter-group:nth-child(4) .filter-options { gap: 1px; }
.filter-options button { display: flex; gap: 9px; align-items: center; min-height: 30px; font-size: 18px !important; text-align: left; white-space: nowrap; }
.filter-options button small { margin-left: auto; color: #99918a; font-size: 16px; }
.filter-options button.chosen { color: #f0bb86; }
.filter-radio { width: 18px; height: 18px; border-radius: 50%; border: 1.5px solid #8a8379; box-shadow: inset 0 0 0 2px #15130f; flex: none; }
.filter-radio.checked { background: #daa067; border-color: #eeb780; }
.filter-state-dot { width: 17px; height: 17px; border-radius: 50%; flex: none; }
.green { background: #75b77e; }
.orange { background: #dd9b52; }
.gray { background: #66615b; }
.filter-state-dot.green { background: #49a865; }
.filter-state-dot.orange { background: #d78739; }
.filter-state-dot.gray { background: #69655f; }
.reset-filters { position: absolute; left: 21px; right: 20px; bottom: 29px; height: 48px; font-size: 18px; border-radius: 13px; }
.bank-list { left: 218px; top: 230px; width: 709px; height: 750px; border-radius: 14px; padding: 6px 17px 6px 11px; background: linear-gradient(140deg, #191714, #171513 70%, #191613); }
.bank-list.expanded { left: 8px; width: 919px; }
.bank-list table { width: 100%; border-collapse: separate; border-spacing: 0; table-layout: fixed; font-size: 18px; text-align: left; }
.bank-list .question-column { width: 265px; }
.bank-list .topic-column { width: 118px; }
.bank-list .standard-column { width: 90px; }
.bank-list .source-column { width: 105px; }
.bank-list th { height: 55px; font-weight: 500; padding: 0 11px; white-space: nowrap; }
.bank-list th button { display: flex; align-items: center; gap: 9px; }
.bank-list td { height: 59px; border-bottom: 1px solid #ffffff04; padding: 0 11px; white-space: nowrap; }
.bank-list tr { cursor: pointer; }
.bank-list tbody tr:hover td { background: #ffffff04; }
.bank-list tr.selected td { background: linear-gradient(180deg, #302923e0, #2c2621e0); }
.bank-list tr.selected td:first-child { border-radius: 7px 0 0 7px; box-shadow: inset 2px 0 #dfa467; }
.bank-list tr.selected td:last-child { border-radius: 0 7px 7px 0; }
.standard-badge { display: inline-block; min-width: 58px; text-align: center; padding: 3px 8px; border-radius: 12px; line-height: 28px; }
.standard-badge.reviewed { background: #2f5938a1; color: #c9f0ce; }
.standard-badge.pending { background: #7d542ebd; color: #ffe1bb; }
.standard-badge.unreviewed { background: #ffffff13; color: #e8e4df; }
.bank-list .empty-row { color: #aaa49b; text-align: center; height: 100px; }
.bank-detail { left: 934px; top: 230px; width: 350px; height: 614px; padding: 16px 19px; border-radius: 14px; }
.bank-detail h2 { max-width: 300px; font-size: 28px; line-height: 1.3; font-weight: 600; min-height: 76px; }
.bank-tags { display: flex; gap: 7px; margin-top: 12px; margin-bottom: 24px; }
.bank-tags span { font-size: 16px; padding: 4px 12px; line-height: 24px; border-radius: 15px; background: #ffffff0b; }
.bank-tags span:first-child { background: #68452278; }
.bank-detail h3 { font-size: 20px; margin-top: 14px; margin-bottom: 3px; }
.bank-detail p { font-size: 16px; line-height: 1.55; }
.scene-14 .bank-condition, .scene-14 .bank-hidden-answer { font-size: 16px; }
.scene-14 .bank-start, .scene-14 .bank-add { font-size: 17px; }
.bank-condition { position: absolute; left: 18px; right: 18px; top: 381px; height: 46px; min-height: 0; justify-content: flex-start; gap: 7px; padding: 6px 10px; border-radius: 11px; font-size: 16px; }
.bank-condition > .r-icon:last-child { margin-left: auto; }
.bank-hidden-answer { position: absolute; left: 18px; right: 18px; top: 439px; height: 45px; min-height: 0; justify-content: flex-start; padding: 6px 14px; border-radius: 11px; color: #928b81 !important; font-size: 16px; }
.bank-hidden-answer span { display: flex; gap: 7px; align-items: center; margin-left: 45px; }
.bank-start { position: absolute; left: 18px; right: 18px; top: 498px; height: 44px; min-height: 0; border-radius: 12px; font-size: 17px; font-weight: 500 !important; }
.bank-start .r-icon { fill: #68431d; }
.bank-add { position: absolute; left: 18px; right: 18px; top: 553px; height: 43px; min-height: 0; border-color: #dda36b !important; border-radius: 12px; font-size: 17px; }
.bank-unknown { display: flex; gap: 10px; color: #aaa299; font-size: 17px; margin-top: 40px; }
.bank-route { left: 934px; top: 842px; width: 350px; height: 138px; border-radius: 15px; padding: 12px 29px; background: #1b1815; }
.bank-route::before { content: ''; position: absolute; inset: 0 10px 1px; border: 1px solid #4c40323d; border-radius: 18px; background: linear-gradient(120deg, #2b251ed6, #29231dd0); pointer-events: none; }
.bank-route > * { position: relative; }
.bank-route-head { display: flex; width: 100%; align-items: center; gap: 7px; white-space: nowrap; }
.bank-route-head strong { font-size: 18px; }
.bank-route-head span { font-size: 16px; color: #c5beb5; }
.bank-route-head .r-icon { margin-left: auto; color: #c5beb5; }
.bank-route ol { position: relative; list-style: none; padding: 6px 0 0 33px; margin: 0; font-size: 16px; line-height: 25px; }
.bank-route ol::before { position: absolute; content: ''; left: 10px; top: 12px; bottom: 6px; width: 1px; background: #9b7958; }
.bank-route li::before { content: ''; position: absolute; left: 6px; width: 9px; height: 9px; margin-top: 9px; border-radius: 50%; background: #e6b17d; }
.route-title { position: absolute; top: 100px; left: 21px; }
.route-title h1 { font-size: 41px; }
.route-tabs { position: absolute; left: 16px; top: 168px; width: 321px; height: 50px; display: flex; border: 1px solid #2b261f; border-radius: 28px; background: #201c178c; }
.route-tabs button { position: relative; flex: 1; font-size: 21px; color: #a79f96; border-radius: 28px; }
.route-tabs .active { color: #ffead2; font-weight: 600; background: #49301c29; }
.route-tabs .active::after { content: ''; position: absolute; bottom: 0; left: 25px; right: 25px; height: 3px; border-radius: 3px; background: #efb072; box-shadow: 0 0 11px #e5a15d6b; }
.route-actions { position: absolute; right: 28px; top: 168px; display: flex; gap: 14px; }
.route-actions .ts-button { height: 50px; min-width: 141px; border-radius: 29px; font-size: 20px; gap: 5px; }
.route-actions .primary { background: linear-gradient(135deg, #ffc17d, #e59d57) !important; }
.route-list { left: 16px; top: 235px; width: 324px; height: 747px; padding: 19px 7px; }
.route-list::before { content: ''; position: absolute; inset: 9px -1px auto; height: 76px; border-top: 1px solid #393127; border-radius: 20px; pointer-events: none; }
.route-list-heading { display: flex; align-items: center; justify-content: space-between; height: 45px; padding: 0 12px; margin-bottom: 9px; }
.route-list-heading h3 { font-size: 20px; }
.route-list-heading button { color: #b8ab9a; }
.route-list-item { display: flex; align-items: center; gap: 15px; width: 100%; min-height: 91px; margin-bottom: 2px; padding: 10px 13px !important; text-align: left; border-radius: 16px; }
.route-list-item.selected { background: linear-gradient(120deg, #92613bc2, #715034b5) !important; }
.route-small-icon { display: grid; place-items: center; width: 38px; height: 42px; border-radius: 15px; background: #ffffff0c; flex: none; }
.route-list-item strong { display: block; font-size: 20px; font-weight: 500; white-space: nowrap; }
.route-list-item small { display: block; font-size: 15px; color: #bcb1a3; margin-top: 2px; white-space: nowrap; }
.route-list-item > .r-icon { margin-left: auto; flex: none; color: #ddd0be; }
.route-map { left: 351px; top: 235px; width: 613px; height: 513px; }
.route-track { position: absolute; left: 59px; width: 4px; top: 61px; bottom: 76px; background: linear-gradient(#d6a260 0 24%, #a6bcc6 24% 100%); }
.route-stage { position: absolute; left: 28px; width: 311px; height: 75px; display: flex; align-items: center; border: 1px solid #39332b !important; background: linear-gradient(120deg, #2b2a25ba, #24211dc7) !important; border-radius: 32px; text-align: left; }
.route-stage.done { border-color: #3a4236 !important; }
.route-stage.selected { border-color: #eaa353 !important; background: linear-gradient(120deg, #72502d55, #452e1b66) !important; box-shadow: inset 0 0 10px #c8832728; }
.stage-orbit { display: grid; place-items: center; width: 63px; height: 75px; border: 1px solid #ffffff06; border-radius: 50%; flex: none; background: #26252155; }
.stage-orbit > span { width: 35px; height: 36px; display: grid; place-items: center; border-radius: 50%; background: #534c4280; }
.route-stage.done .stage-orbit { border-color: #6a99618f; background: #20392c40; }
.route-stage.done .stage-orbit > span { background: #82b680; color: #edf7e2; width: 30px; height: 30px; }
.route-stage.current .stage-orbit > span { background: #795032b5; color: #ffe3c3; }
.stage-copy { padding-left: 18px; }
.stage-copy strong { display: block; font-size: 21px; font-weight: 550; }
.stage-copy small { display: block; margin-top: 1px; font-size: 16px; color: #c9c3b9; }
.route-stage.done .stage-copy small { color: #a1cb96; }
.route-stage.current .stage-copy small { color: #e9c392; }
.stage-end { position: absolute; right: 18px; color: #efbf87; }
.stage-end.done { color: #85bf89; background: transparent; }
.stage-empty { width: 19px; height: 19px; border: 1.5px solid #969084; border-radius: 50%; }
.route-completion { position: absolute; left: 361px; top: 122px; width: 228px; height: 87px; padding: 10px 16px; border: 1px solid #3c362e; border-radius: 17px; background: #2a2621d4; }
.route-completion::before { content: ''; position: absolute; width: 25px; height: 22px; left: -26px; top: 29px; border-bottom: 1px solid #594330; border-radius: 0 0 100% 0; }
.route-completion strong { font-size: 20px; font-weight: 550; }
.route-completion p { font-size: 18px; margin-top: 2px; white-space: nowrap; }
.route-overview { left: 351px; top: 759px; width: 613px; height: 223px; padding: 14px 18px; }
.route-overview h3 { font-size: 20px; margin-bottom: 10px; }
.route-overview table { width: 100%; border-collapse: collapse; font-size: 16px; text-align: left; }
.route-overview th { font-size: 18px; color: #b4a99c; font-weight: 400; height: 36px; white-space: nowrap; }
.route-overview td { height: 40px; white-space: nowrap; }
.route-overview th:first-child { width: 34px; }
.route-overview th:nth-child(2) { width: 139px; }
.route-overview th:nth-child(3) { width: 168px; }
.route-overview th:nth-child(4) { width: 141px; }
.route-overview td i { display: inline-block; width: 12px; height: 12px; border-radius: 50%; }
.route-overview tbody tr { cursor: pointer; }
.route-details { left: 976px; top: 235px; width: 322px; height: 747px; padding: 9px; }
.route-details-inner { height: 513px; border: 1px solid #39332b; border-radius: 18px; padding: 17px 10px 12px; background: linear-gradient(130deg, #2a241db5, #24211db5); }
.route-details h3 { font-size: 20px; }
.route-detail-title { display: flex; align-items: center; gap: 17px; margin-top: 22px; }
.route-detail-title > span { display: grid; place-items: center; width: 43px; height: 45px; background: #77512d88; border-radius: 14px; color: #f4bb77; }
.route-detail-title h2 { font-size: 25px; white-space: nowrap; }
.route-meta { display: flex; align-items: center; gap: 15px; color: #bdb3a8; font-size: 20px; margin-top: 17px; }
.route-meta > span:last-child { color: #e3d9cc; margin-left: -10px; }
.route-creator { display: grid; place-items: center; width: 31px; height: 33px; border-radius: 50%; background: #756f65; font-size: 18px; color: #f6eee4; }
.route-detail-rule { height: 1px; margin: 25px 0 20px; background: #ffffff0a; }
.linked-question-lists { margin: 9px -9px 0; background: #ffffff02; border-radius: 18px; padding: 2px 10px; }
.linked-question-lists button { width: 100%; height: 47px; display: flex; align-items: center; gap: 13px; font-size: 18px; white-space: nowrap; }
.linked-question-lists button > .r-icon:last-child { margin-left: auto; }
.route-reorder { width: 100%; height: 49px; margin-top: 8px; border-radius: 19px; padding: 5px; font-size: 20px; }
.recall-horizon { position: absolute; pointer-events: none; z-index: 0; top: 95px; left: 245px; width: 1600px; height: 1120px; border-top: 1px solid #bd803b6b; border-radius: 50%; box-shadow: 0 -9px 30px #c7803120; }
.recall-title { position: absolute; top: 118px; left: 34px; }
.recall-title h1 { font-size: 50px; }
.recall-title p { display: flex; gap: 10px; align-items: center; margin-top: 3px; color: #bdb6ad; font-size: 24px; }
.recall-tools { position: absolute; right: 30px; top: 160px; display: flex; align-items: center; gap: 15px; }
.recall-tools > .ts-button { min-width: 145px; height: 54px; border-radius: 29px; font-size: 22px; }
.recall-tools > span { height: 44px; width: 1px; background: #5f5447; margin: 0 0 0 1px; }
.recall-tools .ts-icon-button { margin-left: 0; }
.recall-queue { left: 10px; top: 258px; width: 325px; height: 692px; padding: 20px 17px; }
.recall-tabs { display: flex; justify-content: space-between; gap: 18px; height: 61px; margin-bottom: 24px; }
.recall-tabs button { position: relative; flex: 1; color: #9b958f; font-size: 22px; white-space: nowrap; }
.recall-tabs .active { color: #fff2e0; font-weight: 600; }
.recall-tabs .active::after { content: ''; position: absolute; bottom: 1px; left: 5px; right: 5px; height: 2px; background: #f1b773; box-shadow: 0 0 10px #ffb15977; }
.recall-queue-item { position: relative; display: flex; width: 100%; gap: 16px; align-items: flex-start; height: 103px; margin-bottom: 1px; padding: 17px 13px !important; text-align: left; border: 1px solid transparent !important; border-radius: 13px; }
.recall-queue-item.current { height: 103px; border-color: #c78c50 !important; background: linear-gradient(110deg, #84532f47, #32261c73) !important; box-shadow: inset 1px 0 2px #efb074, -4px 0 13px #ee9c4c33; margin-bottom: 5px; }
.recall-queue-item i { width: 15px; height: 15px; border-radius: 50%; flex: none; margin-top: 10px; }
.recall-queue-item i.orange { background: linear-gradient(135deg, #bc793d, #de9661); }
.recall-queue-item i.green { background: linear-gradient(135deg, #427655, #74b082); }
.recall-queue-item strong { display: block; font-size: 23px; font-weight: 500; white-space: nowrap; line-height: 1.5; }
.recall-queue-item small { display: block; margin-top: 3px; color: #a69e93; font-size: 21px; white-space: nowrap; }
.recall-queue-item.current small { color: #efc18f; }
.recall-card-badge { position: absolute; right: 10px; top: 20px; border-radius: 19px; padding: 1px 12px; line-height: 31px; font-size: 18px; background: #ffffff13; white-space: nowrap; }
.recall-queue-item.current .recall-card-badge { background: #9f683cb5; color: #ffe0bf; }
.recall-empty { color: #a69e93; padding: 24px; text-align: center; }
.recall-workspace { left: 345px; top: 258px; width: 624px; height: 692px; padding: 33px 30px; }
.recall-workspace header { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding-bottom: 20px; border-bottom: 1px solid #ffffff0b; font-size: 22px; }
.recall-workspace header span:first-child { display: flex; align-items: center; gap: 12px; white-space: nowrap; }
.recall-workspace header span:last-child { color: #aaa297; white-space: nowrap; }
.recall-workspace h2 { margin-top: 30px; font-size: 35px; line-height: 1.45; font-weight: 650; }
.recall-workspace textarea { position: absolute; top: 270px; left: 28px; width: 568px; height: 267px; padding: 20px 18px; resize: none; border: 1px solid #5e5345; border-radius: 14px; background: linear-gradient(110deg, #211e1a45, #30292124); font-size: 26px; line-height: 1.65; color: #f3eadc; }
.recall-workspace textarea::placeholder { color: #7c756a; }
.recall-workspace footer { position: absolute; left: 30px; right: 30px; bottom: 28px; padding-top: 28px; border-top: 1px solid #ffffff0b; display: flex; gap: 15px; }
.recall-workspace footer .ts-button { height: 65px; border-radius: 36px; font-size: 24px; font-weight: 600; }
.recall-workspace footer .primary { width: 307px; }
.recall-workspace footer .ts-button:last-child { flex: 1; border: 2px solid #4d4439 !important; }
.recall-card-details { left: 979px; top: 258px; width: 314px; height: 692px; padding: 31px 31px; }
.recall-card-details h3 { font-size: 25px; }
.recall-card-details section > div { display: flex; gap: 17px; color: #ada59a; align-items: flex-start; }
.recall-card-details section strong { display: block; font-size: 23px; line-height: 1.7; color: #f3eee7; font-weight: 500; }
.recall-card-details section em { color: #e47c3c; font-style: normal; }
.recall-card-details section > div > .r-icon { flex: none; margin-top: 3px; }
.recall-card-details section > div > span { font-size: 22px; }
.recall-source { margin-top: 23px; padding-bottom: 25px; border-bottom: 1px solid #ffffff0b; }
.recall-source button { margin-top: 8px; white-space: nowrap; font-size: 22px; text-align: left; }
.recall-schedule { padding: 27px 0 17px; border-bottom: 1px solid #ffffff0b; }
.recall-schedule > div + div { margin-top: 12px; }
.recall-next { margin-top: 14px; }
.recall-next .ts-button { width: 100%; font-size: 20px; gap: 8px; color: #bfb6a9; border-radius: 28px; margin-top: 14px; height: 52px; padding: 5px; }
.recall-note { margin-top: 27px; }
.recall-note p { margin-top: 13px; color: #bfb6a9; font-size: 21px; white-space: nowrap; }
.training-frame { position: absolute; top: 24px; left: 0; width: 1292px; height: 926px; border: 1px solid #514338; border-radius: 23px; background: linear-gradient(120deg, #201b1621, #17140f44); }
.writing-header { position: absolute; left: 0; top: 24px; width: 1292px; height: 79px; display: flex; align-items: center; border-bottom: 1px solid #ffffff03; background: linear-gradient(110deg, #25211c5e, #221d176b); border-radius: 23px 23px 0 0; font-size: 18px; white-space: nowrap; }
.writing-back { position: absolute; left: 8px; top: 20px; }
.writing-crumb { position: absolute; left: 67px; top: 26px; }
.writing-search { position: absolute; left: 334px; top: 18px; width: 281px; height: 44px; border-radius: 25px; display: flex; align-items: center; gap: 10px; padding: 0 17px !important; background: #34302b9e !important; color: #b9b0a5 !important; font-size: 16px !important; }
.writing-timer { position: absolute; left: 663px; top: 22px; display: flex; align-items: center; gap: 7px; font-size: 24px; font-weight: 600; }
.writing-header-divider { position: absolute; left: 779px; top: 25px; width: 1px; height: 31px; background: #9c8c764f; }
.writing-saved { position: absolute; left: 790px; top: 18px; height: 44px; width: 146px; border: 1px solid #39372b; border-radius: 24px; display: flex; align-items: center; justify-content: center; gap: 9px; font-size: 16px; color: #a4dbc3; background: #24271e38; }
.writing-saved .r-icon, .writing-count .r-icon { color: #64c89f; fill: #27553e; }
.writing-pause { position: absolute; left: 951px; top: 18px; width: 92px; height: 44px; min-height: 0; font-size: 18px; border-radius: 22px; padding: 6px 10px; }
.writing-pause::before, .writing-end::after { content: ''; position: absolute; width: 1px; height: 31px; top: 6px; background: #a9957b4d; }
.writing-pause::before { left: -11px; }
.writing-end::after { right: -13px; }
.writing-end { position: absolute; left: 1055px; top: 18px; width: 107px; height: 44px; min-height: 0; font-size: 18px; border-radius: 23px; padding: 6px; background: #5242345e !important; }
.writing-bell { position: absolute; left: 1183px; top: 20px; }
.writing-avatar { position: absolute; left: 1241px; top: 19px; width: 39px; height: 42px; border-radius: 50%; background: #766453 !important; font-size: 21px !important; }
.writing-topic { position: absolute; left: 15px; top: 116px; width: 949px; height: 61px; border: 1px solid #463d32; border-radius: 18px; background: #302b238a; padding: 9px; }
.writing-topic > span { display: inline-flex; align-items: center; gap: 16px; height: 41px; padding: 0 13px; border-radius: 23px; background: #3a342d9c; font-size: 20px; }
.writing-topic .r-icon { color: #eaa060; fill: #a66b3a33; }
.writing-question { position: absolute; left: 25px; top: 198px; }
.writing-question h1 { font-size: 34px; line-height: 1.5; font-weight: 650; }
.writing-question p { margin-top: 7px; font-size: 24px; color: #b6afa4; }
.writing-editor { position: absolute; left: 23px; top: 318px; width: 941px; height: 542px; }
.writing-editor textarea { height: 100%; width: 100%; padding: 21px 24px; resize: none; border: 1px solid #6e6558; border-bottom-color: #3f382e; border-radius: 15px; outline: none; background: linear-gradient(115deg, #211e193f, #27211a54); color: #f4eee4; font-size: 24px; line-height: 1.7; }
.writing-editor textarea::placeholder { color: #999287; }
.writing-editor textarea:disabled { opacity: .45; }
.fixed-caret { position: absolute; left: 16px; top: 25px; width: 2px; height: 31px; background: #eee8df; pointer-events: none; }
.writing-paused { position: absolute; inset: 1px; border-radius: 15px; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 18px; background: #18140fb5; color: #e6d3ba; }
.writing-conditions { left: 983px; top: 114px; width: 302px; height: 368px; }
.writing-conditions h3 { font-size: 23px; padding: 16px 21px; border-bottom: 1px solid #ffffff0c; }
.writing-conditions dl { margin: 10px 23px 0; font-size: 18px; }
.writing-conditions dl > div { height: 49px; display: flex; align-items: center; border-bottom: 1px solid #ffffff0b; }
.writing-conditions dt { display: flex; align-items: center; gap: 16px; color: #bcb4a9; }
.writing-conditions dt > .r-icon { color: #e6e0d6; }
.writing-conditions dd { margin: 0 0 0 auto; white-space: nowrap; }
.writing-conditions dl > .condition-warning { display: block; height: auto; padding-top: 10px; border: 0; }
.condition-warning dd { margin: 2px 0 0 40px; color: #dfd6c8; }
.writing-footer { position: absolute; left: 24px; top: 875px; width: 943px; height: 57px; display: flex; justify-content: space-between; align-items: center; }
.writing-count { display: flex; align-items: center; gap: 13px; font-size: 19px; color: #bab0a2; }
.writing-count > span { display: block; margin: 0 16px; height: 30px; width: 1px; background: #786d5e; }
.writing-count strong { font-weight: 400; color: #76c8ad; }
.writing-footer > div:last-child { display: flex; gap: 12px; }
.writing-footer .ts-button { height: 57px; font-size: 18px; border-radius: 25px; padding: 8px 23px; }
.writing-condition-button { min-width: 181px; }
.writing-footer .primary { min-width: 159px; }
.follow-title { position: absolute; left: 23px; top: 115px; }
.follow-title h1 { font-size: 34px; line-height: 1.4; font-weight: 650; }
.follow-title h1 > span { font-size: 30px; color: #b7b2aa; margin-left: 10px; font-weight: 500; }
.follow-end.ts-button { position: absolute; top: 111px; left: 1089px; width: 194px; height: 64px; border-radius: 36px; color: #edaa6c !important; font-size: 24px; font-weight: 600; gap: 10px; }
.follow-end .r-icon { fill: #a5784a77; }
.follow-original { left: 23px; top: 195px; width: 695px; height: 713px; border-radius: 19px; padding: 20px 29px; }
.follow-original, .follow-candidate { background: #181411; border-color: #342b22; }
.follow-original h2, .follow-candidate h2 { display: flex; align-items: center; gap: 13px; font-size: 30px; line-height: 44.8px; font-weight: 600; }
.follow-original h2 .r-icon { fill: #e4e2dc; color: #24231f; }
.follow-rule { height: 1px; background: #5b514244; margin-top: 19px; margin-bottom: 21px; }
.follow-original .follow-rule { margin-bottom: 23px; }
.follow-original p { font-size: 23.5px; line-height: 42px; font-weight: 450; color: #d2cec5; }
.follow-original .original-second { margin-top: 38px; }
.original-second span { border-bottom: 3px solid #b68b39; padding-bottom: 4px; }
.original-second.located { background: #b87a2620; border-radius: 8px; outline: 1px solid #b68b396b; }
.original-citation { position: absolute; right: 32px; top: 330px; height: 45px; border-radius: 11px; padding: 6px 12px !important; background: linear-gradient(#fce4b3, #e8c58b) !important; color: #211704 !important; font-size: 19px !important; font-weight: 600; }
.follow-candidate { left: 731px; top: 195px; width: 552px; height: 713px; padding: 20px 29px 20px 36px; }
.follow-candidate h2 { gap: 9px; }
.follow-candidate h2 > .r-icon { width: 32px; height: 32px; color: #e4a94d; fill: #ca8c2755; }
.follow-candidate .follow-rule { margin-bottom: 20px; }
.candidate-badge { padding: 4px 10px; border-radius: 21px; background: #906513; color: #fff0c0; font-size: 21px; line-height: 32px; }
.follow-candidate h3 { margin-top: 20px; font-size: 30px; line-height: 47px; font-weight: 600; }
.candidate-evidence { display: flex; align-items: center; gap: 19px; width: 100%; height: 83px; margin-top: 31px; border-radius: 23px; padding: 15px 22px 15px 15px !important; background: #2c282180 !important; text-align: left; }
.candidate-evidence .r-icon { color: #d8d4cb; fill: #d0cec5; stroke: #3d3a32; }
.candidate-evidence span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 20px; }
.candidate-notice { display: flex; align-items: center; gap: 10px; margin-top: 25px !important; color: #beb8ad; font-size: 21px; line-height: 39.6px; white-space: nowrap; }
.candidate-notice .r-icon { fill: #b1afa5; stroke: #27251e; }
.candidate-actions { display: flex; align-items: center; gap: 15px; margin-top: 44px; }
.candidate-actions .ts-button { height: 71px; border-radius: 28px; font-size: 24px; font-weight: 600; flex: 1; }
.candidate-actions .primary { flex: 1.07; }
.candidate-disclaimer { display: flex; align-items: center; gap: 10px; padding-top: 24px; margin-top: 29px !important; border-top: 1px solid #584c3b55; color: #b8b0a5; font-size: 20px; white-space: nowrap; }
.candidate-disclaimer .r-icon { fill: #a5a79d; stroke: #272923; }
.follow-candidate.ignored h3 { opacity: .55; }
.report-title { position: absolute; left: 24px; top: 119px; }
.report-title h1 { font-size: 56px; font-weight: 650; line-height: 1.3; }
.report-title p { display: flex; align-items: center; gap: 13px; color: #c5b9a9; margin-top: 8px; font-size: 23px; }
.report-top-actions { position: absolute; left: 927px; top: 139px; display: flex; gap: 16px; }
.report-top-actions .ts-button { height: 58px; font-size: 20px; min-width: 159px; border-radius: 32px; }
.report-top-actions .primary { min-width: 166px; }
.report-top-actions .primary .r-icon { fill: #4b2c12; }
.report-summary { left: 21px; top: 258px; width: 1247px; height: 112px; display: flex; align-items: center; border-radius: 19px; background: linear-gradient(105deg, #30271e9e, #322a218a 85%); padding: 17px 0; }
.report-stat { height: 68px; display: flex; align-items: center; width: 320px; padding-left: 44px; gap: 35px; border-right: 1px solid #746652; }
.report-stat:nth-child(2) { width: 321px; padding-left: 52px; gap: 27px; }
.report-stat:nth-child(3) { width: 336px; padding-left: 50px; gap: 26px; }
.stat-emblem { width: 58px; height: 58px; border-radius: 50%; display: grid; place-items: center; border: 3px solid currentColor; flex: none; }
.stat-emblem.submitted { color: #65d6b4; background: #19473b99; box-shadow: 0 0 0 3px #264b3b77; }
.stat-emblem.skipped { color: #78c8e8; background: #1e3b4e99; box-shadow: 0 0 0 3px #263d4777; }
.stat-emblem.draft { color: #ffc18b; border-width: 2px; border-color: #7a4b29; background: #6a442799; }
.report-stat > span:last-child { display: block; font-size: 21px; line-height: 1.5; color: #cfc4b4; }
.report-stat strong { display: block; font-size: 40px; color: #f8f1e7; line-height: 1.15; font-weight: 600; }
.report-stat strong small { font-size: 24px; font-weight: 400; }
.report-summary > p { display: flex; align-items: center; justify-content: center; flex: 1; gap: 12px; font-size: 20px; color: #b9ae9e; white-space: nowrap; }
.report-question-panel { left: 21px; top: 398px; width: 775px; height: 491px; background: linear-gradient(125deg, #29231caf, #231c158c); }
.report-question-panel > h2, .report-end-conditions > h2 { display: flex; align-items: center; gap: 20px; height: 69px; padding: 0 28px; border-bottom: 1px solid #594b395c; font-size: 23px; font-weight: 600; }
.report-question-list { max-height: 321px; overflow: auto; margin: 0 24px; scrollbar-width: thin; scrollbar-color: #68533c transparent; }
.report-question-row { border-bottom: 1px solid #604e3740; }
.report-question-row:last-child { border-bottom: 0; }
.report-question-row > button { width: 100%; height: 105px; display: flex; align-items: flex-start; text-align: left; padding: 19px 7px !important; }
.report-question-number { display: inline-block; width: 138px; flex: none; color: #bcb0a2; font-size: 21px; padding-top: 3px; }
.report-question-copy { display: block; }
.report-question-copy strong { font-size: 24px; font-weight: 600; }
.report-question-copy small { display: flex; align-items: center; gap: 14px; font-size: 21px; color: #cbbfb0; margin-top: 5px; }
.report-question-copy small > .r-icon { color: #6dc7a0; fill: #32694d; }
.report-question-row:nth-child(3) .report-question-copy small > .r-icon { fill: none; color: #a9bdc8; }
.report-question-row > button > .r-icon:last-child { margin: 23px 0 0 auto; color: #d3c9bc; }
.report-expanded-copy { margin: 0 15px 20px 145px; font-size: 17px; color: #c9bdaa; }
.report-expanded-copy p { font-size: 17px; }
.report-expanded-copy button { display: inline-flex; align-items: center; color: #e6b079; margin-top: 8px; font-size: 17px; }
.report-draft { position: absolute; left: 18px; right: 18px; bottom: 18px; height: 82px; border: 1px solid #59453254 !important; border-radius: 16px; display: flex; align-items: center; gap: 16px; padding: 0 17px !important; background: #3b2d202f !important; font-size: 22px !important; }
.report-draft-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; background: #51453644; color: #d0c2b0; }
.report-draft > span:nth-child(3) { margin-left: auto; color: #afa191; }
.report-draft > .r-icon { margin-left: 4px; color: #b5a493; }
.report-end-conditions { left: 821px; top: 398px; width: 447px; height: 325px; background: linear-gradient(125deg, #29231caa, #251c1585); }
.report-end-conditions h2 { padding-left: 25px; }
.report-end-conditions h2 .r-icon { fill: #64bce0; color: #64bce0; }
.report-end-conditions dl { margin: 14px 24px 0; }
.report-end-conditions dl > div { display: flex; align-items: center; justify-content: space-between; height: 48px; }
.report-end-conditions dt { display: flex; align-items: center; gap: 23px; color: #bfb2a2; font-size: 22px; }
.report-end-conditions dt > .r-icon { color: #b7c6ce; }
.report-end-conditions dd { margin: 0; font-size: 25px; font-weight: 550; }
.report-end-conditions .report-evaluation { height: 81px; margin-top: 8px; padding-top: 3px; border-top: 1px solid #59452f55; }
.report-evaluation dd > span { padding: 5px 18px; border: 1px solid #a2602d77; border-radius: 24px; background: #86471d73; color: #f2b277; font-size: 20px; }
.report-ai { left: 821px; top: 742px; width: 447px; height: 139px; padding: 14px 17px; background: linear-gradient(120deg, #33271d9c, #231b157d); }
.report-ai h3 { font-size: 21px; display: flex; gap: 23px; align-items: center; padding-left: 7px; }
.report-ai button { width: 100%; display: flex; align-items: center; gap: 17px; min-height: 65px; padding: 10px 17px !important; margin-top: 9px; border: 1px solid #9a53403b !important; border-radius: 13px; background: #78382b55 !important; color: #f1b181; font-size: 18px !important; white-space: nowrap; }
.report-ai button .r-icon { fill: #cf6751; color: #5d291e; }
.report-bottom-actions { position: absolute; top: 909px; left: 823px; display: flex; gap: 18px; }
.report-bottom-actions .ts-button { height: 62px; border-radius: 32px; font-size: 22px; font-weight: 600; min-width: 231px; padding: 10px 23px; }
.report-bottom-actions .primary { min-width: 194px; }
.design-title { position: absolute; top: 102px; left: 29px; display: flex; align-items: center; gap: 27px; }
.design-title h1 { font-size: 47px; font-weight: 650; line-height: 1.4; }
.design-title > span { display: inline-flex; align-items: center; gap: 6px; border-radius: 25px; background: #29221b83; height: 43px; padding: 5px 17px; font-size: 21px; }
.design-title i { width: 10px; height: 10px; background: #ed9b42; border-radius: 50%; }
.design-actions { position: absolute; right: 28px; top: 107px; display: flex; gap: 17px; }
.design-actions .ts-button { height: 57px; border-radius: 20px; font-size: 22px; color: #b9b3ac; min-width: 147px; }
.design-actions .primary { min-width: 202px; color: #211206; }
.design-tabs { position: absolute; top: 193px; left: 32px; display: flex; align-items: center; gap: 21px; width: 1245px; height: 53px; border-bottom: 1px solid #493d2c42; }
.design-tabs button { position: relative; min-width: 111px; height: 53px; font-size: 23px; color: #aaa39a; border-radius: 16px 16px 0 0; }
.design-tabs .active { color: #fff0db; background: linear-gradient(#62402208, #79502a1c); font-weight: 600; }
.design-tabs .active::after { position: absolute; content: ''; left: 0; right: 0; bottom: -1px; height: 3px; border-radius: 4px; background: #edb071; box-shadow: 0 0 11px #e5974466; }
.design-canvas { left: 18px; top: 259px; width: 866px; height: 699px; background: linear-gradient(115deg, #131310ab, #1a171233); border-radius: 18px; border-color: #5e5345; }
.architecture-svg { display: block; touch-action: none; user-select: none; }
.architecture-node { cursor: grab; outline: none; }
.architecture-node:active { cursor: grabbing; }
.architecture-node rect { stroke: #448f89; }
.architecture-node.blue rect { stroke: #4a78a5; }
.architecture-node.purple rect { stroke: #8060a4; }
.architecture-node.steel rect { stroke: #56848b; }
.architecture-node:focus-visible rect, .architecture-node.selected rect { stroke: #e8b879; stroke-width: 3; }
.architecture-node text { fill: #f3f6f4; font-family: "Segoe UI", "Microsoft YaHei", sans-serif; font-size: 23px; font-weight: 600; pointer-events: none; }
.architecture-node .r-icon { color: #82d5ce; pointer-events: none; }
.architecture-node.blue .r-icon, .architecture-node.steel .r-icon { color: #b3d6e1; }
.architecture-node.blue .r-icon { fill: #b3d6e1; }
.architecture-node.purple .r-icon { color: #b795d6; }
.architecture-links, .architecture-amber, .architecture-label, .architecture-error { pointer-events: none; }
.architecture-amber { fill: #eab653; font-size: 21px; font-weight: 600; }
.architecture-label { fill: #f4f2ef; font-size: 21px; font-weight: 550; }
.architecture-error { fill: #f25d48; font-size: 20px; font-weight: 550; }
.design-details { top: 253px; left: 895px; width: 402px; height: 705px; padding: 12px 24px; background: linear-gradient(115deg, #251e17b0, #1d18129c); border-color: #352b22; border-radius: 21px; }
.design-aside-tabs { display: flex; width: 283px; height: 47px; margin-left: -8px; background: #332d2499; border-radius: 19px; }
.design-aside-tabs button { position: relative; flex: 1; font-size: 22px; color: #b8afa5; border-radius: 18px; }
.design-aside-tabs .active { color: #fff1df; font-weight: 600; background: #9b622326; }
.design-aside-tabs .active::after { content: ''; position: absolute; left: 12px; right: 12px; bottom: 0; height: 3px; border-radius: 3px; background: #f2bb7e; box-shadow: 0 0 10px #f2b77344; }
.design-constraints { padding: 34px 7px 29px; border-bottom: 1px solid #6a50352b; }
.design-constraints h3, .design-description h3 { display: flex; align-items: center; gap: 15px; font-size: 24px; font-weight: 600; }
.design-constraints ul { list-style: none; padding: 11px 0 0 35px; margin: 0; }
.design-constraints li { position: relative; font-size: 23px; height: 50px; line-height: 50px; white-space: nowrap; }
.design-constraints li::before { content: ''; position: absolute; left: -30px; top: 23px; width: 8px; height: 8px; border-radius: 50%; background: #c28e55; }
.design-description { padding: 28px 7px 0; }
.design-description p { margin-top: 19px; font-size: 18px; line-height: 1.78; }
.design-fixed { position: absolute; left: 24px; right: 24px; bottom: 25px; min-height: 70px; display: flex; align-items: center; gap: 13px; border-radius: 17px; padding: 13px 21px !important; background: #39302766 !important; color: #d3c8ba !important; font-size: 20px !important; }
.design-note-editor, .design-version-list { padding: 30px; }
.design-note-editor h2, .design-version-list h2 { font-size: 28px; margin-bottom: 20px; }
.design-note-editor textarea, .design-aside-note textarea { display: block; width: 100%; height: 470px; padding: 16px; resize: none; border: 1px solid #62513e; border-radius: 12px; background: #17141066; color: #eee5d7; font-size: 21px; line-height: 1.6; }
.design-note-editor > .ts-button { margin-top: 17px; }
.design-version { width: 100%; min-height: 65px; display: flex; align-items: center; gap: 15px; padding: 12px 16px !important; border-bottom: 1px solid #69513855 !important; text-align: left; color: #d6c4ab !important; }
.design-version > .r-icon:last-child { margin-left: auto; }
.design-aside-note { padding: 30px 7px; }
.design-aside-note h3 { margin-bottom: 20px; font-size: 24px; }
.design-aside-note textarea { height: 410px; font-size: 20px; }
.design-aside-note > .ts-button { width: 100%; margin-top: 15px; }
</style>
