<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NInput, NSelect, NSkeleton } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import type { SearchCriteria, SearchDomain, SearchHit, SearchPage, SearchSource } from '../api/search'
import {
  getSearchPage, getSearchSource, searchDomains, searchFieldFragment, searchFragmentParts, searchHitKey,
  searchHitValid, searchKindLabel, searchProjectLabels, searchSourceRoute, searchSourceValid, searchStatusLabels, searchSupersededLabel, searchTextParts,
  searchVerificationLabels, searchVersionLabel,
} from '../api/search'
import SearchSourceContext from '../components/search/SearchSourceContext.vue'
import AiSourceQaCreate from '../components/ai/AiSourceQaCreate.vue'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'
import { readSearchPrefill, SEARCH_PREFILL_EVENT } from '../navigation/commands'

const store = useWorkspaceStore(), access = useTrainingAccessStore()
const route = useRoute(), router = useRouter()
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)
const input = ref(''), domain = ref<SearchDomain>('KNOWLEDGE'), status = ref<SearchCriteria['status']>('ACTIVE')
const submitted = shallowRef<SearchCriteria | null>(null)
const page = shallowRef<SearchPage | null>(null), source = shallowRef<SearchSource | null>(null)
const selected = shallowRef<SearchHit | null>(null)
const offset = ref(0), previousOffsets = ref<number[]>([])
const loading = ref(false), detailLoading = ref(false)
const formError = ref(''), listError = ref(''), detailError = ref('')
const paused = ref(false), detailHeading = ref<HTMLElement>()
const prefillError = ref(''), hasPrefill = ref(false), clearingPrefill = ref(false)
let listGeneration = 0, detailGeneration = 0, disposed = false
let selectedTrigger: HTMLElement | undefined
const statusOptions = [{ label: '未归档内容', value: 'ACTIVE' }, { label: '已归档内容', value: 'ARCHIVED' }]
const currentScope = computed(() => searchDomains.find(option => option.value === domain.value)?.scope)
const criteriaChanged = computed(() => submitted.value && (input.value.trim() !== submitted.value.q
  || domain.value !== submitted.value.domain || status.value !== submitted.value.status))
const rows = computed(() => page.value?.items.map(item => ({ item, key: searchHitKey(item),
  fragments: item.fragments.map(fragment => ({ fragment, parts: searchFragmentParts(fragment) })),
})) ?? [])
const sourceTarget = computed(() => source.value ? searchSourceRoute(source.value.item) : undefined)
const aiDecisionChanged = computed(() => {
  const before = selected.value?.aiFeedback, after = source.value?.item.aiFeedback
  return Boolean(before && after && (before.decisionRevision !== after.decisionRevision || before.decisionChoice !== after.decisionChoice))
})
const sourceFields = computed(() => source.value?.fields.map(field => {
  const fragment = searchFieldFragment(field, source.value!.item.fragments)
  return { field, fragment, parts: fragment ? searchTextParts(field.text, fragment.matchStart, fragment.matchEnd)
    : { before: field.text, match: '', after: '' } }
}) ?? [])
const indexNotice = computed(() => {
  if (!page.value || page.value.domain !== 'KNOWLEDGE') return ''
  switch (page.value.indexStatus) {
    case 'DISABLED': return '服务器尚未启用知识全文检索，当前空页不表示没有知识。可以前往知识库浏览。'
    case 'DEGRADED': return '知识索引暂不可完整使用，当前结果可能不完整。可稍后刷新，或在知识库查看索引状态。'
    case 'BUILDING': return '知识索引正在同步或重建，当前结果可能尚未包含刚刚发布或整理的内容。'
    default: return ''
  }
})
const emptyMessage = computed(() => {
  if (page.value?.applicable === false) {
    if (page.value.domain === 'TRAINING_ANSWER') return '正式提交没有归档状态。切换到“未归档内容”查找；训练结束不代表提交被归档。'
    if (page.value.domain === 'REVIEW_FEEDBACK') return '人工反馈没有归档状态。切换到“未归档内容”查找已开放反馈；训练结束不代表反馈被归档。'
    if (page.value.domain === 'AI_FEEDBACK') return 'AI 评价没有归档状态。切换到“未归档内容”查找已开放评价；训练结束不代表报告被归档。'
    return '资料摘录没有归档状态。切换到“未归档内容”查找已保存的摘录。'
  }
  if (indexNotice.value || page.value?.scanLimited) return '当前可核对范围内没有匹配项。请结合上方状态提示调整检索词或稍后刷新。'
  return '当前来源和状态中没有找到匹配项。试试更短的检索词，或切换来源与归档状态。'
})

