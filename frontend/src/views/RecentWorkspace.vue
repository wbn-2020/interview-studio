<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NSelect, NSkeleton } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { RecentEntry, RecentKind, RecentPage } from '../api/recent'
import { recentCursor, recentEntryRoute, recentIdValid, recentKindLabels, recentKindOptions, recentKindValid, recentPageValid, recentStatusLabel, recentSubtypeLabel, recentTimeLabel } from '../api/recent'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useRecentStore } from '../state/recent'
import RIcon from '../replica/RIcon.vue'

const workspace = useWorkspaceStore(), access = useTrainingAccessStore(), recent = useRecentStore()
const route = useRoute(), router = useRouter()
const kind = ref<RecentKind>(), page = shallowRef<RecentPage>(), cursor = ref<string>(), previous = ref<(string | undefined)[]>([])
function selectKind(value: RecentKind | null) {
  void router.push({ query: { kind: value ?? undefined } })
}
const loading = ref(false), problem = ref(''), navigationProblem = ref(''), paused = ref(false), visible = ref(document.visibilityState === 'visible')
const listedSequence = ref(0), selectedId = ref('')
const previewKey = computed(() => typeof route.query.entry === 'string' ? route.query.entry : '')
const preview = computed(() => previewKey.value ? page.value?.items.find(entry => `${entry.kind}/${entry.id}` === previewKey.value) : page.value?.items[0])
function previewEntry(entry: RecentEntry) { void router.replace({ query: { ...route.query, entry: `${entry.kind}/${entry.id}` } }) }
const readable = computed(() => workspace.sessionActive && Boolean(recent.ownerId) && visible.value && !access.checking && !access.challenge
  && !access.restricted && !workspace.changes.get('training-access-command')?.locked)
const routePrevious = computed(() => {
  const value = route.query.previous
  return value === undefined ? [] : Array.isArray(value) ? value : [value]
})
const routeProblem = computed(() => {
  const q = route.query, filter = q.kind
  if (route.name !== 'recent' || Object.keys(q).some(key => !['kind', 'cursor', 'previous', 'entry'].includes(key))
    || filter !== undefined && !recentKindValid(filter)) return '最近访问筛选参数无效，请回到首页。'
  const selectedKind = recentKindValid(filter) ? filter : undefined
  if (q.cursor !== undefined && !recentCursor(q.cursor, selectedKind)
    || routePrevious.value.length > 40 || routePrevious.value.some((value, index) => !(index === 0 && value === 'start') && !recentCursor(value, selectedKind))
    || routePrevious.value.length > 0 && q.cursor === undefined) return '最近访问分页参数无效，请回到首页。'
  if (q.entry !== undefined) {
    if (typeof q.entry !== 'string') return '所选最近访问记录无效。'
    const [entryKind, id, extra] = q.entry.split('/')
    if (extra !== undefined || !recentKindValid(entryKind) || !recentIdValid(id)
      || selectedKind && entryKind !== selectedKind) return '所选最近访问记录无效。'
  }
  return ''
})
const listAddress = computed(() => JSON.stringify([route.query.kind, route.query.cursor, routePrevious.value, routeProblem.value]))
const changed = computed(() => Boolean(page.value && listedSequence.value !== recent.changeSequence))
let alive = true, generation = 0, firstRead = true
let lastRequest: { cursor?: string; previous: (string | undefined)[] } | undefined

function clear() {
  generation++; loading.value = false; page.value = undefined; cursor.value = undefined; previous.value = []
  problem.value = ''; navigationProblem.value = ''; selectedId.value = ''; lastRequest = undefined
}
watch([readable, () => workspace.sessionEpoch, () => recent.ownerId], ([allowed], before) => {
  if (!allowed || before && (before[1] !== workspace.sessionEpoch || before[2] !== recent.ownerId)) { clear(); paused.value = true }
  if (allowed && firstRead && !routeProblem.value) { firstRead = false; paused.value = false; loadAddress() }
}, { flush: 'sync' })
watch(listAddress, () => { clear(); paused.value = false; if (readable.value && !routeProblem.value) loadAddress() }, { flush: 'sync' })
watch(() => route.query.kind, value => { kind.value = recentKindValid(value) ? value : undefined }, { immediate: true, flush: 'sync' })
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  if (firstRead && readable.value && !routeProblem.value) { firstRead = false; loadAddress() }
})
onUnmounted(() => { alive = false; clear(); document.removeEventListener('visibilitychange', visibilityChanged) })

