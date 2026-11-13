<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { Schema } from '../api/client'
import type { OpenKnowledge } from '../api/knowledge'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ enabled: boolean; sourceSha256?: string }>()
const emit = defineEmits<{ open: [target: OpenKnowledge] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const panelId = `knowledge-import-records-${useId()}`
const element = ref<HTMLElement>(), elementVisible = ref(false), active = ref(true), opened = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const page = shallowRef<Schema['KnowledgeTextImportPage']>(), loading = ref(false), problem = ref('')
const readable = computed(() => active.value && props.enabled && store.sessionActive && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked
  && (props.sourceSha256 === undefined || /^[0-9a-f]{64}$/.test(props.sourceSha256)))
let alive = true, generation = 0, observer: MutationObserver | undefined
function clear() { generation++; page.value = undefined; loading.value = false; problem.value = '' }
watch([readable, () => props.sourceSha256, () => store.sessionEpoch, () => access.active?.id,
  () => access.active?.mode, () => access.active?.materialAccess], () => { clear(); opened.value = false }, { flush: 'sync' })
function visibilityChanged() { documentVisible.value = document.visibilityState === 'visible' }
function elementVisibilityChanged() {
  const node = element.value
  elementVisible.value = Boolean(node && node.getClientRects().length && !node.closest('[hidden], [inert], [aria-hidden="true"]'))
  for (let ancestor: HTMLElement | null = node ?? null; ancestor && elementVisible.value; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.visibility === 'hidden' || style.visibility === 'collapse') elementVisible.value = false
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
  alive = false; clear(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
})
function toggle() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value) return
  opened.value = !opened.value; clear()
  if (opened.value) void load()
}
async function load(cursor?: string) {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || !opened.value || loading.value) return
  clear(); loading.value = true
  const ticket = generation, sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const source = props.sourceSha256
  const current = () => alive && readable.value && opened.value && ticket === generation && sessionEpoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && source === props.sourceSha256 && document.visibilityState === 'visible'
  const query = new URLSearchParams({ limit: '20' })
  if (source) query.set('sourceSha256', source)
  if (cursor) query.set('cursor', cursor)
  try {
    const value = await read<Schema['KnowledgeTextImportPage']>(`/knowledge/text-imports?${query}`)
    elementVisibilityChanged()
    if (current()) page.value = value
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (alive && ticket === generation) loading.value = false }
}
function open(entry: Schema['KnowledgeTextImportEntry']) {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || !opened.value || loading.value || !page.value?.items.includes(entry)) return
  emit('open', { id: entry.itemId, versionId: entry.versionId })
}
</script>

<template>
  <section ref="element" class="knowledge-import-records" aria-label="文本导入记录">
    <NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起导入记录' : sourceSha256 ? '检查这份原文的既有导入' : '读取文本导入记录' }}</NButton>
    <div v-if="opened && readable" :id="panelId" class="record-list">
      <p class="field-help">仅包含启用导入账本后成功登记的记录，不涵盖旧普通创建或全库相似内容。指纹区分BOM及换行；改名仍按原文匹配。这里只读元数据，打开才读取确切首版；记录不认证作者、转换过程或模型许可。</p>
      <p v-if="loading" role="status">正在读取导入记录…</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <p v-if="page && !page.items.length" role="status">本次查询没有匹配的登记记录；这不表示全库没有重复内容。</p>
      <article v-for="entry in page?.items" :key="entry.id">
        <strong>{{ entry.title }}</strong>
        <p>{{ entry.fileName }} · {{ entry.sourceBytes }}字节 · {{ entry.sourceCharacters }}字符</p>
        <p>导入时间（UTC）：{{ entry.createdAt }} · {{ entry.createCopy ? '明确新建副本' : '默认去重创建' }}</p>
        <p>知识当前状态：{{ entry.itemStatus === 'ARCHIVED' ? '已归档' : '可用' }}；标题和打开入口对应导入首版，后续编辑不改写此记录。</p>
        <details><summary>查看原始文本指纹</summary><code>{{ entry.sourceSha256 }}</code></details>
        <NButton size="small" :disabled="!readable || loading" @click="open(entry)">打开导入首版</NButton>
      </article>
      <div class="row-actions"><NButton size="small" :disabled="!readable || loading" @click="load()">刷新首页</NButton><NButton v-if="page?.nextCursor" size="small" :disabled="!readable || loading" @click="load(page.nextCursor)">下一页</NButton></div>
    </div>
  </section>
</template>

<style scoped>
.knowledge-import-records, .record-list, article { display: grid; gap: 10px; min-width: 0; }
.knowledge-import-records { margin: 10px 0; justify-items: start; }
article { padding: 12px; border: 1px solid var(--line); }
p { margin: 0; }
strong, p, code { overflow-wrap: anywhere; }
</style>
