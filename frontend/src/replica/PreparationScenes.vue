<script setup lang="ts">
import { computed, ref } from 'vue'
import RIcon from './RIcon.vue'
defineProps<{ scene: number }>()
const emit = defineEmits<{ navigate: [id: number]; notify: [text: string] }>()
const target = ref(0)
const project = ref(0)
const version = ref(3)
const compare = ref(true)
const editing = ref(false)
const projectExpanded = ref(true)
const expressionExpanded = ref(true)
const file = ref(0)
const pdfPage = ref(8)
const zoom = ref(100)
const filter = ref('全部 12')
const selectedDay = ref(12)
const month = ref(11)
const year = ref(2026)
function weekday(day: number) {
  const offsets = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4]
  const y = year.value - (month.value < 3 ? 1 : 0)
  return (y + Math.floor(y / 4) - Math.floor(y / 100) + Math.floor(y / 400) + offsets[month.value - 1]! + day) % 7
}
const referenceMonth = computed(() => year.value === 2026 && month.value === 11)
const calendarDays = computed(() => {
  // 参考初态只显示11月9日起的四行；其他月份按公历排布，不迁移参考面试数据。
  if (referenceMonth.value) return Array.from({ length: 22 }, (_, i) => i + 9)
  const leap = year.value % 4 === 0 && (year.value % 100 !== 0 || year.value % 400 === 0)
  const length = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month.value - 1]!
  return [...Array<number>((weekday(1) + 6) % 7).fill(0), ...Array.from({ length }, (_, i) => i + 1)]
})
function shiftMonth(delta: number) {
  month.value += delta
  if (month.value === 0) { month.value = 12; year.value-- }
  if (month.value === 13) { month.value = 1; year.value++ }
  selectedDay.value = referenceMonth.value ? 12 : 1
}
function today() { year.value = 2026; month.value = 11; selectedDay.value = 11 }
const tasks = ref([false, false, false])
const facts = [
  { name: '发奖链路', title: '发奖超时，结果未知', icon: 'network' },
  { name: '缓存治理', title: '缓存更新失败后的排查', icon: 'layers' },
  { name: '对账任务', title: '对账任务与恢复判据', icon: 'calendar-check' },
]
const files = [
  ['营销交易平台-项目复盘.pdf', '24 页 · 已解析', 'pdf'],
  ['JD-字节跳动.txt', '2.1 MB · 11.06', 'text'],
  ['系统设计笔记.md', '856 KB · 11.05', 'text'],
  ['面试记录-1112.md', '412 KB · 11.03', 'text'],
  ['分布式系统设计.pdf', '解析部分失败', 'warning'],
]
const pending = [['项目追问演练', '目标要求', '20 分钟', '中', 'code'], ['系统设计取舍', '无记录', '30 分钟', '高', 'box'], ['缓存一致性补证', '复盘', '15 分钟', '低', 'database']]
const targetTitles = ['字节跳动 · Java 后端开发', '美团 · 后端开发', '通用 · Java 基础']
function prepTab(index: number) { emit('navigate', [3, 6, 7, 8][index]!) }
</script>
<template>
  <section v-if="scene === 3" class="prep-target">
    <h1 class="p-title">准备</h1>
    <div class="p-actions"><button class="r-btn" @click="emit('notify', '当前没有新的变更')"><RIcon name="eye" />预览变更</button><button class="r-btn r-primary" @click="emit('notify', '新建目标的业务流程待确认')">新建目标</button></div>
    <div class="r-tabs prep-tabs"><button v-for="(tab, i) in ['目标', '材料', '任务', '安排']" :key="tab" :class="{ 'r-active': i === 0 }" @click="prepTab(i)">{{ tab }}</button></div>
    <aside class="r-panel target-list">
      <div class="r-between"><h3>目标列表</h3><button class="r-icon-btn plus-small" title="新建目标" @click="emit('notify', '新建目标的业务流程待确认')"><RIcon name="plus" /></button></div>
      <button v-for="(title, i) in targetTitles" :key="title" class="target-item" :class="{ selected: target === i }" :aria-pressed="target === i" @click="target = i"><i :style="{ background: ['#439ef3', '#7ccba6', '#76726b'][i] }" /><strong>{{ title }}</strong><small class="r-row"><RIcon name="calendar" variant="target" :size="20" />{{ ['技术一面 · 12 天后', '待安排', '长期目标'][i] }}</small></button>
    </aside>
    <section class="target-center">
      <div class="target-name r-between"><h2 class="r-row"><span v-if="target === 0" class="company-bars"><i /><i /><i /><i /></span>{{ targetTitles[target] }}</h2><div class="r-segmented"><button class="r-active" aria-pressed="true">当前目标</button><button @click="emit('notify', '历史目标内容待确认')">历史目标</button></div></div>
      <!-- 原图只定义首个目标的详情，切换目标不能沿用其证据、路线或预算。 -->
      <template v-if="target === 0">
        <div class="target-meta r-row r-muted"><span class="r-row"><RIcon name="calendar" variant="target" />JD 11.06</span><span class="r-row"><RIcon name="folder" variant="target" />项目 2</span><span class="r-row"><RIcon name="file-text" variant="target" />题目 18</span></div>
        <div class="r-panel requirements"><h2>要求与考察项</h2><table class="r-table"><colgroup><col /><col /><col /><col /></colgroup><thead><tr><th>要求</th><th>关联证据</th><th>状态</th><th>最近</th></tr></thead><tbody><tr v-for="(row, i) in [['分析异常与恢复', '2 条', '有证据', '11.08'], ['讲清本人职责', '1 条', '待补', '11.06'], ['缓存一致性', '3 条', '有证据', '11.10'], ['系统设计取舍', '0 条', '无记录', '—']]" :key="row[0]" tabindex="0" role="link" @click="emit('navigate', 24)" @keydown.enter="emit('navigate', 24)"><td>{{ row[0] }}</td><td>{{ row[1] }}</td><td><span class="r-pill" :class="i === 1 ? 'amber' : i === 3 ? '' : 'green'">● {{ row[2] }}</span></td><td>{{ row[3] }}</td></tr></tbody></table></div>
        <div class="r-panel recent-materials"><h3>近期材料</h3><button v-for="(row, i) in [['字节跳动一面复盘笔记', '11.08 · 2.3k 字'], ['分布式缓存设计要点', '11.07 · 1.8k 字'], ['高并发系统设计思路', '11.05 · 3.1k 字']]" :key="row[0]" class="r-between" @click="emit('navigate', i === 0 ? 23 : 6)"><span class="r-row"><RIcon name="file-text" variant="target" />{{ row[0] }}</span><span class="r-row r-muted">{{ row[1] }}<RIcon name="chevron-right" :size="18" /></span></button></div>
      </template>
      <p v-else class="target-unconfirmed r-muted" role="status">该目标的要求、关联证据、准备路线与预算待确认。</p>
    </section>
    <aside v-if="target === 0" class="r-panel target-route"><h2>准备路线</h2><div v-for="(row, i) in [['11.06', '核实项目事实', '已完成'], ['11.08', '项目追问', '已完成'], ['11.10', '系统设计演练', '进行中'], ['11.12', '技术一面', '']]" :key="row[0]" class="timeline-item" :class="{ current: i === 2, future: i === 3 }"><span class="timeline-dot"><RIcon v-if="i < 2" name="check" :size="22" /></span><div><strong>{{ row[0] }}&nbsp; {{ row[1] }}</strong><p class="r-muted">{{ row[2] }}</p></div></div></aside>
    <aside v-if="target === 0" class="r-panel target-budget"><h3>本周预算</h3><div class="r-between"><span><b>4</b> / 6 小时</span><button class="r-btn" @click="emit('navigate', 7)"><RIcon name="calendar" variant="target" :size="20" />调整安排</button></div></aside>
  </section>

  <section v-else-if="scene === 4" class="prep-facts">
    <h1 class="p-title">项目事实</h1>
    <div class="p-actions"><button class="r-btn" @click="emit('notify', '当前事实 v2 · 2026.11.06')"><RIcon name="history" variant="facts" />历史</button><button class="r-btn r-primary" @click="emit('notify', '新增事实的业务流程待确认')">新增事实<RIcon name="plus" /></button></div>
    <div class="r-tabs facts-tabs"><button class="r-active">事实</button><button @click="emit('navigate', 5)">表达素材</button><button @click="emit('navigate', 6)">来源</button></div>
    <aside class="r-panel project-tree"><h3>项目目录</h3><button class="r-row tree-root" :aria-expanded="projectExpanded" @click="projectExpanded = !projectExpanded"><RIcon :name="projectExpanded ? 'chevron-down' : 'chevron-right'" :size="20" /><RIcon name="building" variant="facts" />营销交易平台</button><template v-if="projectExpanded"><button v-for="(item, i) in facts" :key="item.name" class="project-leaf r-row" :class="{ selected: project === i }" @click="project = i"><RIcon :name="item.icon" variant="facts" />{{ item.name }}</button></template><button class="r-row tree-root" @click="emit('navigate', 6)"><RIcon name="chevron-right" :size="20" /><RIcon name="folder" variant="facts" />简历导入</button><button class="r-row tree-root" @click="emit('notify', '手工项目暂无展开内容')"><RIcon name="chevron-right" :size="20" /><RIcon name="folder" variant="facts" />手工项目</button></aside>
    <article class="r-panel fact-body"><div class="r-between fact-heading"><h3 class="r-row"><span class="fact-document"><RIcon name="file-text" variant="facts" :size="31" /></span>{{ facts[project]!.title }}</h3><div class="r-row"><span class="r-pill amber"><i class="fact-status-dot" aria-hidden="true" />待核实 1 项</span><span class="r-pill"><RIcon name="users" variant="facts" :size="22" />参与排查</span></div></div><div class="r-rule" /><dl><dt>场景</dt><dd>大促期间，发奖链路出现延迟</dd><dt>问题</dt><dd>部分用户未能及时收到奖励，系统显示<br>发奖超时，结果未知</dd><dt>我的动作</dt><dd>参与排查，定位到是下游服务响应较慢，<br>已推动优化降级策略</dd><dt>结果与证据</dt><dd>问题已修复，补发任务已完成，用户可正常<br>领取奖励；关联日志与监控截图已留存</dd></dl><button class="fact-source r-between" @click="emit('navigate', 6)"><span class="r-row"><RIcon name="file-text" variant="facts" />来源：2026.11.06 项目复盘</span><RIcon name="chevron-right" /></button></article>
    <div class="fact-links"><button class="r-panel r-row" @click="emit('navigate', 5)"><RIcon name="link" variant="facts" class="r-amber" />关联表达 2<RIcon name="chevron-right" class="r-muted" /></button><button class="r-panel r-row" @click="emit('navigate', 19)"><RIcon name="graduation-cap" variant="facts" class="r-amber" />关联训练 3<RIcon name="chevron-right" class="r-muted" /></button></div>
    <aside class="r-panel expressions"><h2>表达素材</h2><button v-for="(row, i) in [['项目介绍 · v3', 'file-text'], ['STAR 表达 · v2', 'file-text'], ['技术取舍 · v1', 'file-text']]" :key="row[0]" class="expression-row r-row" :class="{ selected: i === 0 }" @click="emit('navigate', 5)"><RIcon :name="row[1]!" variant="facts" :size="27" :class="i === 0 ? 'r-amber' : 'cyan'" />{{ row[0] }}<span v-if="i === 0" class="r-pill amber">当前</span><RIcon name="chevron-right" /></button><div class="r-rule" /><div class="expression-preview"><h3>项目介绍 · v3 <span class="r-pill amber">当前</span></h3><p>营销交易平台致力于为用户提供高效、安全的交易与奖励服务...</p><div class="r-row"><span class="r-pill teal">依据 事实 v2</span><span class="r-pill">未外发</span></div></div><div class="r-row expression-actions"><button class="r-btn" @click="emit('navigate', 5)"><RIcon name="pencil" />编辑</button><button class="r-btn r-primary" @click="emit('navigate', 5)"><RIcon name="plus" />新建版本</button></div><p class="r-row r-muted fact-foot"><RIcon name="info" variant="facts" />事实与表达分开保存</p></aside>
  </section>

  <section v-else-if="scene === 5" class="prep-expression">
    <h1 class="p-title">表达素材</h1><div class="p-actions"><button class="r-btn" @click="emit('notify', '新建表达的业务流程待确认')">新建空白表达</button><button class="r-btn r-primary" @click="emit('notify', '生成表达需要另行确认，不调用模型')">从事实生成</button></div>
    <div class="r-tabs expression-tabs"><button @click="emit('navigate', 4)">事实</button><button class="r-active">表达素材</button><button @click="emit('navigate', 6)">来源</button></div>
    <div class="r-panel expression-editor"><aside class="expression-directory"><button class="r-between" @click="emit('notify', '自我介绍 2 条')"><strong>自我介绍 <small>2</small></strong><RIcon name="chevron-right" /></button><button class="r-between" :aria-expanded="expressionExpanded" @click="expressionExpanded = !expressionExpanded"><strong>项目介绍 <small>3</small></strong><RIcon :name="expressionExpanded ? 'chevron-down' : 'chevron-right'" /></button><template v-if="expressionExpanded"><button v-for="v in [3, 2, 1]" :key="v" class="version-link" :class="{ selected: version === v }" @click="version = v">项目介绍 · v{{ v }}</button></template><button class="r-between" @click="emit('notify', 'STAR 表达 2 条')"><strong>STAR 表达 <small>2</small></strong><RIcon name="chevron-right" /></button><button class="r-between" @click="emit('notify', '技术取舍 2 条')"><strong>技术取舍 <small>2</small></strong><RIcon name="chevron-right" /></button></aside><article class="expression-text"><h2>项目介绍 v{{ version }}</h2><p class="r-row expression-byline"><RIcon name="file-text" :size="22" />依据 事实 v2 <span class="r-muted">/</span><span class="r-pill r-muted">未外发</span></p><div class="r-rule" /><div class="expression-prose" :contenteditable="editing"><p>我在字节跳动负责支付奖励链路的开发与优化，主要解决<br>高并发下的奖励发放<span class="unreadable" aria-label="原图此处文字待确认" />和一致性问题。</p><p v-if="version === 3">在实际项目中，我们通过异步化 + 事件驱动的方式，将奖励<br>发放链路从同步调用改为异步处理，显著提升了系统的可扩展<br>性，同时保证了数据一致性。</p><p v-else>我们采用了同步调用的方式来发放奖励。</p><p>最终，业务侧的奖励发放成功率提升了 99.99%，单次发放的<br>延迟从 300ms 降低到 80ms。</p></div></article><aside class="expression-outline"><div v-for="row in [['场景', '高并发活动'], ['问题', '发放延迟高'], ['动作', '引入异步架构'], ['结果', '成功率 99.99%']]" :key="row[0]"><span>◆</span><strong>{{ row[0] }}</strong><p>{{ row[1] }}</p></div></aside></div>
    <aside class="r-panel version-history"><h2>版本记录</h2><button v-for="v in [3, 2, 1]" :key="v" class="history-item" :class="{ selected: version === v }" @click="version = v"><strong><i />v{{ v }} <small v-if="v === 3" class="r-amber">当前版本</small></strong><p>{{ ['2025-05-12 16:32', '2025-05-13 21:17', '2025-05-14 10:24'][v - 1] }}</p><p class="r-muted">{{ ['初稿：项目核心内容', '更新：补充技术细节', '更新：优化了表述，补充数据'][v - 1] }}</p></button><div class="r-rule" /><button class="r-between history-count" @click="emit('navigate', 4)"><span class="r-row"><RIcon name="link" :size="20" />关联事实</span>2</button><button class="r-between history-count" @click="emit('navigate', 19)"><span class="r-row"><RIcon name="play" :size="20" />来源训练</span>1</button><button class="r-btn r-primary" @click="emit('notify', '当前表达已保存到本次页面状态')">保存新版本</button><button class="r-btn" @click="compare = !compare">对比上一版</button></aside>
    <section class="r-panel version-diff"><button class="r-between diff-heading" @click="compare = !compare"><strong class="r-row"><RIcon name="scale" />版本对比：<span class="r-amber">v2 → v3</span></strong><span class="r-row r-muted">{{ compare ? '收起' : '展开' }}<RIcon :name="compare ? 'chevron-up' : 'chevron-down'" :size="20" /></span></button><div v-if="compare" class="diff-columns"><p class="removed"><span class="diff-symbol"><RIcon name="minus" :size="15" :stroke-width="2.5" /></span><span>我们采用了同步调用的方式来发放奖励。</span></p><p class="added"><span class="diff-symbol"><RIcon name="plus" :size="15" :stroke-width="2.5" /></span><span>我们通过异步化 + 事件驱动的方式，将奖励发放链路改为异步处理，<br>显著提升了系统的可扩展性。</span></p></div></section>
  </section>

  <section v-else-if="scene === 6" class="prep-materials">
    <h1 class="p-title">资料</h1><div class="p-actions"><button class="r-btn" @click="emit('notify', '导入流程待确认，当前仅展示参考资料')"><RIcon name="download" :size="21" />导入 Markdown</button><button class="r-btn r-primary" @click="emit('notify', '上传流程待确认，未发送文件')"><RIcon name="upload" :size="21" />上传资料</button></div>
    <aside class="r-panel file-library">
      <h3>文件库</h3>
      <label class="file-search r-row"><RIcon name="search" /><input aria-label="搜索文件" placeholder="搜索文件..." @input="filter = ($event.target as HTMLInputElement).value" /></label>
      <div class="file-filters"><button v-for="f in ['全部 12', 'PDF 4', '文本 6', '图片 2']" :key="f" :class="{ active: filter === f }" @click="filter = f">{{ f }}</button></div>
      <button v-for="(row, i) in files" :key="row[0]" v-show="filter.includes('全部') || filter === '' || (filter.startsWith('PDF') ? row[2] !== 'text' : filter.startsWith('文本') ? row[2] === 'text' : row[0]!.includes(filter))" class="file-item r-row" :class="{ selected: file === i }" @click="file = i">
        <span class="file-type" :class="row[2]"><RIcon :name="row[2] === 'warning' ? 'circle-alert' : row[2] === 'pdf' ? 'file-pdf' : 'file-lines'" variant="materials" :size="25" /></span>
        <span><strong>{{ row[0] }}</strong><small :class="row[2] === 'warning' ? 'r-amber' : 'r-muted'">{{ row[1] }}</small></span>
        <RIcon v-if="i === 0" name="more-vertical" :size="22" />
      </button>
    </aside>
    <section class="r-panel pdf-viewer"><div class="pdf-toolbar r-row"><button class="r-icon-btn" title="上一页" @click="pdfPage = Math.max(1, pdfPage - 1)"><RIcon name="chevron-left" /></button><span>第 {{ pdfPage }} / 24 页</span><button class="r-icon-btn" title="下一页" @click="pdfPage = Math.min(24, pdfPage + 1)"><RIcon name="chevron-right" /></button><div class="pdf-zoom r-row"><button title="缩小" @click="zoom = Math.max(50, zoom - 10)"><RIcon name="minus" /></button>{{ zoom }}%<button title="放大" @click="zoom = Math.min(150, zoom + 10)"><RIcon name="plus" /></button></div><button title="搜索文档" @click="emit('notify', '文档内检索流程待确认')"><RIcon name="search" /></button><button title="适应页面" @click="zoom = 100"><RIcon name="maximize" variant="materials" :size="26" /></button><button title="更多" @click="emit('notify', '当前文档第 8 / 24 页')"><RIcon name="more-vertical" /></button></div><aside class="pdf-thumbnails"><button v-for="n in [6, 7, 8, 9]" :key="n" :class="{ selected: pdfPage === n }" @click="pdfPage = n"><span class="mini-document" :class="{ 'mini-document-known': n === 8 }"><template v-if="n === 8"><span class="pdf-mini-page" aria-hidden="true">
