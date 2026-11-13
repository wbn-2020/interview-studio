<script setup lang="ts">
import { computed, ref } from 'vue'
import RIcon from './RIcon.vue'

withDefaults(defineProps<{ scene?: number }>(), { scene: 9 })
const emit = defineEmits<{ navigate: [scene: number]; notify: [message: string] }>()

type TopicId = 'distributed' | 'jvm' | 'database' | 'spring' | 'spring-extra' | 'project'
type GraphPoint = { x: number; y: number; r: number }
type GraphCluster = {
  id: TopicId
  label: string
  color: string
  labelX: number
  labelY: number
  labelWidth: number
  points: GraphPoint[]
  links: [number, number][]
}

const topics = ref<{ id: TopicId; label: string; checked: boolean }[]>([
  { id: 'distributed', label: '分布式', checked: true },
  { id: 'jvm', label: 'JVM', checked: true },
  { id: 'database', label: '数据库', checked: true },
  { id: 'spring', label: 'Spring', checked: true },
  { id: 'spring-extra', label: 'Spring', checked: false },
  { id: 'project', label: '项目', checked: false },
])
const relationships = ref([
  { id: 'includes', label: '包含', checked: true },
  { id: 'depends', label: '依赖', checked: true },
  { id: 'similar', label: '相似', checked: false },
  { id: 'compares', label: '对比', checked: false },
])
const clusters: GraphCluster[] = [
  {
    id: 'distributed', label: '分布式', color: '#99dbcb', labelX: 563, labelY: 237, labelWidth: 101,
    points: [{ x: 487, y: 301, r: 5.5 }, { x: 531, y: 260, r: 6 }, { x: 569, y: 290, r: 6.5 }, { x: 613, y: 322, r: 7 }, { x: 520, y: 335, r: 6.5 }],
    links: [[0, 1], [0, 2], [0, 4], [1, 2], [2, 3], [2, 4], [3, 4]],
  },
  {
    id: 'jvm', label: 'JVM', color: '#88cbe2', labelX: 940, labelY: 246, labelWidth: 79,
    points: [{ x: 879, y: 332, r: 7 }, { x: 917, y: 285, r: 6 }, { x: 961, y: 312, r: 5.5 }, { x: 1015, y: 346, r: 6.5 }, { x: 951, y: 362, r: 5.5 }],
    links: [[0, 1], [0, 2], [0, 4], [1, 2], [2, 3], [2, 4], [3, 4]],
  },
  {
    id: 'database', label: '数据库', color: '#89e0c8', labelX: 453, labelY: 589, labelWidth: 98,
    points: [{ x: 441, y: 601, r: 7 }, { x: 414, y: 647, r: 5.5 }, { x: 476, y: 662, r: 7 }, { x: 424, y: 695, r: 7 }, { x: 520, y: 694, r: 5.5 }],
    links: [[0, 1], [0, 2], [1, 2], [1, 3], [2, 3], [2, 4], [3, 4]],
  },
  {
    id: 'spring', label: 'Spring', color: '#8ecce4', labelX: 615, labelY: 714, labelWidth: 100,
    points: [{ x: 579, y: 728, r: 6 }, { x: 622, y: 768, r: 6.5 }, { x: 551, y: 793, r: 7 }, { x: 605, y: 825, r: 5.5 }, { x: 680, y: 823, r: 7 }],
    links: [[0, 1], [0, 2], [1, 2], [1, 3], [1, 4], [2, 3], [3, 4]],
  },
  {
    id: 'project', label: '项目', color: '#83dfba', labelX: 939, labelY: 753, labelWidth: 80,
    points: [{ x: 901, y: 787, r: 7 }, { x: 946, y: 822, r: 7 }, { x: 990, y: 854, r: 6.5 }, { x: 910, y: 859, r: 5.5 }, { x: 851, y: 833, r: 6 }],
    links: [[0, 1], [0, 3], [0, 4], [1, 2], [1, 3], [1, 4], [2, 3]],
  },
]
const backgroundPoints = [
  { x: 425, y: 350, r: 5 }, { x: 446, y: 471, r: 7 }, { x: 394, y: 529, r: 6 },
  { x: 707, y: 623, r: 8 }, { x: 621, y: 675, r: 5 }, { x: 839, y: 673, r: 6 },
  { x: 946, y: 510, r: 4 }, { x: 1024, y: 474, r: 7 }, { x: 1079, y: 525, r: 6 },
]
const backgroundLinks = [[0, 1], [1, 2], [1, 3], [2, 4], [3, 4], [3, 5], [3, 7], [5, 6], [6, 7], [6, 8], [7, 8]]
const search = ref('')
const candidates = ref(true)
const filtersApplied = ref(false)
const expanded = ref(false)
const selectedId = ref('cache')
const activeTab = ref('概览')
const view = ref('星图')
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragOrigin = ref({ x: 0, y: 0, panX: 0, panY: 0 })
const svg = ref<SVGSVGElement | null>(null)
const selectedCluster = computed(() => clusters.find(cluster => cluster.id === selectedId.value))
const selectedTitle = computed(() => selectedCluster.value?.label ?? (selectedId.value === 'strategy' ? '对账策略' : '缓存一致性'))
const isCache = computed(() => selectedId.value === 'cache')
const graphTransform = computed(() => `translate(${585 + pan.value.x} ${469 + pan.value.y}) scale(${zoom.value}) translate(-703 -469)`)
const visibleClusters = computed(() => clusters.filter(cluster => {
  const matchesSearch = !search.value.trim() || cluster.label.toLowerCase().includes(search.value.trim().toLowerCase())
    || '缓存一致性'.includes(search.value.trim())
  // 原图有未勾选但可见的主题；先完整保留参考态，用户操作后再应用筛选。
  const matchesTopic = !filtersApplied.value || topics.value.some(topic => topic.id === cluster.id && topic.checked)
  return matchesSearch && matchesTopic
}))
const showIncludes = computed(() => relationships.value.find(item => item.id === 'includes')?.checked)
const showDepends = computed(() => relationships.value.find(item => item.id === 'depends')?.checked)
const showSimilar = computed(() => relationships.value.find(item => item.id === 'similar')?.checked)
const showCompares = computed(() => relationships.value.find(item => item.id === 'compares')?.checked)

