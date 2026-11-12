<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiPreparationLink, AiPreparationSelection } from '../../api/ai'
import { aiPreparationPlanTextValid, aiSourceKindLabel, isAiPreparationKind } from '../../api/ai'
import type { PreparationItem, PreparationPage, PreparationSummary } from '../../api/preparation'
import { expressionOptions, referenceLabels } from '../../api/preparation'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean; excludeIds?: string[]; kinds?: AiPreparationLink['kind'][] }>()
const emit = defineEmits<{ choose: [source: AiPreparationSelection]; busy: [value: boolean] }>()
const store = useWorkspaceStore()
const allowedKinds = computed<AiPreparationLink['kind'][]>(() => props.kinds?.length ? props.kinds : ['TARGET', 'PROJECT', 'FACT', 'EXPRESSION'])
const kind = ref<AiPreparationLink['kind']>(allowedKinds.value[0] ?? 'TARGET')
const options = computed(() => allowedKinds.value.map(value => ({ value, label: aiSourceKindLabel(value) })))
const input = ref('')
const search = ref('')
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const choosing = ref(false)
const chooseError = ref('')
type ExpressionItem = Extract<PreparationItem, { kind: 'EXPRESSION' }>
const reviewedExpression = shallowRef<ExpressionItem>()
const reviewedReferences = computed(() => {
  const value = reviewedExpression.value
  return value && expressionReferencesValid(value) ? value.references : []
})
const blocked = computed(() => !store.sessionActive || Boolean(props.disabled))
let alive = true
let selectionRequest = 0

