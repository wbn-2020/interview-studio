<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import { NButton } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../../api/client'
import type { MaterialKnowledgePreview, MaterialKnowledgePreviewEdge, MaterialKnowledgePreviewNode } from '../../api/materialKnowledgePreview'
import { materialKnowledgePreviewPath, materialKnowledgePreviewValid, materialKnowledgeTaskRoute, materialKnowledgeVersionRoute } from '../../api/materialKnowledgePreview'
import { materialAiUseLabels, materialLifecycleStatusLabels } from '../../api/materialLifecycle'
import { materialReferenceIdValid } from '../../api/materialReferences'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ excerptId: string; attachmentId: string; enabled: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), router = useRouter()
const panelId = `material-knowledge-preview-${useId()}`, element = ref<HTMLElement>(), active = ref(true)
const opened = ref(false), documentVisible = ref(document.visibilityState === 'visible'), elementVisible = ref(false)
const preview = shallowRef<MaterialKnowledgePreview>()
const loading = ref(false), problem = ref(''), navigationProblem = ref(''), opening = ref(false), paused = ref(false)
const root = computed(() => `${props.excerptId}/${props.attachmentId}`)
const identityValid = computed(() => materialReferenceIdValid(props.excerptId) && materialReferenceIdValid(props.attachmentId))
const readable = computed(() => active.value && store.sessionActive && props.enabled && identityValid.value && documentVisible.value
  && elementVisible.value && !access.checking && !access.restricted && !access.challenge
  && !store.changes.get('training-access-command')?.locked)
const adoptions = computed(() => {
  const value = preview.value
  if (!value) return []
  const nodes = new Map(value.nodes.map(node => [node.versionId, node]))
  return value.edges.map(edge => ({ edge, target: nodes.get(edge.targetVersionId)!,
    source: edge.sourceVersionId ? nodes.get(edge.sourceVersionId) : undefined }))
})
let alive = true, generation = 0, observer: MutationObserver | undefined

function clear() {
  generation++; preview.value = undefined; loading.value = false; problem.value = ''; navigationProblem.value = ''
}
watch(root, () => { clear(); opened.value = false; paused.value = false }, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode,
  () => access.active?.materialAccess], () => { clear(); paused.value = opened.value }, { flush: 'sync' })
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
  // 详情可能只由上层 v-show 遮挡；隐藏时必须立即清除旧链及在途请求的展示资格。
  observer = new MutationObserver(elementVisibilityChanged)
  for (let node: HTMLElement | null = element.value ?? null; node; node = node.parentElement) {
    observer.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'hidden', 'inert', 'aria-hidden'] })
  }
})
onActivated(() => { active.value = true; elementVisibilityChanged() })
onDeactivated(() => { active.value = false })
onUnmounted(() => {
  alive = false; clear(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibilityChanged)
})
function toggle() {
  if (!readable.value) return
  opened.value = !opened.value; clear(); paused.value = false
  if (opened.value) void load()
}
async function load() {
  if (!readable.value || !opened.value || loading.value) return
  const ticket = ++generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  const excerptId = props.excerptId, attachmentId = props.attachmentId
  preview.value = undefined; loading.value = true; problem.value = ''; navigationProblem.value = ''; paused.value = false
  const current = () => alive && readable.value && opened.value && ticket === generation && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch() && excerptId === props.excerptId && attachmentId === props.attachmentId
  try {
    const value = await read<unknown>(materialKnowledgePreviewPath(excerptId))
    if (!current()) return
    if (!materialKnowledgePreviewValid(value, excerptId, attachmentId)) {
      throw new ApiError(502, 'MATERIAL_KNOWLEDGE_PREVIEW_INVALID', '采用链的根身份、版本、父路径或完整范围无法核对，不能展示部分链，请重新读取。')
    }
    preview.value = value
  } catch (error) {
    if (current()) problem.value = error instanceof ApiError && error.status === 409
      ? `${errorText(error)} 本次完整知识采用链预览不可用，不能据此判断没有影响或动作可以放行。` : errorText(error)
  } finally { if (alive && ticket === generation) loading.value = false }
}
async function navigate(target: RouteLocationRaw | undefined) {
  if (!target || !readable.value || !opened.value || !preview.value || opening.value) return
  const ticket = generation, epoch = store.sessionEpoch, accessEpoch = currentTrainingAccessEpoch()
  opening.value = true; navigationProblem.value = ''
  const current = () => alive && readable.value && opened.value && ticket === generation && epoch === store.sessionEpoch
    && accessEpoch === currentTrainingAccessEpoch()
  try {
    const failure = await router.push(target)
    if (failure && current()) navigationProblem.value = '页面未切换，请先处理当前草稿或资料访问条件。'
  } catch {
    if (current()) navigationProblem.value = '暂时无法打开此入口，请稍后重试。'
  } finally { opening.value = false }
}
function openNode(node: MaterialKnowledgePreviewNode) {
  if (preview.value?.nodes.includes(node)) void navigate(materialKnowledgeVersionRoute(node))
}
function openTask(edge: MaterialKnowledgePreviewEdge) {
  if (preview.value?.edges.includes(edge)) void navigate(materialKnowledgeTaskRoute(edge))
}
function nodeLabel(node: MaterialKnowledgePreviewNode): string {
  return `${node.kind === 'NOTE' ? '知识笔记' : '概念'} v${node.versionNumber} · ${node.current ? '当前发布版' : '历史发布版'} · ${node.status === 'ACTIVE' ? '有效' : '已归档'}`
}
</script>