<strong class="pdf-mini-title">3. 项目复盘：营销交易平台</strong>
<strong class="pdf-mini-section" data-part="background">3.1&nbsp; 项目背景</strong>
<span class="pdf-mini-paragraph" data-part="background">公司希望通过建设一套营销交易平台，提升商家与用户的<br>交易效率，并支持多种营销玩法。</span>
<strong class="pdf-mini-section" data-part="problem">3.2&nbsp; 问题</strong>
<span class="pdf-mini-list" data-part="problem"><span>高并发下订单创建成功率不稳定；</span><span>业务规则复杂，维护成本高。</span></span>
<strong class="pdf-mini-section" data-part="action">3.3&nbsp; 动作</strong>
<span class="pdf-mini-paragraph" data-part="action">我们采用了分布式架构，核心服务拆分为订单、支付、营<br>销三大模块，并引入了异步消息队列，提升系统解耦能力。</span>
<mark>上线后，日均交易量达到 120 万，较之前提升 60%。</mark>
<strong class="pdf-mini-section" data-part="result">3.4&nbsp; 结果</strong>
<span class="pdf-mini-list" data-part="result"><span>系统稳定性提升至 99.95%；</span><span>支持了 5 大营销活动，交易额同比增长 80%。</span></span>
<span class="pdf-mini-footer">8 / 24</span>
</span></template><template v-else><b /><i v-for="k in 12" :key="k" :style="{ width: `${[82, 90, 69, 88, 77][k % 5]}%` }" /></template></span>{{ n }}</button></aside><div class="pdf-page-wrap"><article class="pdf-paper" :style="{ transform: `scale(${zoom / 100})` }"><h2>3. 项目复盘：营销交易平台</h2><h3>3.1&nbsp; 项目背景</h3><p>公司希望通过建设一套营销交易平台，提升商家与用户的<br>交易效率，并支持多种营销玩法。</p><h3>3.2&nbsp; 问题</h3><ul><li>高并发下订单创建成功率不稳定；</li><li>业务规则复杂，维护成本高。</li></ul><h3>3.3&nbsp; 动作</h3><p>我们采用了分布式架构，核心服务拆分为订单、支付、营<br>销三大模块，并引入了异步消息队列，提升系统解耦能力。</p><mark>上线后，日均交易量达到 120 万，较之前提升 60%。</mark><h3>3.4&nbsp; 结果</h3><ul><li>系统稳定性提升至 99.95%；</li><li>支持了 5 大营销活动，交易额同比增长 80%。</li></ul><footer>{{ pdfPage }} / 24</footer></article></div></section>
    <aside class="r-panel parsing">
      <h3>解析结果</h3>
      <button class="parsing-status r-between" @click="editing = !editing"><span class="r-row r-success"><RIcon name="circle-check" variant="materials" :size="27" />已解析文字层</span><RIcon :name="editing ? 'chevron-down' : 'chevron-up'" :size="20" /></button>
      <div v-if="!editing" class="parsing-content"><section v-for="(row, i) in [['项目背景', '公司希望通过建设一套营销交易平台，提升商家与用户的交易效率，并支持多种营销玩法。'], ['问题', '高并发下订单创建成功率不稳定；\n业务规则复杂，维护成本高。'], ['动作', '我们采用了分布式架构，核心服务拆分为订单、支付、营销三大模块，并引入了异步消息队列，提升系统解耦能力。'], ['结果', '系统稳定性提升至 99.95%；\n支持了 5 大营销活动，交易额同比增长 80%。']]" :key="row[0]"><i /><strong>{{ row[0] }}</strong><ul v-if="i === 1 || i === 3"><li v-for="line in row[1]!.split('\n')" :key="line">{{ line }}</li></ul><p v-else>{{ row[1] }}<br v-if="i === 2" /><mark v-if="i === 2">上线后，日均交易量达到 120 万，较之前提升 60%。</mark></p></section></div>
      <div class="parsing-warning r-row"><RIcon name="circle-alert" variant="materials" :size="26" />1 个数字需要核实</div>
      <footer><p>上传于 11.06 · 2.4 MB</p><div class="r-row"><button class="r-btn r-primary" @click="emit('navigate', 4)">采用为项目事实</button><button class="r-btn" @click="emit('notify', '摘录已保留在本次页面状态')">添加摘录</button></div></footer>
    </aside>
  </section>

  <section v-else-if="scene === 7" class="prep-tasks">
    <h1 class="p-title">准备任务</h1><div class="p-actions"><button class="r-btn" @click="emit('notify', '建议生成需要另行确认，不调用模型')"><RIcon name="wand" variant="tasks" :size="27" />自动建议</button><button class="r-btn r-primary" @click="emit('notify', '新建任务流程待确认')">新建任务<RIcon name="plus" /></button></div><div class="r-segmented task-tabs"><button v-for="(tab, i) in ['目标', '材料', '任务', '安排']" :key="tab" :class="{ 'r-active': i === 2 }" :aria-current="i === 2 ? 'page' : undefined" @click="prepTab(i)">{{ tab }}</button></div>
    <div class="r-panel task-stats"><div class="r-row"><span class="stat-icon green"><RIcon name="clock" variant="tasks" :size="34" /></span><span class="r-muted">本周可用<br><strong>4 / 6<small> 小时</small></strong></span></div><div class="r-row"><span class="stat-icon amber"><RIcon name="list" variant="tasks" :size="35" /></span><span class="r-muted">待安排<br><strong>3</strong></span></div><div class="r-row"><span class="stat-icon green"><RIcon name="circle-check" variant="tasks" :size="35" /></span><span class="r-muted">已排<br><strong>5</strong></span></div></div>
    <section class="r-panel pending-tasks"><div class="r-between"><h2>待安排 <small>(3)</small></h2><button class="r-row" @click="emit('notify', '当前按优先级排序')">按优先级<RIcon name="chevron-down" :size="18" /></button></div><button v-for="(row, i) in pending" :key="row[0]" class="pending-task" :aria-pressed="tasks[i]" @click="tasks[i] = !tasks[i]"><span class="pending-icon"><RIcon :name="row[4]!" variant="tasks" :size="35" /></span><div><h3>{{ row[0] }}</h3><span class="r-pill" :class="i === 2 ? 'teal' : i === 0 ? 'amber' : ''">来源: {{ row[1] }}</span><p class="r-muted"><RIcon name="clock" :size="17" /> {{ row[2] }} │ <RIcon name="info" :size="17" /> 证据缺口: {{ row[3] }}</p></div><RIcon :name="tasks[i] ? 'check' : 'ellipsis'" variant="tasks" :size="21" class="r-muted" /></button></section>
    <section class="r-panel scheduled-tasks"><h2>已安排 <small>(3)</small></h2><div v-for="(row, i) in [['11.09', '事实核实', '复盘 · 20 分钟'], ['11.10', '系统设计演练', '目标要求 · 30 分钟'], ['11.12', '技术一面', '目标要求 · 45 分钟']]" :key="row[0]" class="scheduled-row"><span>{{ row[0] }}</span><i :class="`scheduled-${i}`" /><button @click="emit('navigate', i === 2 ? 8 : i === 1 ? 20 : 4)"><strong>{{ row[1] }}</strong><p><span :class="{ 'scheduled-source': i === 0 }">来源: {{ row[2]!.split(' · ')[0] }}</span> · {{ row[2]!.split(' · ')[1] }}</p><RIcon :name="['circle-check', 'circle', 'lock'][i]!" variant="tasks" :size="23" :class="['r-success', 'cyan', 'r-amber'][i]" /></button></div></section>
    <aside class="r-panel schedule-preview"><h2>排期预览</h2><h3>本周负载</h3><div class="week-bars"><div v-for="(day, i) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']" :key="day"><span><i :style="{ height: `${[72, 72, 68, 58, 22, 0, 0][i]}%` }" /></span><small>{{ day }}</small></div></div><div class="r-between"><h3>建议安排计划</h3><button title="编辑安排" @click="emit('navigate', 8)"><RIcon name="square-pen" /></button></div><div v-for="(row, i) in [['11.09', '事实核实'], ['11.10', '系统设计演练'], ['11.12', '技术一面']]" :key="row[0]" class="r-between preview-task"><span class="r-muted">{{ row[0] }}</span><span>{{ row[1] }}</span><RIcon :name="['check', 'circle', 'lock'][i]!" variant="tasks" :size="23" :class="['r-success', 'cyan', 'r-amber'][i]" /></div><p class="r-row r-muted preview-note"><RIcon name="info" :size="21" />改期只移动未开始且未锁定的任务</p><button class="r-btn r-primary" @click="emit('notify', '安排已保存到本次页面状态')">保存安排</button><button class="r-btn" @click="tasks = [false, false, false]">取消</button></aside>
  </section>

  <section v-else-if="scene === 8" class="prep-calendar">
    <h1 class="p-title">面试安排</h1><div class="p-actions"><button class="r-btn" @click="today"><RIcon name="calendar" variant="schedule" :size="28" />今天</button><button class="r-btn r-primary" @click="emit('notify', '新建面试安排流程待确认')"><RIcon name="plus" :size="30" />新建安排</button></div>
    <section class="r-panel calendar-panel"><div class="r-row calendar-title"><h2>{{ year }}年{{ month }}月</h2><button title="上个月" @click="shiftMonth(-1)"><RIcon name="chevron-left" :size="28" /></button><button title="下个月" @click="shiftMonth(1)"><RIcon name="chevron-right" :size="28" /></button></div><div class="calendar-weekdays"><span v-for="day in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']" :key="day">{{ day }}</span></div><div class="calendar-days" :class="{ 'calendar-other-month': !referenceMonth }"><template v-for="(day, index) in calendarDays" :key="index"><button v-if="day" :aria-label="`${year}年${month}月${day}日`" :aria-pressed="day === selectedDay" :class="{ selected: day === selectedDay, marked: referenceMonth && (day === 11 || day === 18) }" @click="selectedDay = day"><span>{{ day }}</span><small v-if="referenceMonth && day === 12">技术一面</small><small v-if="referenceMonth && day === 18" class="tentative">HR 面 · 待确认</small></button><span v-else aria-hidden="true" /></template></div></section>
    <section class="r-panel calendar-event"><h3>{{ month }}月{{ selectedDay }}日 {{ ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][weekday(selectedDay)] }}</h3><article v-if="referenceMonth && selectedDay === 12"><div><h2>Java 后端技术一面</h2><p class="r-row r-muted"><RIcon name="clock" variant="schedule" :size="23" />14:30 – 15:30<span class="event-divider" /><RIcon name="video" variant="schedule" :size="23" />线上 · 视频面试<span class="event-divider" /><RIcon name="square-pen" variant="schedule" :size="23" />准备清单 3 项</p></div><button class="r-btn" @click="emit('navigate', 3)">查看准备</button></article><p v-else class="r-muted">{{ referenceMonth && selectedDay === 18 ? 'HR 面 · 待确认；时间及安排详情待确认' : '该日期的安排详情待确认' }}</p></section>
    <aside class="r-panel reschedule"><h2 class="r-row"><RIcon name="info" variant="schedule" :size="30" />改期前预览</h2><p class="r-muted">只移动未开始且未锁定日期的准备任务</p><div class="r-rule" /><h3>关联任务</h3><button v-for="(row, i) in [['项目追问', 'file-text'], ['系统设计', 'box'], ['缓存一致性', 'database']]" :key="row[0]" class="reschedule-task r-row" :aria-pressed="tasks[i]" @click="tasks[i] = !tasks[i]"><RIcon :name="tasks[i] ? 'circle-check' : 'circle'" variant="schedule" :size="32" class="r-muted" /><RIcon :name="row[1]!" variant="schedule" :size="29" /><strong>{{ row[0] }}</strong><RIcon name="chevron-right" :size="23" class="r-faint" /></button><button class="r-btn adjust" @click="emit('navigate', 7)"><RIcon name="arrow-up-down" variant="schedule" />调整安排</button></aside>
    <aside class="r-panel calendar-reminder"><h2 class="r-row"><RIcon name="bell" variant="schedule" :size="30" />提醒</h2><p class="r-row r-muted"><span class="reminder-dot" />站内 · 提前 30 分钟</p><div class="r-rule" /><p class="r-row r-muted"><RIcon name="clock" variant="schedule" :size="27" />时区: Asia/Shanghai</p></aside>
  </section>
