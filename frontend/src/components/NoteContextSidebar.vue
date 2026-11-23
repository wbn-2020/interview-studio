<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { ConnectionPage, OpenKnowledge } from '../api/knowledge'
import { knowledgeSourceDocumentValid, knowledgeSourcesPath } from '../api/knowledgeSources'
import type { KnowledgeSourceDocument } from '../api/knowledgeSources'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import RIcon from '../replica/RIcon.vue'

const props = defineProps<{ itemId: string; versionId: string; versionNumber: number; enabled: boolean }>()
const emit = defineEmits<{ open: [target: OpenKnowledge]; panel: [value: 'relations' | 'sources' | 'versions'] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), generation = ref(0)
const readable = computed(() => props.enabled && store.sessionActive && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked)
watch([readable, () => store.sessionEpoch, () => props.itemId, () => props.versionId,
  () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => generation.value++, { flush: 'sync' })
function preview<T>(key: string, path: () => string, valid?: (value: unknown) => value is T) {
  const query = useQuery({
    queryKey: computed(() => ['knowledge', store.sessionEpoch, 'note-context', props.itemId, props.versionId, key, generation.value]),
    enabled: readable,
    queryFn: async () => {
      const stamp = generation.value, epoch = currentTrainingAccessEpoch(), url = path()
      const result = await read<T>(url)
      if (!readable.value || generation.value !== stamp || epoch !== currentTrainingAccessEpoch() || url !== path()) {
        throw new ApiError(0, 'NOTE_CONTEXT_CHANGED', '资料读取条件已变化，请恢复访问后重试。')
      }
      if (valid && !valid(result)) throw new ApiError(502, 'NOTE_CONTEXT_INVALID', '来源记录无法核对，请重新读取。')
      return { stamp, result }
    },
  })
  // 只展示本次权限与确切版本核对后的响应，不能复用限制解除前的缓存。
  const data = computed(() => readable.value && query.data.value?.stamp === generation.value ? query.data.value.result : undefined)
  return { data, pending: query.isFetching, error: query.error, retry: () => query.refetch() }
}
const association = preview<ConnectionPage>('association', () => `/knowledge/items/${props.itemId}/connections?type=ASSOCIATION&limit=20`)
const backlinks = preview<ConnectionPage>('backlinks', () => `/knowledge/items/${props.itemId}/connections?type=BACKLINK&limit=20`)
const sources = preview<KnowledgeSourceDocument>('sources', () => knowledgeSourcesPath(props.itemId, props.versionId),
  (value): value is KnowledgeSourceDocument => knowledgeSourceDocumentValid(value, props.itemId, props.versionId))
const count = (page: ConnectionPage) => `${page.items.length}${page.nextCursor ? '+' : ''}`
const sourceDate = (value: string | null) => value ? new Date(value).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }).replace('/', '.') : ''
function open(target: OpenKnowledge) { if (readable.value) emit('open', target) }
</script>

