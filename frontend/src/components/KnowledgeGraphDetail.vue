<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { GraphResponse, ItemDetail, Version } from '../api/client'
import type { Organization } from '../api/knowledge'
import type { KnowledgeReferenceEntry, KnowledgeReferenceKind, KnowledgeReferencePage } from '../api/knowledgeReferences'
import { knowledgeReferencePageValid, knowledgeReferenceRoute, referenceIdValid } from '../api/knowledgeReferences'
import type { KnowledgeSource, KnowledgeSourceDocument } from '../api/knowledgeSources'
import { knowledgeSourceDocumentValid, knowledgeSourcesPath } from '../api/knowledgeSources'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import MarkdownPreview from './MarkdownPreview.vue'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ itemId: string; current: ItemDetail; enabled: boolean; graph?: GraphResponse }>()
const emit = defineEmits<{ open: [id: string] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), route = useRoute(), router = useRouter()
const tabs = [
  { value: 'overview', label: '概览' }, { value: 'note', label: '笔记' }, { value: 'relations', label: '关系' },
  { value: 'practice', label: '练习' }, { value: 'evidence', label: '证据' },
] as const
type GraphPanel = typeof tabs[number]['value']
const panel = computed(() => tabs.find(tab => tab.value === route.query.graphPanel)?.value ?? 'overview')
const identityValid = computed(() => referenceIdValid(props.itemId) && props.current.id === props.itemId
  && (route.query.version === undefined || referenceIdValid(route.query.version))
  && (route.query.graphPanel === undefined || tabs.some(tab => tab.value === route.query.graphPanel)))