</template>
<style scoped>
.p-title { position: absolute; top: 117px; left: 30px; }
.p-actions { position: absolute; right: 31px; top: 135px; display: flex; align-items: center; gap: 20px; }
.prep-tabs { position: absolute; top: 199px; left: 32px; right: 30px; border-bottom: 1px solid #44372c; gap: 20px; height: 62px; }
.prep-tabs button { font-size: 26px; padding: 10px 18px 14px; }
.target-list { position: absolute; top: 284px; left: 15px; width: 295px; bottom: 46px; padding: 24px 12px; }
.target-list > .r-between { padding: 0 10px 15px; }
.plus-small { background: #ffffff0a !important; width: 36px; height: 36px; }
.target-item { position: relative; display: block; width: 100%; text-align: left; padding: 15px 17px 12px 20px !important; margin: 2px 0; min-height: 94px; border-radius: 14px; border: 1px solid transparent !important; }
.target-item.selected { background: linear-gradient(130deg, #8e5a3350, #5a382a66) !important; border-color: #a5744b99 !important; }
.target-item i { position: absolute; width: 6px; height: 35px; left: 0; top: 16px; border-radius: 6px; }
.target-item strong { font-size: 21px; font-weight: 600; white-space: nowrap; }
.target-item small { margin-top: 3px; font-size: 19px; gap: 7px; color: #c5b8aa; }
.target-center { position: absolute; left: 321px; right: 360px; top: 284px; bottom: 46px; background: #1a171396; border: 1px solid #27221d; border-radius: 20px; padding: 22px 19px 0; }
.target-name h2 { white-space: nowrap; font-size: 27px; gap: 15px; }
.target-name .r-segmented { font-size: 18px; gap: 0; padding: 0; margin-right: -3px; }
.target-name .r-segmented button { padding: 5px 15px; }
.company-bars { height: 42px; display: flex; align-items: end; gap: 4px; }
.company-bars i { width: 8px; height: 37px; border-radius: 3px; background: #4e62e6; transform: skewY(-20deg); }
.company-bars i:nth-child(2) { height: 23px; background: #2d63be; }.company-bars i:nth-child(3) { height: 29px; background: #63c8b6; }.company-bars i:nth-child(4) { height: 41px; background: #76e3cc; }
.target-meta { gap: 30px; margin: 24px 0 20px; }
.requirements { padding: 17px 21px 8px; }
.requirements h2 { font-size: 27px; }
.requirements .r-table { margin-top: 9px; }
.requirements th, .requirements td { padding: 8px 3px; font-size: 20px; }
.requirements tr { cursor: pointer; }
.requirements .r-pill { font-size: 18px; padding: 0 10px; }
.recent-materials { position: absolute; bottom: 0; left: 0; right: 0; padding: 16px 21px 18px; }
.recent-materials button { display: flex; width: 100%; padding: 9px 0 !important; font-size: 20px; border-bottom: 1px solid #ffffff07 !important; }
.recent-materials h3 { margin-bottom: 4px; }.recent-materials .r-muted { font-size: 19px; gap: 20px; }
.target-route { position: absolute; top: 284px; right: 23px; width: 330px; height: 495px; padding: 22px 29px; }
.target-route h2 { margin-bottom: 28px; }
.timeline-item { display: flex; gap: 21px; min-height: 105px; position: relative; }
.timeline-item:not(:last-child)::after { content: ""; width: 2px; height: 61px; top: 39px; left: 16px; position: absolute; background: #75bc94aa; }
.timeline-item.current::after { background: #6d655b; }.timeline-dot { margin-top: 1px; width: 33px; height: 33px; border-radius: 50%; background: #9cd7bd; color: #173e2c; display: grid; place-items: center; flex: none; }
.timeline-item.current .timeline-dot { border: 3px solid #edb17a; background: #edb17a; box-shadow: inset 0 0 0 5px #231a13; }
.timeline-item.future .timeline-dot { border: 3px solid #6c665f; background: none; }
.timeline-item strong { font-size: 21px; font-weight: 550; white-space: nowrap; }.timeline-item p { margin-top: 4px; }.timeline-item.current p { color: #e4a05c; }
.target-budget { position: absolute; right: 23px; bottom: 46px; width: 330px; height: 190px; padding: 19px 22px; }.target-budget b { font-size: 40px; }.target-budget .r-btn { min-height: 47px; font-size: 17px; padding: 7px 14px !important; border-radius: 25px; }
.prep-target .p-title { top: 119px; left: 34px; font-size: 58px; font-weight: 500; -webkit-text-stroke: .9px currentColor; transform: scaleX(.94); transform-origin: left center; }
.prep-target h2, .prep-target h3 { font-weight: 500; -webkit-text-stroke: .4px currentColor; }
.prep-target .p-actions { top: 135px; right: 34px; gap: 18px; }
.prep-target .p-actions .r-btn { height: 57px; font-size: 23px; font-weight: 500; border-radius: 28px; }
.prep-target .p-actions .r-btn:first-child { width: 169px; border-color: #59504755 !important; background: linear-gradient(120deg, #2d2925de, #221e1bd9) !important; }
.prep-target .p-actions .r-primary { width: 142px; background: linear-gradient(115deg, #ffd0a5, #ffba87 65%, #f2a56e) !important; box-shadow: inset 0 1px 6px #ffe6cba6, 0 0 22px #f5aa6b33; border-color: #e9af80 !important; color: #21150e; font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.prep-tabs { gap: 28px; border-color: #352d2477; }
.prep-tabs button { flex: none; width: 78px; padding: 10px 0 14px; font-weight: 400; }
.prep-tabs button:first-child { margin-right: 6px; }
.prep-tabs button.r-active { background: none; font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.prep-tabs button.r-active::after { left: 0; right: 0; background: #f9bd91; }
.prep-target .r-panel { background: linear-gradient(115deg, #211d19b8, #171411db 70%, #211b1766); border-color: #3b332b8a; }
.target-list > .r-between { padding-bottom: 17px; padding-right: 7px; }
.target-item strong, .target-item small { position: relative; top: -2px; font-weight: 400; }
.target-item.selected { background: linear-gradient(115deg, #805a3e94, #65472e80) !important; box-shadow: inset 0 0 10px #d5a27814; border-color: #a4774f99 !important; }
.target-center { padding-inline: 20px; background: linear-gradient(120deg, #1c1814ad, #15120fb5); border-color: #30281f52; }
.target-name h2 { gap: 18px; font-size: 27px; }
.target-name .r-segmented { margin-right: -3px; font-size: 18px; }
.target-name .r-segmented button { font-size: 18px; font-weight: 400; }
.target-name .r-segmented .r-active { background: linear-gradient(120deg, #825734b0, #503b29a6); box-shadow: inset 0 0 0 1px #d3945ebd, inset 0 1px 6px #d79b6140; }
.company-bars { width: 48px; gap: 4.5px; flex: none; }
.company-bars i { width: 8.5px; }
.target-meta { position: absolute; top: 91px; left: 20px; height: 37px; margin: 0; gap: 29px; font-size: 21px; font-weight: 400; color: #c8c1b8; }
.target-meta > span { gap: 11px; }
.requirements { position: absolute; left: -1px; top: 144px; width: 630px; height: 327px; padding: 17px 21px 8px; }
.requirements .r-table { table-layout: fixed; margin-top: 14px; }
.requirements col:nth-child(1) { width: 217px; }.requirements col:nth-child(2) { width: 149px; }.requirements col:nth-child(3) { width: 159px; }
.requirements th { height: 49px; font-size: 21px; }.requirements td { height: 47px; line-height: 32px; }
.requirements th,.requirements td { padding: 7px 3px; white-space: nowrap; }
.requirements td { padding-top: 6px; padding-bottom: 6px; }
.requirements td:nth-child(2), .requirements td:last-child { font-weight: 400; }
.requirements th:nth-child(3) { padding-left: 11px; }
.requirements .r-pill { line-height: 33px; padding: 0 10px; font-size: 19px; font-weight: 400; }
.requirements td:last-child { font-size: 20px; }
.requirements tr:last-child td { border-bottom-color: transparent; }
.recent-materials { left: -1px; right: auto; top: 480px; bottom: auto; width: 630px; height: 213px; padding: 16px 34px 18px 21px; }
.prep-target .recent-materials { background: linear-gradient(115deg, #1d1a17b8, #151311db 70%, #1b181566); }
.recent-materials h3 { position: relative; top: 1px; }
.recent-materials button { height: 47px; padding: 8px 0 !important; font-weight: 400; }
.recent-materials button > .r-row:first-child { gap: 22px; }
.recent-materials .r-muted { gap: 24px; color: #bbb4ac; font-size: 20px; font-weight: 400; }
.recent-materials button:last-child { border-bottom: 0 !important; }
.target-route { width: 329px; }
.target-route h2 { font-size: 26px; line-height: 36px; margin-bottom: 30px; }
.timeline-item { gap: 22px; min-height: 102px; }
.timeline-item:nth-of-type(2) { min-height: 112px; }
.timeline-item strong { font-size: 20px; font-weight: 400; }
.timeline-item.current strong { display: block; position: relative; top: -1px; font-size: 21px; font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.timeline-item.future strong { font-weight: 400; color: #c5bdb4; }
.timeline-item p { font-size: 20px; margin-top: 4px; }
.timeline-item:not(:last-child)::after { height: 56px; }
.timeline-item:nth-of-type(2)::after { height: 66px; }
.target-budget { width: 329px; }.target-budget h3 { line-height: 32px; }
.target-budget > div > span { font-weight: 400; }.target-budget b { font-weight: 500; }
.target-budget .r-btn { height: 50px; background: linear-gradient(120deg, #302a24dd, #28231ec7) !important; border-color: #51473b44 !important; }
.target-unconfirmed { margin-top: 36px !important; font-size: 20px; }
.prep-facts .p-title { top: 95px; left: 25px; }.prep-facts .p-actions { top: 139px; right: 26px; gap: 12px; }.prep-facts .r-btn { border-radius: 29px; font-size: 22px; }
.facts-tabs { position: absolute; top: 166px; left: 23px; right: 23px; height: 65px; gap: 20px; border-bottom: 1px solid #342c24; }.facts-tabs button { padding: 11px 23px; font-size: 25px; }
.project-tree { position: absolute; top: 246px; bottom: 71px; left: 22px; width: 246px; padding: 25px 13px; border-radius: 18px; }.project-tree h3 { padding-left: 6px; margin-bottom: 21px; }.tree-root { width: 100%; font-size: 21px; gap: 8px; padding: 5px 2px 14px !important; white-space: nowrap; text-align: left; }.tree-root:not(:first-of-type) { margin-top: 15px; }.project-leaf { margin-left: 0; width: 100%; height: 58px; padding-left: 53px !important; text-align: left; font-size: 21px; gap: 10px; border-radius: 12px; }.project-leaf.selected { color: #f2c69b; background: #88572f55 !important; box-shadow: inset 4px 0 0 #ecb078; }
.fact-body { position: absolute; top: 246px; left: 277px; width: 611px; height: 612px; padding: 50px 22px 18px; border-radius: 20px; }.fact-heading { gap: 8px; }.fact-heading h3 { font-size: 23px; white-space: nowrap; gap: 16px; }.fact-heading .r-row { gap: 8px; }.fact-heading .r-pill { padding: 9px 14px; font-size: 18px; }.fact-document { width: 41px; height: 50px; border-radius: 12px; color: #d5a374; background: #b28b6244; display: grid; place-items: center; }.fact-body .r-rule { margin: 33px 0 27px; }.fact-body dl { display: grid; grid-template-columns: 138px 1fr; gap: 24px 15px; margin: 0; }.fact-body dt { font-size: 23px; color: #a69f97; }.fact-body dd { margin: 0; font-size: 23px; line-height: 1.55; white-space: nowrap; }.fact-source { position: absolute; bottom: 18px; left: 20px; right: 20px; padding: 16px !important; font-size: 22px; border-radius: 15px; background: #ffffff05 !important; }.fact-source > svg { color: #6f675e; }
.fact-links { position: absolute; top: 867px; left: 277px; width: 611px; display: flex; gap: 8px; }.fact-links button { flex: 1; padding: 21px 26px !important; white-space: nowrap; font-size: 22px; border-radius: 18px; gap: 12px; }.fact-links button svg:last-child { margin-left: auto; }
.expressions { position: absolute; top: 246px; right: 23px; width: 369px; bottom: 71px; padding: 26px 22px; border-radius: 21px; }.expressions h2 { margin-bottom: 15px; }.expression-row { width: 100%; text-align: left; min-height: 64px; gap: 14px; padding: 9px 12px !important; border-radius: 12px; font-size: 23px; white-space: nowrap; }.expression-row.selected { background: #ffffff0b !important; color: #f5c79a; box-shadow: inset 3px 0 #e7b98e; }.expression-row > svg:last-child { margin-left: auto; }.cyan { color: #8bd4e7; }.expression-preview { padding: 17px 17px 16px; border-radius: 18px; border: 1px solid #38322b; background: #ffffff03; }.expression-preview h3 { font-size: 23px; white-space: nowrap; }.expression-preview .r-pill { font-size: 19px; }.expression-preview p { margin: 13px 0 20px; line-height: 1.55; font-size: 23px; }.expression-actions { margin-top: 17px; gap: 10px; }.expression-actions .r-btn { padding: 8px 20px !important; }.fact-foot { margin: 33px 0 0 !important; font-size: 22px; }
.prep-expression .p-title { top: 88px; left: 20px; font-size: 44px; font-weight: 600; }.prep-expression .p-actions { top: 108px; right: 28px; gap: 12px; }.prep-expression .p-actions .r-btn { min-height: 51px; font-size: 20px; font-weight: 500; border-radius: 27px; padding: 8px 21px !important; }.expression-tabs { position: absolute; top: 166px; left: 20px; width: 321px; height: 51px; border-radius: 28px; background: linear-gradient(110deg, #1c18139c, #28221b6b); gap: 0; }.expression-tabs button { flex: 1; font-size: 21px; padding: 7px 24px; }
.expression-editor { position: absolute; top: 229px; left: 19px; right: 334px; height: 580px; display: grid; grid-template-columns: 216px 1fr 164px; border-color: #2d261e; }.expression-directory { border-right: 1px solid #393027; padding: 21px 15px; }.expression-directory > button { width: 100%; text-align: left; font-size: 20px; padding: 13px 8px !important; white-space: nowrap; }.expression-directory small { color: #8a8176; font-size: 18px; font-weight: 400; margin-left: 4px; }.expression-directory .version-link { padding-left: 21px !important; margin-bottom: 0; border-radius: 11px; }.expression-directory .version-link.selected { background: #986a4055 !important; color: #fff5e7; box-shadow: inset 2px 0 #e5ad78; }.expression-text { padding: 25px 28px; min-width: 0; }.expression-text h2 { font-size: 34px; }.expression-byline { font-size: 19px; margin-top: 10px !important; gap: 9px; }.expression-byline .r-pill { font-size: 17px; }.expression-text .r-rule { margin: 15px 0 20px; }.expression-prose p { font-size: 19px; line-height: 1.72; margin-bottom: 31px; white-space: nowrap; }.unreadable { display: inline-block; width: 37px; }.expression-outline { margin-top: 129px; border-left: 1px solid #4a4137; padding-left: 26px; padding-top: 5px; }.expression-outline > div { position: relative; margin-bottom: 25px; }.expression-outline > div span { position: absolute; top: 1px; left: -30px; color: #8c8377; font-size: 11px; }.expression-outline strong { color: #b3aaa0; font-size: 18px; font-weight: 400; }.expression-outline p { font-size: 17px; margin-top: 4px; }
.version-history { position: absolute; top: 229px; right: 16px; width: 309px; height: 580px; border-color: #29231c; padding: 17px 14px; }.version-history h2 { font-size: 25px; margin: 0 9px 15px; }.history-item { display: block; width: 100%; text-align: left; padding: 12px 9px !important; margin: 1px 0; border-radius: 14px; }.history-item.selected { background: #7d5c3e30 !important; }.history-item strong { font-weight: 500; font-size: 20px; }.history-item small { font-size: 17px; margin-left: 13px; }.history-item i { display: inline-block; width: 9px; height: 9px; border-radius: 50%; background: #8e877c; margin-right: 6px; }.history-item.selected i { background: #e0a16c; }.history-item p { margin-left: 14px; font-size: 16px; line-height: 1.7; }.version-history .r-rule { margin: 9px 0; }.history-count { width: 100%; padding: 4px 10px !important; font-size: 18px; color: #b3aaa0; }.version-history > .r-btn { display: flex; width: calc(100% - 17px); margin: 10px auto 0; min-height: 37px; font-size: 18px; padding: 5px !important; border-radius: 23px; }.version-history > .r-btn:last-child { margin-top: 6px; background: #ffffff03 !important; border-color: transparent !important; }
.version-diff { position: absolute; top: 821px; left: 19px; right: 16px; height: 170px; padding: 14px 22px 20px; border-color: #29231c; }.diff-heading { width: 100%; font-size: 22px; }.diff-heading > span { font-size: 17px; }.diff-columns { display: grid; grid-template-columns: 1fr 1.06fr; margin-top: 13px; padding-top: 0; border-top: 1px solid #4a3e30; font-size: 18px; }.diff-columns p { display: flex; align-items: flex-start; gap: 10px; padding: 14px 0; line-height: 1.7; }.diff-symbol { display: grid; place-items: center; flex: none; width: 21px; height: 21px; border-radius: 50%; margin-top: 5px; background: currentColor; }.diff-symbol svg { color: #2a211a; }.diff-columns .removed { color: #ed9282; background: #58241a12; }.diff-columns .added { color: #a1d9c9; background: #1a49361b; padding-left: 15px; border-left: 1px solid #34473a; }
.prep-materials .p-title { top: 106px; left: 25px; font-size: 50px; }.prep-materials .p-actions { top: 123px; right: 18px; gap: 13px; }.prep-materials .p-actions .r-btn { height: 47px; min-height: 47px; border-radius: 18px; font-size: 17px; padding: 10px 15px !important; gap: 7px; font-weight: 500; }.prep-materials .p-actions .r-btn:first-child { width: 168px; padding-inline: 10px !important; font-size: 16px; }.prep-materials .p-actions .r-primary { width: 131px; box-shadow: inset 0 1px 5px #ffe8cb99, 0 4px 8px #d1844033; background: linear-gradient(120deg, #eeb583, #dfa572 65%, #c28c63) !important; }
.file-library { position: absolute; top: 196px; left: 13px; bottom: 44px; width: 323px; border-color: #534436; padding: 18px 14px; border-radius: 19px; }.file-library h3 { font-size: 23px; }.file-search { margin-top: 14px; border: 1px solid #514639; background: #ffffff03; border-radius: 18px; height: 51px; padding: 0 14px; gap: 9px; color: #aaa39a; }.file-search input { background: none; border: 0; outline: 0; width: 200px; font-size: 18px; }.file-filters { display: flex; gap: 6px; margin-top: 16px; margin-bottom: 18px; }.file-filters button { font-size: 16px; padding: 7px 10px !important; white-space: nowrap; border-radius: 23px; border: 1px solid #494037 !important; color: #bcb2a6; }.file-filters button.active { background: #75482555 !important; border-color: #ecad67 !important; color: #ffeddb; box-shadow: 0 0 8px #c98a4544; }.file-item { text-align: left; width: calc(100% + 14px); margin-left: -9px; padding: 12px 15px !important; gap: 16px; min-height: 73px; border-radius: 14px; border: 1px solid transparent !important; }.file-item.selected { background: #8c5d3c55 !important; border-color: #9c725099 !important; }.file-item strong { font-size: 17px; font-weight: 500; white-space: nowrap; }.file-item small { display: block; font-size: 16px; margin-top: 0; }.file-item > svg:last-child { margin-left: auto; color: #8e7e6d; }.file-type { display: grid; place-items: center; height: 36px; width: 30px; border-radius: 5px; color: white; flex: none; }.file-type.pdf { background: linear-gradient(#ff6d57, #da372d); }.file-type.text { background: linear-gradient(#6aabf4, #2673dd); }.file-type.warning { background: #e99040; color: #3e2512; border-radius: 50%; }
.pdf-viewer { position: absolute; top: 196px; left: 348px; bottom: 44px; width: 563px; border-color: #75604a; border-radius: 17px; }.pdf-toolbar { position: absolute; top: 0; left: 0; right: 0; height: 75px; padding: 0 16px; gap: 12px; font-size: 18px; white-space: nowrap; border-bottom: 1px solid #494035; }.pdf-toolbar .r-icon-btn { width: 36px; }.pdf-zoom { gap: 17px; background: #ffffff04; border: 1px solid #3f362c; border-radius: 15px; padding: 4px 9px; margin-right: 19px; }.pdf-toolbar > button { color: #c1b8ad; }.pdf-thumbnails { position: absolute; top: 77px; bottom: 0; width: 133px; background: #ffffff03; display: flex; flex-direction: column; gap: 18px; align-items: center; padding-top: 20px; }.pdf-thumbnails button { display: flex; flex-direction: column; align-items: center; gap: 7px; font-size: 17px; }.mini-document { display: flex; flex-direction: column; gap: 5px; width: 85px; height: 115px; background: #eeece6; padding: 11px 8px; box-shadow: 1px 2px 5px #0007; }.mini-document b { height: 3px; width: 70%; background: #69676677; margin-bottom: 4px; }.mini-document i { height: 1px; background: #7e7b7355; }.pdf-thumbnails button.selected .mini-document { outline: 4px solid #e6a45e; outline-offset: 3px; }.pdf-thumbnails button.selected .mini-document i:nth-last-child(3) { height: 6px; background: #f5cb92; }
.pdf-page-wrap { position: absolute; top: 75px; left: 133px; right: 0; bottom: 24px; overflow: hidden; }.pdf-paper { background: #f9f6f2; color: #242220; height: 684px; width: 429px; padding: 50px 37px; transform-origin: top left; font-size: 15px; }.pdf-paper h2 { font-size: 22px; font-weight: 700; margin-bottom: 28px; }.pdf-paper h3 { font-size: 20px; font-weight: 650; margin-bottom: 9px; margin-top: 24px; }.pdf-paper p { font-size: 15px; line-height: 1.6; white-space: nowrap; }.pdf-paper ul { margin: 0; padding-left: 18px; line-height: 2.1; white-space: nowrap; }.pdf-paper mark { display: inline-block; background: #f6d19f; padding: 0 2px; font-size: 15px; margin-top: 3px; white-space: nowrap; }.pdf-paper footer { position: absolute; bottom: 16px; right: 30px; left: 37px; text-align: right; border-top: 1px solid #5e5952; padding-top: 7px; font-size: 15px; }
.parsing { position: absolute; top: 196px; right: 16px; bottom: 44px; width: 356px; border-color: #514131; padding: 18px 17px; border-radius: 18px; }.parsing h3 { font-size: 23px; margin-bottom: 13px; }.parsing-status { height: 51px; width: 100%; padding: 0 13px !important; border: 1px solid #514438 !important; border-radius: 17px; background: #ffffff04 !important; font-size: 18px; }.parsing-content { padding-top: 15px; }.parsing-content section { position: relative; padding-left: 34px; margin: 4px 0 15px; font-size: 16px; }.parsing-content section i { position: absolute; left: 7px; top: 6px; width: 13px; height: 13px; border-radius: 50%; background: #df913f; }.parsing-content section::after { content: ""; position: absolute; left: 13px; top: 29px; bottom: -5px; width: 1px; background: #65524355; }.parsing-content strong { font-size: 17px; }.parsing-content p { font-size: 16px; line-height: 1.45; color: #d3ccc2; margin-top: 5px; white-space: pre-line; }.parsing-content mark { display: block; color: #ffedda; background: #9c6437; width: fit-content; padding: 0 2px; border-radius: 3px; }.parsing-warning { position: absolute; left: 17px; right: 17px; bottom: 129px; color: #ffac58; height: 38px; background: #8c521c55; border-radius: 10px; padding: 0 12px; font-size: 17px; }.parsing footer { position: absolute; bottom: 26px; left: 17px; right: 17px; border-top: 1px solid #4e4033; padding-top: 9px; }.parsing footer p { font-size: 16px; margin-bottom: 12px; }.parsing footer .r-row { gap: 13px; }.parsing footer .r-btn { min-height: 45px; font-size: 17px; flex: 1; padding: 8px 12px !important; border-radius: 18px; }
/* 文档和解析段落按参考页分别定位，避免字体行高累积改变标注位置。 */
.file-library { background: linear-gradient(115deg, #201c18, #1b1815 60%, #211c17); }.file-library h3, .parsing h3 { font-size: 22px; }
.file-library h3 { line-height: 33.35px; }
.file-filters { gap: 9px; margin-top: 15px; margin-left: -1px; }.file-filters button { height: 42px; padding: 0 !important; flex: none; font-size: 16.5px; }.file-filters button:nth-child(1) { width: 72px; }.file-filters button:nth-child(2) { width: 65px; }.file-filters button:nth-child(3) { width: 66px; }.file-filters button:nth-child(4) { width: 65px; }
.file-item { position: relative; height: 73px; min-height: 0; width: calc(100% + 19px); gap: 21px; padding-left: 21px !important; }.file-item:first-of-type { height: 77px; }.file-item:nth-of-type(3) { height: 71px; }.file-item:nth-of-type(4) { height: 70px; }
.file-item.selected { background: linear-gradient(110deg, #8c5d3c70, #694a354d) !important; }.file-item > svg:last-child { position: absolute; right: 10px; top: 25px; }.file-item strong { font-size: 17px; font-weight: 400; }.file-item small { font-size: 15.5px; }
.file-type { position: relative; width: 26px; height: 34px; border-radius: 4px; clip-path: polygon(0 0, 65% 0, 100% 28%, 100% 100%, 0 100%); }.file-type:not(.warning)::after { content: ""; position: absolute; top: 0; right: 0; width: 9px; height: 10px; background: #dceeff99; border-radius: 0 0 0 2px; }.file-type.pdf::after { background: #ffc3b8aa; }.file-type.warning { height: 31px; width: 29px; clip-path: none; background: linear-gradient(120deg, #f6ac5c, #dc7e33); }.file-type.warning svg { width: 24px; height: 27px; }
.pdf-toolbar { padding-inline: 0; font-size: 18px; }.pdf-toolbar > button, .pdf-toolbar > span, .pdf-toolbar > .pdf-zoom { position: absolute; top: 50%; transform: translateY(-50%); }.pdf-toolbar > button { display: grid; place-items: center; width: 36px; height: 38px; }.pdf-toolbar > button:nth-of-type(1) { left: 12px; }.pdf-toolbar > span { left: 69px; }.pdf-toolbar > button:nth-of-type(2) { left: 172px; }.pdf-toolbar > button:nth-of-type(3) { left: 417px; }.pdf-toolbar > button:nth-of-type(4) { left: 476px; }.pdf-toolbar > button:nth-of-type(5) { left: 524px; width: 30px; }.pdf-zoom { left: 233px; width: 155px; height: 38px; margin: 0; gap: 0; justify-content: space-between; padding-inline: 9px; }.pdf-zoom > button { display: grid; place-items: center; width: 27px; height: 34px; border-radius: 50%; background: #ffffff04; }
.mini-document { background: #e8e4e0; }.pdf-thumbnails { align-items: flex-start; padding-left: 19px; gap: 15px; }
.pdf-paper { position: relative; padding-top: 52px; background: #faf6f1; }
.pdf-paper h2 { position: absolute; left: 37px; top: 52px; font-size: 22px; line-height: 31px; margin: 0; }
.pdf-paper h3 { position: absolute; left: 37px; font-size: 19px; line-height: 29px; margin: 0; }
.pdf-paper h3:nth-of-type(1) { top: 112px; }.pdf-paper h3:nth-of-type(2) { top: 221px; }.pdf-paper h3:nth-of-type(3) { top: 343px; }.pdf-paper h3:nth-of-type(4) { top: 485px; }
.pdf-paper p { position: absolute; left: 37px; font-size: 15px; line-height: 24px; }.pdf-paper p:nth-of-type(1) { top: 150px; }.pdf-paper p:nth-of-type(2) { top: 381px; }
.pdf-paper ul { position: absolute; left: 37px; padding-left: 31px; font-size: 15px; line-height: 32px; }.pdf-paper ul:nth-of-type(1) { top: 256px; }.pdf-paper ul:nth-of-type(2) { top: 519px; }
.pdf-paper mark { position: absolute; left: 37px; top: 436px; margin: 0; font-size: 15px; line-height: 24px; border-radius: 3px; }
.parsing { background: linear-gradient(115deg, #231f1a, #1c1916 60%, #211c17); }.parsing h3 { line-height: 33.35px; }.parsing-status { font-size: 17px; background: linear-gradient(115deg, #38322b88, #302a2388) !important; }.parsing-status .r-success { color: #69d49a; }.parsing-status .r-success svg { color: #082e21; border-radius: 50%; background: linear-gradient(120deg, #61f0a7, #35bc87); padding: 3px; }
.parsing-content { position: absolute; top: 134px; bottom: 174px; left: 17px; right: 17px; padding: 0; }
.parsing-content section { position: absolute; left: 0; right: 0; margin: 0; padding-left: 36px; }.parsing-content section:nth-child(1) { top: 0; }.parsing-content section:nth-child(2) { top: 113px; }.parsing-content section:nth-child(3) { top: 208px; }.parsing-content section:nth-child(4) { top: 374px; }
.parsing-content p, .parsing-content ul { margin-top: 3px; font-size: 16px; line-height: 23px; }.parsing-content ul { margin-bottom: 0; padding-left: 22px; font-size: 15.5px; line-height: 24.5px; color: #d3ccc2; }.parsing-content mark { margin-top: 3px; font-size: 15.5px; line-height: 23px; }
.parsing-content section:last-child li:last-child { font-size: 15.3px; }
.parsing-warning { font-size: 16px; bottom: 132px; }.parsing-warning svg { border-radius: 50%; background: linear-gradient(120deg, #f6aa58, #df8037); color: #4c2a14; padding: 3px; }.parsing footer { bottom: 29px; }.parsing footer p { font-size: 15.5px; }.parsing footer .r-btn { font-size: 16px; }.parsing footer .r-btn:first-child { flex: 0 0 163px; font-weight: 500; background: linear-gradient(120deg, #eeb583, #dfa572 65%, #c28c63) !important; box-shadow: inset 0 1px 5px #ffe8cb99, 0 4px 8px #d1844033; }
/* 原生列表保留正文基线；摘录背景随行盒断开，避免涂满两行之间的空白。 */
.prep-materials .p-title { top: 107px; left: 26px; font-weight: 650; transform: scaleX(.98); transform-origin: left center; }
.prep-materials .file-library { background: linear-gradient(115deg, #201c18, #1b1713 60%, #1c1814); }
.prep-materials .file-library h3, .prep-materials .parsing h3 { font-weight: 500; -webkit-text-stroke: .4px currentColor; }
.prep-materials .file-item small, .prep-materials .pdf-toolbar { font-weight: 400; }
.prep-materials .pdf-viewer { background: linear-gradient(115deg, #28231eaa, #1e1a15dc 70%); }
.prep-materials .pdf-page-wrap { left: 132px; bottom: 23px; }
.prep-materials .pdf-paper { font-weight: 400; background: linear-gradient(115deg, #faf7f4, #faf6f1); }
.prep-materials .pdf-paper h2, .prep-materials .pdf-paper h3, .prep-materials .pdf-paper p, .prep-materials .pdf-paper ul, .prep-materials .pdf-paper footer { left: 38px; }
.prep-materials .pdf-paper h2 { font-weight: 650; transform: scaleX(.97); transform-origin: left center; }
.prep-materials .pdf-paper h3 { font-weight: 600; }
.prep-materials .pdf-paper p, .prep-materials .pdf-paper ul, .prep-materials .pdf-paper mark { transform: scaleX(.98); transform-origin: left center; }
.prep-materials .pdf-paper ul { padding-left: 21px; }.prep-materials .pdf-paper li { padding-left: 10px; }
.prep-materials .parsing-content { top: 135px; }.prep-materials .parsing-content strong { font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.prep-materials .parsing-content section:nth-child(3) p { line-height: 24.5px; }
.prep-materials .parsing-content mark { display: inline; position: relative; left: -4px; font-size: 16px; line-height: 23px; box-decoration-break: clone; -webkit-box-decoration-break: clone; padding: 2px 4px; font-weight: 400; }
.prep-materials .pdf-toolbar [title="适应页面"] svg { transform: translate(1px, -1px); }
.prep-materials .file-item:first-of-type > svg { stroke-width: 4px; top: 27px; right: 10.5px; }
.prep-tasks .p-title { top: 104px; left: 20px; font-size: 52px; }.prep-tasks .p-actions { top: 129px; right: 32px; gap: 22px; }.prep-tasks .p-actions .r-btn { border-radius: 17px; }.task-tabs { position: absolute; top: 191px; left: 19px; width: 482px; height: 48px; padding: 0; border-color: #393026; }.task-tabs button { font-size: 22px; }.task-stats { position: absolute; top: 255px; left: 16px; right: 32px; height: 118px; display: grid; grid-template-columns: 1fr 1.08fr 1.12fr; padding: 24px 28px; }.task-stats > div + div { border-left: 1px solid #3d3227; padding-left: 34px; }.stat-icon { height: 65px; width: 65px; border-radius: 50%; display: grid; place-items: center; }.stat-icon.green { background: #25473d55; color: #79d8c7; }.stat-icon.amber { background: #694a2c44; color: #e89c61; }.task-stats strong { color: #f5eee3; font-size: 34px; font-weight: 600; line-height: 1.4; }.task-stats small { font-size: 22px; }
.pending-tasks { position: absolute; top: 394px; left: 16px; bottom: 38px; width: 437px; padding: 20px 16px; border-color: #32291f; }.pending-tasks h2 { font-size: 27px; }.pending-tasks h2 small, .scheduled-tasks h2 small { font-size: 18px; font-weight: 400; }.pending-tasks > .r-between { margin-bottom: 22px; }.pending-tasks > .r-between button { font-size: 16px; }.pending-task { display: flex; position: relative; width: 100%; text-align: left; gap: 16px; padding: 17px 18px 14px 24px !important; min-height: 138px; border: 1px solid #3c3229 !important; border-radius: 17px; background: #ffffff03 !important; margin-bottom: 9px; overflow: hidden; }.pending-task::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 5px; background: linear-gradient(#ffb478, #94613b); }.pending-icon { width: 60px; height: 60px; border-radius: 14px; background: #ffffff04; border: 1px solid #4d403233; display: grid; place-items: center; color: #8ad7d0; flex: none; }.pending-task h3 { font-size: 22px; white-space: nowrap; }.pending-task .r-pill { font-size: 16px; padding: 0 12px; margin: 3px 0 9px; }.pending-task p { font-size: 16px; white-space: nowrap; }.pending-task > svg { margin-left: auto; }
.scheduled-tasks { position: absolute; top: 394px; bottom: 38px; left: 466px; width: 409px; padding: 20px 17px; border-color: #32291f; }.scheduled-tasks h2 { margin-bottom: 22px; }.scheduled-row { position: relative; display: flex; gap: 18px; margin-bottom: 10px; }.scheduled-row > span { padding-top: 16px; white-space: nowrap; font-size: 21px; width: 62px; }.scheduled-row > i { position: absolute; top: 30px; left: 78px; width: 12px; height: 12px; border-radius: 50%; background: #64bfae; }.scheduled-row > i::after { content: ""; position: absolute; left: 5px; top: 6px; width: 20px; height: 1px; background: inherit; }.scheduled-row > i.scheduled-1 { background: #509bbb; }.scheduled-row > i.scheduled-2 { background: #e6a06c; }.scheduled-row button { position: relative; margin-left: 19px; flex: 1; text-align: left; min-height: 93px; border: 1px solid #3c3229 !important; border-radius: 16px; background: #ffffff03 !important; padding: 12px 28px !important; }.scheduled-row button strong { font-size: 22px; font-weight: 500; white-space: nowrap; }.scheduled-row button p { color: #bbb0a3; font-size: 16px; margin-top: 3px; white-space: nowrap; }.scheduled-row button > svg { position: absolute; top: 19px; right: 18px; }
.schedule-preview { position: absolute; top: 394px; right: 32px; bottom: 38px; width: 356px; border-color: #3b3026; padding: 20px 18px; }.schedule-preview h2 { margin-bottom: 18px; }.schedule-preview h3 { font-size: 21px; }.week-bars { display: flex; justify-content: space-between; margin-top: 16px; margin-bottom: 26px; }.week-bars > div { text-align: center; }.week-bars span { position: relative; display: block; width: 24px; height: 47px; margin: 0 auto 10px; border-radius: 6px; background: #ffffff09; overflow: hidden; }.week-bars i { position: absolute; bottom: 0; left: 0; right: 0; background: linear-gradient(#55a38c, #4e9e94); border-radius: 4px; }.week-bars small { font-size: 16px; color: #beb4a9; }.preview-task { font-size: 20px; padding: 10px 0; border-bottom: 1px solid #ffffff08; gap: 12px; }.preview-task > span:nth-child(2) { flex: 1; }.preview-note { font-size: 17px; white-space: nowrap; gap: 8px; margin: 13px 0 18px !important; }.schedule-preview > .r-btn { display: flex; width: 100%; min-height: 43px; padding: 5px !important; border-radius: 17px; font-size: 22px; margin-top: 7px; }.schedule-preview > .r-btn:last-child { border-color: #332b24 !important; }
.prep-tasks .p-title { left: 23px; top: 105px; }
.prep-tasks .p-actions .r-btn { height: 54px; font-size: 20px; font-weight: 500; background: linear-gradient(115deg, #27231f, #1f1b17) !important; border-color: #4f443a66 !important; }
.prep-tasks .p-actions .r-btn:first-child { width: 167px; }
.prep-tasks .p-actions .r-btn:last-child { width: 152px; padding-inline: 12px !important; }
.prep-tasks .p-actions .r-primary { background: linear-gradient(120deg, #f1b68b, #d79e79) !important; color: #140b06; border-color: #f0bd9699 !important; }
.prep-tasks .task-tabs { border-color: #4d41352b; background: linear-gradient(115deg, #29231ddd, #29241ff2); }
.prep-tasks .task-tabs button.r-active { font-weight: 500; background: radial-gradient(ellipse at 50% 100%, #db945e99, transparent 80%), linear-gradient(115deg, #65452f, #8d6044); box-shadow: inset 0 1px 2px #ffe0b64d, inset 0 -1px 1px #f2b57c; }
.prep-tasks .r-panel { background: linear-gradient(120deg, #26211cba, #191613df 70%); border-color: #383029; }
.prep-tasks .task-stats { grid-template-columns: 346px 406px 1fr; }
.prep-tasks .task-stats > .r-row { gap: 17px; }
.prep-tasks .task-stats > div + div { border-color: #7a685333; }
.prep-tasks .task-stats .r-muted { display: block; height: 68px; line-height: 26px; color: #d2ccc5; }
.prep-tasks .task-stats strong { position: relative; top: 6px; font-size: 32px; line-height: 40px; font-weight: 600; }
.prep-tasks .task-stats small { font-weight: 500; }
.prep-tasks .pending-tasks > .r-between { margin-bottom: 23px; }
.prep-tasks .pending-task { min-height: 137px; padding-top: 15px !important; background: linear-gradient(115deg, #2b2620c0, #24201bd9) !important; border-color: #4c40364d !important; }
.prep-tasks .pending-task:nth-of-type(2) { margin-bottom: 12px; }
.prep-tasks .pending-icon { background: linear-gradient(115deg, #453b2d55, #3a332a66); border-color: #7b675c1f; }
.prep-tasks .pending-task .r-pill { padding: 0 12px; font-size: 17px; margin-top: 7px; margin-bottom: 10px; }
.prep-tasks .pending-task:nth-of-type(n+2) .r-pill { margin-top: 8px; }
.prep-tasks .pending-task p { font-size: 17px; }
.prep-tasks .pending-task .r-pill.amber { color: #f7d5b0; background: linear-gradient(115deg, #7b4d2866, #59402777); }
.prep-tasks .pending-task .r-pill.teal { color: #99d8d0; background: #2d514c6b; }
.prep-tasks .scheduled-tasks { padding-right: 16px; }
.prep-tasks .scheduled-row button { margin-left: 18px; background: linear-gradient(115deg, #2b2620c0, #24201bd9) !important; border-color: #4c40364d !important; }
.prep-tasks .scheduled-row button p { margin-top: 5px; color: #b6aea4; }
.prep-tasks .scheduled-source { color: #a6d6cf; font-weight: 600; }
.prep-tasks .scheduled-row > i { left: 74px; background: #354a43; z-index: 1; }
.prep-tasks .scheduled-row > i::after { width: 19px; background: #65bba9; }
.prep-tasks .scheduled-row > i::before { content: ""; position: absolute; left: 21px; top: 3px; width: 6px; height: 6px; border-radius: 1px; transform: rotate(45deg); background: #65bba9; }
.prep-tasks .scheduled-row > i.scheduled-1 { background: #2c444e; }
.prep-tasks .scheduled-row > i.scheduled-1::before, .prep-tasks .scheduled-row > i.scheduled-1::after { background: #4b9bb8; }
.prep-tasks .scheduled-row > i.scheduled-2 { background: #675442; }
.prep-tasks .scheduled-row > i.scheduled-2::before, .prep-tasks .scheduled-row > i.scheduled-2::after { background: #e2a06d; }
.prep-tasks .scheduled-row:not(:last-child)::after { content: ""; position: absolute; left: 80px; top: 36px; width: 1px; height: 103px; background: #5f574333; }
.prep-tasks .scheduled-row button::before { content: ""; position: absolute; left: 0; width: 14px; top: 16px; bottom: 16px; background: #78c1b710; }
.prep-tasks .scheduled-row:nth-of-type(2) button::before { background: #69accf10; }
.prep-tasks .scheduled-row:nth-of-type(3) button::before { background: #dda36c10; }
.prep-tasks .scheduled-row .cyan, .prep-tasks .preview-task .cyan { color: #4299ba; }
.prep-tasks .scheduled-row .cyan :deep(path:first-child), .prep-tasks .preview-task .cyan :deep(path:first-child) { stroke-opacity: .45; }
.prep-tasks .schedule-preview { padding-left: 17px; padding-right: 16px; padding-top: 23px; }
.prep-tasks .schedule-preview h2 { position: relative; top: -2px; font-size: 25px; }
.prep-tasks .schedule-preview h2 { margin-bottom: 19px; }
.prep-tasks .schedule-preview h3 { font-size: 21px; font-weight: 500; }
.prep-tasks .week-bars { margin-top: 18px; margin-bottom: 25px; }
.prep-tasks .week-bars span { background: linear-gradient(115deg, #51473d2b, #4e453b55); }
.prep-tasks .week-bars span { margin-bottom: 7px; }
.prep-tasks .week-bars i { background: linear-gradient(120deg, #5fae9ccc, #65baa9de 65%, #3f948b); box-shadow: inset 2px 0 7px #8ad6c82b; }
.prep-tasks .schedule-preview > .r-between:not(.preview-task) { padding-bottom: 13px; border-bottom: 1px solid #7a685338; }
.prep-tasks .preview-task { padding: 8.5px 0 8px; border-bottom-color: transparent; }
.prep-tasks .preview-task > span:nth-child(2) { margin-left: 10px; }
.prep-tasks .preview-note { border-top: 1px solid #7a68532b; padding-top: 10px; margin-top: 3px !important; margin-bottom: 18px !important; font-size: 18px; gap: 9px; color: #bfb7ad; }
.prep-tasks .schedule-preview > .r-primary { background: linear-gradient(115deg, #d79970, #b37d59 70%, #9c6c4e) !important; border-color: #e9b38b55 !important; font-size: 21px; font-weight: 600; }
.prep-tasks .schedule-preview > .r-btn:last-child { background: linear-gradient(115deg, #2d2822, #29241f) !important; border-color: #51433533 !important; font-size: 21px; font-weight: 500; }
.prep-calendar .p-title { top: 119px; left: 25px; font-size: 48px; }.prep-calendar .p-actions { top: 127px; right: 28px; gap: 22px; }.prep-calendar .p-actions .r-btn { border-radius: 22px; font-size: 24px; }.calendar-panel { position: absolute; top: 203px; left: 21px; width: 787px; height: 506px; padding: 28px 28px; border-radius: 20px; }.calendar-title { gap: 21px; }.calendar-title h2 { font-size: 32px; margin-right: 34px; }.calendar-title button { color: #efdfc9; }.calendar-weekdays { display: grid; grid-template-columns: repeat(7, 1fr); margin-top: 22px; font-size: 23px; color: #c0b7ac; }.calendar-weekdays > span { text-align: left; }.calendar-days { display: grid; grid-template-columns: repeat(7, 1fr); grid-template-rows: 119px 100px 82px 65px; margin-top: 19px; }.calendar-days button { position: relative; text-align: left; font-size: 25px; height: 100%; }.calendar-days button > span { display: grid; place-items: center; width: 47px; height: 47px; margin-top: -28px; margin-left: -4px; border-radius: 50%; }.calendar-days button.selected > span { background: #f1b782; color: #201106; }.calendar-days button.marked > span { background: #ffffff0a; }.calendar-days button small { display: block; position: absolute; top: 60px; left: -32px; font-size: 19px; padding: 2px 15px; white-space: nowrap; border-radius: 11px; background: #6942208a; color: #ffdeb8; }.calendar-days button small::before { content: ""; position: absolute; left: calc(50% - 4px); top: -10px; width: 9px; height: 9px; border-radius: 50%; background: #e9ad76; }.calendar-days button small.tentative { background: #ffffff0b; color: #c8beb2; left: -49px; }.calendar-days button small.tentative::before { background: #c7b3a0; }
.calendar-event { position: absolute; top: 708px; left: 21px; width: 787px; height: 246px; padding: 25px 18px; border-radius: 0 0 20px 20px; border-top: none; }.calendar-event h3 { font-size: 24px; margin-bottom: 7px; }.calendar-event article { border: 1px solid #a17c5788; border-radius: 17px; position: relative; display: flex; align-items: center; gap: 16px; padding: 18px 18px 18px 29px; background: #bd93621c; min-height: 116px; overflow: hidden; }.calendar-event article::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 8px; background: #e9b180; }.calendar-event h2 { font-size: 26px; }.calendar-event p { font-size: 20px; gap: 7px; margin-top: 7px; white-space: nowrap; }.event-divider { height: 22px; width: 1px; background: #534230; margin: 0 11px; }.calendar-event .r-btn { min-height: 49px; padding: 6px 15px !important; color: #e9a464; font-size: 20px; border-radius: 26px; border-color: #d8934e !important; margin-left: auto; }
.reschedule { position: absolute; top: 203px; right: 26px; width: 448px; height: 531px; padding: 29px 28px; }.reschedule h2 { font-size: 26px; }.reschedule > p { font-size: 20px; margin-top: 15px; white-space: nowrap; }.reschedule .r-rule { margin: 26px 0 23px; }.reschedule h3 { font-size: 25px; margin-bottom: 14px; }.reschedule-task { height: 64px; width: 100%; text-align: left; font-size: 25px; gap: 25px; }.reschedule-task > svg:last-child { margin-left: auto; }.reschedule-task strong { font-weight: 500; }.reschedule .adjust { width: 100%; margin-top: 22px; font-size: 23px; border-radius: 18px; min-height: 63px; border-color: #b5a08b99 !important; background: #ffffff09 !important; }
.calendar-reminder { position: absolute; right: 26px; width: 448px; top: 751px; height: 203px; padding: 25px 28px; }.calendar-reminder h2 { font-size: 26px; margin-bottom: 13px; }.calendar-reminder p { font-size: 23px; gap: 14px; }.calendar-reminder .r-rule { margin: 13px 0; }.reminder-dot { width: 31px; height: 31px; border-radius: 50%; background: #ffffff0b; box-shadow: inset 0 0 0 9px #5b4a3a; }
.prep-calendar .p-title { top: 118px; font-size: 48px; font-weight: 600; }
.prep-calendar .p-actions { gap: 22px; }
.prep-calendar .p-actions .r-btn { height: 57px; padding: 8px 21px !important; border-radius: 21px; font-size: 24px; font-weight: 500; background: linear-gradient(115deg, #393027cc, #302820d9) !important; }
.prep-calendar .p-actions .r-primary { font-weight: 600; background: linear-gradient(115deg, #f8c698, #e7a570) !important; border-color: #efba8e !important; }
.prep-calendar .p-actions .r-btn:first-child { width: 133px; padding-inline: 0 !important; }
.prep-calendar .p-actions .r-btn:last-child { width: 167px; padding-inline: 12px !important; }
.prep-calendar .calendar-panel, .prep-calendar .calendar-event, .prep-calendar .reschedule, .prep-calendar .calendar-reminder { border-color: #5f4e3c66; background: linear-gradient(115deg, #1b1713cf, #17130fe6 70%); box-shadow: inset 0 1px #ffffff06; }
.prep-calendar .calendar-panel { top: 204px; width: 786px; height: 505px; }
.prep-calendar .calendar-title h2 { font-weight: 600; }
.prep-calendar .calendar-weekdays, .prep-calendar .calendar-days { grid-template-columns: 113px 119px 116px 114px 110px 110px 1fr; }
.prep-calendar .calendar-days { grid-template-rows: 121px 99px 62px 62px; margin-top: 22.5px; }
.prep-calendar .calendar-days button > span { position: absolute; left: -4px; top: 0; margin: 0; }
.prep-calendar .calendar-days button.selected > span { background: linear-gradient(120deg, #f4bf8e, #eba56c); }
.prep-calendar .calendar-days button.marked > span { background: linear-gradient(120deg, #3c352bc4, #2f2922cc); }
.prep-calendar .calendar-days button small { top: 61px; padding: 3px 15px; color: #ffe9d0; background: linear-gradient(115deg, #73502fd9, #54391fd9); }
.prep-calendar .calendar-days button small.tentative { top: 55px; background: linear-gradient(115deg, #35302b, #2b2621); color: #d7cfc5; }
.prep-calendar .calendar-days.calendar-other-month { grid-template-rows: repeat(6, 57px); margin-top: 20px; }
.prep-calendar .calendar-event { width: 786px; padding-right: 14px; background: linear-gradient(115deg, #15120fcc, #19140fe6 70%); }
.prep-calendar .calendar-event h3 { font-weight: 500; margin-bottom: 10px; }
.prep-calendar .calendar-event article { padding-left: 30px; background: linear-gradient(115deg, #3d342bd6, #2c241cde); border-color: #bc9875a1; }
.prep-calendar .calendar-event h2 { font-weight: 500; }
.prep-calendar .calendar-event p { font-size: 21px; gap: 8px; }
.prep-calendar .event-divider { margin-inline: 9px; background: #a9927740; }
.prep-calendar .calendar-event .r-btn { min-width: 113px; padding: 6px 15px !important; font-size: 20px; }
.prep-calendar .reschedule { top: 204px; height: 530px; }
.prep-calendar .reschedule h3 { margin-bottom: 20px; font-size: 24px; }
.prep-calendar .reschedule-task { display: grid; grid-template-columns: 32px 29px 1fr 23px; column-gap: 23px; border-top: 1px solid #a9987f09; font-size: 24px; }
.prep-calendar .reschedule .adjust { width: calc(100% + 3px); margin-left: -1px; margin-top: 29.5px; background: linear-gradient(115deg, #393129d9, #2c251ed9) !important; border-color: #ab9b8ab3 !important; font-weight: 500; }
.prep-calendar .calendar-reminder h2 { margin-bottom: 14px; }
.prep-calendar .calendar-reminder p { font-size: 22px; gap: 16px; color: #bcb3a9; }
.prep-calendar .reminder-dot { background: #b2997f; box-shadow: inset 0 0 0 9px #534438; }
.prep-facts .p-title { top: 92px; }
.prep-facts .facts-tabs { gap: 6px; }
.prep-facts .facts-tabs button { font-size: 24px; }
.prep-facts .fact-body { padding-inline: 23px; background: #171513; }
.prep-facts .fact-heading h3 { gap: 16px; font-size: 21.5px; }
.prep-facts .fact-body .r-rule { margin-bottom: 28px; }
.prep-facts .fact-body dl { grid-template-columns: 139px 1fr; row-gap: 24.5px; }
.prep-facts .fact-body dt, .prep-facts .fact-body dd { font-size: 21px; line-height: 35px; }
.prep-facts .expressions { padding-top: 29px; }
.prep-facts .expressions > .r-rule { margin: 17px 0 18px; }
.prep-facts .expression-preview { padding-top: 15px; }
.prep-facts .expression-preview p { font-size: 20px; line-height: 34px; margin-top: 12px; margin-bottom: 25px; transform: scaleY(1.09); transform-origin: left top; }
.prep-facts .expression-actions { margin-top: 22px; }
.prep-facts .expression-actions .r-btn { font-size: 21px; }
.prep-facts .fact-foot { font-size: 20px; margin-top: 34px !important; }
.prep-facts .p-title { top: 92px; left: 27px; font-size: 48px; font-weight: 500; -webkit-text-stroke: .6px currentColor; transform: scaleY(1.1); transform-origin: left top; }
.prep-facts .p-actions { top: 141px; right: 25px; gap: 12px; }
.prep-facts .p-actions .r-btn { height: 55px; min-height: 55px; }
.prep-facts .p-actions .r-btn:first-child { width: 121px; padding-inline: 0 !important; }
.prep-facts .p-actions .r-btn:last-child { width: 161px; padding-inline: 0 !important; }
.prep-facts .r-primary { font-weight: 400; background: linear-gradient(125deg, #dcb08c, #bc9679) !important; border-color: #d9b293 !important; }
.prep-facts .facts-tabs { top: 171px; height: 60px; }
.prep-facts .facts-tabs button { padding: 7px 23px 19px; }
.prep-facts .facts-tabs button.r-active { font-weight: 500; }
.prep-facts .facts-tabs button.r-active::after { left: 21px; right: 21px; box-shadow: none; }
.prep-facts .project-tree, .prep-facts .fact-body, .prep-facts .expressions { border-color: #69615a66; background: linear-gradient(120deg, #191714e0, #151311e8); box-shadow: inset 0 1px #ffffff0b; }
.prep-facts .project-tree h3 { font-size: 21px; line-height: 33.34375px; font-weight: 400; margin-bottom: 24px; }
.prep-facts .tree-root:not(:first-of-type) { margin-top: 9px; }
.prep-facts .project-tree { padding-left: 12px; padding-right: 16px; }
.prep-facts .project-leaf { height: 58px; border-radius: 11px; }
.prep-facts .project-leaf.selected { background: linear-gradient(110deg, #9b6b4373, #60442d70) !important; }
.prep-facts .fact-heading h3 { font-size: 22px; font-weight: 500; -webkit-text-stroke: .15px currentColor; transform: scaleY(1.07); transform-origin: left center; }
/* 标题行高度独立于状态标签，标签尺寸不改变正文基线。 */
.prep-facts .fact-heading { height: 50.6px; }
.prep-facts .fact-heading .r-pill { width: 127px; height: 49px; font-size: 18px; padding: 0; }
.prep-facts .fact-heading .r-pill.amber { width: 144px; gap: 10px; background: linear-gradient(110deg, #4a321b, #342414); color: #ffc67f; }
.fact-status-dot { width: 13px; height: 15px; border-radius: 50%; background: linear-gradient(120deg, #ffc580, #eea654); flex: none; }
.prep-facts .fact-body dt { color: #aaa6a0; }
/* 固定21px字面宽度与23px字高，原有显式换行不参与缩放计算。 */
.prep-facts .fact-body dd { font-size: 23px; font-weight: 400; transform: scaleX(.9130434783); transform-origin: left top; }
.prep-facts .fact-source { left: 20px; right: 20px; height: 70px; background: linear-gradient(110deg, #ffffff0b, #ffffff07) !important; }
.prep-facts .fact-links { height: 86px; }
.prep-facts .fact-links button { position: relative; padding: 21px 27px !important; border-color: #50453a66; background: linear-gradient(120deg, #191613, #151311); }
.prep-facts .fact-links button::before { content: ""; position: absolute; z-index: 0; inset: 12px 17px; border-radius: 14px; background: #ffffff04; pointer-events: none; }
.prep-facts .fact-links button > * { position: relative; }
.prep-facts .expression-row { font-size: 22px; font-weight: 300; min-height: 64px; }
.prep-facts .expressions { padding-left: 18px; }
.prep-facts .expressions > h2 { font-weight: 500; -webkit-text-stroke: .2px currentColor; margin-left: 4px; margin-bottom: 20px; }
.prep-facts .expressions > .r-rule { margin-top: 10px; }
.prep-facts .expression-row { padding-inline: 19px !important; gap: 10px; }
.prep-facts .expression-row.selected { background: linear-gradient(110deg, #ffffff0e, #ffffff16) !important; border-radius: 12px; }
.prep-facts .expression-row .r-pill { margin-left: auto; font-size: 18px; }
.prep-facts .expression-row > svg:last-child { margin-left: 4px; }
.prep-facts .expression-row:not(.selected) > svg:last-child { margin-left: auto; }
.prep-facts .expression-preview { margin-left: 2px; padding-bottom: 22px; background: linear-gradient(115deg, #211e1b, #1b1815); }
.prep-facts .expression-preview h3 { font-size: 22px; font-weight: 400; }
.prep-facts .expression-preview h3 .r-pill { margin-left: 17px; }
.prep-facts .expression-preview p { transform: translate(2px, 2px) scaleY(1.09); }
.prep-facts .expression-actions { gap: 10px; margin-top: 18px; margin-left: 2px; }
.prep-facts .expression-actions .r-btn { height: 55px; padding: 8px 27px !important; font-weight: 500; -webkit-text-stroke: .2px currentColor; }
.prep-facts .expression-actions .r-btn:first-child { width: 148px; justify-content: center; padding-inline: 0 !important; background: linear-gradient(125deg, #171513, #141210) !important; }
.prep-facts .expression-actions .r-btn:last-child { width: 162px; justify-content: center; padding-inline: 0 !important; }
.prep-facts .fact-foot { font-size: 21px; gap: 12px; }
/* 表达页的字面比例和提纲标记单独校正，不改变其他准备页的排版。 */
.prep-expression .p-title { left: 22px; font-weight: 500; -webkit-text-stroke: .6px currentColor; transform: scaleX(.96); transform-origin: left center; }
.prep-expression h2 { font-weight: 500; -webkit-text-stroke: .3px currentColor; }
.prep-expression .expression-directory strong { font-weight: 500; -webkit-text-stroke: .15px currentColor; }
.prep-expression .expression-editor { background: linear-gradient(115deg, #1b1815, #1c1815 65%, #1b1814); border-color: #25211b; }
.prep-expression .p-actions .r-btn { background: linear-gradient(120deg, #2c2923, #24211c) !important; border-color: #70685f8c !important; }
.prep-expression .p-actions .r-primary { background: linear-gradient(120deg, #dba77f, #e5ab7b 65%, #ce916a) !important; box-shadow: inset 0 1px 5px #ffe9ce99, 0 0 17px #d4884a29; }
.prep-expression .expression-tabs button.r-active { font-weight: 500; -webkit-text-stroke: .15px currentColor; border-radius: 27px; background: radial-gradient(ellipse at 50% 100%, #7d5a3033, transparent 75%); }
.prep-expression .expression-tabs button.r-active::after { left: 25px; right: 25px; height: 2px; }
.prep-expression .version-history, .prep-expression .version-diff { background: linear-gradient(115deg, #231f1a, #211c17 70%, #231e18); border-color: #28221c; }
.prep-expression .version-history { background: linear-gradient(115deg, #1e1a16, #1f1a16 70%, #201b17); }
.prep-expression .history-item.selected { background: linear-gradient(110deg, #7d5c3e38, #5b48392e) !important; }
.prep-expression .history-item p { font-weight: 400; }
.prep-expression .history-item:not(.selected) p { color: #9f978d; }
.prep-expression .history-item.selected small { color: #efc59f; }
.prep-expression .version-history > .r-primary { color: #fff6e8; font-weight: 500; background: linear-gradient(115deg, #d49e75, #bb9272 60%, #b38b6a) !important; box-shadow: inset 0 1px 5px #ffe9ce77; border-color: #e9c4a366 !important; }
.prep-expression .version-history > .r-btn:last-child { background: linear-gradient(110deg, #2c261f, #26211b) !important; }
.prep-expression .diff-heading > strong { font-weight: 500; font-size: 21px; gap: 14px; }
.prep-expression .diff-heading > strong > svg { width: 26px; height: 26px; }
.prep-expression .diff-columns { grid-template-columns: 574px 1fr; font-size: 19px; margin-top: 15.5px; border-color: #554b3f66; }
.prep-expression .diff-columns p { line-height: 30px; padding-top: 12px; padding-bottom: 12px; }
.prep-expression .diff-columns p > span:last-child { transform: scaleX(.96); transform-origin: left center; white-space: nowrap; }
.prep-expression .diff-columns .removed { background: transparent; }
.prep-expression .diff-columns .added { background: linear-gradient(110deg, #1733294a, #1c2b2422); border-left-color: #55513b88; }
.prep-expression .expression-directory { padding-top: 20px; }
.prep-expression .expression-directory > button { padding-block: 12px !important; }
.prep-expression .expression-directory .version-link { padding-block: 10.5px !important; }
.prep-expression .expression-directory > button:nth-last-child(-n+2) { padding-block: 10.5px !important; }
.prep-expression .expression-directory > button:nth-last-child(2) { margin-top: 6px; }
.prep-expression .expression-text { padding-left: 30px; }
.prep-expression .expression-text h2 { transform: scaleX(.98); transform-origin: left center; }
.prep-expression .expression-byline { font-weight: 400; gap: 11px; }
.prep-expression .expression-byline .r-pill { margin-left: 4px; }
.prep-expression .expression-text .r-rule { width: 497px; margin-top: 11px; margin-bottom: 19px; }
.prep-expression .expression-prose p { line-height: 32px; margin-bottom: 30.5px; }
.prep-expression .expression-outline { position: relative; padding-top: 7px; padding-left: 27px; }
.prep-expression .expression-outline::before { content: ""; position: absolute; top: 0; left: 26px; right: 24px; height: 1px; background: #49413766; }
.prep-expression .expression-outline::after { content: ""; position: absolute; top: -1px; left: -3px; width: 5px; height: 5px; background: #6e675c; transform: rotate(45deg); }
.prep-expression .expression-outline > div span { font-size: 0; top: 7px; left: -32px; width: 10px; height: 14px; background: #8c8377; clip-path: polygon(50% 0, 65% 40%, 100% 50%, 65% 60%, 50% 100%, 35% 60%, 0 50%, 35% 40%); }
.prep-expression .expression-outline > div { margin-bottom: 25px; }
.prep-expression .expression-outline strong { display: block; line-height: 27px; }
.prep-expression .expression-outline p { font-size: 17px; font-weight: 300; line-height: 27px; margin-top: 2px; }
.prep-expression .diff-columns .added { padding-left: 15px; gap: 13px; }
.prep-expression .history-item:first-of-type { height: 106px; }
.prep-expression .history-item:nth-of-type(2) { height: 91px; }
.prep-expression .history-item p:first-of-type { font-size: 17.5px; line-height: 27.2px; }
.prep-expression .history-count { padding-block: 5.5px !important; }
.prep-expression .version-history > .r-btn { font-size: 17px; }
.prep-expression .version-history > .r-primary { margin-top: 13px; }

/* 已确认的第8页提供缩略正文；其他页保留占位。独立绘制层保持页码往返时的缩略栅格结果一致。 */
.prep-materials .mini-document-known { position: relative; display: block; padding: 0; overflow: hidden; will-change: transform; border-radius: 2px; background: linear-gradient(125deg, #f9f5ef, #f8ebd8 65%, #faf5ed); }
.prep-materials .pdf-mini-page { position: absolute; top: 0; left: 0; display: block; width: 429px; height: 684px; color: #625b53; font-size: 15px; font-weight: 400; line-height: 1.5; text-align: left; filter: blur(1px); transform-origin: top left; transform: translate(3px, 3px) scale(0.198, 0.16); }
.pdf-mini-title, .pdf-mini-section, .pdf-mini-paragraph, .pdf-mini-list { position: absolute; left: 37px; display: block; }
.pdf-mini-title { top: 52px; font-size: 22px; font-weight: 700; line-height: 31px; }
.pdf-mini-section { font-size: 19px; font-weight: 650; line-height: 29px; }
.pdf-mini-section[data-part="background"] { top: 112px; }.pdf-mini-section[data-part="problem"] { top: 221px; }.pdf-mini-section[data-part="action"] { top: 343px; }.pdf-mini-section[data-part="result"] { top: 485px; }
.pdf-mini-paragraph { font-size: 15px; line-height: 24px; white-space: nowrap; }.pdf-mini-paragraph[data-part="background"] { top: 150px; }.pdf-mini-paragraph[data-part="action"] { top: 381px; }
.pdf-mini-list { padding-left: 31px; font-size: 15px; line-height: 32px; white-space: nowrap; }.pdf-mini-list[data-part="problem"] { top: 256px; }.pdf-mini-list[data-part="result"] { top: 519px; }.pdf-mini-list > span { display: list-item; list-style-type: disc; }
.pdf-mini-page mark { position: absolute; left: 37px; top: 436px; padding: 0 2px; border-radius: 3px; background: #f6d19f; font-size: 15px; line-height: 24px; white-space: nowrap; }
.pdf-mini-footer { position: absolute; bottom: 16px; right: 30px; left: 37px; display: block; border-top: 1px solid #5e5952; padding-top: 7px; font-size: 15px; text-align: right; }
.pdf-thumbnails button.selected .mini-document-known { outline-color: #e9a567; box-shadow: 0 0 0 2px #4b3325, 0 0 0 4px #edb77b70; }
</style>
