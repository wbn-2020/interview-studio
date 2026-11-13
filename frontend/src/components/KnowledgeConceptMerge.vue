<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NSelect } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ItemSummary, Schema } from '../api/client'
import { conceptMergeImpactLabel, conceptMergePath, conceptMergeProblem, previewConceptMerge } from '../api/knowledgeMerge'
import type { ConceptMergeCommit, ConceptMergePreview, ConceptMergeResult } from '../api/knowledgeMerge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import KnowledgePicker from './KnowledgePicker.vue'
import MarkdownPreview from './MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ sourceId?: string; enabled?: boolean }>()
const emit = defineEmits<{ merged: [result: ConceptMergeResult]; cancel: []; state: [dirty: boolean, locked: boolean] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), writer = useCommand()
const element = ref<HTMLElement>(), elementVisible = ref(false), active = ref(true)
const documentVisible = ref(document.visibilityState === 'visible')
const sourceId = ref(props.sourceId ?? ''), targetId = ref('')
const sourceTitle = ref(''), targetTitle = ref('')
const choosing = ref<'source' | 'target' | 'note'>('target')
const prepared = shallowRef<ConceptMergePreview>(), completed = shallowRef<ConceptMergeResult>()
const selectionPair = ref('')
const loading = ref(false), previewFresh = ref(false), problem = ref(''), aliasesText = ref(''), tagsText = ref('')
const topicIds = ref<string[]>([]), noteIds = ref<string[]>([]), mainNoteId = ref<string | null>(null)
const favorite = ref(false), useSourcePosition = ref(false), confirmed = ref(false), refreshed = ref(false)
const noteNames = reactive<Record<string, string>>({})
const changeState = reactive({ dirty: false, locked: false })
const changeKey = 'knowledge-concept-merge'
const readable = computed(() => props.enabled !== false && active.value && elementVisible.value && documentVisible.value
  && store.sessionActive && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const locked = computed(() => writer.locked.value)
const dirty = computed(() => Boolean(targetId.value || prepared.value) || loading.value)
const hasSelection = computed(() => selectionPair.value === `${sourceId.value}:${targetId.value}`)
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']),
  enabled: readable, queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const topicOptions = computed(() => {
  const values = topics.data.value?.items.map(item => ({ value: item.id, label: item.name })) ?? []
  for (const id of topicIds.value) if (!values.some(item => item.value === id)) values.push({ value: id, label: `待核对主题 ${id}` })
  return values
})
const noteOptions = computed(() => [...new Set([...(prepared.value?.suggestedNoteIds ?? []), ...noteIds.value])]
  .map(id => ({ value: id, label: noteNames[id] ?? `关联笔记 ${id}` })))
const mainNoteOptions = computed(() => noteIds.value.map(id => ({ value: id, label: noteNames[id] ?? id })))
const additionalImpactNames: Record<string, string> = {
  AI_KNOWLEDGE_ADOPTIONS: 'AI知识采用来源', AI_QUESTION_ADOPTIONS: 'AI题目采用来源',
  AI_RELATION_INPUTS: 'AI关系候选输入', AI_CARD_INPUTS: 'AI回忆卡候选输入', AI_SOURCE_QA_INPUTS: 'AI来源问答输入',
  STANDARD_REVIEW_EVIDENCE: '标准审阅知识证据', TODAY_PLACEMENTS: '今日安排偏好',
  TODAY_PLACEMENT_HISTORY: '今日安排偏好历史', MEMORY_REVIEW_DEPENDENCIES: '记忆来源复核依赖',
  MEMORY_REVIEW_OBSERVATIONS: '记忆来源复核观察', MEMORY_IMPACT_DEPENDENCIES: '记忆影响依赖',
  MEMORY_IMPACT_PREVIOUS: '记忆影响原来源', MEMORY_IMPACT_CURRENT: '记忆影响当前来源',
}
function impactLabel(kind: string) {
  const name = additionalImpactNames[kind.replace(/^(SOURCE|TARGET)_/, '')]
  return name ? (kind.startsWith('SOURCE_') ? '源概念 · ' : kind.startsWith('TARGET_') ? '目标概念 · ' : '') + name
    : conceptMergeImpactLabel(kind)
}
const labels = (value: string) => value ? value.split(/\r?\n/).filter(line => line.trim()) : []
const request = computed<ConceptMergeCommit>(() => ({
  sourceId: sourceId.value, targetId: targetId.value, expectedDigest: prepared.value?.digest ?? '',
  aliases: labels(aliasesText.value), tags: labels(tagsText.value), topicIds: [...topicIds.value],
  noteIds: [...noteIds.value], mainNoteId: mainNoteId.value, favorite: favorite.value,
  useSourcePosition: useSourcePosition.value, confirmed: confirmed.value,
}))
const selectionProblem = computed(() => conceptMergeProblem(request.value))
const conflict = computed(() => writer.error.value instanceof ApiError && writer.error.value.status === 409)
let alive = true, sequence = 0, observer: MutationObserver | undefined
let writeContext = { session: 0, access: 0, sequence: 0 }

watch([dirty, locked], ([dirty, locked]) => {
  changeState.dirty = dirty; changeState.locked = locked
  store.changes.set(changeKey, changeState); emit('state', dirty, locked)
}, { immediate: true, flush: 'sync' })
watch([aliasesText, tagsText, topicIds, noteIds, mainNoteId, favorite, useSourcePosition], () => {
  confirmed.value = false
}, { deep: true, flush: 'sync' })
watch(conflict, value => { if (value) confirmed.value = false }, { flush: 'sync' })
watch(noteIds, ids => { if (mainNoteId.value && !ids.includes(mainNoteId.value)) mainNoteId.value = null }, { deep: true })
watch(() => props.sourceId, value => {
  if (value === sourceId.value || value === undefined) return
  if (locked.value || dirty.value) { problem.value = '入口概念已变化，当前合并输入仍保留；请先完成或取消本次操作。'; return }
  sourceId.value = value; sourceTitle.value = ''; prepared.value = undefined
  previewFresh.value = false
})
watch(readable, () => {
  // 暂时遮挡保留原预览和最终选择，但任何重新放行都必须先刷新摘要、重新确认。
  sequence++; loading.value = false; previewFresh.value = false
  confirmed.value = false; refreshed.value = false
}, { flush: 'sync' })
watch([() => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => {
  sequence++; loading.value = false; prepared.value = undefined; completed.value = undefined
  confirmed.value = false; refreshed.value = false
  previewFresh.value = false
}, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let parent: HTMLElement | null = node ?? null; parent && elementVisible.value; parent = parent.parentElement) {
    if (['hidden', 'collapse'].includes(getComputedStyle(parent).visibility)) elementVisible.value = false
  }
}
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged); elementVisibilityChanged()
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; visibilityChanged(); elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; sequence++; observer?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
  if (store.changes.get(changeKey) === changeState) store.changes.delete(changeKey)
  emit('state', false, false)
})
function choose(item: ItemSummary) {
  if (!readable.value || locked.value || loading.value) return
  if (choosing.value === 'note') {
    if (item.kind !== 'NOTE' || item.status !== 'ACTIVE' || noteIds.value.length >= 100) return
    noteNames[item.id] = item.title
    if (!noteIds.value.includes(item.id)) noteIds.value.push(item.id)
    return
  }
  if (item.kind !== 'CONCEPT' || item.status !== 'ACTIVE') return
  if (item.id === (choosing.value === 'source' ? sourceId.value : targetId.value)) return
  if (hasSelection.value && !window.confirm('更换合并对象会清除本次预览与最终选择，确认继续？')) return
  selectionPair.value = ''
  prepared.value = undefined; previewFresh.value = false; completed.value = undefined; confirmed.value = false; problem.value = ''
  if (choosing.value === 'source') { sourceId.value = item.id; sourceTitle.value = item.title; choosing.value = 'target' }
  else { targetId.value = item.id; targetTitle.value = item.title }
}
async function loadPreview(preserveChoices = hasSelection.value) {
  if (!readable.value || locked.value || loading.value || !sourceId.value || !targetId.value || sourceId.value === targetId.value) return
  const pair = { sourceId: sourceId.value, targetId: targetId.value }
  const session = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch(), ticket = ++sequence
  loading.value = true; previewFresh.value = false; problem.value = ''; confirmed.value = false; completed.value = undefined
  writer.error.value = undefined
  try {
    const value = await previewConceptMerge(pair)
    if (!alive || ticket !== sequence || !readable.value || session !== store.sessionEpoch || accessEpoch !== currentTrainingAccessEpoch()) return
    if (!/^[0-9a-f]{64}$/.test(value.digest) || value.source.item.id !== pair.sourceId || value.target.item.id !== pair.targetId
      || value.source.item.kind !== 'CONCEPT' || value.target.item.kind !== 'CONCEPT'
      || value.source.item.status !== 'ACTIVE' || value.target.item.status !== 'ACTIVE'
      || !Number.isSafeInteger(value.layoutRevision) || value.layoutRevision < 0) throw new Error('合并预览身份或摘要无法核对，请重新读取。')
    prepared.value = value; sourceTitle.value = value.source.item.published.title; targetTitle.value = value.target.item.published.title
    refreshed.value = preserveChoices
    if (!preserveChoices) {
      aliasesText.value = value.suggestedAliases.join('\n'); tagsText.value = value.suggestedTags.join('\n')
      topicIds.value = [...value.suggestedTopicIds]; noteIds.value = [...value.suggestedNoteIds]
      mainNoteId.value = value.target.organization.mainNoteId
      favorite.value = value.target.organization.favorite || value.source.organization.favorite
      useSourcePosition.value = false
    }
    selectionPair.value = `${pair.sourceId}:${pair.targetId}`
    previewFresh.value = true
  } catch (error) {
    if (alive && ticket === sequence && readable.value && session === store.sessionEpoch && accessEpoch === currentTrainingAccessEpoch()) {
      problem.value = errorText(error)
    }
  } finally { if (alive && ticket === sequence) loading.value = false }
}
function adoptSuggestions() {
  if (hasSelection.value && !window.confirm('采用最新建议会替换当前别名、标签、主题、关联笔记、主笔记、收藏和位置选择，继续？')) return
  void loadPreview(false)
}
function receive(result: ConceptMergeResult, expected: ConceptMergeCommit) {
  if (result.merge.sourceId !== expected.sourceId || result.merge.targetId !== expected.targetId
    || result.target.id !== expected.targetId || result.target.status !== 'ACTIVE' || result.target.kind !== 'CONCEPT'
    || result.merge.targetVersionId !== result.target.currentVersionId
    || result.merge.targetRevisionAfter !== result.target.revision) throw new ApiError(502, 'MERGE_RECEIPT_MISMATCH', '合并回执身份不一致，请保留原键重试。')
  if (!alive || !readable.value || writeContext.sequence !== sequence
    || writeContext.session !== store.sessionEpoch || writeContext.access !== currentTrainingAccessEpoch()) {
    throw new ApiError(0, 'MERGE_RECEIPT_DEFERRED', '读取条件已变化，合并结果暂不展示。恢复权限后请用原请求读取回执。')
  }
  completed.value = result; prepared.value = undefined; previewFresh.value = false; confirmed.value = false; targetId.value = ''; targetTitle.value = ''
  sourceId.value = ''; sourceTitle.value = ''; selectionPair.value = ''
  void queryClient.invalidateQueries({ queryKey: ['knowledge'] })
  emit('merged', result)
}
function commit() {
  if (!readable.value || locked.value || loading.value || !previewFresh.value || conflict.value || !prepared.value || selectionProblem.value || !confirmed.value) return
  const body = JSON.parse(JSON.stringify(request.value)) as ConceptMergeCommit
  writeContext = { session: store.sessionEpoch, access: currentTrainingAccessEpoch(), sequence }
  void writer.run<ConceptMergeResult>(conceptMergePath, 'POST', body, result => receive(result, body))
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  writeContext = { session: store.sessionEpoch, access: currentTrainingAccessEpoch(), sequence }
  void writer.retry()
}
function cancel() {
  if (!store.canLeave([changeKey])) return
  sequence++; prepared.value = undefined; previewFresh.value = false; targetId.value = ''; selectionPair.value = ''; loading.value = false; emit('cancel')
}
</script>