async function load(after?: string, stack: (string | undefined)[] = []) {
  if (!readable.value || routeProblem.value || loading.value) return
  const ticket = ++generation, epoch = workspace.sessionEpoch, owner = recent.ownerId, accessEpoch = currentTrainingAccessEpoch(), filter = kind.value
  const sequence = recent.changeSequence
  lastRequest = { cursor: after, previous: [...stack] }
  page.value = undefined; loading.value = true; paused.value = false; problem.value = ''; navigationProblem.value = ''
  const query = new URLSearchParams({ limit: '20' })
  if (filter) query.set('kind', filter)
  if (after) query.set('cursor', after)
  const current = () => alive && readable.value && ticket === generation && epoch === workspace.sessionEpoch
    && owner === recent.ownerId && accessEpoch === currentTrainingAccessEpoch() && filter === kind.value && !routeProblem.value
  try {
    const result = await read<unknown>(`/recent?${query}`)
    if (!current()) return
    if (!recentPageValid(result, filter, after)) throw new ApiError(502, 'RECENT_PAGE_INVALID', '最近访问的身份、排序或分页回执无法核对，请重新读取。')
    page.value = result; cursor.value = after; previous.value = [...stack]; listedSequence.value = sequence
  } catch (error) { if (current()) { page.value = undefined; problem.value = errorText(error) } }
  finally { if (alive && ticket === generation) loading.value = false }
}
function loadAddress() {
  kind.value = recentKindValid(route.query.kind) ? route.query.kind : undefined
  void load(typeof route.query.cursor === 'string' ? route.query.cursor : undefined,
    routePrevious.value.map(value => value === 'start' ? undefined : value as string))
}
function refresh() {
  if (loading.value) return
  firstRead = false
  if (route.query.cursor !== undefined) void router.replace({ query: { kind: kind.value } })
  else loadAddress()
}
function retryRead() { if (lastRequest && !loading.value) void load(lastRequest.cursor, lastRequest.previous) }
function nextPage() {
  if (page.value?.nextCursor) void router.push({ query: { kind: kind.value, cursor: page.value.nextCursor,
    previous: [...previous.value, cursor.value].slice(-40).map(value => value ?? 'start') } })
}
function previousPage() {
  if (previous.value.length) void router.push({ query: { kind: kind.value, cursor: previous.value.at(-1),
    previous: previous.value.length > 1 ? previous.value.slice(0, -1).map(value => value ?? 'start') : undefined } })
}
async function open(entry: RecentEntry) {
  const target = recentEntryRoute(entry)
  if (!target || !readable.value || selectedId.value) return
  const ticket = generation, epoch = workspace.sessionEpoch, owner = recent.ownerId
  selectedId.value = `${entry.kind}/${entry.id}`; navigationProblem.value = ''
  try {
    // 记录只恢复对象入口，正式保稿及资料权限继续由原路由和详情处理。
    const failure = await router.push(target)
    if (failure && alive && readable.value && generation === ticket && epoch === workspace.sessionEpoch && owner === recent.ownerId) navigationProblem.value = '页面未切换，请先处理当前页面的保存或资料访问条件。'
  } catch { if (alive && readable.value && generation === ticket && epoch === workspace.sessionEpoch && owner === recent.ownerId) navigationProblem.value = '暂时无法打开该页面，请稍后重试。' }
  finally { selectedId.value = '' }
}
</script>

