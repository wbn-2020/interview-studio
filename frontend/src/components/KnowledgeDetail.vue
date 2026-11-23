<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { ItemDetail, Topic, Version } from '../api/client'
import type { ConnectionPage, Organization } from '../api/knowledge'
import type { KnowledgeReferenceEntry, KnowledgeReferenceKind } from '../api/knowledgeReferences'
import { knowledgeReferencePageValid, knowledgeReferenceRoute, referenceIdValid } from '../api/knowledgeReferences'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { usePreferencesStore } from '../state/preferences'
import { useRecentVisit } from '../composables/useRecentVisit'
import { useKnowledgeDetailEvidence } from '../composables/useKnowledgeDetailEvidence'
import type { DetailEvidence } from '../composables/useKnowledgeDetailEvidence'
import MarkdownPreview from './MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ itemId: string; topics: Topic[] }>()
const emit = defineEmits<{ open: [id: string]; edit: [] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), route = useRoute(), router = useRouter()
const preferences = usePreferencesStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible')
const evidenceTabs = [{ value: 'ALL', label: '全部' }, { value: 'ANSWER', label: '回答' }, { value: 'FACT', label: '事实' }, { value: 'MEMORY', label: '回忆' }] as const
type EvidenceCategory = typeof evidenceTabs[number]['value']
const category = computed<EvidenceCategory>(() => evidenceTabs.find(tab => tab.value === route.query.evidence)?.value ?? 'ALL')
const identityValid = computed(() => referenceIdValid(props.itemId) && (route.query.version === undefined || referenceIdValid(route.query.version))
  && (route.query.evidence === undefined || evidenceTabs.some(tab => tab.value === route.query.evidence)))
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
const questions = referenceQuery('QUESTION_VERSIONS')
const evidence = useKnowledgeDetailEvidence(() => props.itemId, version, readable)
const evidenceRows = computed(() => evidence.rows.value.filter(row => category.value === 'ALL' || row.category === category.value))
const recent = evidence.recent
const relationLabels = { RELATED: '相关', CONTRASTS: '对比', PRECEDES: '前置于', PART_OF: '组成部分', APPLIED_IN: '应用于' }
const recordVisit = useRecentVisit('KNOWLEDGE', () => props.itemId, readable)
watch(item.data, value => { if (value?.id === props.itemId && readable.value) recordVisit(value.id) }, { immediate: true })
function openReference(entry: KnowledgeReferenceEntry) {
  const destination = knowledgeReferenceRoute(entry)
  if (readable.value && destination) void router.push(destination)
}
function openEvidence(row: DetailEvidence) {
  if (readable.value && row.preparationKind) {
    void router.push({ name: 'prepare', query: { kind: row.preparationKind, item: row.entry.targetId, version: row.entry.targetVersionId ?? undefined } })
  } else openReference(row.entry)
}
function openPanel(panel: string) { if (readable.value) void router.push({ name: 'knowledge', query: { view: 'list', item: props.itemId, edit: '1', panel } }) }
function practice() {
  const question = questions.data.value?.items[0]
  if (question) openReference(question)
  else if (readable.value) void router.push({ name: 'bank' })
}
function selectCategory(value: EvidenceCategory) {
  if (readable.value) void router.replace({ query: { ...route.query, evidence: value === 'ALL' ? undefined : value } })
}
function sourceDate(value: string, full = false) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: preferences.current.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date(value))
  const part = (name: string) => parts.find(value => value.type === name)?.value
  return full ? `${part('year')}-${part('month')}-${part('day')}` : `${part('month')}.${part('day')}`
}
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onActivated(() => { active.value = true; visibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibilityChanged))
</script>

