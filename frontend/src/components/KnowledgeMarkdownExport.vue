<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { currentTrainingAccessEpoch, errorText, read } from '../api/client'
import { createKnowledgeMarkdown, knowledgeMarkdownIdentityValid } from '../api/knowledgeMarkdown'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

const props = defineProps<{ itemId: string; versionId: string; versionNumber: number; enabled: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const panelId = `knowledge-markdown-export-${useId()}`
const element = ref<HTMLElement>(), active = ref(true), opened = ref(false), elementVisible = ref(false)
const documentVisible = ref(document.visibilityState === 'visible')
const exported = shallowRef<ReturnType<typeof createKnowledgeMarkdown> & { sessionEpoch: number; accessEpoch: number }>()
const loading = ref(false), problem = ref(''), downloadMessage = ref('')
const readable = computed(() => active.value && store.sessionActive && props.enabled && documentVisible.value
  && elementVisible.value && knowledgeMarkdownIdentityValid(props.itemId, props.versionId, props.versionNumber)
  && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
let alive = true, generation = 0, observer: MutationObserver | undefined

function clear() {
  generation++; exported.value = undefined; loading.value = false; problem.value = ''; downloadMessage.value = ''
}
watch(() => [props.itemId, props.versionId, props.versionNumber], () => { clear(); opened.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => { clear(); opened.value = false }, { flush: 'sync' })
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
  // 上层可能用 v-show 保留详情实例；隐藏后清除全文并丢弃迟到响应，恢复时由用户重新读取。
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
async function load() {
  visibilityChanged(); elementVisibilityChanged()
  if (!readable.value || !opened.value || loading.value) return
  clear(); loading.value = true
  const ticket = generation, sessionEpoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const itemId = props.itemId, versionId = props.versionId, versionNumber = props.versionNumber
  const current = () => alive && readable.value && opened.value && ticket === generation && sessionEpoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && itemId === props.itemId && versionId === props.versionId
    && versionNumber === props.versionNumber && document.visibilityState === 'visible'
  try {
    const response = await read<unknown>(`/knowledge/items/${itemId}/versions/${versionId}`)
    elementVisibilityChanged()
    if (!current()) return
    exported.value = { ...createKnowledgeMarkdown(itemId, versionId, versionNumber, response), sessionEpoch, accessEpoch }
  } catch (error) {
    if (current()) problem.value = errorText(error)
  } finally { if (alive && ticket === generation) loading.value = false }
}
function download() {
  visibilityChanged(); elementVisibilityChanged()
  const value = exported.value
  if (!readable.value || !opened.value || loading.value || !value) return
  if (value.sessionEpoch !== store.sessionEpoch || value.accessEpoch !== currentTrainingAccessEpoch()
    || value.itemId !== props.itemId || value.version.id !== props.versionId || value.version.number !== props.versionNumber) {
    clear(); problem.value = '读取条件已变化，请重新读取发布版本。'; return
  }
  problem.value = ''; downloadMessage.value = ''
  let url: string | undefined
  try {
    url = URL.createObjectURL(new Blob([value.text], { type: 'text/markdown;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = value.filename; anchor.click()
    downloadMessage.value = '已向浏览器发起下载，请确认下载记录和文件内容；系统无法确认文件已保存。'
  } catch {
    problem.value = '浏览器未能发起下载，请检查下载限制后重试。'
  } finally {
    // 下载动作不等待异步读取；短暂保留对象 URL 供浏览器接收，异常路径也会释放。
    if (url) { const released = url; setTimeout(() => URL.revokeObjectURL(released), 1000) }
  }
}
</script>

<template>
  <section ref="element" class="knowledge-markdown-export" aria-label="知识发布版本Markdown导出">
    <div class="row-actions">
      <NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起 Markdown 导出' : `导出发布 v${versionNumber} · Markdown` }}</NButton>
      <span class="field-help">仅所选发布版，编辑草稿不包含在内。</span>
    </div>
    <div v-if="opened && readable" :id="panelId" class="export-content">
      <p class="notice">导出范围：一份发布版本全文及身份／时间元数据。不会包含草稿、其他笔记、附件、主题／关系／星图布局、AI任务与采用／证据账本、训练记录或复习状态。这不是完整备份，不能据此恢复系统。</p>
      <p class="field-help">正文与内部链接原样保留，不抓取关联内容或远程媒体；未转换为完整 Obsidian 库，也不恢复审阅或来源资格。文件在浏览器内存即时生成，无服务器长期下载链接；收起、隐藏或读取条件变化会清除预览，已经下载的文件无法撤回。</p>
      <p v-if="loading" role="status">正在重新读取确切发布版本…</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
      <template v-if="exported">
        <strong>发布 v{{ exported.version.number }} · {{ exported.version.title }}</strong>
        <dl>
          <dt>知识对象</dt><dd>{{ exported.itemId }}</dd>
          <dt>发布版本</dt><dd>{{ exported.version.id }}</dd>
          <dt>发布时间（UTC）</dt><dd>{{ exported.version.createdAt }}</dd>
          <dt>客户端生成时间（UTC）</dt><dd>{{ exported.generatedAt }}（不是服务器签名）</dd>
          <dt>文件名</dt><dd>{{ exported.filename }}</dd>
        </dl>
        <p class="field-help">以下为完整文件原文，包括导出元数据；不会混入右侧对照版本或只导出当前行号页。</p>
        <pre tabindex="0" aria-label="完整Markdown导出原文">{{ exported.text }}</pre>
        <NButton size="small" type="primary" :disabled="!readable || loading" @click="download">下载此发布版（.md）</NButton>
      </template>
      <NButton size="small" :disabled="!readable || loading" @click="load">重新读取此发布版</NButton>
      <p v-if="downloadMessage" class="success-text" role="status">{{ downloadMessage }}</p>
    </div>
  </section>
</template>

<style scoped>
.knowledge-markdown-export, .export-content { display: grid; gap: 12px; min-width: 0; }
.knowledge-markdown-export { margin: 12px 0; }
.export-content { justify-items: start; }
p { margin: 0; }
strong, dd { overflow-wrap: anywhere; }
dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 6px 12px; margin: 0; font-size: 12px; }
dt { color: var(--muted); } dd { margin: 0; }
pre { box-sizing: border-box; width: 100%; max-height: 300px; overflow: auto; margin: 0; padding: 12px; border: 1px solid var(--line); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
</style>