function clearDetail() {
  detailGeneration++
  selected.value = null
  source.value = null
  detailError.value = ''
  detailLoading.value = false
}
function clearResults() {
  listGeneration++
  page.value = null
  loading.value = false
  listError.value = ''
  clearDetail()
  selectedTrigger = undefined
}
function applyRoutePrefill() {
  if (route.name !== 'search' || disposed) return
  // 路由预填只改变条件；先废弃旧请求，避免前进／后退或同地址命令带回旧正文。
  clearResults()
  submitted.value = null
  offset.value = 0
  previousOffsets.value = []
  paused.value = false
  formError.value = ''
  input.value = ''
  domain.value = 'KNOWLEDGE'
  status.value = 'ACTIVE'
  const prefill = readSearchPrefill(route.query)
  prefillError.value = prefill.kind === 'INVALID' ? prefill.message : ''
  hasPrefill.value = prefill.kind === 'VALID'
  if (prefill.kind === 'VALID') {
    input.value = prefill.criteria.q
    domain.value = prefill.criteria.domain
    status.value = prefill.criteria.status
  }
}
async function clearRoutePrefill() {
  if (clearingPrefill.value) return
  clearingPrefill.value = true
  try {
    const failure = await router.replace({ name: 'search' })
    if (failure) formError.value = '链接条件尚未清除，请完成当前页面提示后重试。'
  } catch (reason) { formError.value = errorText(reason) }
  finally { clearingPrefill.value = false }
}
watch(() => route.fullPath, applyRoutePrefill, { immediate: true, flush: 'sync' })
watch([readable, () => store.sessionEpoch], ([allowed], previous) => {
  if (!allowed || previous && previous[1] !== store.sessionEpoch) {
    // App 会保留被遮挡的页面；权限变化必须实际清除正文，并让已发请求失去展示资格。
    clearResults()
    paused.value = Boolean(submitted.value)
  }
}, { flush: 'sync' })
onMounted(() => window.addEventListener(SEARCH_PREFILL_EVENT, applyRoutePrefill))
onUnmounted(() => {
  window.removeEventListener(SEARCH_PREFILL_EVENT, applyRoutePrefill)
  disposed = true
  clearResults()
})