<template>
  <section class="knowledge-detail-page">
    <p v-if="!identityValid" class="notice error" role="alert">知识对象、版本或证据筛选地址无效，请从目录重新打开。</p>
    <p v-else-if="!readable" class="notice" role="status">知识资料暂不可读，请恢复会话或完成训练资料访问确认。</p>
    <template v-else>
      <p v-if="item.isPending.value || historical.isFetching.value" class="loading-block" role="status">正在读取知识详情…</p>
      <p v-if="item.error.value || historical.error.value" class="notice error" role="alert">{{ errorText(item.error.value || historical.error.value) }}</p>
      <template v-if="item.data.value && version">
        <header class="concept-heading"><span class="concept-symbol"><RIcon :name="item.data.value.kind === 'CONCEPT' ? 'layers' : 'file-text'" variant="detail" :size="55" /></span><div><h1>{{ version.title }}</h1><div class="concept-metadata"><span>{{ item.data.value.kind === 'CONCEPT' ? '概念' : '笔记' }} v{{ version.number }}</span><span v-for="topicId in item.data.value.topicIds" :key="topicId">{{ topics.find(topic => topic.id === topicId)?.name ?? '主题' }}</span><span v-if="relations.data.value" class="relation-count"><RIcon name="check" :size="20" />{{ relations.data.value.items.length }}{{ relations.data.value.nextCursor ? '+' : '' }} 条当前已读关系</span><span v-if="version.id !== item.data.value.currentVersionId || item.data.value.status === 'ARCHIVED'">{{ item.data.value.status === 'ARCHIVED' ? '已归档' : '历史版本' }}</span></div></div><div class="row-actions"><NButton @click="emit('edit')"><RIcon name="pencil" />编辑知识</NButton><NButton type="primary" @click="practice"><RIcon name="play" variant="detail" />练这一点</NButton></div></header>
        <div class="concept-columns">
          <div class="concept-primary">
            <section class="concept-panel concept-definition"><h2><RIcon name="book-open" />{{ item.data.value.kind === 'CONCEPT' ? '定义' : '正文' }}</h2><MarkdownPreview :value="version.body" /><p v-if="!version.body" class="field-help">此版本尚未填写正文。</p></section>
            <section v-if="item.data.value.kind === 'CONCEPT'" class="concept-panel concept-main-note"><h2><RIcon name="square-pen" />主笔记</h2><p v-if="organization.error.value || mainNote.error.value" class="notice error">{{ errorText(organization.error.value || mainNote.error.value) }}</p><p v-if="organization.isFetching.value || mainNote.isFetching.value" class="field-help" role="status">正在读取主笔记…</p><template v-if="mainNote.data.value"><div class="note-excerpt"><MarkdownPreview :value="mainNote.data.value.published.body" /></div><button class="concept-open-link" @click="emit('open', mainNote.data.value.id)">打开完整笔记<RIcon name="arrow-up-right" :size="18" /></button></template><p v-else-if="organization.data.value && !organization.data.value.mainNoteId" class="field-help">尚未指定主笔记。<button class="text-link" @click="openPanel('organization')">选择关联笔记</button></p></section>
            <section class="concept-panel concept-paths"><h2><RIcon name="link" />关系路径</h2><p v-if="relations.error.value" class="notice error">{{ errorText(relations.error.value) }}</p><div v-for="connection in relations.data.value?.items.slice(0, 3)" :key="connection.id" class="concept-path"><span>{{ version.title }}</span><span class="path-direction"><RIcon :name="connection.direction === 'IN' ? 'arrow-left' : connection.direction === 'BOTH' ? 'arrow-up-down' : 'arrow-right'" :size="21" /><small>{{ connection.relationType ? relationLabels[connection.relationType] : '关联' }}</small></span><button @click="emit('open', connection.itemId)">{{ connection.title }}</button></div><p v-if="relations.data.value && !relations.data.value.items.length" class="field-help">尚未记录语义关系。</p><button v-if="(relations.data.value?.items.length ?? 0) > 3 || relations.data.value?.nextCursor" class="concept-open-link" @click="openPanel('relations')">查看全部关系<RIcon name="arrow-up-right" :size="18" /></button></section>
            <section class="concept-panel concept-practices"><h2><RIcon name="list-checks" />关联练习</h2><p v-if="questions.error.value" class="notice error">{{ errorText(questions.error.value) }}</p><button v-for="question in questions.data.value?.items.slice(0, 3)" :key="question.referenceId" @click="openReference(question)"><RIcon name="circle" :size="23" /><span>{{ question.title }}</span><small>v{{ question.targetVersionNumber }}</small></button><p v-if="questions.data.value && !questions.data.value.items.length" class="field-help">当前版本没有已登记的关联题目。</p><button v-if="questions.data.value?.nextCursor" class="concept-open-link" @click="openPanel('references')">查看更多关联练习<RIcon name="arrow-up-right" :size="18" /></button></section>
            <section class="concept-panel concept-recent-source"><h2><RIcon name="clock" />最近证据</h2><button v-if="recent?.occurredAt" class="recent-source-link" :title="`${evidence.incomplete.value ? '当前已读来源中的最近记录；目录或原文尚未完整读取。' : '已登记直接出处中的最近记录。'}${preferences.formatDate(recent.occurredAt)} · ${recent.entry.title}`" @click="openEvidence(recent)"><time :datetime="recent.occurredAt">{{ sourceDate(recent.occurredAt, true) }}</time><span>{{ recent.label }} · {{ recent.summary || recent.entry.title }}</span><RIcon name="chevron-right" :size="23" /></button><p v-else-if="evidence.query.isFetching.value" class="field-help" role="status">正在核对原始记录时间…</p><p v-else class="field-help">当前已读出处尚无可核对时间的记录。</p><p v-if="recent && evidence.incomplete.value" class="recent-source-limit">当前已读范围，完整目录仍需核对。</p></section>
          </div>
          <aside class="concept-panel concept-evidence"><h2><RIcon name="shield" variant="detail" :size="29" />证据与出处</h2><nav class="concept-evidence-tabs" aria-label="出处类型"><button v-for="tab in evidenceTabs" :key="tab.value" :class="{ selected: category === tab.value }" :aria-pressed="category === tab.value" @click="selectCategory(tab.value)">{{ tab.label }} <span>{{ evidence.counts.value[tab.value].value ?? '—' }}{{ evidence.counts.value[tab.value].more ? '+' : '' }}</span></button></nav>
            <p v-if="evidence.query.isFetching.value" class="field-help" role="status">正在核对已登记出处与原文…</p>
            <p v-if="evidence.query.error.value" class="notice error" role="alert">{{ errorText(evidence.query.error.value) }}<NButton text @click="evidence.query.refetch()">重新读取</NButton></p>
            <div v-if="evidence.problems.value.length" class="evidence-directory-errors" role="alert"><p v-for="problem in evidence.problems.value" :key="problem">{{ problem }}</p><NButton text @click="evidence.query.refetch()">重新读取</NButton></div>
            <div class="concept-evidence-list">
              <article v-for="row in evidenceRows" :key="`${row.category}-${row.entry.referenceId}`" class="concept-evidence-row"><span :class="['source-symbol', row.category.toLowerCase()]"><RIcon :name="row.category === 'MEMORY' ? 'message-square' : 'file-text'" variant="detail" :size="25" /></span><div><h3 :title="row.entry.title">{{ row.label }}<template v-if="row.occurredAt"> · <time :datetime="row.occurredAt" :title="preferences.formatDate(row.occurredAt)">{{ sourceDate(row.occurredAt) }}</time></template></h3><p :title="row.summary">{{ row.summary || row.entry.title }}</p><div class="source-tags"><span v-for="tag in row.tags" :key="tag">{{ tag }}</span></div><p v-if="row.problem" class="evidence-row-error">{{ row.problem }}<button @click="evidence.query.refetch()">重试</button></p></div><button class="concept-open-link" :aria-label="`查看${row.entry.title}原文`" @click="openEvidence(row)">查看原文<RIcon name="arrow-up-right" :size="18" /></button></article>
              <p v-if="evidence.data.value && !evidence.problems.value.length && !evidenceRows.length" class="concept-evidence-empty">{{ evidence.incomplete.value ? '当前已读范围尚不能确认全部出处，请查看完整目录。' : '当前已登记的直接出处中没有这一类记录。' }}</p>
            </div>
            <footer><RIcon name="info" :size="21" /><span>证据不构成掌握分。</span><button class="text-link" :title="`仅含已登记直接引用，每类最多20项。${evidence.incomplete.value ? '有后续页或尚未核对的出处。' : ''}`" @click="openPanel('references')">完整目录<RIcon name="arrow-up-right" :size="16" /></button></footer>
          </aside>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.knowledge-detail-page { min-width: 0; }.concept-heading { display: flex; align-items: start; flex-wrap: wrap; gap: 14px; margin-bottom: 22px; }.concept-symbol { width: 42px; height: 42px; flex: none; display: grid; place-items: center; border-radius: 8px; background: var(--brand-soft); color: var(--brand); }.concept-symbol svg { width: 23px; height: 23px; }.concept-heading > div:nth-child(2) { flex: 1; min-width: 180px; }.concept-heading h1 { font-size: 26px; line-height: 1.4; font-weight: 600; margin: 0 0 8px; overflow-wrap: anywhere; }.concept-metadata { display: flex; gap: 7px; align-items: center; flex-wrap: wrap; font-size: 12px; color: var(--muted); }.concept-metadata > span { padding: 2px 7px; border-radius: 4px; background: var(--surface-soft); }.concept-metadata .relation-count { background: none; display: flex; align-items: center; gap: 5px; }