<template>
  <section ref="element" class="concept-merge" aria-label="合并知识概念">
    <header><div><h2>选择要合并的概念</h2></div><NButton v-if="prepared" :disabled="locked" @click="cancel">重新选择</NButton></header>
    <p v-if="!readable" class="notice" role="status">资料访问或页面可见性尚未就绪。未确认的写入请求保留原键，恢复后再继续。</p>
    <template v-else>
      <p class="notice">目标概念保留发布正文与版本；源概念归档并保留合并身份。语义关系会迁移、去重或移除自指，历史正文引用、训练证据和卡片来源仍保留原身份。请先处理双方未发布草稿；此操作没有一键撤销。</p>
      <div class="merge-pair">
        <div :class="{ 'merge-choice-active': choosing === 'source' }"><RIcon name="layers" :size="29" /><strong>源概念 <span>将归档</span></strong><p>{{ sourceTitle || sourceId || '尚未选择源概念' }}</p><NButton :disabled="locked || loading" @click="choosing = 'source'">选择源概念<RIcon name="chevron-down" :size="19" /></NButton></div>
        <div :class="{ 'merge-choice-active': choosing === 'target' }"><RIcon name="sparkles" :size="29" /><strong>目标概念 <span>保留正文</span></strong><p>{{ targetTitle || targetId || '尚未选择目标概念' }}</p><NButton :disabled="locked || loading" @click="choosing = 'target'">选择目标概念<RIcon name="chevron-down" :size="19" /></NButton></div>
      </div>
      <details :open="!prepared"><summary>选择{{ choosing === 'source' ? '源概念' : choosing === 'target' ? '目标概念' : '新增关联笔记' }}</summary>
        <KnowledgePicker :key="choosing" :kind="choosing === 'note' ? 'NOTE' : 'CONCEPT'" :disabled="locked || loading"
          :exclude-ids="choosing === 'source' ? [targetId] : choosing === 'target' ? [sourceId] : noteIds" @choose="choose" />
      </details>
      <div class="row-actions merge-preview-action"><NButton type="primary" :disabled="locked || loading || !sourceId || !targetId || sourceId === targetId" :loading="loading" @click="loadPreview()"><RIcon name="eye" :size="22" />{{ hasSelection ? '刷新摘要并保留当前选择' : '预览合并影响' }}</NButton><NButton v-if="hasSelection && !locked" :disabled="loading" @click="adoptSuggestions">重新预览并采用最新建议</NButton></div>
      <p v-if="sourceId && sourceId === targetId" class="notice error">源概念和目标概念必须不同。</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}<NButton v-if="writer.pending.value" text :disabled="!readable || writer.busy.value" @click="retry">用原请求与原键恢复</NButton><NButton v-else-if="conflict" text :disabled="loading" @click="loadPreview(true)">重新读取预览并保留选择</NButton></p>
      <p v-if="writer.pending.value" class="notice" role="status">合并结果尚未确认，所有选择已冻结。不得新建另一请求替代本次操作；恢复时复用原始正文和幂等键。</p>
      <template v-if="prepared">
        <p v-if="!previewFresh" class="notice error" role="status">当前展示的是上次预览，尚未成功刷新摘要，不能再次确认合并。请重新读取预览；已有最终选择仍保留。</p>
        <p class="field-help">布局修订 {{ prepared.layoutRevision }} · 预览摘要 <code>{{ prepared.digest }}</code></p>
        <p v-if="refreshed" class="notice">已读取新的服务端摘要，保留了你的最终选择。请重新核对变化后的双方资料与影响，再勾选确认；候选建议没有覆盖你的输入。</p>
        <div class="merge-pair"><details><summary>源概念发布正文 · v{{ prepared.source.item.published.number }}</summary><MarkdownPreview :value="prepared.source.item.published.body" /></details><details><summary>目标概念发布正文 · v{{ prepared.target.item.published.number }}（保留）</summary><MarkdownPreview :value="prepared.target.item.published.body" /></details></div>
        <section><h3>影响清单</h3><p class="field-help">下列为固定引用条目数，包含历史版本，并非受影响对象的去重数量；同一任务的固定输入与后续采用来源分开计数，不能相加作为任务数量。源概念归档后的复核要求仍沿原生命周期规则处理；目标历史证据不被改写。</p><ul><li v-for="impact in prepared.impacts" :key="impact.kind">{{ impactLabel(impact.kind) }}：{{ impact.count }}</li></ul></section>
        <section class="merge-fields"><h3>确认目标最终的组织信息</h3>
          <div class="merge-pair"><label>别名（每行一项，最多20项）<NInput v-model:value="aliasesText" type="textarea" :disabled="locked || loading" :autosize="{ minRows: 3, maxRows: 8 }" /></label><label>标签（每行一项，最多20项）<NInput v-model:value="tagsText" type="textarea" :disabled="locked || loading" :autosize="{ minRows: 3, maxRows: 8 }" /></label></div>
          <p class="field-help">别名建议包含源概念标题；超限时必须明确取舍，不会截断。空行不作为标签，实际提交值会按服务端规则去首尾空白。</p>
          <label>最终主题<NSelect v-model:value="topicIds" multiple filterable :disabled="locked || loading" :options="topicOptions" /></label>
          <p v-if="topics.error.value" class="notice error">{{ errorText(topics.error.value) }}<NButton text @click="topics.refetch()">重读主题</NButton></p>
          <label>最终关联笔记<NSelect v-model:value="noteIds" multiple filterable :disabled="locked || loading" :options="noteOptions" /></label>
          <NButton size="small" :disabled="locked || loading || noteIds.length >= 100" @click="choosing = 'note'">在上方选择器添加笔记</NButton>
          <label>主笔记（可明确不设置）<NSelect v-model:value="mainNoteId" clearable :disabled="locked || loading" :options="mainNoteOptions" placeholder="不设置主笔记" /></label>
          <NCheckbox v-model:checked="favorite" :disabled="locked || loading">合并后的目标概念保留收藏</NCheckbox>
          <fieldset :disabled="locked || loading"><legend>目标概念最终星图位置</legend><label><input v-model="useSourcePosition" type="radio" :value="false" />保留目标位置（{{ prepared.target.position.x }}，{{ prepared.target.position.y }}）</label><label><input v-model="useSourcePosition" type="radio" :value="true" />采用源位置（{{ prepared.source.position.x }}，{{ prepared.source.position.y }}）</label></fieldset>
          <p v-if="selectionProblem" class="notice error" role="alert">{{ selectionProblem }}</p>
          <NCheckbox v-model:checked="confirmed" :disabled="locked || conflict || !previewFresh || !!selectionProblem">我已核对最终组织、主笔记、布局与影响，确认保留目标正文、归档源概念，并接受历史证据不自动改绑。</NCheckbox>
          <NButton type="primary" :disabled="locked || conflict || !previewFresh || !confirmed || !!selectionProblem" :loading="writer.busy.value" @click="commit">确认合并概念</NButton>
        </section>
      </template>
      <p v-if="completed" class="notice" role="status">合并回执已确认：{{ completed.target.published.title }} · 记录 {{ completed.merge.id }}。历史身份保持可追溯。</p>
    </template>
  </section>