<template>
  <aside class="note-context" aria-label="笔记关联与版本">
    <p v-if="!readable" class="field-help">资料访问待确认。</p>
    <template v-else>
      <section>
        <h3>关联概念 <span v-if="association.data.value">{{ count(association.data.value) }}</span><button class="context-info" type="button" title="读取笔记的概念关联；有后续页时数量以加号表示" aria-label="关联概念说明" @click="emit('panel', 'relations')"><RIcon name="info" :size="20" /></button></h3>
        <p v-if="association.pending.value && !association.data.value" class="field-help">正在读取关联…</p>
        <button v-if="association.error.value" class="context-error" @click="association.retry()">{{ errorText(association.error.value) }} · 重试</button>
        <div class="context-concepts"><button v-for="(concept, index) in association.data.value?.items.slice(0, 6)" :key="concept.id" :class="`concept-color-${index % 3}`" @click="open({ id: concept.itemId })">{{ concept.title }}</button></div>
        <p v-if="association.data.value && !association.data.value.items.length" class="field-help">尚未关联概念。</p>
        <button v-if="association.data.value && (association.data.value.nextCursor || association.data.value.items.length > 6)" class="context-all" @click="emit('panel', 'relations')">查看全部<RIcon name="arrow-right" :size="18" /></button>
      </section>
      <section>
        <h3>反向链接 <span v-if="backlinks.data.value">{{ count(backlinks.data.value) }}</span><button class="context-all" @click="emit('panel', 'relations')">查看全部<RIcon name="arrow-right" :size="18" /></button></h3>
        <p v-if="backlinks.pending.value && !backlinks.data.value" class="field-help">正在读取反向链接…</p>
        <button v-if="backlinks.error.value" class="context-error" @click="backlinks.retry()">{{ errorText(backlinks.error.value) }} · 重试</button>
        <button v-for="link in backlinks.data.value?.items.slice(0, 2)" :key="link.id" class="context-link" @click="open({ id: link.itemId, versionId: link.sourceVersionId ?? undefined, lineStart: link.lineStart ?? undefined, lineEnd: link.lineEnd ?? undefined })"><RIcon name="file-text" :size="25" /><span>{{ link.title }}<small v-if="link.sourceVersionNumber">引用源 v{{ link.sourceVersionNumber }}<template v-if="link.status === 'ARCHIVED'"> · 已归档</template></small></span></button>
        <p v-if="backlinks.data.value && !backlinks.data.value.items.length" class="field-help">尚无当前发布正文引用。</p>
      </section>
      <section>
        <h3>来源</h3>
        <p v-if="sources.pending.value && !sources.data.value" class="field-help">正在读取来源…</p>
        <button v-if="sources.error.value" class="context-error" @click="sources.retry()">{{ errorText(sources.error.value) }} · 重试</button>
        <button v-for="(source, index) in sources.data.value?.sources.slice(0, 2)" :key="index" class="context-link" @click="emit('panel', 'sources')"><RIcon name="file-text" :size="25" /><span>{{ source.title }}<template v-if="source.acquiredAt"> · {{ sourceDate(source.acquiredAt) }}</template></span></button>
        <button v-if="sources.data.value && !sources.data.value.sources.length" class="context-all" @click="emit('panel', 'sources')">登记资料来源<RIcon name="chevron-right" :size="18" /></button>
        <button v-if="sources.data.value && sources.data.value.sources.length > 2" class="context-all" @click="emit('panel', 'sources')">查看全部<RIcon name="arrow-right" :size="18" /></button>
      </section>
      <section><h3>版本</h3><button class="context-version" @click="emit('panel', 'versions')"><i /><span>v{{ versionNumber }} 当前</span></button></section>
    </template>
  </aside>
</template>

<style scoped>
.note-context { min-width: 0; padding: 16px; display: grid; align-content: start; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-soft); }
.note-context section { min-width: 0; padding-bottom: 16px; margin-bottom: 16px; border-bottom: 1px solid var(--line); display: grid; gap: 12px; }.note-context section:last-child { border: 0; padding: 0; margin: 0; }.note-context h3 { display: flex; align-items: center; gap: 8px; font-size: 14px; margin: 0; }.note-context h3 > span { color: var(--muted); font-weight: 400; font-size: 12px; }.context-info { margin-left: auto; color: var(--muted); padding: 0; display: grid; place-items: center; width: 24px; height: 24px; }
.context-concepts { display: flex; flex-wrap: wrap; gap: 6px; }.context-concepts button { border-radius: 4px; padding: 3px 7px; font-size: 12px; line-height: 1.5; color: var(--brand-strong); background: var(--brand-soft); }
.context-link { display: flex; gap: 8px; align-items: start; text-align: left; padding: 0; font-size: 13px; line-height: 1.6; }.context-link > svg { width: 17px; height: 19px; flex: none; margin-top: 2px; }.context-link > span { min-width: 0; overflow-wrap: anywhere; }.context-link small { display: block; color: var(--muted); font-size: 11px; }
.context-all { display: inline-flex; align-items: center; gap: 4px; padding: 0; color: var(--muted); font-size: 12px; text-align: left; }.note-context h3 .context-all { margin-left: auto; white-space: nowrap; }.context-version { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; padding: 0; text-align: left; color: var(--brand-strong); font-size: 12px; }.context-version i { width: 6px; height: 6px; border-radius: 50%; background: var(--brand); flex: none; }.context-version span { background: var(--brand-soft); border-radius: 4px; padding: 2px 6px; }.context-error { font-size: 12px; color: var(--danger); text-align: left; overflow-wrap: anywhere; padding: 0; }
</style>