.concept-columns { display: grid; grid-template-columns: minmax(0,1.3fr) minmax(300px,1fr); gap: 20px; align-items: start; }.concept-primary { display: grid; gap: 16px; min-width: 0; }.concept-panel { min-width: 0; border: 1px solid var(--line); border-radius: 10px; padding: 20px; background: var(--surface); }.concept-panel h2 { display: flex; align-items: center; gap: 8px; font-size: 16px; margin-bottom: 14px; }.concept-panel h2 > svg { width: 19px; height: 19px; color: var(--brand); }
.concept-definition :deep(.markdown-preview) { font-size: var(--studio-font-size); line-height: 1.8; max-height: 440px; overflow-y: auto; }.note-excerpt { max-height: 160px; overflow: auto; }.note-excerpt :deep(.markdown-preview) { font-size: var(--studio-font-size); line-height: 1.8; }
.concept-open-link { display: inline-flex; align-items: center; gap: 4px; padding: 5px 0; color: var(--brand); font-size: 12px; }.concept-main-note > .concept-open-link { margin-top: 10px; }.concept-path { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; }.concept-path > :is(span:not(.path-direction),button) { background: var(--surface-soft); border-radius: 5px; padding: 5px 9px; font-size: 12px; }.path-direction { display: flex; align-items: center; gap: 4px; color: var(--muted); }.path-direction small { font-size: 11px; }
.concept-practices > button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 0; text-align: left; font-size: 13px; }.concept-practices > button > span { flex: 1; }.concept-practices > button > svg { width: 16px; }.concept-practices small { font-size: 11px; color: var(--muted); }
.concept-evidence { display: flex; flex-direction: column; }.concept-evidence-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }.concept-evidence-tabs button { display: flex; align-items: center; gap: 5px; padding: 5px 8px; border-radius: 5px; font-size: 12px; }.concept-evidence-tabs button.selected { background: var(--brand-soft); color: var(--brand-strong); }.concept-evidence-tabs button svg { width: 16px; }.concept-evidence-tabs button span { font-size: 11px; }
.concept-evidence-list { max-height: 600px; overflow-y: auto; }.concept-evidence-row { display: flex; align-items: start; flex-wrap: wrap; gap: 10px; border-bottom: 1px solid var(--line); padding: 16px 0; }.source-symbol { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 6px; flex: none; color: var(--brand); background: var(--brand-soft); }.source-symbol svg { width: 17px; height: 17px; }.concept-evidence-row > div { flex: 1; min-width: 0; }.concept-evidence-row h3 { margin: 0 0 6px; font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }.concept-evidence-row p { font-size: 12px; line-height: 1.7; margin: 0 0 8px; color: var(--muted); overflow-wrap: anywhere; }.source-tags { display: flex; flex-wrap: wrap; gap: 5px; }.source-tags span { background: var(--surface-soft); padding: 2px 6px; border-radius: 4px; font-size: 11px; color: var(--muted); }.concept-evidence-row > button { font-size: 12px; }
.concept-evidence-empty { color: var(--muted); padding: 24px 0; font-size: 13px; }.concept-evidence footer { display: flex; align-items: start; flex-wrap: wrap; gap: 7px; margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--line); font-size: 12px; line-height: 1.7; color: var(--muted); }.concept-evidence footer > svg { width: 17px; flex: none; }.concept-evidence footer > span { flex: 1; }.concept-evidence-row .evidence-row-error, .evidence-directory-errors { color: var(--danger); font-size: 12px; overflow-wrap: anywhere; }.evidence-row-error button { margin-left: 8px; text-decoration: underline; }
.recent-source-link { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; width: 100%; font-size: 13px; text-align: left; }.recent-source-link time { font-size: 12px; color: var(--muted); }.recent-source-link > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }.recent-source-limit { font-size: 12px; color: var(--muted); margin-top: 8px; }
@media(max-width:1000px) { .concept-columns { grid-template-columns: minmax(0,1fr); } }
@media(max-width:600px) { .concept-heading h1 { font-size: 24px; }.concept-panel { padding: 18px; }.concept-heading .row-actions { width: 100%; } }
</style>