<template>
  <section class="recent-workspace" aria-labelledby="recent-title">
    <header class="page-heading"><div><h1 id="recent-title">最近访问</h1><p class="muted">回到你已打开的内容，继续准备与思考。</p></div><NButton :disabled="!readable || loading || !!routeProblem" :loading="loading" @click="refresh">刷新最近访问</NButton></header>
    <section v-if="recent.pendingCount || recent.message" class="recent-pending" aria-label="访问记录保存状态">
      <div><strong v-if="recent.pendingCount">{{ recent.pendingCount }} 条访问记录待确认</strong><p v-if="recent.message" role="status">{{ recent.message }}</p><p class="field-help">待确认请求只保留在本页所在的浏览器实例中，关闭后无法继续恢复。正常保存和页面切换不受影响。</p></div>
      <NButton v-if="recent.pendingCount" :disabled="!recent.canRetry" :loading="recent.busy" @click="recent.retryPending">恢复待确认记录</NButton>
    </section>
    <p v-if="routeProblem" class="notice error" role="alert">{{ routeProblem }}<RouterLink class="text-link" :to="{ name: 'recent' }">打开首页</RouterLink></p>
    <div class="recent-toolbar"><label for="recent-kind">内容类型</label><NSelect id="recent-kind" :value="kind" :options="recentKindOptions" clearable placeholder="全部类型" :disabled="!readable || loading || !!routeProblem" @update:value="selectKind" /></div>
    <p v-if="!readable || paused" class="notice" role="status">{{ !readable ? '目录暂不可读，已清除本页标题和数据。' : '访问条件或页面可见性已变化，请主动刷新目录。' }}</p>
    <p v-if="changed" class="notice" role="status">访问记录有变化，可刷新查看最新顺序。</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text :disabled="!readable || loading" @click="retryRead">重新读取这一页</NButton></p>
    <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
    <div v-if="loading" class="recent-skeleton" aria-label="正在读取最近访问" role="status"><NSkeleton text :repeat="5" /><span class="field-help">正在核对当前对象与访问顺序…</span></div>
    <template v-else-if="page && readable">
      <p v-if="previewKey && !preview" class="notice" role="status">所选记录不在本次可读页中；它可能已移位或不可访问。<button class="text-link" @click="router.replace({ query: { ...route.query, entry: undefined } })">返回当前页首项</button></p>
      <p v-if="page.scanLimited" class="notice">本次已检查 64 条访问索引，当前页尚未填满。可继续下一页查看其余记录。</p>
      <div v-if="!page.items.length" class="recent-empty"><h2>{{ page.nextCursor ? '这一段没有可展示的对象' : kind ? '此类型暂无可展示的访问记录' : '从你打开的第一份内容开始' }}</h2><p>{{ page.nextCursor ? '部分对象可能已不存在或当前不可用；继续下一页核对。' : '打开知识、准备材料或训练等内容后，这里会保留返回入口。访问记录不表示学习或训练已经完成。' }}</p><RouterLink v-if="!page.nextCursor" class="text-link" :to="{ name: 'knowledge' }">浏览知识空间</RouterLink></div>
      <div v-else class="recent-browser">
        <div class="recent-table"><div class="recent-table-heading"><span>对象</span><span>类型 / 当前状态</span><span>最近访问</span><span aria-hidden="true"></span></div><ol class="recent-list"><li v-for="entry in page.items" :key="`${entry.kind}/${entry.id}`">
          <button type="button" :class="{ selected: preview?.id === entry.id && preview?.kind === entry.kind }" :aria-pressed="preview?.id === entry.id && preview?.kind === entry.kind" :disabled="!readable || !!selectedId" @click="previewEntry(entry)" @dblclick="open(entry)"><span class="recent-name"><RIcon :name="entry.kind === 'KNOWLEDGE' ? 'layers' : 'file-text'" :size="18" /><strong>{{ entry.title }}</strong></span><span class="recent-category">{{ recentSubtypeLabel(entry) }}<small>{{ recentStatusLabel(entry) }}</small></span><time :datetime="entry.visitedAt">{{ recentTimeLabel(entry.visitedAt) }}</time><RIcon class="recent-open" name="chevron-right" :size="18" /></button>
        </li></ol></div>
        <aside v-if="preview" class="recent-object-preview"><div class="recent-object-symbol"><RIcon name="layers" :size="52" /></div><h2>{{ preview.title }}</h2><span class="status-pill">{{ recentKindLabels[preview.kind] }} · {{ recentSubtypeLabel(preview) }}</span><div class="recent-current-state"><h3>当前状态</h3><p>{{ recentStatusLabel(preview) }}</p></div><p class="recent-object-note"><RIcon name="info" :size="23" />访问不构成学习完成</p><NButton type="primary" :disabled="!readable || !!selectedId" @click="open(preview)"><RIcon name="play" :size="21" />打开当前对象</NButton><p class="field-help">不恢复历史版本或滚动位置</p></aside>
      </div>
      <nav v-if="previous.length || page.nextCursor" class="pagination" aria-label="最近访问分页"><NButton :disabled="!previous.length || !readable || loading" @click="previousPage">上一页</NButton><NButton :disabled="!page.nextCursor || !readable || loading" @click="nextPage">下一页</NButton><span class="field-help">第 {{ previous.length + 1 }} 页</span></nav>
    </template>
    <p class="workspace-footnote">同一对象只保留一个入口，标题和状态以本次读取为准。打开后进入当前内容页；历史版本、原回答及具体位置在原模块中查看。新访问会改变排序，翻页期间可刷新回到最新。</p>
  </section>
</template>