const active = ref(true), visible = ref(document.visibilityState === 'visible'), generation = ref(0)
const readable = computed(() => props.enabled && active.value && visible.value && identityValid.value && store.sessionActive
  && !access.checking && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const gradientId = `graph-detail-star-${useId()}`
const content = ref<HTMLElement>()
watch([readable, () => store.sessionEpoch, () => props.itemId, () => props.current.published.id,
  () => route.query.version, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess],
() => generation.value++, { flush: 'sync' })
watch([panel, () => route.query.version, () => props.itemId], () => { if (content.value) content.value.scrollTop = 0 }, { flush: 'post' })

function preview<T>(key: string, path: () => string | undefined, valid?: (value: unknown) => value is T) {
  const query = useQuery({
    queryKey: computed(() => ['knowledge', store.sessionEpoch, 'graph-detail', props.itemId, key, path(), generation.value]),
    enabled: computed(() => readable.value && Boolean(path())),
    queryFn: async () => {
      const url = path(), stamp = generation.value, epoch = currentTrainingAccessEpoch()
      if (!url) throw new ApiError(0, 'GRAPH_DETAIL_NOT_READY', '知识版本尚未就绪。')
      const result = await read<T>(url)
      if (!readable.value || stamp !== generation.value || epoch !== currentTrainingAccessEpoch() || url !== path()) {
        throw new ApiError(0, 'GRAPH_DETAIL_CONTEXT_CHANGED', '资料读取条件已变化，请恢复访问后重新读取。')
      }
      if (valid && !valid(result)) throw new ApiError(502, 'GRAPH_DETAIL_INVALID', '知识来源或版本无法核对，请重新读取。')
      return { stamp, url, result }
    },
  })
  // 缓存须同时匹配本次资料权限与确切请求路径，解除限制后不能复用旧正文。
  const data = computed(() => readable.value && query.data.value?.stamp === generation.value && query.data.value.url === path()
    ? query.data.value.result : undefined)
  return { data, pending: query.isFetching, error: query.error, retry: () => query.refetch() }
}
const historical = preview<Version>('version',
  () => referenceIdValid(route.query.version) ? `/knowledge/items/${props.itemId}/versions/${route.query.version}` : undefined,
  (value): value is Version => {
    const version = value as Version | null
    return Boolean(version && version.id === route.query.version && Number.isSafeInteger(version.number) && version.number > 0
      && typeof version.title === 'string' && typeof version.body === 'string')
  })
const version = computed(() => route.query.version === undefined ? props.current.published : historical.data.value)
const isHistorical = computed(() => Boolean(version.value && version.value.id !== props.current.currentVersionId))
const organization = preview<Organization>('organization',
  () => props.current.kind === 'CONCEPT' ? `/knowledge/items/${props.itemId}/organization` : undefined,
  (value): value is Organization => {
    const record = value as Organization | null
    return Boolean(record && Array.isArray(record.noteIds) && record.noteIds.every(referenceIdValid)
      && (record.mainNoteId === null || referenceIdValid(record.mainNoteId) && record.noteIds.includes(record.mainNoteId)))
  })
const mainNote = preview<ItemDetail>('main-note',
  () => organization.data.value?.mainNoteId ? `/knowledge/items/${organization.data.value.mainNoteId}` : undefined,
  (value): value is ItemDetail => {
    const note = value as ItemDetail | null
    return Boolean(note && note.id === organization.data.value?.mainNoteId && note.kind === 'NOTE'
      && note.published && note.published.id === note.currentVersionId && referenceIdValid(note.published.id)
      && typeof note.published.title === 'string' && typeof note.published.body === 'string')
  })
const sources = preview<KnowledgeSourceDocument>('sources',
  () => version.value ? knowledgeSourcesPath(props.itemId, version.value.id) : undefined,
  (value): value is KnowledgeSourceDocument => Boolean(version.value && knowledgeSourceDocumentValid(value, props.itemId, version.value.id)))
function references(kind: KnowledgeReferenceKind) {
  return preview<KnowledgeReferencePage>(kind,
    () => version.value ? `/knowledge/items/${props.itemId}/references?${new URLSearchParams({ versionId: version.value.id, kind, limit: '20' })}` : undefined,
    (value): value is KnowledgeReferencePage => Boolean(version.value
      && knowledgeReferencePageValid(value, props.itemId, version.value.id, version.value.number, kind)))
}
const relations = references('RELATIONS'), questions = references('QUESTION_VERSIONS')
const relationLabels: Record<string, string> = { RELATED: '相关', CONTRASTS: '对比', PRECEDES: '前置于', PART_OF: '组成部分', APPLIED_IN: '应用于' }
const noteProblem = computed(() => organization.error.value || mainNote.error.value)
function selectPanel(value: GraphPanel) {
  if (readable.value) void router.push({ query: { ...route.query, graphPanel: value === 'overview' ? undefined : value } })
}
function openDetail() {
  if (readable.value) void router.push({ name: 'knowledge', query: { view: 'detail', item: props.itemId, version: route.query.version } })
}
function openEditor(panel: string) {
  if (readable.value) void router.push({ name: 'knowledge', query: { view: 'list', item: props.itemId, edit: '1', panel,
    version: isHistorical.value ? version.value?.id : undefined } })
}
function openNote() {
  if (readable.value && mainNote.data.value) void router.push({ name: 'knowledge', query: { view: 'list', item: mainNote.data.value.id, edit: '1', panel: 'preview' } })
}
function relationTarget(entry: KnowledgeReferenceEntry) {
  return entry.anchorItemId === props.itemId ? entry.otherItemId : entry.anchorItemId
}
function relationTitle(entry: KnowledgeReferenceEntry) {
  return props.graph?.nodes.find(node => node.id === relationTarget(entry))?.title ?? '打开关联概念'
}
function openRelation(entry: KnowledgeReferenceEntry) {
  const id = relationTarget(entry)
  if (readable.value && id) emit('open', id)
}
function openQuestion(entry: KnowledgeReferenceEntry) {
  const target = knowledgeReferenceRoute(entry)
  if (readable.value && target) void router.push(target)
}
function practice() {
  if (!readable.value || !version.value || questions.pending.value || questions.error.value || !questions.data.value) return
  const question = questions.data.value.items[0]
  if (question) openQuestion(question)
  else void router.push({ name: 'bank' })
}
function sourceHref(source: KnowledgeSource) {
  try {
    const url = new URL(source.url)
    return /^https?:$/.test(url.protocol) && !url.username && !url.password ? url.href : undefined
  } catch { return undefined }
}
function retryNote() {
  if (organization.error.value || !organization.data.value) void organization.retry()
  else if (organization.data.value.mainNoteId) void mainNote.retry()
}
function visibilityChanged() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
onActivated(() => { active.value = true; visibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibilityChanged))
</script>

<template>
  <div class="graph-detail-content">
    <p v-if="!identityValid" class="graph-detail-message error" role="alert">知识对象、版本或星图页签地址无效，请从目录重新打开。</p>
    <p v-else-if="!readable" class="graph-detail-message" role="status">知识资料暂不可读，请恢复会话或完成训练资料访问确认。</p>
    <template v-else>
      <header class="graph-detail-header">
        <div class="graph-detail-title">
          <svg v-if="current.kind === 'CONCEPT'" class="graph-detail-star" viewBox="0 0 48 54" aria-hidden="true"><defs><radialGradient :id="gradientId"><stop stop-color="#fff7cf" /><stop offset=".53" stop-color="#ffe4a2" /><stop offset="1" stop-color="#efb64e" /></radialGradient></defs><path d="m24 1 7 18 16 8-17 6-6 20-7-20-16-6 16-7Z" :fill="`url(#${gradientId})`" /></svg>
          <RIcon v-else name="file-text" :size="47" />
          <h2 :title="version?.title">{{ version?.title || '正在读取版本…' }}</h2>
          <button class="graph-detail-open" aria-label="打开完整详情" title="打开完整详情" @click="openDetail"><RIcon name="external-link" :size="29" /></button>
        </div>
        <p>{{ current.kind === 'CONCEPT' ? '概念' : '笔记' }}<span v-if="version" title="当前已读的正式关系；有后续页时以加号表示">v{{ version.number }}<template v-if="relations.data.value"> · {{ relations.data.value.items.length }}{{ relations.data.value.nextCursor ? '+' : '' }} 条已确认关系</template></span></p>
      </header>
      <nav class="graph-detail-tabs" aria-label="知识详情标签">
        <button v-for="tab in tabs" :key="tab.value" :class="{ selected: panel === tab.value }" :aria-pressed="panel === tab.value" @click="selectPanel(tab.value)">{{ tab.label }}</button>
      </nav>
      <div ref="content" class="graph-detail-scroll" :class="{ 'graph-detail-overview': panel === 'overview' }">
        <p v-if="historical.pending.value" class="graph-detail-message" role="status">正在读取所选知识版本…</p>
        <button v-if="historical.error.value" class="graph-detail-message error" @click="historical.retry()">{{ errorText(historical.error.value) }} · 重试</button>
        <p v-if="isHistorical" class="graph-detail-message">正文与来源为历史 v{{ version?.number }}；关系及主笔记关联为当前记录。</p>
        <template v-if="version">
          <section v-if="panel === 'overview'" class="graph-detail-definition">
            <h3><RIcon name="file-text" :size="23" />{{ current.kind === 'CONCEPT' ? '定义' : '正文' }}</h3>
            <MarkdownPreview :value="version.body" />
          </section>
          <section v-if="(panel === 'overview' || panel === 'note') && current.kind === 'CONCEPT'" class="graph-detail-notes">
            <h3><RIcon name="file-text" :size="23" />核心笔记</h3>
            <p v-if="organization.pending.value || mainNote.pending.value" class="graph-detail-message" role="status">正在读取主笔记…</p>
            <button v-if="noteProblem" class="graph-detail-message error" @click="retryNote">{{ errorText(noteProblem) }} · 重试</button>
            <template v-if="mainNote.data.value">
              <p v-if="panel === 'note'" class="graph-note-version">{{ mainNote.data.value.published.title }} · v{{ mainNote.data.value.published.number }}</p>
              <div class="graph-core-note-body"><MarkdownPreview :value="mainNote.data.value.published.body" /></div>
              <button v-if="panel === 'note'" class="graph-detail-link" @click="openNote">打开完整笔记<RIcon name="arrow-up-right" :size="18" /></button>
            </template>
            <p v-else-if="organization.data.value && !organization.data.value.mainNoteId" class="graph-detail-message">尚未指定主笔记。<button class="text-link" @click="openEditor('organization')">选择关联笔记</button></p>
          </section>
          <section v-if="panel === 'note' && current.kind === 'NOTE'" class="graph-detail-notes"><h3><RIcon name="file-text" :size="23" />笔记</h3><MarkdownPreview :value="version.body" /><button class="graph-detail-link" @click="openEditor('preview')">打开完整笔记<RIcon name="arrow-up-right" :size="18" /></button></section>
          <section v-if="panel === 'overview' || panel === 'evidence'" class="graph-detail-evidence">
            <h3><RIcon name="link" :size="23" />相关证据</h3>
            <p v-if="sources.pending.value" class="graph-detail-message" role="status">正在读取此版本来源…</p>
            <button v-if="sources.error.value" class="graph-detail-message error" @click="sources.retry()">{{ errorText(sources.error.value) }} · 重试</button>
            <div v-for="(source, index) in sources.data.value?.sources" :key="index" class="graph-source">
              <a v-if="sourceHref(source)" class="graph-source-link" :href="sourceHref(source)" target="_blank" rel="noopener noreferrer"><RIcon name="file-text" :size="24" /><span>{{ source.title }}</span><RIcon name="chevron-right" :size="22" /></a>
              <button v-else class="graph-source-link" @click="openEditor('sources')"><RIcon name="file-text" :size="24" /><span>{{ source.title }}</span><RIcon name="chevron-right" :size="22" /></button>
              <template v-if="panel === 'evidence'"><p v-if="source.author || source.applicableVersion" class="graph-detail-message">{{ [source.author, source.applicableVersion].filter(Boolean).join(' · ') }}</p><p v-if="source.note" class="graph-source-note">{{ source.note }}</p><p v-if="source.rights" class="graph-detail-message">{{ source.rights }}</p></template>
            </div>
            <p v-if="sources.data.value && !sources.data.value.sources.length" class="graph-detail-message">此发布版本尚未登记资料来源。<button class="text-link" @click="openEditor('sources')">查看来源记录</button></p>
          </section>
          <section v-if="panel === 'relations'" class="graph-detail-relations">
            <h3><RIcon name="share-2" :size="23" />已确认关系</h3>
            <p v-if="relations.pending.value" class="graph-detail-message" role="status">正在读取关系…</p>
            <button v-if="relations.error.value" class="graph-detail-message error" @click="relations.retry()">{{ errorText(relations.error.value) }} · 重试</button>
            <button v-for="relation in relations.data.value?.items" :key="relation.referenceId" class="graph-detail-row" @click="openRelation(relation)"><RIcon :name="relation.title === 'RELATED' || relation.title === 'CONTRASTS' ? 'arrow-up-down' : relation.anchorItemId === itemId ? 'arrow-right' : 'arrow-left'" :size="22" /><span>{{ relationTitle(relation) }}</span><small>{{ relationLabels[relation.title] || relation.title }}</small><RIcon name="chevron-right" :size="20" /></button>
            <p v-if="relations.data.value && !relations.data.value.items.length" class="graph-detail-message">尚未记录语义关系。</p>
            <button class="graph-detail-link" @click="openEditor('relations')">管理关系<RIcon name="arrow-up-right" :size="18" /></button>
            <p v-if="relations.data.value?.nextCursor" class="graph-detail-message">当前仅显示已读的前20条关系。</p>
          </section>
          <section v-if="panel === 'practice'" class="graph-detail-practices">
            <h3><RIcon name="crosshair" :size="23" />关联练习</h3>
            <p v-if="questions.pending.value" class="graph-detail-message" role="status">正在读取所选版本关联题目…</p>
            <button v-if="questions.error.value" class="graph-detail-message error" @click="questions.retry()">{{ errorText(questions.error.value) }} · 重试</button>
            <button v-for="question in questions.data.value?.items" :key="question.referenceId" class="graph-detail-row" @click="openQuestion(question)"><RIcon name="circle" :size="21" /><span>{{ question.title }}</span><small>v{{ question.targetVersionNumber }}</small><RIcon name="chevron-right" :size="20" /></button>
            <p v-if="questions.data.value && !questions.data.value.items.length" class="graph-detail-message">此版本没有已登记的关联题目，练习入口将打开题库。</p>
            <button v-if="questions.data.value?.nextCursor" class="graph-detail-link" @click="openEditor('references')">查看完整目录<RIcon name="arrow-up-right" :size="18" /></button>
          </section>
        </template>
      </div>
      <footer class="graph-detail-footer">
        <button class="graph-detail-practice" :disabled="!version || questions.pending.value || !!questions.error.value || !questions.data.value" :title="questions.error.value ? '关联题目读取失败，请在练习页签重试' : undefined" @click="practice"><RIcon name="crosshair" :size="24" />练这一点</button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.graph-detail-content { --graph-detail-divider: #a690661a; --graph-source-border: #ffffff05; --graph-source-background: linear-gradient(120deg,#28251dd9,#24221bd9); display: flex; flex-direction: column; height: 100%; min-height: 0; }
.graph-detail-header { flex: none; height: 128px; padding: 24px 22px 0; }
.graph-detail-title { display: flex; align-items: center; gap: 13px; height: 47px; }
.graph-detail-star { width: 47px; height: 53px; flex: none; filter: drop-shadow(0 0 12px #eeab43a6); }
.graph-detail-title h2 { flex: 1; min-width: 0; margin: 0; font-size: 29px; line-height: 1.5; font-weight: 650; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.graph-detail-open { display: grid; place-items: center; flex: none; width: 35px; height: 37px; border-radius: 9px; background: var(--surface-soft); }
.graph-detail-header > p { display: flex; gap: 18px; padding-top: 6px; margin: 0; color: var(--muted); font-size: 18px; white-space: nowrap; }
.graph-detail-tabs { display: flex; align-items: center; flex: none; height: 52px; padding: 0 22px 6px; border-bottom: 1px solid var(--graph-detail-divider); }
.graph-detail-tabs button { flex: 1; height: 43px; padding: 0; font-size: 19px; white-space: nowrap; border-radius: 24px; }
.graph-detail-tabs button.selected { flex: 1.06; color: #fff4dc; background: linear-gradient(120deg,#8d622ca6,#67431c9c); box-shadow: inset 0 1px 1px #b58c4f70; font-weight: 600; }
.graph-detail-scroll { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 23px; }
.graph-detail-scroll > section { padding: 23px 0 22px; border-bottom: 1px solid var(--graph-detail-divider); }
.graph-detail-scroll > section:last-child { border: 0; }
.graph-detail-scroll h3 { display: flex; align-items: center; gap: 11px; margin: 0 0 12px; font-size: 20px; font-weight: 600; line-height: 30px; }
.graph-detail-scroll :deep(.markdown-preview) { color: var(--ink); font-size: 18.5px; line-height: 34px; overflow-wrap: anywhere; }
.graph-detail-scroll :deep(.markdown-preview > :first-child) { margin-top: 0; }
.graph-detail-scroll :deep(.markdown-preview > :last-child) { margin-bottom: 0; }
.graph-detail-overview .graph-detail-definition { max-height: 190px; overflow-y: auto; }
.graph-detail-scroll .graph-detail-notes { padding-top: 18px; padding-bottom: 21px; }
.graph-detail-overview .graph-core-note-body { max-height: 105px; overflow: hidden; }
.graph-core-note-body :deep(ul) { padding-left: 22px; line-height: 35px; }
.graph-core-note-body :deep(li::marker) { color: var(--brand); }
.graph-note-version { margin: 0 0 14px; color: var(--muted); font-size: 16px; }
.graph-detail-scroll .graph-detail-evidence { padding-top: 17px; }
.graph-source { margin-top: 12px; }
.graph-source-link { display: flex; align-items: center; gap: 11px; width: 100%; min-height: 58px; padding: 0 15px; border: 1px solid var(--graph-source-border); border-radius: 13px; color: var(--ink); background: var(--graph-source-background); text-decoration: none; font-size: 19px; text-align: left; }
.graph-source-link > svg { flex: none; }
.graph-source-link > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.graph-source-link > svg:last-child { color: var(--muted); }
.graph-detail-message { margin: 12px 0; font-size: 15px; line-height: 1.7; color: var(--muted); text-align: left; overflow-wrap: anywhere; }
.graph-detail-content > .graph-detail-message { padding: 0 23px; }
.graph-detail-message.error { color: var(--danger); }
.graph-source-note { font-size: 17px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.graph-detail-link { display: inline-flex; align-items: center; gap: 7px; margin-top: 15px; padding: 0; font-size: 16px; color: var(--brand-strong); }
.graph-detail-row { display: flex; gap: 10px; align-items: center; width: 100%; padding: 17px 0; border-bottom: 1px solid var(--line); text-align: left; font-size: 18px; }
.graph-detail-row > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.graph-detail-row > svg { flex: none; }
.graph-detail-row small { font-size: 14px; color: var(--muted); }
.graph-detail-footer { flex: none; padding: 20px 23px 23px; }
.graph-detail-practice { display: flex; align-items: center; justify-content: center; gap: 11px; width: 100%; height: 58px; border: 1px solid #b18136; border-radius: 16px; color: #191207; background: linear-gradient(120deg,#b88a42,#ac7c35); box-shadow: inset 0 1px 2px #efd39860; font-size: 21px; font-weight: 650; }
.graph-detail-practice:disabled { opacity: .5; cursor: not-allowed; }
.graph-detail-practice:hover:not(:disabled) { filter: brightness(1.1); }
.graph-detail-content button:focus-visible,.graph-detail-content a:focus-visible { outline: 2px solid var(--brand-strong); outline-offset: 3px; }
:global(html[data-studio-theme="light"]) .graph-detail-content { --graph-detail-divider: var(--line); --graph-source-border: var(--line); --graph-source-background: var(--surface-soft); }
@media(max-width:1250px) { .graph-detail-title h2 { font-size: 24px; }.graph-detail-tabs { padding-inline: 12px; }.graph-detail-tabs button { font-size: 17px; }.graph-detail-header > p { gap: 10px; font-size: 15px; } }
</style>