<template>
  <section ref="element" class="material-knowledge-preview" aria-label="材料实际知识采用链预览">
    <header><div><h3>实际知识采用链</h3><p class="field-help">主动展开后，只读取这条摘录已登记的知识采用元数据。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" :aria-controls="panelId" @click="toggle">{{ opened ? '收起采用链' : '查看实际知识采用链' }}</NButton></header>
    <p v-if="!identityValid" class="notice error" role="alert">摘录与附件身份无法核对，请重新读取材料详情。</p>
    <p v-else-if="opened && !readable" class="notice" role="status">当前查看条件未就绪，旧知识采用链已清除。</p>
    <div v-if="opened" v-show="readable" :id="panelId" class="preview-content" :inert="!readable || undefined" :aria-busy="loading">
      <p class="field-help">仅覆盖已登记的实际知识采用。题目、卡片、准备、事实、关系、审阅、训练固定内容、回忆等出口尚未完整覆盖；这不是完整更正或删除影响清单。需要核对其他出口，请打开知识确切版，在“引用影响”直接引用目录中继续核对。</p>
      <p class="field-help">后续人工发布版保守保留此前采用的依赖范围；下列实际源版不证明其全部正文由原材料直接生成。普通主题或结构关联不单独视为内容派生。当前／历史只比较发布指针，不代表内容正确、已复核或允许外发。</p>
      <p class="field-help">已有正式采用的摘录仍不能归档／恢复。仅有受支持的知识采用且没有未支持出口时，撤回／重新允许 AI 用途可由服务器在本人数据锁内重建真实知识闭包后核验放行；题目、卡片等原始出口仍保守阻塞。预览及引用存在性标记均非放行凭据。读取不创建更正事件或持久影响、不修改 AI 许可；AI 用途变化也不更正正文、不触发知识内容复核或暂停复习。新修订须重新预览与授权，旧授权不会恢复。</p>
      <div class="row-actions"><NButton size="small" :loading="loading" :disabled="!readable || loading" @click="load">重新读取采用链</NButton><span class="field-help">每次刷新重新核对当前登记，最多200个确切知识版本／500条边；超限不展示部分链。</span></div>
      <p v-if="paused" class="notice" role="status">查看条件已变化，请主动重新读取；旧结果不会自动恢复。</p>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}<NButton text size="small" :disabled="!readable || loading" @click="load">重新读取</NButton></p>
      <p v-if="navigationProblem" class="notice error" role="alert">{{ navigationProblem }}</p>
      <p v-if="loading" class="field-help" role="status">正在核对已登记的实际知识采用链…</p>
      <template v-else-if="preview && readable">
        <p class="preview-identity">根摘录 {{ preview.source.excerptId }} · 附件 {{ preview.source.attachmentId }}<br />{{ preview.source.origin === 'MANUAL' ? '手工摘录' : '解析片段摘录' }} · 此次状态：{{ materialLifecycleStatusLabels[preview.lifecycle.status] }}／{{ materialAiUseLabels[preview.lifecycle.aiUse] }} · 修订 {{ preview.lifecycle.revision }}</p>
        <p v-if="!preview.edges.length" class="preview-empty">本次登记范围内没有实际知识采用链；不能据此判断其他用途或完整更正、删除范围没有影响。</p>
        <template v-else>
          <p class="field-help">{{ preview.nodes.length }} 个确切知识版本 · {{ preview.edges.length }} 条实际采用边。循环与菱形的同一边去重，保留一条最短发现路径。</p>
          <ol class="adoption-list">
            <li v-for="entry in adoptions" :key="entry.edge.id">
              <div class="adoption-heading"><strong>{{ entry.target.title }}</strong><span>深度 {{ entry.edge.depth }} · {{ nodeLabel(entry.target) }}</span></div>
              <p class="preview-identity">采用目标 {{ entry.target.itemId }}<br />确切目标版 {{ entry.target.versionId }}</p>
              <template v-if="entry.source">
                <p class="source-heading">实际知识源：{{ entry.source.title }} · {{ nodeLabel(entry.source) }}</p>
                <p class="preview-identity">源知识 {{ entry.source.itemId }}<br />实际源版 {{ entry.source.versionId }}</p>
                <p class="preview-identity">上级路径 {{ entry.edge.parentId }}</p>
              </template>
              <p v-else class="preview-identity">材料根第一跳 · 来源摘录 {{ entry.edge.sourceItemId }} · 无上级边</p>
              <p class="preview-identity">原知识整理任务 {{ entry.edge.taskId }}<br />采用边 {{ entry.edge.id }}</p>
              <div class="row-actions"><NButton text size="small" :disabled="!readable || opening" @click="openNode(entry.target)">打开采用目标确切版</NButton><NButton v-if="entry.source" text size="small" :disabled="!readable || opening" @click="openNode(entry.source)">打开实际源确切版</NButton><NButton text size="small" :disabled="!readable || opening" @click="openTask(entry.edge)">打开原任务</NButton></div>
            </li>
          </ol>
          <p class="field-help">入口继续经过现有草稿与资料守卫。只打开所选版本或任务，不自动采用、授权、复核或调用模型。</p>
        </template>
      </template>
    </div>
  </section>
</template>

<style scoped>
.material-knowledge-preview, .preview-content { display: grid; gap: 12px; min-width: 0; }
.material-knowledge-preview { padding-top: 14px; border-top: 1px solid var(--line); }
header, .adoption-heading { display: flex; align-items: start; justify-content: space-between; flex-wrap: wrap; gap: 10px 12px; }
header > div { display: grid; gap: 7px; min-width: 0; } h3, p { margin: 0; } h3 { font-size: 14px; }
p, strong { overflow-wrap: anywhere; }.row-actions { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
.adoption-list { display: grid; list-style: none; padding: 0; margin: 0; min-width: 0; }
.adoption-list > li { display: grid; gap: 8px; justify-items: start; min-width: 0; padding: 15px 0; border-top: 1px solid var(--line); }
.adoption-heading { width: 100%; }.adoption-heading strong, .source-heading { font-size: 13px; }
.adoption-heading span, .preview-identity { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.preview-empty { padding: 15px 0; color: var(--muted); font-size: 13px; }
</style>
