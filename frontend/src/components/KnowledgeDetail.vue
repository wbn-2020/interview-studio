<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { ItemDetail, Topic, Version } from '../api/client'
import type { ConnectionPage, Organization } from '../api/knowledge'
import type { KnowledgeReferenceEntry, KnowledgeReferenceKind } from '../api/knowledgeReferences'
import { knowledgeReferencePageValid, knowledgeReferenceRoute, knowledgeReferenceStatusLabel, referenceIdValid } from '../api/knowledgeReferences'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useRecentVisit } from '../composables/useRecentVisit'
import MarkdownPreview from './MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ itemId: string; topics: Topic[] }>()
const emit = defineEmits<{ open: [id: string]; edit: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), route = useRoute(), router = useRouter()
const active = ref(true), visible = ref(document.visibilityState === 'visible'), category = ref('ALL')
const identityValid = computed(() => referenceIdValid(props.itemId) && (route.query.version === undefined || referenceIdValid(route.query.version)))
const readable = computed(() => active.value && visible.value && identityValid.value && store.sessionActive
  && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const item = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-page', props.itemId]), enabled: readable,
  queryFn: () => read<ItemDetail>(`/knowledge/items/${props.itemId}`) })
const historical = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-version', props.itemId, route.query.version]),
  enabled: computed(() => readable.value && referenceIdValid(route.query.version)),
  queryFn: () => read<Version>(`/knowledge/items/${props.itemId}/versions/${route.query.version}`) })
const version = computed(() => typeof route.query.version === 'string' ? historical.data.value : item.data.value?.published)
const organization = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-organization', props.itemId]), enabled: readable,
  queryFn: () => read<Organization>(`/knowledge/items/${props.itemId}/organization`) })
const mainNote = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-main-note', props.itemId, organization.data.value?.mainNoteId]),
  enabled: computed(() => readable.value && Boolean(organization.data.value?.mainNoteId)),
  queryFn: () => read<ItemDetail>(`/knowledge/items/${organization.data.value!.mainNoteId}`) })
const relations = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-relations', props.itemId]), enabled: readable,
  queryFn: () => read<ConnectionPage>(`/knowledge/items/${props.itemId}/connections?type=SEMANTIC&limit=20`) })
function referenceQuery(kind: KnowledgeReferenceKind) {
  return useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-references', props.itemId, version.value?.id, kind]),
    enabled: computed(() => readable.value && Boolean(version.value)),
    queryFn: async () => {
      const id = props.itemId, selected = version.value!
      const value = await read<unknown>(`/knowledge/items/${id}/references?${new URLSearchParams({ versionId: selected.id, kind, limit: '20' })}`)
      if (!knowledgeReferencePageValid(value, id, selected.id, selected.number, kind)) {
        throw new ApiError(502, 'KNOWLEDGE_REFERENCES_INVALID', '出处的对象、版本或分页无法核对，请重新读取。')
      }
      return value
    } })
}
// 只展示已登记的确切引用，不把普通知识邻接或题目标题猜作已作答证据。
const answerSources = referenceQuery('TRAINING_ANSWER_DIAGRAMS')
const preparationSources = referenceQuery('PREPARATION_VERSIONS')
const memorySources = referenceQuery('MEMORY_ATTEMPT_SOURCES')
const questions = referenceQuery('QUESTION_VERSIONS')
const groups = [
  { value: 'ANSWER', label: '回答图解', icon: 'file-text', kind: 'TRAINING_ANSWER_DIAGRAMS' as const, query: answerSources },
  { value: 'PREPARATION', label: '准备资料', icon: 'file-text', kind: 'PREPARATION_VERSIONS' as const, query: preparationSources },
  { value: 'MEMORY', label: '回忆', icon: 'message-square', kind: 'MEMORY_ATTEMPT_SOURCES' as const, query: memorySources },
]
const evidenceRows = computed(() => groups.filter(group => category.value === 'ALL' || category.value === group.value)
  .flatMap(group => (group.query.data.value?.items ?? []).map(entry => ({ entry, group }))))
const sourceBusy = computed(() => groups.some(group => group.query.isFetching.value))
const sourceError = computed(() => groups.find(group => group.query.error.value)?.query.error.value)
const sourceCount = computed(() => groups.every(group => group.query.data.value)
  ? groups.reduce((count, group) => count + group.query.data.value!.items.length, 0) : undefined)
