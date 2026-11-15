<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import RIcon from './RIcon.vue'

const props = defineProps<{ scene: number }>()
const emit = defineEmits<{ navigate: [scene: number]; notify: [message: string] }>()

type FeedbackDecision = '有异议' | '接受' | '暂不判断'
type EvidenceKind = 'memory' | 'answer' | 'practice' | 'observation'

const feedbackDecisions = ref<FeedbackDecision[]>(['有异议', '接受', '接受'])
const feedbackMenu = ref<number | null>(null)
const selectedObservation = ref(0)
const reviewTab = ref('回答与反馈')
const improvement = ref('下一次说明恢复判据及\n验证步骤')
const addedToToday = ref(false)
const judgments = ['已处理', '部分处理', '未处理', '无法判断']
const judgment = ref('未处理')
const judgmentBasis = ref('说明了恢复判据，但未说明重试上限')
const confidenceOptions = ['记得较清楚', '有些模糊', '不确定']
const confidence = ref('有些模糊')
const editingFragment = ref<number | null>(null)
const fragments = ref([
  { title: '问题', text: '缓存删除失败后如何恢复？' },
  { title: '我的回答片段', text: '先查删除结果，再补偿......' },
  { title: '面试官反馈（回忆）', text: '似乎追问了重试上限' },
])
const direction = ref('技术深度')
const directions = [
  { label: '技术深度', icon: 'chart-no-axes-column' },
  { label: '工程实践', icon: 'settings' },
  { label: '系统设计', icon: 'box' },
  { label: '项目经验', icon: 'briefcase' },
]
const evidenceTypes: { key: EvidenceKind; label: string; heading: string; icon: string; scene: number }[] = [
  { key: 'memory', label: '回忆', heading: '回忆', icon: 'rotate-ccw', scene: 23 },
  { key: 'answer', label: '作答', heading: '独立作答', icon: 'square-pen', scene: 17 },
  { key: 'practice', label: '复练', heading: '复练', icon: 'refresh-cw', scene: 22 },
  { key: 'observation', label: '受审观察', heading: '受审观察', icon: 'eye', scene: 21 },
]
const evidenceEnabled = ref<Record<EvidenceKind, boolean>>({
  memory: true, answer: true, practice: true, observation: true,
})
const visibleEvidence = computed(() => evidenceTypes.filter((kind) => evidenceEnabled.value[kind.key]))
const evidenceRows: { title: string; dates: Record<EvidenceKind, string | null>; blue?: EvidenceKind[] }[] = [
  { title: '异常与恢复', dates: { memory: '11.10', answer: '11.09', practice: '11.11', observation: null } },
  { title: '职责边界', dates: { memory: '11.08', answer: '11.10', practice: null, observation: '11.11' } },
  { title: '缓存一致性', dates: { memory: null, answer: '11.09', practice: '11.11', observation: null } },
  { title: '系统设计取舍', dates: { memory: '11.07', answer: null, practice: '11.10', observation: null }, blue: ['practice'] },
]
const hiddenSuggestions = ref<number[]>([])
const prioritySuggestions = ref<number[]>([])
const targetMenu = ref(false)
const filterMenu = ref(false)
const suggestions = [
  { title: '补充恢复判据', source: '来自复练 · 11.11', text: '梳理异常恢复的判定标准\n（重试、降级、告警）。', tone: 'amber' },
  { title: '说明职责边界', source: '来自项目事实', text: '明确核心服务与周边服务的\n职责边界与协作方式。', tone: 'green' },
  { title: '做一次系统设计复练', source: '尚无记录', text: '围绕高并发、高可用场景进行\n系统设计复练。', tone: 'blue' },
]
const recallSuggestions = [
  { title: '待审题', icon: 'list', tone: 'amber', text: '这道题涉及缓存一致性与数据恢复，\n建议标记为待审题。', target: 14 },
  { title: '知识缺口', icon: 'book-open', tone: 'cyan', text: '对缓存删除后的恢复策略不够熟悉，\n需要补充相关知识。', target: 10 },
  { title: '复练动作', icon: 'zap', tone: 'green', text: '下次重点练习缓存一致性相关问题，\n并准备重试策略。', target: 17 },
]

function decideFeedback(index: number, decision: FeedbackDecision) {
  feedbackDecisions.value[index] = decision
  feedbackMenu.value = null
  emit('notify', `观察 ${String(index + 1).padStart(2, '0')}：${decision}`)
}

function switchReviewTab(tab: string) {
  if (tab === '新旧对照') {
    emit('navigate', 22)
    return
  }
  reviewTab.value = tab
  feedbackMenu.value = null
}

function addToday() {
  addedToToday.value = !addedToToday.value
  emit('notify', addedToToday.value ? '本次页面已标记加入今日；持久安排待确认' : '本次页面已取消标记；持久安排待确认')
}

function judgmentKeydown(event: KeyboardEvent) {
  const index = judgments.indexOf(judgment.value)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? judgments.length - 1
    : ['ArrowRight', 'ArrowDown'].includes(event.key) ? (index + 1) % judgments.length
      : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? (index + judgments.length - 1) % judgments.length : -1
  if (next < 0) return
  event.preventDefault()
  judgment.value = judgments[next]!
  const group = (event.currentTarget as HTMLElement).parentElement
  group?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus()
}

function confidenceKeydown(event: KeyboardEvent) {
  const index = confidenceOptions.indexOf(confidence.value)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? confidenceOptions.length - 1
    : ['ArrowRight', 'ArrowDown'].includes(event.key) ? (index + 1) % confidenceOptions.length
      : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? (index + confidenceOptions.length - 1) % confidenceOptions.length : -1
  if (next < 0) return
  event.preventDefault()
  confidence.value = confidenceOptions[next]!
  const group = (event.currentTarget as HTMLElement).parentElement
  group?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus()
}

async function editFragment(index: number) {
  editingFragment.value = editingFragment.value === index ? null : index
  await nextTick()
  document.querySelector<HTMLTextAreaElement>('.rv23-fragment.editing textarea')?.focus()
}

function openRecallSuggestion(target: number, title: string) {
  emit('navigate', target)
  emit('notify', `已打开相关页面；由回忆创建${title}的持久流程待确认`)
}

function togglePriority(index: number) {
  prioritySuggestions.value = prioritySuggestions.value.includes(index)
    ? prioritySuggestions.value.filter((item) => item !== index)
    : [...prioritySuggestions.value, index]
  emit('notify', prioritySuggestions.value.includes(index) ? '优先级标记仅在本次页面内保留；持久安排待确认' : '本次页面已恢复原优先级；持久安排待确认')
}

function selectDirection(value: string) {
  direction.value = value
  if (value !== '技术深度') emit('notify', `${value}的证据分类规则待确认；当前保留固定参考数据`)
}

function directionKeydown(event: KeyboardEvent) {
  const index = directions.findIndex(item => item.label === direction.value)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? directions.length - 1
    : ['ArrowRight', 'ArrowDown'].includes(event.key) ? (index + 1) % directions.length
      : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? (index + directions.length - 1) % directions.length : -1
  if (next < 0) return
  event.preventDefault()
  selectDirection(directions[next]!.label)
  const group = (event.currentTarget as HTMLElement).parentElement
  group?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus()
}