</template>

<style scoped>
.concept-merge, .merge-fields { display: grid; gap: 16px; min-width: 0; }
.concept-merge { padding: 22px; border: 1px solid var(--line); background: var(--surface); border-radius: 8px; }
header, .merge-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
header { grid-template-columns: minmax(0, 1fr) auto; align-items: start; }
h2 { font-size: 21px; } h3 { margin-bottom: 8px; }
.merge-pair > div { padding: 14px; border: 1px solid var(--line); border-radius: 6px; }
.merge-pair p { margin: 8px 0; overflow-wrap: anywhere; } label { display: grid; gap: 7px; font-size: 12px; color: var(--muted); }
summary { cursor: pointer; padding: 8px 0; } code { overflow-wrap: anywhere; } li { margin: 6px 0; }
fieldset { border: 1px solid var(--line); display: grid; gap: 10px; padding: 12px; } fieldset label { display: flex; gap: 8px; align-items: center; }
@media (max-width: 700px) { .merge-pair { grid-template-columns: 1fr; } .concept-merge { padding: 14px; } }
</style>

<style scoped>
.concept-merge { gap: 22px; padding: 28px; border-radius: 22px; background: linear-gradient(120deg, #26211bbb, #17130fbb); }.concept-merge > header { align-items: center; }.concept-merge h2 { font-size: 26px; }.concept-merge > .notice { margin: 0; font-size: 15px; color: var(--muted); line-height: 1.8; background: #ffffff03; padding: 18px; }.merge-pair { gap: 22px; }.merge-pair > div { padding: 22px; border-radius: 17px; min-height: 200px; background: #ffffff02; }.merge-pair > div > svg { color: #d9a164; margin-bottom: 12px; }.merge-pair strong { display: flex; justify-content: space-between; gap: 10px; font-size: 22px; font-weight: 500; }.merge-pair strong span { display: inline-block; padding: 3px 9px; border-radius: 18px; font-size: 13px; color: #c9a07a; background: #b87b3e20; }.merge-pair p { margin: 16px 0 22px; min-height: 27px; color: var(--muted); font-size: 18px; }.merge-pair > div.merge-choice-active { border-color: #ac7b4d; background: linear-gradient(120deg, #98602c22, #2a1e1233); }.merge-pair :deep(.n-button__content) { gap: 10px; }.concept-merge > details { padding: 15px 18px 20px; border: 1px solid var(--line); border-radius: 17px; background: #ffffff02; }.concept-merge > details summary { font-size: 19px; margin-bottom: 10px; }.concept-merge :deep(.knowledge-picker) { padding: 0; border: 0; background: none; }.concept-merge :deep(.picker-results) { max-height: 230px; }.concept-merge :deep(.picker-results > button) { padding: 14px 9px; font-size: 18px; }.concept-merge :deep(.picker-results small) { font-size: 12px; margin-top: 7px; color: var(--faint); }.concept-merge :deep(.knowledge-picker > .field-help) { min-height: 100px; display: grid; place-items: center; font-size: 17px; }.merge-preview-action { justify-content: end; padding-top: 4px; }.merge-fields { gap: 18px; }.merge-fields label { font-size: 16px; }.merge-fields h3 { font-size: 22px; }.concept-merge fieldset { border-radius: 12px; padding: 16px; }.concept-merge li { font-size: 16px; }
@media (max-width: 650px) { .concept-merge { padding: 20px 16px; }.merge-pair > div { min-height: 170px; }.merge-pair { gap: 14px; } }
</style>