const results = useQuery({
  queryKey: computed(() => ['preparation', store.sessionEpoch, 'ai-source-picker', kind.value, search.value, cursor.value]),
  enabled: computed(() => !blocked.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const selectedKind = kind.value
    const query = search.value
    const before = cursor.value
    const parameters = new URLSearchParams({ kind: selectedKind, status: 'ACTIVE', q: query, limit: '20' })
    if (selectedKind === 'FACT') parameters.set('verification', 'CONFIRMED')
    if (before) parameters.set('cursor', before)
    const value = await read<PreparationPage>(`/prepare/items?${parameters}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || selectedKind !== kind.value || query !== search.value || before !== cursor.value) {
      throw new ApiError(0, 'AI_PICKER_CONTEXT_CHANGED', '选择条件已变化，请重新读取当前范围。')
    }
    return value
  },
})

watch(kind, () => { cursor.value = undefined; previous.value = []; chooseError.value = '' }, { flush: 'sync' })
watch(allowedKinds, value => { if (!value.includes(kind.value)) kind.value = value[0] ?? 'TARGET' }, { flush: 'sync', deep: true })
watch([kind, search, cursor, () => allowedKinds.value.join(','), () => store.sessionEpoch, blocked], () => { selectionRequest++; choosing.value = false; reviewedExpression.value = undefined }, { flush: 'sync' })
watch(choosing, value => emit('busy', value), { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; selectionRequest++; emit('busy', false) })

function submit() {
  if (blocked.value || choosing.value) return
  search.value = input.value.trim()
  cursor.value = undefined
  previous.value = []
  chooseError.value = ''
}
async function choose(summary: PreparationSummary) {
  if (blocked.value || choosing.value || results.isFetching.value || !isAiPreparationKind(summary.kind) || summary.kind !== kind.value || !allowedKinds.value.includes(summary.kind) || props.excludeIds?.includes(summary.id)) return
  const epoch = store.sessionEpoch
  const generation = ++selectionRequest
  const selectedKind = summary.kind
  choosing.value = true
  chooseError.value = ''
  reviewedExpression.value = undefined
  try {
    const value = await read<PreparationItem>(`/prepare/items/${summary.id}`)
    if (!alive || blocked.value || epoch !== store.sessionEpoch || generation !== selectionRequest || selectedKind !== kind.value) return
    // 目录与详情之间若改版，要求重新选择；不能把用户点选的旧版本悄悄换成最新正文。
    if (value.id !== summary.id || value.kind !== selectedKind || value.status !== 'ACTIVE'
      || value.currentVersion.id !== summary.versionId || (value.kind === 'FACT' && (value.verification !== 'CONFIRMED' || value.currentVersion.content.responsibility === 'UNVERIFIED'))) {
      chooseError.value = '所选来源已改版、归档或不再确认，请重新读取列表后选择。'
      return
    }
    if (props.excludeIds?.includes(value.id)) return
    // 请求只发送选择身份；表达本地留存正文和依据顺序以核对预览，实际外发投影仍由服务器生成。
    if (value.kind === 'EXPRESSION') {
      const problem = expressionProblem(value)
      if (problem) { reviewedExpression.value = value; chooseError.value = problem; return }
      const expression = value.currentVersion.content
      emit('choose', { kind: 'EXPRESSION', itemId: value.id, versionId: value.currentVersion.id, title: expression.title, number: value.currentVersion.number,
        expression: { type: expression.type, body: expression.body, facts: expression.factVersionIds.map(id => ({ ...value.references.find(reference => reference.versionId === id)! })) } })
      return
    }
    emit('choose', { kind: selectedKind, itemId: value.id, versionId: value.currentVersion.id, title: value.currentVersion.content.title, number: value.currentVersion.number,
      ...(value.kind === 'FACT' ? { responsibility: value.currentVersion.content.responsibility } : {}) })
  } catch (error) {
    if (alive && generation === selectionRequest && epoch === store.sessionEpoch) chooseError.value = errorText(error)
  } finally { if (alive && generation === selectionRequest) choosing.value = false }
}
function expressionReferencesValid(value: ExpressionItem): boolean {
  const versions = value.currentVersion.content.factVersionIds
  const references = value.references
  const idValid = (id: string) => typeof id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)
  return Array.isArray(versions) && versions.length >= 1 && versions.length <= 50 && versions.every(idValid) && new Set(versions).size === versions.length
    && Array.isArray(references) && references.length === versions.length
    && references.every(reference => reference && reference.kind === 'FACT' && idValid(reference.itemId) && versions.includes(reference.versionId))
    && new Set(references.map(reference => reference.versionId)).size === references.length
    && new Set(references.map(reference => reference.itemId)).size === references.length
}
function expressionProblem(value: ExpressionItem): string {
  const content = value.currentVersion.content
  if (!expressionOptions.some(option => option.value === content.type) || !aiPreparationPlanTextValid(content.body, 50000) || !content.body.trim()) {
    return '该表达的类型或正文不可用于新任务，请先在准备区检查并保存有效内容。'
  }
  // reviewRequired 只反映动态可用性；还要核对保存的版本集合，不能把缺失依据当作“无需复核”。
  if (!expressionReferencesValid(value)) return '表达保存的事实依据与读取结果不一致，暂不能加入本次选择。请先核对原表达。'
  if (value.reviewRequired !== false || value.references.some(reference => reference.state !== 'CURRENT')) return '表达的事实依据需要复核。请在准备区“来源与影响”核对并确认依据，或编辑保存新版本；事实恢复有效不会自动解除待复核。'
  return ''
}
function nextPage() {
  if (blocked.value || choosing.value || results.isFetching.value || !results.data.value?.nextCursor) return
  previous.value.push(cursor.value)
  cursor.value = results.data.value.nextCursor
}
function previousPage() { if (!blocked.value && !choosing.value && !results.isFetching.value && previous.value.length) cursor.value = previous.value.pop() }
</script>

<template>
  <section class="preparation-source-picker" aria-label="选择准备来源版本">
    <NSelect v-if="options.length > 1" v-model:value="kind" :options="options" :disabled="blocked || choosing" aria-label="准备来源类型" />
    <form class="title-search" @submit.prevent="submit"><NInput v-model:value="input" :maxlength="100" :disabled="blocked || choosing" placeholder="按已保存标题查找" aria-label="查找准备来源" /><NButton attr-type="submit" :disabled="blocked || choosing">查找</NButton></form>
    <p class="field-help">{{ kind === 'FACT' ? '仅选择当前由你确认的事实；预览时还会核对所属项目是否有效。确认事实本身不代表允许外发。' : kind === 'TARGET' ? '选择目标与 JD 的当前版本。岗位要求是准备背景，不作为已经核实的个人经历。' : kind === 'EXPRESSION' ? '点击后核对表达的当前版本与事实依据。表达属于写作素材，保存或曾由 AI 生成都不表示已经逐句核实；依据状态正常后仍需预览并授权。' : '选择项目概况的当前版本。预览会逐字列出实际发送的概述与技术范围。' }}</p>
    <p v-if="results.isFetching.value" class="field-help" role="status">正在读取准备来源…</p>
    <p v-if="results.error.value || chooseError" class="notice error" role="alert">{{ chooseError || errorText(results.error.value) }}<NButton text :disabled="blocked || choosing || results.isFetching.value" @click="results.refetch()">重新读取列表</NButton></p>
    <section v-if="reviewedExpression && !blocked" class="expression-review" aria-label="所选表达的依据核对"><RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'EXPRESSION', item: reviewedExpression.id, version: reviewedExpression.currentVersion.id } }">核对 {{ reviewedExpression.currentVersion.content.title }} · v{{ reviewedExpression.currentVersion.number }}</RouterLink><p class="field-help">以下是这次读取时的事实依据状态；未加入本次选择，也未授权外发。</p><ul><li v-for="reference in reviewedReferences" :key="reference.versionId"><RouterLink v-if="reference.kind === 'FACT'" class="text-link" :to="{ name: 'prepare', query: { kind: 'FACT', item: reference.itemId, version: reference.versionId } }">{{ reference.title }} · v{{ reference.number }}</RouterLink><span v-else>{{ reference.title }}</span><span>{{ referenceLabels[reference.state] ?? '状态待核对' }}</span></li></ul></section>
    <div class="preparation-source-results"><button v-for="item in results.data.value?.items" :key="item.id" type="button" :disabled="blocked || choosing || results.isFetching.value || item.kind !== kind || item.status !== 'ACTIVE' || (item.kind === 'FACT' && item.verification !== 'CONFIRMED') || excludeIds?.includes(item.id)" @click="choose(item)"><strong>{{ item.title }}</strong><small>{{ aiSourceKindLabel(item.kind) }} · v{{ item.versionNumber }} · {{ excludeIds?.includes(item.id) ? '已在本次选择中' : item.id.slice(0, 8) }}</small></button></div>
    <p v-if="choosing" class="field-help" role="status">正在核对所选版本…</p>
    <p v-if="results.data.value && !results.data.value.items.length && !results.isFetching.value" class="field-help">没有匹配的来源。{{ kind === 'FACT' ? '可调整关键词，或先在项目事实中保存并确认。' : '可调整关键词，或先在准备区保存内容。' }}</p>
    <div v-if="previous.length || results.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="blocked || !previous.length || choosing || results.isFetching.value" @click="previousPage">上一页</NButton><NButton size="small" :disabled="blocked || !results.data.value?.nextCursor || choosing || results.isFetching.value" @click="nextPage">下一页</NButton></div>
  </section>
</template>

<style scoped>
.preparation-source-picker { display: grid; gap: 10px; min-width: 0; padding: 14px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
.title-search { margin: 0; }
.preparation-source-results { display: grid; gap: 4px; max-height: 250px; overflow-y: auto; }
.preparation-source-results button { display: grid; gap: 5px; min-width: 0; padding: 10px; border-radius: 5px; text-align: left; }
.preparation-source-results button:hover:not(:disabled) { background: var(--surface-deep); }
.preparation-source-results strong { font-size: 13px; overflow-wrap: anywhere; }
.preparation-source-results small { font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
.expression-review { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 5px; font-size: 12px; overflow-wrap: anywhere; }
.expression-review ul { display: grid; gap: 8px; margin: 0; padding-left: 18px; } .expression-review li > span:last-child { display: block; color: var(--muted); margin-top: 3px; }
@media (max-width: 550px) { .title-search { flex-wrap: wrap; } }
</style>