<style scoped>
.recent-workspace { max-width: 1120px; margin: 0 auto; }
.recent-workspace .page-heading { align-items: start; } .recent-workspace .page-heading > button { flex: none; }
.recent-pending { display: flex; align-items: start; justify-content: space-between; gap: 18px; margin-bottom: 22px; padding: 17px 19px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.recent-pending > div { display: grid; gap: 7px; } .recent-pending strong { font-size: 14px; } .recent-pending p { max-width: 72ch; font-size: 12px; }
.recent-toolbar { display: flex; align-items: center; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--line); }
.recent-toolbar label { color: var(--muted); font-size: 12px; } .recent-toolbar .n-select { width: 210px; }
.recent-list { list-style: none; margin: 0; padding: 0; } .recent-list li { border-bottom: 1px solid var(--line); }
.recent-list button { display: grid; width: 100%; grid-template-columns: 92px minmax(0, 1fr) 156px 58px; align-items: center; gap: 18px; padding: 19px 8px; text-align: left; border-radius: 5px; }
.recent-list button:hover:not(:disabled) { background: var(--surface-soft); } .recent-category { color: var(--muted); font-size: 12px; }
.recent-name { display: grid; gap: 6px; min-width: 0; } .recent-name strong { font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.recent-name > span, time { color: var(--muted); font-size: 12px; } time { font-variant-numeric: tabular-nums; } .recent-open { color: var(--brand); font-size: 12px; text-align: right; }
.recent-empty { display: grid; gap: 13px; max-width: 65ch; padding: 44px 0; } .recent-empty h2 { font-size: 20px; } .recent-empty p { color: var(--muted); }
.recent-skeleton { display: grid; gap: 15px; padding: 25px 0; } .pagination { margin: 20px 0; }
@media (max-width: 760px) { .recent-list button { grid-template-columns: minmax(0, 1fr) auto; gap: 8px 15px; } .recent-category { grid-column: 1; } .recent-name { grid-column: 1; grid-row: 2; } time { grid-column: 1; grid-row: 3; } .recent-open { grid-column: 2; grid-row: 2; } .recent-pending, .recent-workspace .page-heading { flex-wrap: wrap; } }
</style>

<style scoped>
.recent-workspace { max-width: none; }.recent-browser { display: grid; grid-template-columns: minmax(0,1fr) 288px; gap: 32px; margin-top: 24px; align-items: start; }.recent-table,.recent-object-preview { min-width: 0; }.recent-toolbar { padding-top: 0; padding-bottom: 20px; border-bottom-color: var(--studio-divider); }
.recent-table-heading { display: grid; grid-template-columns: minmax(0,1.5fr) minmax(100px,1fr) 100px 18px; gap: 16px; padding: 0 12px 14px; font-size: 12px; color: var(--faint); }.recent-list li { border-bottom-color: var(--studio-divider); }.recent-list button { grid-template-columns: minmax(0,1.5fr) minmax(100px,1fr) 100px 18px; gap: 16px; padding: 20px 12px; border-radius: 0; }.recent-list button.selected { background: var(--brand-soft); }.recent-name { display: flex; align-items: center; gap: 12px; }.recent-name svg { width: 18px; color: var(--muted); flex: none; }.recent-name strong { font-size: 14px; font-weight: 500; }.recent-category { color: var(--muted); font-size: 12px; }.recent-category small { display: block; margin-top: 4px; font-size: 11px; }.recent-list time { font-size: 12px; color: var(--muted); }
.recent-object-preview { position: sticky; top: 132px; padding: 24px; display: grid; align-content: start; gap: 18px; border: 1px solid var(--studio-divider); border-radius: 10px; background: var(--studio-pane); }.recent-object-symbol { width: 36px; height: 36px; border-radius: 7px; display: grid; place-items: center; color: var(--brand); background: var(--brand-soft); }.recent-object-symbol svg { width: 20px; height: 20px; }.recent-object-preview h2 { font-size: 17px; overflow-wrap: anywhere; }.recent-object-preview .status-pill { justify-self: start; white-space: normal; }.recent-current-state { padding-top: 18px; border-top: 1px solid var(--studio-divider); }.recent-current-state h3 { font-size: 12px; color: var(--muted); font-weight: 400; }.recent-current-state p { margin-top: 8px; font-size: 13px; }.recent-object-note { display: flex; gap: 8px; align-items: center; font-size: 12px; color: var(--muted); }.recent-object-note svg { width: 16px; flex: none; }
@container workspace (max-width: 880px) { .recent-browser { grid-template-columns: minmax(0,1fr); gap: 24px; }.recent-object-preview { position: static; grid-column: 1; grid-row: 1; }.recent-table { grid-row: 2; } }
@media(max-width:760px) { .recent-table-heading { display: none; }.recent-list button { grid-template-columns: minmax(0,1fr) 18px; gap: 6px; padding: 18px 8px; }.recent-name { grid-column: 1; grid-row: 1; }.recent-category { grid-column: 1; grid-row: 2; padding-left: 30px; }.recent-list time { grid-column: 1; grid-row: 3; padding-left: 30px; }.recent-open { grid-column: 2; grid-row: 1/4; }.recent-object-preview { padding: 20px; } }
</style>