function selectNode(id: string) {
  selectedId.value = id
  activeTab.value = '概览'
}
function setZoom(value: number) { zoom.value = Math.min(1.8, Math.max(0.5, Math.round(value * 100) / 100)) }
function resetView() { zoom.value = 1; pan.value = { x: 0, y: 0 } }
function startPan(event: PointerEvent) {
  if (event.button !== 0 || (event.target as Element).closest('[data-node]')) return
  dragging.value = true
  dragOrigin.value = { x: event.clientX, y: event.clientY, panX: pan.value.x, panY: pan.value.y }
  svg.value?.setPointerCapture(event.pointerId)
}
function movePan(event: PointerEvent) {
  if (!dragging.value || !svg.value) return
  const displayScale = svg.value.getBoundingClientRect().width / 1418
  pan.value = {
    x: dragOrigin.value.panX + (event.clientX - dragOrigin.value.x) / displayScale,
    y: dragOrigin.value.panY + (event.clientY - dragOrigin.value.y) / displayScale,
  }
}
function endPan(event: PointerEvent) {
  dragging.value = false
  if (svg.value?.hasPointerCapture(event.pointerId)) svg.value.releasePointerCapture(event.pointerId)
}
function wheelZoom(event: WheelEvent) { setZoom(zoom.value + (event.deltaY < 0 ? 0.08 : -0.08)) }
function chooseView(label: string) {
  if (label === '列表') { emit('navigate', 10); return }
  if (label === '白板') { emit('navigate', 13); return }
  view.value = label
}
function practice() {
  emit('notify', `开始练习：${selectedTitle.value}`)
  emit('navigate', 15)
}
</script>