function validInput(): string | undefined {
  const q = input.value.trim()
  if (!q || q.length > 100 || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(q)) {
    formError.value = '请输入 1–100 个字符的完整检索词。'
    return undefined
  }
  formError.value = ''
  return q
}
function submit() {
  if (!readable.value || prefillError.value) return
  const q = validInput()
  if (q === undefined) return
  previousOffsets.value = []
  void loadPage({ domain: domain.value, q, status: status.value }, 0)
}
function changeDomain(value: SearchDomain) {
  if (domain.value === value || !readable.value || prefillError.value) return
  domain.value = value
  const hadSearch = Boolean(submitted.value)
  clearResults()
  submitted.value = null
  previousOffsets.value = []
  paused.value = false
  if (hadSearch) submit()
}
function changeStatus(value: SearchCriteria['status']) {
  if (!readable.value || prefillError.value) return
  status.value = value
  const hadSearch = Boolean(submitted.value)
  clearResults()
  submitted.value = null
  previousOffsets.value = []
  paused.value = false
  if (hadSearch) submit()
}
async function loadPage(criteria: SearchCriteria, targetOffset: number) {
  if (!readable.value) return
  clearResults()
  const generation = listGeneration, sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const filter = { ...criteria }
  submitted.value = filter
  offset.value = targetOffset
  loading.value = true
  paused.value = false
  const current = () => !disposed && readable.value && generation === listGeneration && store.sessionEpoch === sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && submitted.value === filter && offset.value === targetOffset
  try {
    const result = await getSearchPage(filter, targetOffset)
    if (!current()) return
    if (result.domain !== filter.domain || result.status !== filter.status || result.offset !== targetOffset
      || result.limit !== 20 || !Array.isArray(result.items) || result.items.length > 20
      || typeof result.query !== 'string' || typeof result.applicable !== 'boolean' || typeof result.hasMore !== 'boolean' || typeof result.scanLimited !== 'boolean'
      || result.items.some(item => !searchHitValid(item) || item.domain !== filter.domain
        || ['KNOWLEDGE', 'PREPARATION', 'QUESTION', 'RECOLLECTION'].includes(item.domain) && item.status !== filter.status)
      || new Set(result.items.map(searchHitKey)).size !== result.items.length
      || !result.applicable && (result.items.length > 0 || result.hasMore)) {
      throw new ApiError(502, 'SEARCH_RESPONSE_INVALID', '搜索响应与本次条件不一致，请重新查询。')
    }
    page.value = result
  } catch (reason) { if (current()) listError.value = errorText(reason) }
  finally { if (generation === listGeneration) loading.value = false }
}
function retryList() { if (submitted.value) void loadPage(submitted.value, offset.value) }
function refresh() {
  if (!submitted.value) return
  previousOffsets.value = []
  void loadPage(submitted.value, 0)
}
function nextPage() {
  if (!readable.value || loading.value || !submitted.value || !page.value?.hasMore || !page.value.items.length) return
  const target = offset.value + page.value.items.length
  if (target > 1000) return
  previousOffsets.value.push(offset.value)
  void loadPage(submitted.value, target)
}
function previousPage() {
  if (!readable.value || loading.value || !submitted.value || !previousOffsets.value.length) return
  const target = previousOffsets.value.pop()!
  void loadPage(submitted.value, target)
}
async function selectHit(hit: SearchHit, event?: Event) {
  if (!readable.value || loading.value || !submitted.value) return
  clearDetail()
  selected.value = hit
  if (event?.currentTarget instanceof HTMLElement) selectedTrigger = event.currentTarget
  const filter = submitted.value, generation = detailGeneration, queryGeneration = listGeneration
  const sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), identity = searchHitKey(hit)
  detailLoading.value = true
  const current = () => !disposed && readable.value && generation === detailGeneration && queryGeneration === listGeneration
    && store.sessionEpoch === sessionEpoch && accessEpoch === currentTrainingAccessEpoch()
    && submitted.value === filter && selected.value !== null && searchHitKey(selected.value) === identity
  try {
    const result = await getSearchSource(hit, page.value?.query ?? filter.q)
    if (!current()) return
    if (!searchSourceValid(result, hit)) {
      throw new ApiError(502, 'SEARCH_SOURCE_INVALID', '来源响应与所选版本不一致，请重新打开。')
    }
    source.value = result
    await nextTick()
    if (current()) {
      detailHeading.value?.focus({ preventScroll: true })
      detailHeading.value?.scrollIntoView({ block: 'nearest' })
    }
  } catch (reason) { if (current()) detailError.value = errorText(reason) }
  finally { if (generation === detailGeneration) detailLoading.value = false }
}
function retryDetail() { if (selected.value) void selectHit(selected.value) }
async function closeDetail() {
  const trigger = selectedTrigger
  clearDetail()
  selectedTrigger = undefined
  await nextTick()
  if (readable.value && !disposed && trigger?.isConnected) trigger.focus()
}
</script>