async function closeGrowthMenu() {
  const selector = targetMenu.value ? '.rv24-target' : filterMenu.value ? '.rv24-filter' : ''
  targetMenu.value = false
  filterMenu.value = false
  await nextTick()
  if (selector) document.querySelector<HTMLButtonElement>(selector)?.focus()
}

// 这些判断与记录仅用于复现页交互；切换场景时恢复原图初态，不写入业务数据。
watch(() => props.scene, () => {
  feedbackDecisions.value = ['有异议', '接受', '接受']
  feedbackMenu.value = null
  selectedObservation.value = 0
  reviewTab.value = '回答与反馈'
  improvement.value = '下一次说明恢复判据及\n验证步骤'
  addedToToday.value = false
  judgment.value = '未处理'
  judgmentBasis.value = '说明了恢复判据，但未说明重试上限'
  confidence.value = '有些模糊'
  editingFragment.value = null
  fragments.value = [
    { title: '问题', text: '缓存删除失败后如何恢复？' },
    { title: '我的回答片段', text: '先查删除结果，再补偿......' },
    { title: '面试官反馈（回忆）', text: '似乎追问了重试上限' },
  ]
  direction.value = '技术深度'
  evidenceEnabled.value = { memory: true, answer: true, practice: true, observation: true }
  hiddenSuggestions.value = []
  prioritySuggestions.value = []
  targetMenu.value = false
  filterMenu.value = false
})
</script>