<template>
  <section class="graph-scene" aria-label="知识星图">
    <svg ref="svg" class="graph-map" :class="{ 'is-dragging': dragging }" viewBox="0 0 1418 1024"
      aria-label="可平移缩放的知识关系图" @pointerdown="startPan" @pointermove="movePan"
      @pointerup="endPan" @pointercancel="endPan" @lostpointercapture="dragging = false" @wheel.prevent="wheelZoom">
      <defs>
        <radialGradient id="g9-node"><stop stop-color="white" stop-opacity=".35" /><stop offset="1" stop-color="white" stop-opacity="0" /></radialGradient>
        <radialGradient id="g9-amber"><stop stop-color="#ffe3a1" /><stop offset=".55" stop-color="#ffc45e" /><stop offset="1" stop-color="#c47d24" /></radialGradient>
        <radialGradient id="g9-halo"><stop stop-color="#ffb737" stop-opacity=".22" /><stop offset=".4" stop-color="#b87718" stop-opacity=".08" /><stop offset="1" stop-color="#b87718" stop-opacity="0" /></radialGradient>
        <linearGradient id="g9-star" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#fff7cf" /><stop offset=".5" stop-color="#ffcf76" /><stop offset="1" stop-color="#e19a30" /></linearGradient>
        <filter id="g9-glow" x="-150%" y="-150%" width="400%" height="400%"><feGaussianBlur stdDeviation="5" /></filter>
        <marker id="g9-arrow-amber" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse"><path d="M0 0 10 5 0 10 3 5Z" fill="#efac4e" /></marker>
        <marker id="g9-arrow-teal" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0 10 5 0 10 3 5Z" fill="#82dbd3" /></marker>
      </defs>
      <g class="graph-backdrop" transform="translate(-118 0)" fill="none">
        <ellipse cx="701" cy="506" rx="703" ry="377" stroke="#b88c4c" stroke-opacity=".07" stroke-dasharray="3 5" transform="rotate(-9 701 506)" />
        <ellipse cx="703" cy="505" rx="466" ry="278" stroke="#b88c4c" stroke-opacity=".07" stroke-dasharray="3 6" transform="rotate(14 703 505)" />
        <path d="M118 243Q582 98 1004 142M118 853Q321 875 437 1024M1177 1024Q1401 867 1514 760" stroke="#b78c4c" stroke-width=".6" stroke-opacity=".34" />
        <path d="m390 470 638 2M705 239v631M437 794l579-447M488 301l500 554" stroke="#8e7955" stroke-opacity=".08" stroke-dasharray="2 6" />
        <circle cx="339" cy="938" r="10" fill="#e9a84c" opacity=".1" />
        <circle cx="339" cy="938" r="3.5" fill="#f5b44f" />
      </g>
      <g :transform="graphTransform">
        <g class="graph-background-nodes">
          <path v-for="(pair, index) in backgroundLinks" :key="`bg-${index}`"
            :d="`M${backgroundPoints[pair[0]!]!.x} ${backgroundPoints[pair[0]!]!.y}L${backgroundPoints[pair[1]!]!.x} ${backgroundPoints[pair[1]!]!.y}`" />
          <path d="M425 350 487 301M446 471 703 469M394 529 441 601M707 623 703 469M621 675 579 728M839 673 895 623M946 510 1015 346M1024 474 1015 346M1079 525 990 854M680 823 901 787M520 694 579 728" />
          <circle v-for="(point, index) in backgroundPoints" :key="`faint-${index}`" :cx="point.x" :cy="point.y" :r="point.r"
            :fill="index === 3 ? '#b59767' : '#9d9e93'" :opacity="index === 3 ? '.46' : '.25'" />
        </g>
        <g v-if="showIncludes" class="graph-primary-links">
          <path v-if="visibleClusters.some(item => item.id === 'distributed')" d="M628 338Q667 374 690 434" stroke="#edaa4c" marker-start="url(#g9-arrow-amber)" marker-end="url(#g9-arrow-amber)" />
        </g>
        <g v-if="showDepends" class="graph-primary-links">
          <path v-if="visibleClusters.some(item => item.id === 'jvm')" d="M735 447Q799 381 877 334" stroke="#82dbd3" marker-end="url(#g9-arrow-teal)" />
          <path v-if="visibleClusters.some(item => item.id === 'database')" d="M688 482Q618 548 545 592" stroke="#82dbd3" marker-end="url(#g9-arrow-teal)" />
        </g>
        <path v-if="showSimilar" class="graph-extra-link" d="M613 322Q737 224 879 332" />
        <path v-if="showCompares" class="graph-extra-link" d="M476 662Q680 859 901 787" />
        <g v-for="cluster in visibleClusters" :key="cluster.id" :style="{ color: cluster.color }">
          <path v-for="(pair, index) in cluster.links" :key="`link-${index}`" class="graph-cluster-link"
            :d="`M${cluster.points[pair[0]]!.x} ${cluster.points[pair[0]]!.y}L${cluster.points[pair[1]]!.x} ${cluster.points[pair[1]]!.y}`" />
          <g v-for="(point, index) in cluster.points" :key="`node-${index}`" class="graph-point" data-node
            role="button" tabindex="0" :aria-label="`${cluster.label}知识点 ${index + 1}`"
            @click="selectNode(cluster.id)" @keydown.enter.prevent="selectNode(cluster.id)" @keydown.space.prevent="selectNode(cluster.id)">
            <circle :cx="point.x" :cy="point.y" :r="point.r + 8" fill="currentColor" opacity=".07" filter="url(#g9-glow)" />
            <circle :cx="point.x" :cy="point.y" :r="point.r" fill="currentColor" opacity=".83" />
            <circle :cx="point.x" :cy="point.y" :r="point.r" fill="url(#g9-node)" />
            <circle :cx="point.x" :cy="point.y" r="15" fill="transparent" />
          </g>
          <g data-node class="graph-node-label" :class="{ 'is-selected': selectedId === cluster.id }" role="button" tabindex="0"
            :aria-label="cluster.label" @click="selectNode(cluster.id)" @keydown.enter.prevent="selectNode(cluster.id)" @keydown.space.prevent="selectNode(cluster.id)">
            <rect :x="cluster.labelX" :y="cluster.labelY" :width="cluster.labelWidth" height="48" rx="22" />
            <text :x="cluster.labelX + cluster.labelWidth / 2" :y="cluster.labelY + 32" text-anchor="middle">{{ cluster.label }}</text>
          </g>
        </g>
        <g v-if="candidates" class="graph-candidate">
          <path d="M732 491Q808 556 879 607" fill="none" stroke="#f0ad45" stroke-width="1.8" stroke-dasharray="12 9" marker-end="url(#g9-arrow-amber)" />
          <g data-node role="button" tabindex="0" aria-label="对账策略" @click="selectNode('strategy')" @keydown.enter.prevent="selectNode('strategy')" @keydown.space.prevent="selectNode('strategy')">
            <circle cx="895" cy="623" r="24" fill="#e7a33a" opacity=".12" filter="url(#g9-glow)" />
            <circle cx="895" cy="623" r="7.5" fill="#ffca63" />
            <rect x="903" y="599" width="115" height="48" rx="23" class="graph-strategy-label" />
            <text x="959" y="632" text-anchor="middle">对账策略</text>
          </g>
        </g>
        <g data-node class="graph-core" role="button" tabindex="0" aria-label="缓存一致性" :aria-pressed="isCache"
          @click="selectNode('cache')" @keydown.enter.prevent="selectNode('cache')" @keydown.space.prevent="selectNode('cache')">
          <circle cx="703" cy="469" r="116" fill="url(#g9-halo)" />
          <ellipse cx="705" cy="464" rx="115" ry="19" transform="rotate(-20 705 464)" fill="none" stroke="#ffc65a" stroke-width="1.25" />
          <path d="m703 442 7 18 18 9-18 7-7 20-8-20-17-7 17-8Z" fill="#ffcf72" filter="url(#g9-glow)" />
          <path d="m703 443 6 17 17 9-17 7-6 18-7-18-17-7 17-8Z" fill="url(#g9-star)" />
          <rect x="729" y="438" width="160" height="57" rx="19" />
          <text x="809" y="476" text-anchor="middle">缓存一致性</text>
        </g>
      </g>
    </svg>

    <aside class="graph-filters" aria-label="星图筛选">
      <label class="graph-search">
        <RIcon name="search" :size="22" />
        <input v-model="search" aria-label="搜索知识点" placeholder="搜索知识点..." />
      </label>
      <h2>主题筛选</h2>
      <div class="graph-topic-options">
        <label v-for="topic in topics" :key="topic.id" :class="`topic-${topic.id}`">
          <input v-model="topic.checked" class="r-checkbox" type="checkbox" @change="filtersApplied = true" />
          <span>{{ topic.label }}</span>
        </label>
      </div>
      <button class="graph-expand" :aria-expanded="expanded" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开全部' }}<RIcon :name="expanded ? 'chevron-up' : 'chevron-down'" :size="17" />
      </button>
      <div v-if="expanded" class="graph-topic-summary"><span v-for="cluster in clusters" :key="cluster.id">{{ cluster.label }}</span></div>
      <div class="graph-filter-divider" />
      <h2>关系类型</h2>
      <div class="graph-relation-options">
        <label v-for="relationship in relationships" :key="relationship.id">
          <input v-model="relationship.checked" type="checkbox" class="r-checkbox" />
          <span>{{ relationship.label }}</span>
        </label>
      </div>
      <div class="graph-filter-divider graph-candidate-divider" />
      <div class="graph-candidate-toggle"><h2>候选关系</h2><button class="r-switch" role="switch" :aria-checked="candidates" aria-label="显示候选关系" @click="candidates = !candidates" /></div>
      <p class="graph-candidate-help">显示潜在关联</p>
    </aside>

    <nav class="graph-view-switch" aria-label="知识视图">
      <button v-for="item in [{ label: '星图', icon: 'star' }, { label: '列表', icon: 'list' }, { label: '白板', icon: 'square' }]"
        :key="item.label" :class="{ selected: view === item.label }" :aria-pressed="view === item.label" @click="chooseView(item.label)">
        <RIcon :name="item.icon" :size="23" />{{ item.label }}
      </button>
    </nav>

    <aside class="graph-detail" aria-label="知识点详情">
      <header class="graph-detail-heading">
        <div class="graph-detail-title">
          <svg class="graph-detail-star" viewBox="0 0 48 54" aria-hidden="true"><path d="m24 1 7 18 16 8-17 6-6 20-7-20-16-6 16-7Z" fill="url(#g9-star)" /></svg>
          <h1>{{ selectedTitle }}</h1>
          <button class="graph-open" aria-label="打开知识点" title="打开知识点" @click="emit('navigate', 12)"><RIcon name="external-link" :size="29" /></button>
        </div>
        <p>概念 <span>v4 · {{ isCache ? '8' : selectedCluster ? String(selectedCluster.links.length) : '1' }} 条已确认关系</span></p>
      </header>
      <nav class="graph-detail-tabs" aria-label="知识详情标签">
        <button v-for="tab in ['概览', '笔记', '关系', '练习', '证据']" :key="tab" :class="{ selected: activeTab === tab }"
          :aria-pressed="activeTab === tab" @click="activeTab = tab">{{ tab }}</button>
      </nav>
      <div v-if="isCache && activeTab === '概览'" class="graph-overview">
        <section class="graph-definition">
          <h2><RIcon name="file-text" :size="23" />定义</h2>
          <p>缓存一致性是指在分布式系统中，缓存与数据源之间的数据保持一致的能力，常见策略包括 Cache Aside、Write Through、Write Back 等。</p>
        </section>
        <section class="graph-core-notes">
          <h2><RIcon name="file-text" :size="23" />核心笔记</h2>
          <ul>
            <li>常见问题：缓存脏数据、数据不一致</li>
            <li>解决方案：更新时删除缓存 / 双写策略</li>
            <li>适用场景：高并发读多写少的系统</li>
          </ul>
        </section>
        <section class="graph-evidence">
          <h2><RIcon name="link" :size="23" />相关证据</h2>
          <button @click="emit('navigate', 6)"><RIcon name="file-text" :size="24" /><span>分布式系统设计与实现.pdf</span><RIcon name="chevron-right" :size="22" /></button>
        </section>
      </div>
      <div v-else class="graph-alternate-content">
        <template v-if="activeTab === '笔记'">
          <h2><RIcon name="file-text" :size="23" />核心笔记</h2>
          <button @click="emit('navigate', 11)">分布式缓存策略<RIcon name="chevron-right" :size="20" /></button>
        </template>
        <template v-else-if="activeTab === '关系' || !isCache">
          <h2><RIcon name="share-2" :size="23" />{{ selectedTitle }}</h2>
          <button v-for="cluster in visibleClusters" :key="cluster.id" @click="selectNode(cluster.id)"><span class="graph-mini-dot" :style="{ background: cluster.color }" />{{ cluster.label }}<RIcon name="chevron-right" :size="20" /></button>
          <button v-if="!isCache" @click="selectNode('cache')"><span class="graph-mini-dot graph-amber-dot" />缓存一致性<RIcon name="chevron-right" :size="20" /></button>
        </template>
        <template v-else-if="activeTab === '练习'">
          <h2><RIcon name="play" :size="23" />练习</h2>
          <button @click="practice">缓存删除失败以后<RIcon name="chevron-right" :size="20" /></button>
        </template>
        <template v-else>
          <h2><RIcon name="link" :size="23" />相关证据</h2>
          <button @click="emit('navigate', 6)">分布式系统设计与实现.pdf<RIcon name="chevron-right" :size="20" /></button>
        </template>
      </div>
      <button class="graph-practice" @click="practice"><RIcon name="crosshair" :size="24" />练这一点</button>
    </aside>

    <div class="graph-bottom-tools">
      <div class="graph-legend" aria-label="星图图例">
        <span><i class="legend-core" />核心关系</span>
        <span><i class="legend-related" />关联关系</span>
        <span><i class="legend-candidate" />候选关系</span>
      </div>
      <div class="graph-zoom">
        <button aria-label="缩小星图" title="缩小" @click="setZoom(zoom - 0.1)"><RIcon name="minus" :size="23" /></button>
        <button class="graph-zoom-value" aria-label="重置星图视图" title="重置视图" @click="resetView">{{ Math.round(zoom * 100) }}%</button>
        <button aria-label="放大星图" title="放大" @click="setZoom(zoom + 0.1)"><RIcon name="plus" :size="23" /></button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.graph-scene { position: absolute; inset: 0; overflow: hidden; color: #f4f0e8; font-size: 19px; }
.graph-map { position: absolute; inset: 0; width: 1418px; height: 1024px; touch-action: none; cursor: grab; user-select: none; }
.graph-map.is-dragging { cursor: grabbing; }
.graph-background-nodes path { fill: none; stroke: #a29372; stroke-width: .7; stroke-opacity: .09; stroke-dasharray: 2 5; }
.graph-primary-links { fill: none; stroke-width: 1.6; }
.graph-cluster-link { fill: none; stroke: currentColor; stroke-width: .8; opacity: .29; }
.graph-point, .graph-node-label, .graph-core, .graph-candidate [data-node] { cursor: pointer; outline: none; }
.graph-point:focus-visible > circle:last-child { stroke: #ffe3af; stroke-width: 2; }
.graph-node-label rect, .graph-strategy-label { fill: #11100dc9; stroke: #b1a18b25; stroke-width: 1; }
.graph-node-label text, .graph-candidate text { fill: #f5f3ee; font-size: 22px; font-weight: 550; }
.graph-node-label:hover rect, .graph-node-label.is-selected rect, .graph-node-label:focus-visible rect { stroke: #c49b5c; fill: #392b1899; }
.graph-extra-link { fill: none; stroke: #b8bdac; stroke-width: 1; stroke-dasharray: 5 7; opacity: .55; }
.graph-core rect { fill: #49301066; stroke: #c78e32; stroke-width: .8; }
.graph-core text { fill: #fff6e0; font-size: 24px; font-weight: 650; }
.graph-core:focus-visible rect { stroke: #ffe3aa; stroke-width: 2; }
.graph-filters { position: absolute; z-index: 2; left: 18px; top: 121px; width: 253px; height: 707px; padding: 19px 20px; border: 1px solid #4d4437; border-radius: 22px; background: linear-gradient(120deg, #1b1913e8, #12110ee8); }
.graph-search { display: flex; align-items: center; gap: 11px; height: 46px; padding: 0 12px; color: #c1beb5; border: 1px solid #ffffff05; border-radius: 25px; background: #ffffff03; }
.graph-search input { width: 155px; min-width: 0; padding: 0; color: #eee9dc; font-size: 18px; border: 0; outline: none; background: transparent; }
.graph-search input::placeholder { color: #9d998f; }
.graph-filters h2 { margin: 18px 0 15px; font-size: 19px; font-weight: 600; line-height: 1.5; }
.graph-topic-options { position: relative; height: 193px; }
.graph-topic-options > label { position: absolute; left: 0; display: flex; align-items: center; gap: 10px; line-height: 27px; }
.graph-topic-options .topic-distributed { top: 0; }
.graph-topic-options .topic-jvm { top: 39px; }
.graph-topic-options .topic-database { top: 62px; }
.graph-topic-options .topic-spring { top: 98px; }
.graph-topic-options .topic-spring-extra { top: 120px; }
.graph-topic-options .topic-project { top: 157px; }
.graph-filters .r-checkbox { width: 25px; height: 25px; border-radius: 6px; border-color: #565148; background: linear-gradient(130deg, #49473e, #2b2924); }
.graph-filters .r-checkbox:checked { border-color: #d4a368; background: linear-gradient(130deg, #e8ad60, #a66b2d); }
.graph-filters .topic-jvm .r-checkbox:checked { background: linear-gradient(130deg, #a57a45, #68431f); border-color: #a17640; }
.graph-filters .r-checkbox:checked::after { width: 12px; height: 7px; }
.graph-expand { display: inline-flex; align-items: center; gap: 5px; margin-top: 4px; color: #bfbab0; font-size: 18px; }
.graph-filter-divider { height: 1px; margin: 13px 2px 13px; background: #aca08b18; }
.graph-filter-divider + h2 { margin-top: 0; margin-bottom: 16px; }
.graph-relation-options { display: flex; flex-direction: column; gap: 17px; }
.graph-relation-options label { display: flex; align-items: center; gap: 10px; line-height: 26px; }
.graph-candidate-divider { margin-top: 21px; margin-bottom: 16px; }
.graph-candidate-toggle { display: flex; align-items: center; justify-content: space-between; }
.graph-candidate-toggle h2 { margin: 0; }
.graph-candidate-toggle .r-switch { width: 54px; height: 31px; background: linear-gradient(110deg, #aa7937, #e6b76b) !important; border: 0 !important; }
.graph-candidate-toggle .r-switch::after { width: 26px; height: 26px; left: 2px; top: 2px; background: #f7ecd8; }
.graph-candidate-toggle .r-switch[aria-checked="true"]::after { left: 26px; }
.graph-candidate-toggle .r-switch[aria-checked="false"] { background: #494139 !important; }
.graph-candidate-help { margin-top: 8px; color: #b6b2a9; font-size: 18px; }
.graph-topic-summary { position: absolute; top: 345px; left: 18px; z-index: 3; width: 215px; display: flex; flex-wrap: wrap; gap: 10px; padding: 15px; border: 1px solid #7d6748; border-radius: 12px; background: #262019; box-shadow: 0 5px 8px #0008; }
.graph-topic-summary span { font-size: 16px; }
.graph-view-switch { position: absolute; z-index: 2; left: 454px; top: 110px; width: 392px; height: 57px; display: flex; align-items: center; padding: 4px; border: 1px solid #4f42305c; border-radius: 31px; background: linear-gradient(125deg, #26201566, #17151080); }
.graph-view-switch > button { display: flex; align-items: center; justify-content: center; flex: 1; gap: 11px; height: 47px; color: #d6d2c7; font-size: 21px; border-radius: 25px; }
.graph-view-switch > button.selected { background: linear-gradient(120deg, #a87935be, #99652caa); box-shadow: inset 0 0 0 1px #cda45c80; color: #fff7e6; font-weight: 600; }
.graph-detail { position: absolute; z-index: 2; top: 131px; left: 976px; width: 415px; height: 777px; border: 1px solid #796447; border-radius: 22px; background: linear-gradient(120deg, #31271480, #14120ee8 25%, #171510f0); overflow: hidden; }
.graph-detail-heading { padding: 24px 22px 0; height: 128px; }
.graph-detail-title { display: flex; align-items: center; gap: 13px; height: 47px; }
.graph-detail-star { width: 47px; height: 53px; flex: none; filter: drop-shadow(0 0 12px #eeab43a6); }
.graph-detail-title h1 { margin: 0; font-size: 29px; font-weight: 650; white-space: nowrap; }
.graph-open { margin-left: auto; display: grid; place-items: center; width: 35px; height: 37px; color: #f0eee6; background: #ffffff05 !important; border-radius: 9px; }
.graph-detail-heading > p { color: #aaa697; font-size: 18px; padding-top: 6px; }
.graph-detail-heading > p span { margin-left: 18px; }
.graph-detail-tabs { display: flex; gap: 0; align-items: center; height: 52px; padding: 0 22px 6px; border-bottom: 1px solid #b7a27b0d; }
.graph-detail-tabs button { flex: 1; height: 43px; color: #ddd8cd; font-size: 19px; white-space: nowrap; border-radius: 24px; }
.graph-detail-tabs button.selected { flex: 1.06; color: #fff4dc; background: linear-gradient(120deg, #8d622ca6, #67431c9c); box-shadow: inset 0 1px 1px #b58c4f70; font-weight: 600; }
.graph-overview { padding: 0 23px; }
.graph-overview h2, .graph-alternate-content h2 { display: flex; align-items: center; gap: 11px; font-size: 20px; font-weight: 600; line-height: 30px; }
.graph-overview h2 .r-icon, .graph-alternate-content h2 .r-icon { color: #dbd7c9; }
.graph-definition { padding-top: 23px; padding-bottom: 22px; border-bottom: 1px solid #a690661a; }
.graph-definition p { margin-top: 12px; color: #eae5da; font-size: 18.5px; line-height: 34px; }
.graph-core-notes { padding-top: 18px; padding-bottom: 21px; border-bottom: 1px solid #a690661a; }
.graph-core-notes ul { margin: 12px 0 0; padding: 0; list-style: none; color: #e6e0d4; font-size: 18px; line-height: 35px; white-space: nowrap; }
.graph-core-notes li::before { content: ""; display: inline-block; width: 8px; height: 8px; margin: 0 12px 2px 2px; border-radius: 50%; background: #d49b4e; }
.graph-evidence { padding-top: 17px; }
.graph-evidence > button { display: flex; align-items: center; gap: 11px; width: 100%; height: 58px; margin-top: 12px; padding: 0 15px; border: 1px solid #ffffff05; border-radius: 13px; background: linear-gradient(120deg, #28251dd9, #24221bd9); font-size: 19px; }
.graph-evidence > button span { flex: 1; text-align: left; white-space: nowrap; }
.graph-evidence > button > .r-icon:last-child { color: #aaa594; }
.graph-practice { position: absolute; display: flex; align-items: center; justify-content: center; gap: 11px; left: 23px; right: 23px; bottom: 23px; height: 58px; border: 1px solid #b18136 !important; border-radius: 16px; color: #191207; font-size: 21px; font-weight: 650; background: linear-gradient(120deg, #b88a42, #ac7c35) !important; box-shadow: inset 0 1px 2px #efd39860; }
.graph-practice:hover { filter: brightness(1.1); }
.graph-bottom-tools { position: absolute; z-index: 2; left: 331px; top: 927px; width: 638px; height: 64px; display: flex; align-items: center; padding: 0 23px 0 29px; border: 1px solid #8c6c3f80; border-radius: 34px; background: linear-gradient(115deg, #3a2a10a3, #12110eeb 60%); }
.graph-legend { display: flex; align-items: center; gap: 29px; }
.graph-legend > span { display: flex; align-items: center; gap: 10px; white-space: nowrap; font-size: 18px; color: #d4cfc3; }
.graph-legend > span:first-child { color: #ffe4a2; }
.graph-legend i { width: 16px; height: 16px; border-radius: 50%; flex: none; }
.legend-core { background: linear-gradient(120deg, #ffc768, #d79a39); }
.legend-related { background: linear-gradient(120deg, #8bdfd6, #69c8c6); }
.legend-candidate { background: linear-gradient(120deg, #8b8a80, #615f57); }
.graph-zoom { height: 34px; margin-left: auto; padding-left: 34px; border-left: 1px solid #aaa18e66; display: flex; align-items: center; gap: 8px; }
.graph-zoom > button:not(.graph-zoom-value) { display: grid; place-items: center; width: 39px; height: 39px; border: 1px solid #a59b8320; border-radius: 50%; color: #c4bfb2; background: linear-gradient(130deg, #25231b70, #14130e); }
.graph-zoom-value { min-width: 60px; color: #ded9cc; font-size: 19px; text-align: center; }
.graph-alternate-content { padding: 24px 23px; }
.graph-alternate-content > button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 17px 0; border-bottom: 1px solid #ffffff0c; color: #ded6c7; font-size: 18px; text-align: left; }
.graph-alternate-content > button .r-icon:last-child { margin-left: auto; }
.graph-mini-dot { width: 10px; height: 10px; border-radius: 50%; }
.graph-amber-dot { background: #eab75e; }
</style>