<template>
  <div class="unified-search">
    <header class="page-heading">
      <div><p class="section-label">工作台 / 统一搜索</p><h1>找回一段内容，回到它的出处</h1><p class="muted">查找知识、材料、题面、训练回答、人工反馈、AI 评价和面后回忆，按确切来源打开原文。</p></div>
      <RouterLink class="text-link" :to="{ name: 'knowledge' }">前往知识库</RouterLink>
    </header>
    <p v-if="prefillError" class="notice error" role="alert">{{ prefillError }} <NButton text :loading="clearingPrefill" @click="clearRoutePrefill">清除链接条件</NButton></p>
    <p v-else-if="hasPrefill" class="notice" role="status">链接条件已填入表单。请核对或调整后点击“搜索”，页面不会自动查询。链接中的检索词会保留在地址和浏览器历史中；后续手动编辑只改变当前表单。</p>
    <form class="unified-search-form" @submit.prevent="submit">
      <fieldset :disabled="!readable || Boolean(prefillError)" class="search-domain-picker"><legend>搜索来源</legend><div class="search-domains">
        <button v-for="option in searchDomains" :key="option.value" type="button" :class="{ active: domain === option.value }" :aria-pressed="domain === option.value" @click="changeDomain(option.value)">{{ option.label }}</button>
      </div></fieldset>
      <div class="search-inputs">
        <div class="search-keyword"><label for="unified-search-q">检索词</label><NInput id="unified-search-q" v-model:value="input" :disabled="!readable || Boolean(prefillError)" :maxlength="100" placeholder="例如：缓存一致性、ConcurrentHashMap" :input-props="{ autocomplete: 'off', 'aria-describedby': 'unified-search-scope' }" /></div>
        <div class="search-state"><label for="unified-search-status">内容状态</label><NSelect id="unified-search-status" :value="status" :options="statusOptions" :disabled="!readable || Boolean(prefillError)" @update:value="changeStatus" /></div>
        <NButton type="primary" attr-type="submit" :loading="loading" :disabled="!readable || Boolean(prefillError) || !input.trim()">搜索</NButton>
      </div>
      <p id="unified-search-scope" class="field-help">{{ currentScope }}</p>
      <p v-if="domain === 'REVIEW_FEEDBACK'" class="notice">仅搜索已在复盘确认开放的人工反馈。<RouterLink class="text-link" :to="{ name: 'review' }">前往复盘确认</RouterLink></p>
      <p v-if="domain === 'AI_FEEDBACK'" class="notice">仅搜索已在复盘确认开放的 AI 评价。本次有界扫描最多 64 份报告、8 MiB 原文，结果可能不完整；更多评价请回原复盘查看。<RouterLink class="text-link" :to="{ name: 'review' }">前往复盘</RouterLink></p>
      <p v-if="formError" class="notice error" role="alert">{{ formError }}</p>
    </form>
    <p v-if="!readable" class="notice" role="status">资料读取暂不可用，请先完成登录或训练资料查阅确认。</p>
    <p v-else-if="paused" class="notice" role="status">会话或资料权限已变化，旧结果已清除。可重新搜索以读取当前结果。</p>
    <template v-if="readable">
      <p v-if="criteriaChanged" class="notice">检索词尚未提交，下面仍显示上一次查询的结果。点击“搜索”更新。</p>
      <div v-if="submitted" class="search-results-header">
        <div><h2>{{ searchDomains.find(option => option.value === submitted?.domain)?.label }} · “{{ page?.query ?? submitted.q }}”</h2><p class="field-help">{{ submitted.status === 'ARCHIVED' ? '已归档内容' : '未归档内容' }} · {{ page?.matchMode === 'ANALYZED' || submitted.domain === 'KNOWLEDGE' ? '分词检索' : '完整检索词匹配，忽略大小写' }}</p></div>
        <NButton size="small" :disabled="loading" @click="refresh">从首页刷新</NButton>
      </div>
      <p v-if="indexNotice" class="notice" role="status">{{ indexNotice }}</p>
      <p v-if="page?.scanLimited" class="notice" role="status"><template v-if="page.domain === 'AI_FEEDBACK'">本次已达报告数、原文字节或分页上限，只覆盖本次扫描范围。空页或末页不表示全库无命中，调整检索词也不会扩大报告扫描预算；更多评价请回原复盘查看。</template><template v-else>本次查询已达扫描或分页上限，结果可能不完整。可缩短或调整检索词；当前页结束不代表没有其他匹配项。</template></p>
      <p v-if="listError" class="notice error" role="alert">{{ listError }} <NButton text @click="retryList">重试本页</NButton></p>
      <div v-if="!submitted" class="search-empty"><h2>选择一种来源开始查找</h2><p>知识支持分词检索，其余来源按完整检索词匹配。搜索不会调用 AI，也不会把内容加入模型上下文。</p></div>
      <div v-else class="search-content" :class="{ 'with-source': selected }">
        <section class="search-results" aria-label="搜索结果" :aria-busy="loading">
          <div v-if="loading" class="search-skeleton" role="status" aria-label="正在搜索"><NSkeleton text :repeat="5" :sharp="false" /><span class="field-help">正在读取当前来源…</span></div>
          <p v-else-if="page && !rows.length" class="empty-inline">{{ emptyMessage }}</p>
          <article v-for="row in rows" :key="row.key" class="unified-result" :class="{ selected: selected && searchHitKey(selected) === row.key }">
            <div class="result-heading"><button class="text-link" :aria-expanded="selected !== null && searchHitKey(selected) === row.key" aria-controls="search-source-panel" @click="selectHit(row.item, $event)">{{ row.item.title }}</button><span class="status-pill">{{ searchVersionLabel(row.item) }}</span></div>
            <p class="result-meta">{{ searchKindLabel(row.item) }} · {{ searchStatusLabels[row.item.status] ?? row.item.status }}<span v-if="!row.item.currentVersion"> · {{ searchSupersededLabel(row.item) }}</span></p>
            <p v-if="row.item.kind === 'FACT'" class="field-help">当前事实核实：{{ searchVerificationLabels[row.item.verification ?? ''] ?? '状态待核对' }} · 所属项目：{{ searchProjectLabels[row.item.projectStatus ?? ''] ?? '状态待核对' }}</p>
            <p v-if="row.item.kind === 'EXPRESSION'" class="field-help">表达的事实依据未在搜索中复核。</p>
            <SearchSourceContext :hit="row.item" compact />
            <div v-for="entry in row.fragments" :key="entry.fragment.field" class="result-fragment"><span class="field-help">{{ entry.fragment.label }} · 第 {{ entry.fragment.lineStart }}{{ entry.fragment.lineEnd > entry.fragment.lineStart ? `–${entry.fragment.lineEnd}` : '' }} 行</span><p>{{ entry.parts.before }}<mark v-if="entry.parts.match">{{ entry.parts.match }}</mark>{{ entry.parts.after }}</p></div>
            <p v-if="!row.fragments.length" class="field-help">分词或组织元数据命中，当前版本正文没有完整检索词的定位片段。</p>
            <NButton text size="small" @click="selectHit(row.item, $event)">查看命中的原版本</NButton>
          </article>
          <nav v-if="page || listError" class="pagination" aria-label="搜索结果翻页"><NButton size="small" :disabled="loading || !previousOffsets.length" @click="previousPage">上一页</NButton><span class="field-help">第 {{ previousOffsets.length + 1 }} 页</span><NButton size="small" :disabled="loading || !page?.hasMore || !page.items.length || offset + page.items.length > 1000" @click="nextPage">下一页</NButton></nav>
        </section>
        <aside v-if="selected" id="search-source-panel" class="search-source" aria-label="命中来源原文" :aria-busy="detailLoading">
          <div class="source-heading"><div><p class="section-label">确切来源</p><h2 ref="detailHeading" tabindex="-1">{{ source?.item.title ?? selected.title }}</h2></div><NButton size="small" quaternary @click="closeDetail">关闭原文</NButton></div>
          <div v-if="detailLoading" class="search-skeleton" role="status" aria-label="正在读取确切来源"><NSkeleton text :repeat="6" /><span class="field-help">正在读取命中版本…</span></div>
          <p v-if="detailError" class="notice error" role="alert">{{ detailError }} <NButton text @click="retryDetail">重试原文</NButton></p>
          <template v-if="source">
            <div class="row-actions"><span class="status-pill">{{ searchVersionLabel(source.item) }}</span><span class="status-pill">{{ searchStatusLabels[source.item.status] ?? source.item.status }}</span><RouterLink v-if="sourceTarget" class="text-link" :to="sourceTarget">在原模块打开此版本</RouterLink></div>
            <p v-if="!source.item.currentVersion" class="notice">{{ searchSupersededLabel(source.item) }}，下面保留本次命中的原文。当前状态不代表历史内容已经重新核实。</p>
            <p v-if="source.item.kind === 'FACT'" class="notice">当前事实核实：{{ searchVerificationLabels[source.item.verification ?? ''] ?? '状态待核对' }}；所属项目：{{ searchProjectLabels[source.item.projectStatus ?? ''] ?? '状态待核对' }}。核实状态与版本内容分别保留。</p>
            <p v-if="source.item.kind === 'EXPRESSION'" class="notice">此处展示表达原文；关联事实是否仍有效，需要在准备材料中复核。</p>
            <SearchSourceContext :hit="source.item" />
            <p v-if="aiDecisionChanged" class="notice" role="status">这项观察的决定自列表读取后已有变化，以下显示刚刚核对的当前决定；报告原文与固定身份保持不变。</p>
            <p class="field-help">仅展示本来源的可检索字段，保留原始文字。高亮最多覆盖前三个命中字段，不表示内容已经成为证据或获得 AI 外发许可。</p>
            <section v-for="entry in sourceFields" :key="entry.field.id" class="source-field"><h3>{{ entry.field.label }}</h3><p v-if="entry.fragment" class="field-help">命中第 {{ entry.fragment.lineStart }}{{ entry.fragment.lineEnd > entry.fragment.lineStart ? `–${entry.fragment.lineEnd}` : '' }} 行</p><pre tabindex="0" :aria-label="`${entry.field.label}原文`">{{ entry.parts.before }}<mark v-if="entry.parts.match">{{ entry.parts.match }}</mark>{{ entry.parts.after }}</pre></section>
            <details class="source-identity"><summary>来源身份</summary><dl><dt>{{ source.item.domain === 'AI_FEEDBACK' ? 'AI 任务标识' : '内容标识' }}</dt><dd>{{ source.item.id }}</dd><dt>{{ source.item.domain === 'MATERIAL_EXCERPT' ? '固定摘录标识' : source.item.domain === 'TRAINING_ANSWER' ? '正式提交标识' : source.item.domain === 'REVIEW_FEEDBACK' ? '反馈标识' : source.item.domain === 'AI_FEEDBACK' ? '成功执行标识' : '版本标识' }}</dt><dd>{{ source.item.versionId }}</dd><template v-if="source.item.aiFeedback"><dt>报告原文摘要</dt><dd>{{ source.item.aiFeedback.reportHash }}</dd><dt>观察编号</dt><dd>{{ source.item.aiFeedback.feedbackId }}</dd></template><template v-if="source.item.domain === 'REVIEW_FEEDBACK'"><dt>反馈修订</dt><dd>r{{ source.item.versionNumber }}</dd></template><template v-if="source.item.training"><dt>训练标识</dt><dd>{{ source.item.training.answer.sessionId }}</dd><dt>训练题标识</dt><dd>{{ source.item.training.answer.questionId }}</dd><dt>原答标识</dt><dd>{{ source.item.training.answer.answerId }}</dd></template><template v-if="source.item.attachmentId"><dt>附件标识</dt><dd>{{ source.item.attachmentId }}</dd></template><template v-if="source.item.runId"><dt>解析记录标识</dt><dd>{{ source.item.runId }}</dd></template></dl></details>
          </template>
        </aside>
      </div>
      <p v-if="submitted" class="workspace-footnote">翻页期间资料可能发生变化。需要重新核对时，从首页刷新；这里不显示未经核对的命中总数。</p>
    </template>
    <AiSourceQaCreate :source="readable ? source ?? undefined : undefined" :disabled="!readable" @source-rejected="clearResults" />
  </div>