<template>
  <section v-if="scene === 21" class="rv-scene rv21" aria-label="复盘" @keydown.esc="feedbackMenu = null">
    <div class="rv-heading">
      <h1>复盘</h1>
      <p class="rv-subheading"><RIcon name="calendar-days" variant="review" :size="24" />提交于 11.10 14:32<span>·</span>独立专项</p>
    </div>
    <div class="rv-actions rv21-actions">
      <button class="r-btn rv21-knowledge" @click="emit('navigate', 10)"><RIcon name="book-open" variant="review" :size="27" />沉淀知识</button>
      <button class="r-btn r-primary rv21-practice" @click="emit('navigate', 17)"><RIcon name="play" variant="review" :size="25" />开始独立复练</button>
    </div>
    <nav class="rv21-tabs" aria-label="复盘视图">
      <button v-for="(tab, index) in ['回答与反馈', '新旧对照', '来源']" :key="tab" :class="{ selected: reviewTab === tab }" :aria-current="reviewTab === tab ? 'page' : undefined" @click="switchReviewTab(tab)">
        <RIcon :name="['message-square', 'git-compare', 'file-text'][index]!" variant="review" :size="27" />{{ tab }}
      </button>
    </nav>

    <template v-if="reviewTab === '回答与反馈'">
      <article class="r-panel rv-panel rv21-original">
        <h2><RIcon name="file-text" variant="review" :size="31" />原答</h2>
        <button class="rv21-quote-tag" aria-label="原文 02，定位观察 01" @click="selectedObservation = 0">原文 02</button>
        <div class="rv21-answer">
          <p>在删除缓存时，<span class="rv21-highlight">如果出现缓存未命中</span><span class="rv21-highlight-comma">，</span><br>会直接返回，不需要额外处理。</p>
          <p>为了保证数据一致性，我考虑在删除<br>缓存前先检查数据是否存在，如果不<br>存在则跳过删除操作。</p>
        </div>
      </article>
      <svg class="rv21-evidence-link" width="62" height="30" viewBox="0 0 62 30" aria-hidden="true">
        <path d="M8 15H54" fill="none" stroke="#d78e59" stroke-width="2" />
        <circle cx="8" cy="15" r="4" fill="#dc9662" />
        <circle cx="54" cy="15" r="5.5" fill="#dc9662" />
      </svg>
      <section class="r-panel rv-panel rv21-observations" aria-label="逐项观察">
        <h2><RIcon name="eye" variant="review" :size="32" />逐项观察</h2>
        <article v-for="index in 3" :key="index" class="rv21-observation" :class="[{ selected: selectedObservation === index - 1 }, `observation-${index}`]">
          <button class="rv21-observation-number" :aria-label="`选中观察 ${index}`" :aria-pressed="selectedObservation === index - 1" @click="selectedObservation = index - 1">{{ index }}</button>
          <button class="rv21-observation-copy" @click="selectedObservation = index - 1">
            <strong v-if="index === 1">条件是否讲清 /<br>区分已知条件与待核实条件</strong>
            <strong v-else-if="index === 2">恢复判据是否明确</strong>
            <strong v-else>职责边界 /<br>区分负责与参与</strong>
            <span>{{ ['引用 原文 02', '原文未提及恢复验证', '引用 原文 03'][index - 1] }}</span>
          </button>
          <button class="rv21-decision" :class="{ disputed: feedbackDecisions[index - 1] === '有异议', undecided: feedbackDecisions[index - 1] === '暂不判断' }" :aria-label="`观察 ${index}：${feedbackDecisions[index - 1]}`" :aria-expanded="feedbackMenu === index - 1" @click="feedbackMenu = feedbackMenu === index - 1 ? null : index - 1">{{ feedbackDecisions[index - 1] }}</button>
          <div v-if="feedbackMenu === index - 1" class="rv-feedback-menu" role="group" :aria-label="`观察 ${index} 的反馈决定`">
            <button v-for="decision in (['接受', '有异议', '暂不判断'] as const)" :key="decision" @click="decideFeedback(index - 1, decision)">{{ decision }}</button>
          </div>
        </article>
      </section>
      <aside class="r-panel rv-panel rv21-improvement">
        <h2><RIcon name="lightbulb" variant="review" :size="31" />改进动作</h2>
        <textarea v-model="improvement" class="rv21-improvement-input" aria-label="改进动作" spellcheck="false" />
        <p class="rv21-source-label">来源</p>
        <div class="rv21-source-tags">
          <button v-for="index in 2" :key="index" :aria-pressed="selectedObservation === index - 1" @click="selectedObservation = index - 1">观察 {{ String(index).padStart(2, '0') }}</button>
        </div>
        <button class="r-btn r-primary rv21-add" :aria-pressed="addedToToday" @click="addToday"><RIcon :name="addedToToday ? 'check' : 'plus'" :size="27" />{{ addedToToday ? '已加入今日' : '加入今日' }}</button>
      </aside>
    </template>
    <article v-else class="r-panel rv-panel rv21-source-view">
      <h2><RIcon name="file-text" :size="30" />来源</h2>
      <p>缓存删除失败以后 / 第 2 次提交</p>
      <p class="r-muted">提交于 11.10 14:32 · 独立专项</p>
      <div class="r-rule" />
      <button class="r-btn" @click="reviewTab = '回答与反馈'"><RIcon name="arrow-left" />返回回答与反馈</button>
    </article>
    <footer class="rv21-note"><RIcon name="info" :size="28" /><span>AI 候选 · 未审标准不构成技术判错。</span></footer>
  </section>

  <section v-else-if="scene === 22" class="rv-scene rv22" aria-label="新旧对照">
    <div class="rv-heading">
      <h1>新旧对照</h1>
      <p class="rv-subheading"><RIcon name="file-text" variant="compare" :size="27" />同一来源动作 · 题干未变</p>
    </div>
    <button class="r-btn rv22-source" @click="emit('navigate', 21)"><RIcon name="external-link" :size="23" />查看固定来源</button>
    <article class="r-panel rv-panel rv22-old rv22-answer-panel">
      <h2 class="rv22-panel-heading"><span class="rv22-history-icons"><RIcon name="history" :size="18" /><RIcon name="history" variant="compare" :size="25" /></span>上次回答 · 11.08</h2>
      <h3 class="rv22-answer-heading">我的回答</h3>
      <p class="rv22-answer-intro">可以通过重试机制来恢复连接，具体方案:</p>
      <ol class="rv22-answer-list">
        <li>监听连接异常，触发重试逻辑；</li>
        <li>使用指数退避策略，避免频繁重试；</li>
        <li>设置最大重试次数，防止无限重试。</li>
      </ol>
      <div class="rv22-answer-rule" />
      <h3 class="rv22-detail-heading"><RIcon name="lightbulb" variant="compare" :size="25" />思路要点</h3>
      <div class="rv22-keywords">
        <button v-for="keyword in ['重试机制', '退避策略', '最大次数']" :key="keyword" @click="emit('notify', keyword)">{{ keyword }}</button>
      </div>
    </article>
    <section class="r-panel rv-panel rv22-conditions" aria-label="条件变化">
      <h2 class="rv22-panel-heading"><RIcon name="sliders-horizontal" variant="compare" :size="27" />条件变化</h2>
      <dl>
        <div><dt>题干</dt><dd>未变</dd></div>
        <div><dt>辅助</dt><dd>已看参考 →<br>未开启</dd></div>
        <div><dt>用时</dt><dd>12 → 9 分钟</dd></div>
        <div><dt>参考暴露</dt><dd>有 → 无</dd></div>
      </dl>
    </section>
    <article class="r-panel rv-panel rv22-new rv22-answer-panel">
      <h2 class="rv22-panel-heading"><RIcon name="refresh-cw" variant="compare" :size="29" />本次复练 · 11.11</h2>
      <h3 class="rv22-answer-heading">我的回答</h3>
      <p class="rv22-answer-intro">可以通过重试机制来恢复连接:</p>
      <ol class="rv22-answer-list">
        <li><span class="rv22-underlined">监听异常并触发重试，建议使用指数<br>退避；</span></li>
        <li>设置合理的最大重试次数，避免无限重试。</li>
      </ol>
      <div class="rv22-answer-rule" />
      <h3 class="rv22-detail-heading"><RIcon name="pencil" variant="compare" :size="25" />主要差异</h3>
      <p class="rv22-difference">补充了退避策略，但未说明重试上限。</p>
    </article>
    <section class="r-panel rv-panel rv22-judgment" aria-label="我的判断">
      <h2><RIcon name="scale" variant="compare" :size="29" />我的判断</h2>
      <div class="rv22-judgment-options" role="radiogroup" aria-label="处理情况">
        <button v-for="option in judgments" :key="option" role="radio" :aria-checked="judgment === option" :tabindex="judgment === option ? 0 : -1" :class="{ selected: judgment === option }" @click="judgment = option" @keydown="judgmentKeydown">
          <RIcon v-if="option === '已处理'" name="check" :size="23" />
          <span v-else class="rv22-radio" :class="{ checked: judgment === option }" />
          {{ option }}
        </button>
      </div>
      <label class="rv22-basis"><strong>依据:</strong><input v-model="judgmentBasis" aria-label="判断依据" spellcheck="false"></label>
      <button class="r-btn r-primary rv22-save" @click="emit('notify', '判断已在本次页面内保留；持久保存待确认')">保存判断</button>
    </section>
  </section>

  <section v-else-if="scene === 23" class="rv-scene rv23" aria-label="面后回忆">
    <div class="rv-heading">
      <h1>面后回忆</h1>
      <p class="rv-subheading"><span class="rv23-company" aria-hidden="true"><svg viewBox="0 0 30 30"><path d="M7 9v12M12 13v8M17 7v14M22 11v10" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" /><path d="m6 19 6-4 5 2 6-7" fill="none" stroke="#89d4ed" stroke-width="2" /></svg></span>字节跳动 · 技术一面 · 11月12日</p>
    </div>
    <div class="rv-actions rv23-actions">
      <button class="r-btn rv23-draft" @click="emit('notify', '草稿仅在本次页面内保留；持久保存待确认')"><RIcon name="file-text" variant="recollection" :size="26" />保存草稿</button>
      <button class="r-btn r-primary rv23-complete" @click="editingFragment = null; emit('notify', '本次页面已结束编辑；记录提交与持久保存待确认')"><RIcon name="check" :size="29" />完成记录</button>
    </div>
    <section class="r-panel rv-panel rv23-confidence" aria-label="自信程度">
      <h2>自信程度</h2>
      <div class="rv23-confidence-options" role="radiogroup" aria-label="回忆自信程度">
        <button v-for="option in confidenceOptions" :key="option" role="radio" :aria-checked="confidence === option" :tabindex="confidence === option ? 0 : -1" :class="{ selected: confidence === option }" @click="confidence = option" @keydown="confidenceKeydown"><span v-if="confidence === option" class="rv23-confidence-check"><RIcon name="check" :size="17" /></span>{{ option }}</button>
      </div>
    </section>
    <article class="r-panel rv-panel rv23-record">
      <section v-for="(fragment, index) in fragments" :key="fragment.title" class="rv23-fragment" :class="{ editing: editingFragment === index }">
        <span class="rv23-fragment-number">{{ String(index + 1).padStart(2, '0') }}</span>
        <div class="rv23-fragment-content">
          <h3>{{ fragment.title }}</h3>
          <textarea v-if="editingFragment === index" v-model="fragment.text" :aria-label="fragment.title" rows="1" spellcheck="false" @keydown.ctrl.enter="editingFragment = null" @keydown.esc="editingFragment = null" />
          <p v-else @click="editFragment(index)">{{ fragment.text }}</p>
        </div>
        <button class="rv23-add-fragment" :aria-label="`${editingFragment === index ? '完成' : '编辑'}${fragment.title}片段`" :aria-expanded="editingFragment === index" @click="editFragment(index)"><RIcon :name="editingFragment === index ? 'check' : 'plus'" :size="23" />{{ editingFragment === index ? '完成片段' : '添加片段' }}</button>
      </section>
      <p class="rv23-warning"><RIcon name="info" :size="28" />模糊处保留，不补写确定结论</p>
      <p class="rv23-record-source"><RIcon name="file-text" variant="recollection" :size="27" />来源：<span>回忆记录 · 非逐字实录</span></p>
    </article>
    <aside class="r-panel rv-panel rv23-create">
      <h2>从这段回忆创建</h2>
      <button v-for="suggestion in recallSuggestions" :key="suggestion.title" class="rv23-create-item" @click="openRecallSuggestion(suggestion.target, suggestion.title)">
        <span class="rv23-create-icon" :class="suggestion.tone"><RIcon :name="suggestion.icon" :size="35" /></span>
        <span class="rv23-create-copy"><strong>{{ suggestion.title }}</strong><span>{{ suggestion.text }}</span></span>
      </button>
      <div class="rv23-create-meta">
        <p><RIcon name="file-text" variant="recollection" :size="27" /><span>来源：</span><span>目标：字节跳动 · 技术一面</span></p>
        <div class="rv23-tags"><RIcon name="file-text" variant="recollection" :size="27" /><span>标签：</span><button class="amber" @click="emit('notify', '回忆身份：回忆记录 · 非逐字实录')">回忆身份</button><button class="cyan" @click="emit('notify', '条件不完整：仅标识原记录条件，不改变回忆自信程度')">条件不完整</button></div>
      </div>
    </aside>
  </section>

  <section v-else-if="scene === 24" class="rv-scene rv24" aria-label="成长" @keydown.esc="closeGrowthMenu">
    <div class="rv-heading">
      <h1>成长</h1>
      <p class="rv-subheading"><span class="rv24-shield"><RIcon name="check" :size="15" /></span>证据目录 · 按当前目标筛选</p>
    </div>
    <div class="rv24-actions">
      <button class="rv24-target" aria-controls="rv24-target-menu" :aria-expanded="targetMenu" @click="targetMenu = !targetMenu; filterMenu = false"><RIcon name="target" variant="growth" :size="29" /><span>Java 后端 · 技术一面</span><RIcon name="chevron-down" :size="22" /></button>
      <button class="rv24-filter" aria-controls="rv24-filter-menu" :aria-expanded="filterMenu" @click="filterMenu = !filterMenu; targetMenu = false"><RIcon name="filter" :size="26" />筛选</button>
      <div v-if="targetMenu" id="rv24-target-menu" class="rv24-target-menu"><button @click="targetMenu = false; emit('notify', '固定参考目标：Java 后端 · 技术一面；其他目标待确认')"><RIcon name="check" :size="20" />Java 后端 · 技术一面</button></div>
      <fieldset v-if="filterMenu" id="rv24-filter-menu" class="rv24-filter-menu">
        <legend>证据类型</legend>
        <label v-for="kind in evidenceTypes" :key="kind.key"><input v-model="evidenceEnabled[kind.key]" class="r-checkbox" type="checkbox">{{ kind.label }}</label>
      </fieldset>
    </div>
    <div class="rv24-heading-rule" />
    <aside class="r-panel rv-panel rv24-directions">
      <h2>目标方向</h2>
      <nav role="radiogroup" aria-label="目标方向">
        <button v-for="item in directions" :key="item.label" role="radio" :class="{ selected: direction === item.label }" :aria-checked="direction === item.label" :tabindex="direction === item.label ? 0 : -1" @click="selectDirection(item.label)" @keydown="directionKeydown"><RIcon :name="item.icon" variant="growth" :size="25" /><span>{{ item.label }}</span><RIcon name="chevron-right" :size="21" /></button>
      </nav>
      <div class="rv24-direction-rule" />
      <fieldset class="rv24-evidence-filters">
        <legend>证据类型</legend>
        <label v-for="kind in evidenceTypes" :key="kind.key"><input v-model="evidenceEnabled[kind.key]" class="r-checkbox" type="checkbox">{{ kind.label }}</label>
      </fieldset>
    </aside>
    <section class="r-panel rv-panel rv24-evidence" aria-label="要求与证据">
      <h2>要求与证据</h2>
      <div class="rv24-evidence-table-wrap">
        <table class="rv24-evidence-table">
          <colgroup><col class="rv24-requirement-col"><col v-for="kind in visibleEvidence" :key="kind.key"></colgroup>
          <thead>
            <tr><th aria-label="要求" /><th v-for="kind in visibleEvidence" :key="kind.key" scope="col"><RIcon :name="kind.icon" variant="growth" :size="31" /><span>{{ kind.heading }}</span></th></tr>
          </thead>
          <tbody>
            <tr v-for="row in evidenceRows" :key="row.title">
              <th scope="row">{{ row.title }}</th>
              <td v-for="kind in visibleEvidence" :key="kind.key">
                <button v-if="row.dates[kind.key]" class="rv24-evidence-date" :aria-label="`${row.title}，${kind.heading}，${row.dates[kind.key]}`" @click="emit('navigate', kind.scene)"><span class="rv24-dot" :class="kind.key === 'answer' || row.blue?.includes(kind.key) ? 'blue' : 'green'" />{{ row.dates[kind.key] }}</button>
                <span v-else class="rv24-no-evidence" aria-label="无记录">- -- -</span>
              </td>
            </tr>
          </tbody>
          <tfoot aria-hidden="true"><tr><td /><td v-for="kind in visibleEvidence" :key="kind.key" /></tr></tfoot>
        </table>
      </div>
    </section>
    <aside class="r-panel rv-panel rv24-suggestions" aria-label="下一步建议">
      <h2><RIcon name="lightbulb" variant="growth" :size="28" />下一步建议</h2>
      <template v-for="(suggestion, index) in suggestions" :key="suggestion.title">
        <article v-if="!hiddenSuggestions.includes(index)" class="rv24-suggestion" :class="{ prioritized: prioritySuggestions.includes(index) }">
          <h3><span class="rv24-dot" :class="suggestion.tone" />{{ suggestion.title }}</h3>
          <p class="rv24-suggestion-source">{{ suggestion.source }}</p>
          <p class="rv24-suggestion-text">{{ suggestion.text }}</p>
          <div class="rv24-suggestion-actions">
            <button @click="hiddenSuggestions = [...hiddenSuggestions, index]">忽略</button>
            <button class="priority" :aria-pressed="prioritySuggestions.includes(index)" @click="togglePriority(index)">{{ prioritySuggestions.includes(index) ? '已标记优先' : '调整优先级' }}</button>
          </div>
        </article>
      </template>
      <button v-if="hiddenSuggestions.length" class="rv24-restore" @click="hiddenSuggestions = []">恢复已隐藏建议</button>
    </aside>
    <footer class="rv24-note"><span class="rv24-note-icon"><RIcon name="info" :size="21" /></span><strong>证据可追溯</strong><span>· 不输出能力总分</span></footer>
  </section>