const sourceHasMore = computed(() => groups.some(group => group.query.data.value?.nextCursor))
const relationLabels = { RELATED: '相关', CONTRASTS: '对比', PRECEDES: '前置于', PART_OF: '组成部分', APPLIED_IN: '应用于' }
const recordVisit = useRecentVisit('KNOWLEDGE', () => props.itemId, readable)
watch(item.data, value => { if (value?.id === props.itemId && readable.value) recordVisit(value.id) }, { immediate: true })
function openReference(entry: KnowledgeReferenceEntry) {
  const destination = knowledgeReferenceRoute(entry)
  if (readable.value && destination) void router.push(destination)
}
function openPanel(panel: string) { if (readable.value) void router.push({ name: 'knowledge', query: { view: 'list', item: props.itemId, edit: '1', panel } }) }
function practice() {
  const question = questions.data.value?.items[0]
  if (question) openReference(question)
  else if (readable.value) void router.push({ name: 'bank' })
}
function refreshSources() { for (const group of groups) void group.query.refetch() }
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onActivated(() => { active.value = true; visibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibilityChanged))
</script>

<template>
  <section class="knowledge-detail-page">
    <p v-if="!identityValid" class="notice error" role="alert">知识对象或版本地址无效，请从目录重新打开。</p>
    <p v-else-if="!readable" class="notice" role="status">知识资料暂不可读，请恢复会话或完成训练资料访问确认。</p>
    <template v-else>
      <p v-if="item.isPending.value || historical.isFetching.value" class="loading-block" role="status">正在读取知识详情…</p>
      <p v-if="item.error.value || historical.error.value" class="notice error" role="alert">{{ errorText(item.error.value || historical.error.value) }}</p>
      <template v-if="item.data.value && version">
        <header class="concept-heading"><span class="concept-symbol"><RIcon :name="item.data.value.kind === 'CONCEPT' ? 'layers' : 'file-text'" :size="55" /></span><div><h1>{{ version.title }}</h1><div class="concept-metadata"><span>{{ item.data.value.kind === 'CONCEPT' ? '概念' : '笔记' }} v{{ version.number }}</span><span v-for="topicId in item.data.value.topicIds" :key="topicId">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span><span v-if="relations.data.value" class="relation-count"><RIcon name="check" :size="20" />{{ relations.data.value.items.length }}{{ relations.data.value.nextCursor ? '+' : '' }} 条当前已读关系</span><span v-if="version.id !== item.data.value.currentVersionId || item.data.value.status === 'ARCHIVED'">{{ item.data.value.status === 'ARCHIVED' ? '已归档' : '历史版本' }}</span></div></div><div class="row-actions"><NButton @click="emit('edit')"><RIcon name="pencil" />编辑知识</NButton><NButton type="primary" @click="practice"><RIcon name="play" />练这一点</NButton></div></header>
        <div class="concept-columns">
          <div class="concept-primary">
            <section class="concept-panel concept-definition"><h2><RIcon name="book-open" />{{ item.data.value.kind === 'CONCEPT' ? '定义' : '正文' }}</h2><MarkdownPreview :value="version.body" /><p v-if="!version.body" class="field-help">此版本尚未填写正文。</p></section>
            <section v-if="item.data.value.kind === 'CONCEPT'" class="concept-panel concept-main-note"><h2><RIcon name="square-pen" />主笔记</h2><p v-if="organization.error.value || mainNote.error.value" class="notice error">{{ errorText(organization.error.value || mainNote.error.value) }}</p><p v-if="organization.isFetching.value || mainNote.isFetching.value" class="field-help" role="status">正在读取主笔记…</p><template v-if="mainNote.data.value"><div class="note-excerpt"><MarkdownPreview :value="mainNote.data.value.published.body" /></div><button class="concept-open-link" @click="emit('open', mainNote.data.value.id)">打开完整笔记<RIcon name="arrow-up-right" :size="18" /></button></template><p v-else-if="organization.data.value && !organization.data.value.mainNoteId" class="field-help">尚未指定主笔记。<button class="text-link" @click="openPanel('organization')">选择关联笔记</button></p></section>
            <section class="concept-panel concept-paths"><h2><RIcon name="link" />关系路径</h2><p v-if="relations.error.value" class="notice error">{{ errorText(relations.error.value) }}</p><div v-for="connection in relations.data.value?.items.slice(0, 3)" :key="connection.id" class="concept-path"><span>{{ version.title }}</span><span class="path-direction"><RIcon :name="connection.direction === 'IN' ? 'arrow-left' : connection.direction === 'BOTH' ? 'arrow-up-down' : 'arrow-right'" :size="21" /><small>{{ connection.relationType ? relationLabels[connection.relationType] : '关联' }}</small></span><button @click="emit('open', connection.itemId)">{{ connection.title }}</button></div><p v-if="relations.data.value && !relations.data.value.items.length" class="field-help">尚未记录语义关系。</p><button v-if="(relations.data.value?.items.length ?? 0) > 3 || relations.data.value?.nextCursor" class="concept-open-link" @click="openPanel('relations')">查看全部关系<RIcon name="arrow-up-right" :size="18" /></button></section>
            <section class="concept-panel concept-practices"><h2><RIcon name="list-checks" />关联练习</h2><p v-if="questions.error.value" class="notice error">{{ errorText(questions.error.value) }}</p><button v-for="question in questions.data.value?.items.slice(0, 3)" :key="question.referenceId" @click="openReference(question)"><RIcon name="circle" :size="23" /><span>{{ question.title }}</span><small>v{{ question.targetVersionNumber }}</small></button><p v-if="questions.data.value && !questions.data.value.items.length" class="field-help">当前版本没有已登记的关联题目。</p><button v-if="questions.data.value?.nextCursor" class="concept-open-link" @click="openPanel('references')">查看更多关联练习<RIcon name="arrow-up-right" :size="18" /></button></section>
            <section class="concept-panel concept-recent-source"><h2><RIcon name="clock" />最近证据</h2><p class="field-help">出处目录没有提供发生时间，不能据此推断最近记录。</p><button class="concept-open-link" @click="openPanel('references')">查看确切出处<RIcon name="chevron-right" :size="21" /></button></section>
          </div>
          <aside class="concept-panel concept-evidence"><h2><RIcon name="shield" :size="29" />证据与出处</h2><nav class="concept-evidence-tabs" aria-label="出处类型"><button :class="{ selected: category === 'ALL' }" @click="category = 'ALL'">全部 <span>{{ sourceCount ?? '—' }}{{ sourceHasMore ? '+' : '' }}</span></button><button v-for="group in groups" :key="group.value" :class="{ selected: category === group.value }" @click="category = group.value">{{ group.label }} <span>{{ group.query.data.value?.items.length ?? '—' }}{{ group.query.data.value?.nextCursor ? '+' : '' }}</span></button></nav><p v-if="sourceBusy" class="field-help" role="status">正在核对已登记出处…</p><p v-if="sourceError" class="notice error">{{ errorText(sourceError) }}<NButton text @click="refreshSources">重新读取</NButton></p>
            <article v-for="row in evidenceRows" :key="`${row.group.value}-${row.entry.referenceId}`" class="concept-evidence-row"><span :class="['source-symbol', row.group.value.toLowerCase()]"><RIcon :name="row.group.icon" :size="25" /></span><div><h3>{{ row.entry.title }}</h3><p>{{ knowledgeReferenceStatusLabel(row.entry, row.group.kind) }}</p><div class="source-tags"><span>{{ row.group.label }}</span><span v-if="row.entry.targetVersionNumber">固定 v{{ row.entry.targetVersionNumber }}</span><span v-if="!row.entry.targetCurrent">历史版本</span></div></div><button class="concept-open-link" @click="openReference(row.entry)">查看原文<RIcon name="arrow-up-right" :size="18" /></button></article>
            <p v-if="!sourceBusy && !sourceError && !evidenceRows.length" class="concept-evidence-empty">当前已读目录没有这一类出处。</p><footer><RIcon name="info" :size="21" /><span>仅含已登记直接引用，不构成掌握分。{{ sourceHasMore ? '仍有后续页，可进入引用目录继续读取。' : '' }}</span><button class="text-link" @click="openPanel('references')">完整目录</button></footer>
          </aside>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.knowledge-detail-page { min-width: 0; }.concept-heading { display: flex; align-items: center; gap: 23px; margin: -9px 0 27px; padding: 0 12px; min-height: 93px; }.concept-symbol { width: 91px; height: 91px; flex: none; display: grid; place-items: center; border: 1px solid #bd8a5a; border-radius: 22px; background: linear-gradient(120deg,#b68151,#71462d); color: #f8cb9b; }.concept-heading > div:nth-child(2) { flex: 1; min-width: 0; }.concept-heading h1 { font-size: 42px; line-height: 1.4; font-weight: 600; margin: 0 0 11px; overflow-wrap: anywhere; }.concept-metadata { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 18px; }.concept-metadata > span { padding: 3px 12px; border-radius: 20px; background: var(--surface-soft); }.concept-metadata .relation-count { background: none; display: flex; align-items: center; gap: 7px; }.concept-heading :deep(.n-button) { min-height: 55px; font-size: 21px; border-radius: 23px; }.concept-heading :deep(.n-button__content) { gap: 10px; }.concept-columns { display: grid; grid-template-columns: minmax(0,1.27fr) minmax(0,1fr); gap: 22px; align-items: start; }.concept-primary { display: grid; gap: 12px; min-width: 0; }.concept-panel { min-width: 0; border: 1px solid var(--line); border-radius: 19px; padding: 21px 24px; background: var(--surface); }.concept-panel h2 { display: flex; align-items: center; gap: 12px; font-size: 24px; margin: 0 0 12px; }.concept-definition { min-height: 210px; }.concept-definition :deep(.markdown-preview) { font-size: 21px; line-height: 1.75; max-height: 165px; overflow-y: auto; }.concept-main-note { min-height: 130px; position: relative; padding-bottom: 40px; }.note-excerpt { max-height: 65px; overflow: hidden; }.note-excerpt :deep(.markdown-preview) { font-size: 21px; line-height: 1.6; }.concept-open-link { display: inline-flex; align-items: center; gap: 5px; padding: 6px 12px; border-radius: 20px; background: var(--surface-soft); font-size: 16px; white-space: nowrap; }.concept-main-note > .concept-open-link { position: absolute; right: 24px; bottom: 12px; }.concept-path { display: flex; align-items: center; flex-wrap: wrap; gap: 15px; margin-top: 13px; }.concept-path > :is(span:not(.path-direction),button) { background: var(--surface-soft); border: 1px solid var(--line); border-radius: 24px; padding: 7px 17px; font-size: 18px; }.path-direction { display: flex; align-items: center; gap: 5px; color: var(--muted); }.path-direction small { font-size: 13px; }.concept-practices > button { display: flex; align-items: center; gap: 16px; width: 100%; padding: 7px 0; text-align: left; font-size: 19px; }.concept-practices > button > span { flex: 1; }.concept-practices small { font-size: 15px; color: var(--muted); border: 1px solid var(--line); border-radius: 18px; padding: 3px 13px; }.concept-recent-source { min-height: 100px; }.concept-evidence { min-height: 685px; display: flex; flex-direction: column; padding: 26px 24px; }.concept-evidence h2 { margin-bottom: 22px; }.concept-evidence-tabs { display: flex; flex-wrap: wrap; gap: 9px; margin-bottom: 14px; }.concept-evidence-tabs button { display: flex; align-items: center; gap: 6px; padding: 8px 13px; border: 1px solid var(--line); border-radius: 24px; font-size: 17px; }.concept-evidence-tabs button.selected { border-color: #b58050; background: linear-gradient(120deg,#a56a3f,#69442d); color: #fff1dc; }.concept-evidence-tabs button span { font-size: 15px; }.concept-evidence-row { display: flex; align-items: start; gap: 16px; border-bottom: 1px solid var(--line); padding: 28px 0 24px; }.source-symbol { display: grid; place-items: center; width: 47px; height: 47px; border-radius: 50%; flex: none; color: #dae8e8; background: #527b89; }.source-symbol.preparation { background: #47677c; }.source-symbol.memory { background: #b47b4d; color: #fff0dc; }.concept-evidence-row > div { flex: 1; min-width: 0; }.concept-evidence-row h3 { margin: 3px 0 8px; font-size: 21px; line-height: 1.5; overflow-wrap: anywhere; }.concept-evidence-row p { font-size: 18px; line-height: 1.6; margin: 0 0 9px; color: var(--muted); }.source-tags { display: flex; flex-wrap: wrap; gap: 8px; }.source-tags span { background: var(--surface-soft); padding: 4px 12px; border-radius: 20px; font-size: 15px; color: var(--muted); }.concept-evidence-row > button { font-size: 15px; padding: 7px 10px; }.concept-evidence-empty { color: var(--muted); padding: 70px 15px; text-align: center; font-size: 18px; }.concept-evidence footer { display: flex; align-items: start; gap: 10px; margin-top: auto; padding-top: 24px; font-size: 15px; line-height: 1.7; color: var(--muted); }.concept-evidence footer > svg { flex: none; margin-top: 2px; }.concept-evidence footer > span { flex: 1; }.concept-panel .field-help { font-size: 16px; }
@media(max-width:1200px) { .concept-heading { flex-wrap: wrap; }.concept-heading > .row-actions { margin-left: auto; }.concept-columns { grid-template-columns: minmax(0,1fr); }.concept-evidence { min-height: 400px; } }
@media(max-width:600px) { .concept-heading { gap: 15px; padding: 0; }.concept-symbol { width: 65px; height: 65px; }.concept-heading h1 { font-size: 29px; }.concept-metadata { font-size: 14px; }.concept-heading :deep(.n-button) { font-size: 17px; min-height: 45px; }.concept-panel { padding: 20px; }.concept-evidence-row { gap: 11px; flex-wrap: wrap; }.concept-evidence-row > button { margin-left: 58px; }.concept-definition :deep(.markdown-preview),.note-excerpt :deep(.markdown-preview) { font-size: 18px; } }
</style>