</template>

<style scoped>
.unified-search { max-width: 1280px; margin: 0 auto; }
.unified-search .page-heading { align-items: start; }
.unified-search .page-heading > a { flex: none; margin-top: 12px; font-size: 12px; }
.unified-search-form { display: grid; gap: 14px; padding: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
.search-domain-picker { min-width: 0; margin: 0; padding: 0; border: 0; }
.search-domain-picker legend, .search-inputs label { margin-bottom: 7px; color: var(--muted); font-size: 12px; }
.search-domains { display: flex; flex-wrap: wrap; gap: 5px; }
.search-domains button { min-height: 36px; padding: 6px 14px; border: 1px solid transparent; border-radius: 6px; color: var(--muted); }
.search-domains button:hover { background: var(--surface-soft); }
.search-domains button.active { border-color: var(--brand); color: var(--brand-strong); background: var(--brand-soft); font-weight: 650; }
.search-inputs { display: flex; align-items: end; gap: 12px; }
.search-inputs label { display: block; }
.search-keyword { flex: 1; min-width: 0; }
.search-state { width: 150px; flex: none; }
.search-inputs > button { flex: none; }
.search-results-header { display: flex; justify-content: space-between; align-items: center; gap: 15px; margin: 24px 0 12px; }
.search-results-header h2 { overflow-wrap: anywhere; font-size: 17px; }
.search-results-header .field-help { margin-top: 5px; }
.search-results-header > button { flex: none; }
.search-content { border-top: 1px solid var(--line); }
.search-content.with-source { display: grid; grid-template-columns: minmax(240px, .9fr) minmax(0, 1.25fr); gap: 24px; }
.search-results { min-width: 0; }
.search-skeleton { display: grid; gap: 12px; padding: 24px 0; }
.unified-result { display: grid; gap: 8px; padding: 20px 4px; border-bottom: 1px solid var(--line); }
.unified-result.selected { border-left: 3px solid var(--brand); padding-left: 13px; }
.result-heading { display: flex; align-items: start; gap: 12px; }
.result-heading > button { flex: 1; min-width: 0; text-align: left; overflow-wrap: anywhere; font-size: 15px; }
.result-meta { color: var(--muted); font-size: 12px; }
.result-fragment { display: grid; gap: 4px; }
.result-fragment > p { max-width: 75ch; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
mark { border-radius: 2px; background: var(--brand-soft); color: var(--brand-strong); box-shadow: 0 1px 0 var(--brand); }
.unified-result > button { justify-self: start; }
.search-source { min-width: 0; align-self: start; display: grid; gap: 16px; padding: 22px; border: 1px solid var(--line); border-top: 0; border-radius: 0 0 8px 8px; background: var(--surface); }
.source-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
.source-heading h2 { margin-top: 4px; overflow-wrap: anywhere; font-size: 19px; }
.source-heading > button { flex: none; }
.search-source .notice { margin: 0; }
.source-field { display: grid; gap: 6px; min-width: 0; }
.source-field h3 { font-size: 13px; }
.source-field pre { max-height: 360px; overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.8 Consolas, "Microsoft YaHei", monospace; }
.source-identity { padding-top: 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 11px; }
.source-identity summary { cursor: pointer; }
.source-identity dl { display: grid; gap: 5px; }
.source-identity dt { margin-top: 5px; }
.source-identity dd { margin: 0; overflow-wrap: anywhere; font-family: Consolas, monospace; }
.search-empty { display: grid; gap: 10px; padding: 44px 0; max-width: 65ch; }
.search-empty h2 { font-size: 18px; }
.search-empty p { color: var(--muted); font-size: 13px; }
.pagination { margin-bottom: 16px; }
@media (max-width: 1100px) { .search-content.with-source { grid-template-columns: 1fr; gap: 0; } .with-source .search-results { max-height: 480px; overflow: auto; padding-right: 6px; } .search-source { border-top: 1px solid var(--line); border-radius: 8px; } }
@media (max-width: 600px) { .unified-search-form, .search-source { padding: 15px; } .search-inputs { flex-wrap: wrap; gap: 10px; } .search-keyword { flex-basis: 100%; } .search-state { flex: 1; width: auto; } .search-domains button { padding: 6px 10px; } .search-results-header { align-items: start; } .result-heading { flex-wrap: wrap; gap: 6px; } .result-heading > button { flex-basis: 100%; } .source-heading { flex-wrap: wrap; } }
</style>