</template>

<style scoped>
.rv-scene {
  position: absolute;
  inset: 0;
  color: #f3eee7;
  font-size: 22px;
}
.rv-heading { position: absolute; }
.rv-heading h1 { font-weight: 650; }
.rv-subheading { display: flex; align-items: center; gap: 12px; color: #a8a29a; white-space: nowrap; }
.rv-subheading > .r-icon { color: #9c9388; }
.rv-actions { position: absolute; display: flex; align-items: center; gap: 18px; }
.rv-panel { background: linear-gradient(119deg, #24201ba3, #191612b3 74%); border-color: #373129; }
.rv-panel h2 { display: flex; align-items: center; gap: 13px; }
.rv-panel h2 .r-icon { color: #ede5da; }
.rv21 .rv-heading { left: 30px; top: 118px; }
.rv21 .rv-heading h1 { font-size: 49px; }
.rv21 .rv-subheading { margin-top: 7px; font-size: 22px; gap: 10px; }
.rv21-actions { top: 143px; right: 25px; gap: 18px; }
.rv21-actions .r-btn { height: 63px; border-radius: 21px; font-size: 23px; font-weight: 500; padding: 12px 23px !important; }
.rv21-knowledge { width: 172px; background: linear-gradient(120deg, #2d2822, #211d18) !important; border-color: #3e352d !important; }
.rv21-practice { width: 204px; font-size: 22px !important; }
.rv21 .rv21-practice, .rv21 .rv21-add { background: linear-gradient(130deg, #dca074, #d99b6e 50%, #a66b45) !important; }
.rv21-practice .r-icon { fill: #1b1108; }
.rv21-tabs { position: absolute; top: 246px; left: 28px; right: 26px; height: 64px; display: flex; align-items: stretch; gap: 10px; border-bottom: 1px solid #ffffff08; }
.rv21-tabs button { display: flex; align-items: center; gap: 12px; padding: 0 22px; font-size: 25px; color: #aaa39b; position: relative; white-space: nowrap; }
.rv21-tabs button:first-child { width: 214px; padding: 0 11px 0 30px; flex: none; }
.rv21-tabs button:nth-child(2) { width: 177px; padding: 0 10px 0 20px; flex: none; }
.rv21-tabs button:nth-child(3) { padding-inline: 17px; }
.rv21-tabs button .r-icon { color: #8f877d; }
.rv21-tabs button.selected { background: linear-gradient(105deg, #241c138c, #211c1644); border-radius: 17px 17px 0 0; color: #f6f0e8; font-weight: 600; }
.rv21-tabs button.selected .r-icon { fill: #c99f7c; color: #c99f7c; }
.rv21-tabs button.selected::after { content: ""; position: absolute; height: 2px; left: 1px; right: 1px; bottom: 0; border-radius: 50%; background: #da9a67; }
.rv21-original { position: absolute; left: 27px; top: 331px; width: 441px; height: 564px; padding: 23px 24px; border-radius: 17px; }
.rv21-original h2 { font-size: 28px; gap: 8px; }
.rv21-quote-tag { position: absolute; top: 49px; right: 22px; height: 46px; min-width: 104px; border-radius: 11px; background: linear-gradient(110deg, #59412b88, #48382c99) !important; font-size: 22px; }
.rv21-quote-tag::before { content: ""; position: absolute; top: 45px; left: 47px; border: 8px solid transparent; border-top: 11px solid #60442e; }
.rv21-quote-tag::after { content: ""; position: absolute; top: 56px; left: 50px; width: 8px; height: 8px; border-radius: 50%; background: #d99765; }
.rv21-answer { margin-top: 47px; font-size: 24px; color: #e7dfd1; }
.rv21-answer p { line-height: 1.72; white-space: nowrap; }
.rv21-answer p + p { margin-top: 31px; }
.rv21-highlight { color: #e6a779; text-decoration: underline; text-decoration-color: #cf8447; text-decoration-thickness: 1px; text-underline-offset: 8px; }
.rv21-highlight-comma { color: #e6a779; }
.rv21-evidence-link { position: absolute; z-index: 2; left: 440px; top: 446px; overflow: visible; pointer-events: none; }
.rv21-observations { position: absolute; top: 331px; left: 487px; width: 485px; height: 564px; border-radius: 17px; overflow: visible; padding-top: 23px; }
.rv21-observations > h2 { padding-left: 23px; font-size: 28px; }
.rv21-observation { position: absolute; left: 0; right: 0; border: 1px solid #38302955; border-radius: 19px; background: linear-gradient(105deg, #2a241da3, #24201b5c); }
.rv21-observation.selected { border-color: #8763459c; background: linear-gradient(105deg, #3e30238c, #2c251c8c); }
.rv21-observation.observation-1 { top: 91px; height: 151px; }
.rv21-observation.observation-2 { top: 251px; height: 113px; }
.rv21-observation.observation-3 { top: 362px; height: 200px; border-bottom-color: transparent; }
.rv21-observation-number { position: absolute; top: 21px; left: 23px; width: 36px; height: 36px; border-radius: 50%; background: linear-gradient(140deg, #aa7a54, #5e4634) !important; font-size: 23px; }
.rv21-observation-copy { display: block; position: absolute; top: 22px; left: 80px; text-align: left; }
.rv21-observation-copy strong { display: block; font-weight: 500; font-size: 23px; line-height: 1.53; }
.rv21-observation-copy > span { display: block; color: #a49a8f; font-size: 21px; line-height: 1.65; margin-top: 5px; }
.rv21-observation.observation-2 .rv21-observation-copy { top: 20px; left: 86px; }
.rv21-observation.observation-2 .rv21-observation-number { top: 20px; }
.rv21-observation.observation-3 .rv21-observation-copy { top: 21px; left: 86px; }
.rv21-decision { position: absolute; z-index: 3; top: 19px; right: 21px; min-width: 76px; height: 42px; padding: 0 15px !important; border-radius: 24px; font-size: 23px; background: linear-gradient(130deg, #67977e, #395949) !important; }
.rv21-decision.disputed { color: #241208; background: linear-gradient(120deg, #ce8b66, #ad6044) !important; }
.rv21-decision.undecided { color: #ded2bd; background: #5c5040 !important; font-size: 19px; }
.rv-feedback-menu { position: absolute; z-index: 8; top: 67px; right: 19px; width: 132px; padding: 7px; background: #2b231c; border: 1px solid #806144; border-radius: 12px; }
.rv-feedback-menu button { display: block; width: 100%; padding: 6px !important; border-radius: 7px; font-size: 20px; }
.rv-feedback-menu button:hover { background: #725036; }
.rv21-improvement { position: absolute; top: 331px; left: 990px; width: 299px; height: 564px; border-radius: 17px; padding: 23px 22px; background: linear-gradient(125deg, #2a251ec9, #1b1712bb 80%); }
.rv21-improvement h2 { font-size: 28px; gap: 12px; }
.rv21-improvement-input { display: block; position: absolute; top: 104px; left: 22px; width: 254px; height: 114px; padding: 14px 13px; resize: none; background: #ffffff02; border: 1px solid #5e4b3a8c; border-radius: 13px; color: #f5eee4; font-size: 22px; line-height: 1.6; overflow: hidden; }
.rv21-source-label { position: absolute; left: 22px; top: 242px; color: #c5bdb2; font-size: 22px; }
.rv21-source-tags { position: absolute; left: 22px; top: 286px; display: flex; gap: 9px; }
.rv21-source-tags button { padding: 6px 16px !important; background: #4b3b2c66 !important; border-radius: 25px; font-size: 21px; }
.rv21-add { position: absolute; left: 22px; bottom: 63px; width: 254px; height: 63px; border-radius: 16px; font-size: 25px; font-weight: 500; }
.rv21-note { position: absolute; top: 923px; left: 27px; right: 25px; height: 63px; display: flex; align-items: center; gap: 17px; padding: 0 25px; color: #999186; border: 1px solid #4a3d2e9c; border-radius: 16px; background: linear-gradient(100deg, #29231ba0, #17130f50); font-size: 20px; }
.rv21-source-view { position: absolute; left: 27px; right: 25px; top: 331px; height: 564px; padding: 26px; }
.rv21-source-view p { margin-top: 22px; }
.rv22 .rv-heading { left: 28px; top: 121px; }
.rv22 .rv-heading h1 { font-size: 49px; font-weight: 600; }
.rv22 .rv-subheading { margin-top: 6px; font-size: 25px; color: #c7c2ba; gap: 15px; }
.rv22-source { position: absolute; top: 141px; right: 35px; height: 56px; width: 204px; min-height: 0; padding: 8px 17px !important; font-size: 22px; border-radius: 30px; border-color: #3c352d !important; background: linear-gradient(130deg, #211e1988, #16141188) !important; }
.rv22-old, .rv22-new, .rv22-conditions { position: absolute; top: 259px; height: 525px; border-radius: 22px; padding: 23px 26px; }
.rv22-old { left: 28px; width: 442px; }
.rv22-conditions { left: 490px; width: 288px; padding-left: 26px; padding-right: 24px; }
.rv22-new { left: 798px; width: 481px; }
.rv22-old, .rv22-conditions, .rv22-judgment { background: linear-gradient(115deg, #1d1b17, #181612 72%, #1d1a16); }
.rv22-new h2, .rv22-new h3 { font-weight: 500; }
.rv22-old .rv22-panel-heading, .rv22-old .rv22-answer-heading, .rv22-conditions .rv22-panel-heading { font-weight: 500; }
.rv22-panel-heading { height: 54px; border-bottom: 1px solid #64544366; font-size: 24px; gap: 15px !important; white-space: nowrap; align-items: flex-start !important; padding-top: 1px; }
.rv22-panel-heading > .r-icon { margin-top: 4px; color: #e3bc90 !important; }
.rv22-history-icons { display: flex; align-items: center; gap: 5px; height: 35px; color: #d0ab83; }
.rv22-history-icons .r-icon:first-child { color: #8a8071; }
.rv22-answer-heading { font-size: 24px; margin-top: 18px !important; margin-bottom: 18px !important; }
.rv22-answer-intro { font-size: 22px; font-weight: 500; white-space: nowrap; line-height: 1.6 !important; }
.rv22-answer-list { font-size: 22px; margin: 14px 0 0; padding-left: 32px; line-height: 2.04; }
.rv22-old .rv22-answer-list { margin-top: 10px; }
.rv22-answer-list li { padding-left: 1px; white-space: nowrap; }
.rv22-answer-list li::marker { font-family: Arial, sans-serif; font-size: 24px; }
.rv22-answer-rule { position: absolute; top: 358px; left: 26px; right: 26px; height: 1px; background: #5d4d3e55; }
.rv22-detail-heading { position: absolute; top: 386px; left: 27px; display: flex; align-items: center; gap: 11px; font-size: 23px; color: #bcb7ae; font-weight: 500; }
.rv22-detail-heading .r-icon { color: #e0b98e; }
.rv22-keywords { position: absolute; left: 26px; top: 433px; display: flex; gap: 9px; }
.rv22-keywords button { font-size: 21px; padding: 8px 16px !important; border-radius: 25px; background: linear-gradient(110deg, #ffffff17, #ffffff0f) !important; }
.rv22-conditions dl { margin: 16px 0 0; }
.rv22-conditions dl > div { display: flex; align-items: flex-start; gap: 9px; margin-bottom: 18px; }
.rv22-conditions dt { width: 91px; flex: none; font-size: 22px; font-weight: 500; line-height: 50px; white-space: nowrap; }
.rv22-conditions dd { margin: 0; padding: 9px 14px; border-radius: 16px; background: #ffffff0c; font-size: 22px; line-height: 1.5; white-space: nowrap; }
.rv22-conditions dl > div:nth-child(2) { margin-bottom: 18px; }
.rv22-conditions dl > div:nth-child(3) dd { padding-left: 13px; padding-right: 13px; }
.rv22-conditions dl > div:nth-child(4) { margin-top: 1px; }
.rv22-conditions dl > div:nth-child(4) dd { min-width: 137px; }
.rv22-new .rv22-answer-list { margin-top: 14px; line-height: 1.6; }
.rv22-new .rv22-answer-list li + li { margin-top: 10px; }
.rv22-new .rv22-answer-intro { font-size: 22px; }
.rv22-underlined { text-decoration: underline; text-decoration-color: #b07a3f; text-underline-offset: 9px; text-decoration-thickness: 1px; }
.rv22-difference { position: absolute; left: 26px; right: 22px; top: 433px; padding: 9px 0 9px 26px; font-size: 22px; white-space: nowrap; line-height: 1.6 !important; }
.rv22-difference::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 7px; background: #bf680e; border-radius: 5px; }
.rv22-judgment { position: absolute; left: 28px; top: 812px; width: 1251px; height: 156px; border-radius: 23px; }
.rv22-judgment h2 { position: absolute; left: 26px; top: 24px; font-size: 23px; gap: 13px; font-weight: 500; }
.rv22-judgment h2 .r-icon { color: #edba83; }
.rv22-judgment::after { content: ""; position: absolute; top: 28px; bottom: 28px; left: 624px; width: 1px; background: #584b3a66; }
.rv22-judgment-options { position: absolute; left: 18px; top: 73px; display: flex; gap: 10px; }
.rv22-judgment-options > button { height: 54px; display: flex; align-items: center; justify-content: center; gap: 10px; min-width: 132px; border: 1px solid #ffffff03; border-radius: 30px; padding: 0 17px !important; color: #ded9d0; font-size: 21px; background: #ffffff02; }
.rv22-judgment-options > button:nth-child(2) { min-width: 148px; }
.rv22-judgment-options > button:nth-child(4) { min-width: 150px; }
.rv22-judgment-options > button.selected { border-color: #dfa05b; background: linear-gradient(100deg, #76502b70, #3f2a1680); color: #f5d9b2; font-weight: 600; }
.rv22-judgment-options .r-icon { color: #979887; }
.rv22-radio { width: 18px; height: 18px; border-radius: 50%; border: 2px solid #625e52; }
.rv22-radio.checked { background: #e3a56d; border-color: #edb985; }
.rv22-basis { display: flex; align-items: center; gap: 10px; position: absolute; top: 59px; left: 644px; width: 434px; height: 69px; border: 1px solid #554b3e; border-radius: 15px; padding: 0 17px; font-size: 19px; background: #ffffff02; white-space: nowrap; }
.rv22-basis strong { flex: none; font-weight: 500; }
.rv22-basis input { min-width: 0; flex: 1; background: transparent; padding: 0; border: 0; color: #d7d0c6; font-size: 19px; outline-offset: 6px; }
.rv22-save { position: absolute; top: 59px; right: 18px; width: 112px; height: 59px; min-height: 0; border-radius: 21px; padding: 0 10px !important; font-size: 20px; }
.rv22-save::before { content: ""; position: absolute; left: -22px; top: 0; bottom: -10px; width: 1px; background: #4a3d2c66; }
.rv23 .rv-heading { left: 32px; top: 117px; }
.rv23 .rv-heading h1 { font-size: 54px; line-height: 1.35; font-weight: 500; }
.rv23 .rv-subheading { margin-top: 5px; font-size: 23px; gap: 16px; }
.rv23-company { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid #5b70ac77; border-radius: 9px; color: #eef9ff; background: linear-gradient(130deg, #5371b1, #343d6b); }
.rv23-company svg { width: 27px; height: 27px; }
.rv23-actions { top: 132px; right: 31px; gap: 16px; }
.rv23-actions .r-btn { height: 62px; font-size: 24px; border-radius: 35px; padding: 11px 22px !important; }
.rv23-draft { width: 181px; border-color: #52473d !important; background: linear-gradient(110deg, #24201c77, #211b1677) !important; }
.rv23 .rv23-complete.r-primary { width: 174px; box-shadow: inset 0 0 9px #ffdeb56b; color: #fff8f0; border-color: #f4d0a3 !important; background: linear-gradient(115deg, #a2714a, #b2825b 50%, #edbb91) !important; font-weight: 500; text-shadow: 0 1px 1px #754b2b; }
.rv23-confidence { position: absolute; left: 32px; top: 266px; width: 725px; height: 175px; border-radius: 19px; padding: 28px 27px; background: linear-gradient(115deg, #1c1816, #191614); }
.rv23-confidence h2 { font-size: 25px; }
.rv23-confidence-options { display: flex; position: absolute; top: 80px; left: 27px; right: 27px; height: 64px; border-radius: 38px; border: 1px solid #4b4139a6; background: #ffffff06; }
.rv23-confidence-options button { display: flex; align-items: center; justify-content: center; gap: 13px; flex: 1; border-radius: 36px; font-size: 24px; font-weight: 500; }
.rv23-confidence-options button.selected { border: 1px solid #efb979; background: radial-gradient(ellipse at 52% 100%, #da9a5d8c, transparent), linear-gradient(120deg, #805632, #a5764b9c); box-shadow: inset 0 0 13px #fbd4a83b, 0 0 13px #d1904c26; color: #fff6e7; font-weight: 500; }
.rv23-confidence-check { width: 25px; height: 25px; border-radius: 50%; background: #f4c493; color: #59402a; display: inline-flex; align-items: center; justify-content: center; }
.rv23-record { position: absolute; left: 32px; top: 440px; width: 725px; height: 514px; border-radius: 22px; padding: 10px 27px 0; background: linear-gradient(115deg, #1c1816, #191614); }
.rv23-fragment { position: relative; height: 104px; display: flex; gap: 30px; padding: 17px 10px 13px; border-radius: 15px; background: linear-gradient(110deg, #211c18, #1d1917); margin-bottom: 10px; }
.rv23-fragment-number { width: 38px; flex: none; color: #7f7973; font-family: Arial, sans-serif; font-size: 29px; line-height: 1.6; }
.rv23-fragment-content { min-width: 0; padding-top: 1px; }
.rv23-fragment h3 { font-size: 24px; font-weight: 500; line-height: 1.5; }
.rv23-fragment-content p { font-size: 23px; color: #f0e9df; white-space: nowrap; line-height: 1.6; cursor: text; }
.rv23-fragment:nth-child(2) .rv23-fragment-content p { padding-left: 12px; }
.rv23-add-fragment { display: flex; align-items: center; justify-content: center; gap: 4px; position: absolute; top: 18px; right: 14px; height: 46px; width: 139px; border: 1px solid #807468 !important; border-radius: 27px; background: linear-gradient(120deg, #2e27208c, #221d188c) !important; font-size: 21px; }
.rv23-fragment-content textarea { width: 410px; height: 39px; padding: 0 4px; resize: none; border: 1px solid #a57b54; border-radius: 4px; background: #17120b88; font-size: 22px; line-height: 1.6; }
.rv23-warning { display: flex; align-items: center; gap: 12px; height: 66px; padding: 0 16px; margin-top: 1px !important; border-radius: 14px; background: #29211b; color: #ffd19a; font-size: 20px; }
.rv23-record-source { display: flex; align-items: center; gap: 13px; height: 79px; margin-top: 12px !important; border-top: 1px solid #75614c40; color: #d2c9be; font-size: 21px; }
.rv23-record-source .r-icon { color: #9a9288; }
.rv23-record-source > span { margin-left: 0; }
.rv23-create { position: absolute; left: 768px; top: 266px; width: 493px; height: 688px; border-radius: 20px; padding: 28px 26px 0; background: linear-gradient(115deg, #1c1816, #191614); }
.rv23-create h2 { font-size: 25px; margin-bottom: 17px; }
.rv23-create-item { display: flex; gap: 20px; width: 100%; height: 136px; border: 1px solid #4d413533 !important; border-radius: 16px; padding: 16px 16px !important; background: linear-gradient(110deg, #24201c, #201b18) !important; text-align: left; margin-bottom: 10px; }
.rv23-create-icon { display: inline-flex; align-items: center; justify-content: center; flex: none; width: 64px; height: 65px; margin-top: 3px; border: 1px solid; border-radius: 50%; }
.rv23-create-icon.amber { color: #e89a57; border-color: #8c552b; background: radial-gradient(ellipse, #77431e30, #53361f20); }
.rv23-create-icon.cyan { color: #a8e8ee; border-color: #387078; background: radial-gradient(ellipse, #28566155, #263e4355); }
.rv23-create-icon.green { color: #89deb0; border-color: #385d3b; background: #2842303b; }
.rv23-create-copy { min-width: 0; }
.rv23-create-copy strong { display: block; font-size: 24px; line-height: 1.4; margin-bottom: 4px; font-weight: 500; }
.rv23-create-copy > span { display: block; white-space: pre-line; font-size: 20px; line-height: 1.55; color: #d5cdc3; }
.rv23-create-meta { border-top: 1px solid #79614b40; margin-top: 27px; padding-top: 22px; color: #d0c6bb; }
.rv23-create-meta > p { display: flex; align-items: center; gap: 13px; font-size: 20px; white-space: nowrap; }
.rv23-create-meta .r-icon { color: #958b7f; flex: none; }
.rv23-tags { display: flex; align-items: center; gap: 13px; margin-top: 16px; font-size: 20px; white-space: nowrap; }
.rv23-tags button { padding: 4px 15px !important; border: 1px solid; border-radius: 25px; font-size: 19px; }
.rv23-tags button.amber { border-color: #a16937; color: #edb46e; background: #89542520; }
.rv23-tags button.cyan { border-color: #4f8e88; color: #9eddd7; background: #397a7418; }
.rv24 .rv-heading { left: 36px; top: 124px; }
.rv24 .rv-panel { background: linear-gradient(110deg, #171512, #12110f); border-color: #302a23; }
.rv24 .rv-heading h1 { font-size: 54px; }
.rv24 .rv-subheading { margin-top: 8px; font-size: 23px; color: #d5c9b7; gap: 11px; }
.rv24-shield { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 25px; color: #fff8eb; background: linear-gradient(120deg, #9d5526, #bf8752); clip-path: polygon(50% 0, 95% 14%, 95% 57%, 83% 82%, 50% 100%, 17% 82%, 5% 57%, 5% 14%); }
.rv24-actions { position: absolute; right: 31px; top: 141px; display: flex; gap: 18px; z-index: 8; }
.rv24-target, .rv24-filter { display: flex; align-items: center; justify-content: center; border: 1px solid #65544366 !important; border-radius: 34px; height: 62px; background: #211c1640 !important; font-size: 22px; }
.rv24-target { width: 312px; gap: 13px; }
.rv24-target > .r-icon:last-child { margin-left: 4px; }
.rv24-filter { width: 131px; gap: 11px; }
.rv24-target-menu, .rv24-filter-menu { position: absolute; top: 71px; border: 1px solid #6e5941; border-radius: 14px; padding: 15px; margin: 0; background: #241d15; }
.rv24-target-menu { left: 0; width: 312px; }
.rv24-target-menu button { display: flex; align-items: center; gap: 8px; font-size: 20px; }
.rv24-filter-menu { right: 0; width: 230px; }
.rv24-filter-menu legend { padding: 0 7px; font-size: 20px; }
.rv24-filter-menu label { display: flex; align-items: center; gap: 13px; margin-bottom: 12px; font-size: 20px; }
.rv24-heading-rule { position: absolute; left: 23px; right: 34px; top: 254px; height: 1px; background: #6e513424; }
.rv24-directions { position: absolute; top: 270px; left: 23px; width: 226px; height: 617px; padding: 22px 11px; border-radius: 17px; }
.rv24-directions h2 { padding-left: 11px; font-size: 21px; margin-bottom: 18px; }
.rv24-directions nav { display: flex; flex-direction: column; gap: 2px; }
.rv24-directions nav button { display: flex; align-items: center; gap: 11px; height: 59px; padding: 0 11px !important; border-radius: 14px; font-size: 21px; text-align: left; color: #d3c7b7; }
.rv24-directions nav button > span { flex: 1; white-space: nowrap; }
.rv24-directions nav button > .r-icon:last-child { color: #877867; }
.rv24-directions nav button:last-child > .r-icon:last-child { visibility: hidden; }
.rv24-directions nav button.selected { color: #fff2df; background: linear-gradient(110deg, #8b5c2b70, #6a482a99); }
.rv24-directions nav button.selected > .r-icon { color: #e6c19a; }
.rv24-direction-rule { height: 1px; margin: 22px 11px 23px; background: #7354342a; }
.rv24-evidence-filters { border: 0; padding: 0 11px; margin: 0; }
.rv24-evidence-filters legend { font-size: 21px; font-weight: 600; padding: 0; margin-bottom: 14px; }
.rv24-evidence-filters label { display: flex; align-items: center; gap: 13px; font-size: 20px; margin-bottom: 15px; line-height: 29px; color: #e6ded3; }
.rv24-evidence-filters .r-checkbox { width: 22px; height: 23px; border-radius: 5px; }
.rv24-evidence-filters .r-checkbox:checked { border-color: #c48a57; background: linear-gradient(130deg, #c88e56, #9b552c); }
.rv24-evidence { position: absolute; left: 264px; top: 270px; width: 672px; height: 617px; border-radius: 17px; }
.rv24-evidence > h2 { height: 73px; padding: 17px 25px 19px; border-bottom: 1px solid #6d563c36; font-size: 25px; }
.rv24-evidence-table-wrap { margin: 0 15px; height: 544px; }
.rv24-evidence-table { width: 100%; height: 544px; table-layout: fixed; border-collapse: collapse; text-align: center; }
.rv24-requirement-col { width: 148px; }
.rv24-evidence-table th, .rv24-evidence-table td { padding: 0; border-right: 1px solid #6b503328; border-bottom: 1px solid #6b503328; vertical-align: middle; }
.rv24-evidence-table th:last-child, .rv24-evidence-table td:last-child { border-right: 0; }
.rv24-evidence-table thead tr { height: 107px; }
.rv24-evidence-table thead th { font-size: 20px; font-weight: 500; }
.rv24-evidence-table thead th .r-icon { color: #e2d8c8; display: block; margin: 0 auto 10px; }
.rv24-evidence-table thead th > span { white-space: nowrap; }
.rv24-evidence-table tbody tr { height: 85px; }
.rv24-evidence-table tbody th { font-size: 20px; font-weight: 600; text-align: left; padding-left: 10px; white-space: nowrap; }
.rv24-evidence-table tfoot tr { height: 96px; }
.rv24-evidence-table tfoot td { border-bottom: 0; }
.rv24-evidence-date { display: inline-flex; align-items: center; justify-content: center; gap: 7px; height: 48px; font-size: 20px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.rv24-dot { display: inline-block; width: 12px; height: 12px; flex: none; border-radius: 50%; background: #62b99d; }
.rv24-dot.green { background: linear-gradient(130deg, #83d0ae, #459b84); }
.rv24-dot.blue { background: linear-gradient(130deg, #60a9e5, #2d83d0); }
.rv24-dot.amber { background: linear-gradient(130deg, #dfa973, #ac7449); }
.rv24-no-evidence { font-size: 23px; color: #777062; white-space: nowrap; word-spacing: 3px; }
.rv24 .rv24-suggestions { position: absolute; top: 270px; left: 951px; width: 353px; height: 617px; padding: 20px 13px 0; border-radius: 17px; background: linear-gradient(115deg, #191611, #12110e); }
.rv24-suggestions > h2 { font-size: 22px; gap: 8px; margin: 0 6px 26px; }
.rv24-suggestion { position: relative; height: 180px; padding: 11px 14px 8px; margin-bottom: 8px; border: 1px solid #353029; border-radius: 17px; background: linear-gradient(110deg, #171512, #121210); }
.rv24-suggestion h3 { display: flex; align-items: center; gap: 11px; font-size: 20px; font-weight: 600; line-height: 1.5; white-space: nowrap; }
.rv24-suggestion h3 .rv24-dot { width: 15px; height: 16px; }
.rv24-suggestion-source { padding-left: 26px; color: #d5c1a7; margin-top: 5px !important; font-family: "Microsoft YaHei", sans-serif; font-size: 15px; line-height: 1.6 !important; }
.rv24-suggestion-text { padding-left: 26px; color: #c2b7a7; margin-top: 5px !important; white-space: pre-line; font-family: "Microsoft YaHei", sans-serif; font-size: 15px; line-height: 1.5 !important; }
.rv24-suggestion-actions { position: absolute; display: flex; justify-content: flex-end; gap: 10px; bottom: 7px; right: 7px; }
.rv24-suggestion-actions button { height: 37px; padding: 0 19px !important; border: 1px solid #78634d55 !important; border-radius: 21px; background: linear-gradient(110deg, #24211c, #1c1a16) !important; font-size: 16px; font-weight: 500; }
.rv24-suggestion-actions button.priority { padding: 0 14px !important; color: #fff0d9; border-color: #a6713f !important; background: linear-gradient(110deg, #584b37, #664d31) !important; box-shadow: inset 0 0 4px #f8c69366; }
.rv24-suggestion:nth-of-type(1) .priority, .rv24-suggestion:nth-of-type(3) .priority { background: linear-gradient(110deg, #6a4b31, #87532d) !important; }
.rv24-suggestion:nth-of-type(2) { height: 171px; }
.rv24-suggestion:nth-of-type(3) { height: 173px; margin-bottom: 0; }
.rv24-suggestion.prioritized { border-color: #bc894b; }
.rv24-restore { display: block; width: 100%; padding: 10px !important; color: #d6b38a; font-size: 17px; }
.rv24-note { position: absolute; top: 923px; left: 23px; right: 24px; height: 65px; display: flex; align-items: center; gap: 8px; padding: 0 24px; border: 1px solid #55422e88; border-radius: 18px; background: linear-gradient(110deg, #2a21198c, #20180f77); font-size: 20px; color: #c8b8a1; }
.rv24-note-icon { display: inline-flex; align-items: center; justify-content: center; color: #65513d; background: #c8b99f; border-radius: 50%; width: 22px; height: 22px; margin-right: 6px; }
.rv24-note-icon .r-icon { width: 18px; height: 18px; }
.rv24-note strong { color: #eee5d7; font-weight: 600; }
.rv24 h2, .rv24 h3, .rv24-evidence-filters legend, .rv24-evidence-table tbody th, .rv24-note strong { font-weight: 500; -webkit-text-stroke: .15px currentColor; }
</style>
