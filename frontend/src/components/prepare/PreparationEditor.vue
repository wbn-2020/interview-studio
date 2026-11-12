<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useRecentVisit } from '../../composables/useRecentVisit'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import type { ChosenVersion, FactContent, PreparationDecisionPage, PreparationImpactPage, PreparationItem, PreparationKind, PreparationReference } from '../../api/preparation'
import { cloneContent, contentProblem, emptyContent, kindLabels, referenceLabels, responsibilityLabels, verificationLabels } from '../../api/preparation'
import AiExpressionOrigins from '../ai/AiExpressionOrigins.vue'
import AiJdRequirementsOrigins from '../ai/AiJdRequirementsOrigins.vue'
import AiFactOrigins from './AiFactOrigins.vue'
import ExpressionBasisReview from './ExpressionBasisReview.vue'
import PreparationKnowledgeImpacts from '../PreparationKnowledgeImpacts.vue'
import PreparationContentForm from './PreparationContentForm.vue'
import PreparationHistory from './PreparationHistory.vue'
import PreparationPicker from './PreparationPicker.vue'
import PreparationReadout from './PreparationReadout.vue'

const props = defineProps<{ id?: string; kind: PreparationKind }>()
const emit = defineEmits<{ changed: []; created: [item: PreparationItem]; close: []; open: [id: string, kind: PreparationKind, versionId?: string] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const router = useRouter()
const route = useRoute()
const writer = useCommand()
const basisReviewState = ref({ dirty: false, locked: false })
const editorLocked = computed(() => writer.locked.value || basisReviewState.value.locked)
const current = ref<PreparationItem>()
const content = ref(emptyContent(props.kind))
const projectId = ref<string | null>(null)
const projectName = ref('')
const reason = ref('')
const tab = ref<'edit' | 'read' | 'history' | 'sources'>('edit')
const savedMessage = ref('')
const serverCopy = ref<PreparationItem>()
const serverError = ref('')
const separateCopy = ref<PreparationItem>()
const decisionsBefore = ref<string>()
const decisionsCursors = ref<(string | undefined)[]>([])
const impactsCursor = ref<string>()
const impactsCursors = ref<(string | undefined)[]>([])
let alive = true
const kind = computed(() => current.value?.kind ?? props.kind)
const creating = computed(() => !props.id)
const contentDirty = computed(() => JSON.stringify(content.value) !== JSON.stringify(current.value?.currentVersion.content ?? emptyContent(props.kind)) || (creating.value && projectId.value !== null))
const dirty = computed(() => contentDirty.value || Boolean(reason.value))
const editable = computed(() => store.sessionActive && !editorLocked.value && (creating.value || current.value?.status === 'ACTIVE'))
const canGenerateExpression = computed(() => store.sessionActive && !editorLocked.value && !access.checking && !access.restricted && !access.challenge)
const problem = computed(() => contentProblem(kind.value, content.value) || (creating.value && kind.value === 'FACT' && !projectId.value ? '请先选择一个活跃项目，事实将固定属于这个项目。' : ''))
const detail = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'item', props.id]), enabled: computed(() => store.sessionActive && Boolean(props.id)), queryFn: () => read<PreparationItem>(`/prepare/items/${props.id}`) })
const recordRecentVisit = useRecentVisit('PREPARATION', () => props.id)
const latestSources = computed(() => detail.data.value?.currentVersion.id === current.value?.currentVersion.id ? detail.data.value : current.value)
const basisReviewStale = computed(() => detail.isFetching.value || Boolean(detail.error.value) || !current.value
  || detail.data.value?.id !== current.value.id || detail.data.value.revision !== current.value.revision
  || detail.data.value.currentVersion.id !== current.value.currentVersion.id)
const canGeneratePreparationPlan = computed(() => canGenerateExpression.value && current.value?.kind === 'TARGET'
  && current.value.status === 'ACTIVE' && detail.data.value?.status === 'ACTIVE'
  && detail.data.value.currentVersion.id === current.value.currentVersion.id)
const canGenerateJdRequirements = computed(() => canGeneratePreparationPlan.value && !creating.value && !dirty.value
  && !detail.isFetching.value && !detail.error.value && tab.value !== 'history'
  && current.value?.kind === 'TARGET' && current.value.id === props.id && Boolean(current.value.currentVersion.content.jd.trim()))
const decisions = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'decisions', props.id, decisionsBefore.value]), enabled: computed(() => store.sessionActive && Boolean(props.id) && kind.value === 'FACT' && tab.value === 'sources'), queryFn: () => read<PreparationDecisionPage>(`/prepare/items/${props.id}/decisions?limit=20${decisionsBefore.value ? `&before=${encodeURIComponent(decisionsBefore.value)}` : ''}`) })
const impacts = useQuery({ queryKey: computed(() => ['preparation', store.sessionEpoch, 'impacts', props.id, impactsCursor.value]), enabled: computed(() => store.sessionActive && Boolean(props.id) && tab.value === 'sources'), queryFn: () => read<PreparationImpactPage>(`/prepare/items/${props.id}/impacts?limit=20${impactsCursor.value ? `&cursor=${encodeURIComponent(impactsCursor.value)}` : ''}`) })

function accept(value: PreparationItem) { current.value = value; content.value = cloneContent(value.currentVersion.content); projectId.value = value.projectId }
watch(detail.data, value => {
  if (value && value.id === props.id && (!current.value || (!dirty.value && !editorLocked.value))) {
    accept(value)
    recordRecentVisit(value.id)
  }
}, { immediate: true })
watch(() => route.query.version, value => { if (typeof value === 'string' && props.id) tab.value = 'history' }, { immediate: true })
// 准备区内部换对象只检查编辑器键，复核理由和未决请求也必须进入同一保稿守卫。
watch([dirty, () => basisReviewState.value.dirty, editorLocked], ([changed, reviewDirty, locked]) =>
  store.changes.set('prepare-editor', { dirty: changed || reviewDirty, locked }), { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; store.changes.delete('prepare-editor') })
function refresh() { void queryClient.invalidateQueries({ queryKey: ['preparation'] }); emit('changed') }
function update(value: PreparationItem, message: string) {
  // 元数据、核实和归档在正文干净时才能执行；成功响应不能抬升另一份脏正文的基线。
  accept(value)
  savedMessage.value = message
  serverCopy.value = undefined
  queryClient.setQueryData(['preparation', store.sessionEpoch, 'item', value.id], value)
  refresh()
}
function save() {
  if (!editable.value || problem.value) return
  if (creating.value) {
    void writer.run<PreparationItem>('/prepare/items', 'POST', { kind: kind.value, projectId: kind.value === 'FACT' ? projectId.value : null, content: cloneContent(content.value) }, value => {
      separateCopy.value = value
      content.value = emptyContent(props.kind)
      projectId.value = null
      projectName.value = ''
      reason.value = ''
      store.changes.set('prepare-editor', { dirty: false, locked: false })
      refresh()
      emit('created', value)
    })
  } else if (current.value) {
    void writer.run<PreparationItem>(`/prepare/items/${props.id}`, 'PUT', { expectedRevision: current.value.revision, content: cloneContent(content.value) }, value => update(value, `已保存为版本 ${value.currentVersion.number}${value.kind === 'FACT' ? '，本版本需重新核实' : ''}`))
  }
}
function mutate(action: 'favorite' | 'archive' | 'restore' | 'confirm' | 'withdraw') {
  if (!current.value || contentDirty.value || editorLocked.value || !store.sessionActive) return
  const decision = action === 'confirm' || action === 'withdraw'
  if (action === 'withdraw' && !reason.value.trim()) return
  if (decision && reason.value.length > 2000) return
  if (action === 'confirm' && !window.confirm(`确认已核实当前版本 v${current.value.currentVersion.number} 的事实和责任边界？这不授予模型外发权限。`)) return
  if (action === 'archive' && !window.confirm('归档后退出默认选择；历史版本和已保存引用保留。确定归档？')) return
  const body = { expectedRevision: current.value.revision, ...(action === 'favorite' ? { favorite: !current.value.favorite } : {}), ...(decision ? { reason: reason.value } : {}) }
  void writer.run<PreparationItem>(`/prepare/items/${props.id}/${action}`, action === 'favorite' ? 'PUT' : 'POST', body, value => {
    if (decision) reason.value = ''
    update(value, action === 'favorite' ? (value.favorite ? '已收藏' : '已移出收藏') : action === 'confirm' ? '该事实版本已由你核实，职责保持原样' : action === 'withdraw' ? '该事实版本已撤回，既有表达保留并提示来源变化' : action === 'archive' ? '已归档，历史保留' : '已恢复，核实状态保持原样')
  })
}
async function compareServer() {
  if (!props.id || !store.sessionActive) return
  const sessionEpoch = store.sessionEpoch
  serverError.value = ''
  try {
    const value = await read<PreparationItem>(`/prepare/items/${props.id}`)
    // 冲突副本只供当前页面和会话查看，迟到读取不能在重新登录后替换本地比较对象。
    if (alive && store.sessionActive && store.sessionEpoch === sessionEpoch) serverCopy.value = value
  } catch (error) {
    if (alive && store.sessionActive && store.sessionEpoch === sessionEpoch) serverError.value = errorText(error)
  }
}
function adoptServer() {
  if (!serverCopy.value || editorLocked.value || !window.confirm('采用服务器最新内容并丢弃本地未保存输入和核实备注？')) return
  accept(serverCopy.value)
  reason.value = ''
  serverCopy.value = undefined
  writer.error.value = undefined
  savedMessage.value = '已采用服务器副本'
}
function saveSeparate() {
  if (!current.value || problem.value || editorLocked.value || !store.sessionActive) return
  void writer.run<PreparationItem>('/prepare/items', 'POST', { kind: kind.value, projectId: kind.value === 'FACT' ? current.value.projectId : null, content: cloneContent(content.value) }, value => { separateCopy.value = value; refresh() })
}
function chooseProject(value: ChosenVersion) { if (editable.value && creating.value) { projectId.value = value.itemId; projectName.value = value.title } }
function generateExpression() {
  if (!canGenerateExpression.value) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'EXPRESSION_DRAFT' } })
}
function generatePreparationPlan() {
  if (!canGeneratePreparationPlan.value || !current.value) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'PREPARATION_PLAN', targetItemId: current.value.id, targetVersionId: current.value.currentVersion.id } })
}
function generateJdRequirements() {
  if (!canGenerateJdRequirements.value || !current.value) return
  void router.push({ name: 'ai', query: { create: '1', purpose: 'JD_REQUIREMENT_DRAFT', targetItemId: current.value.id, targetVersionId: current.value.currentVersion.id } })
}
function openReference(reference: PreparationReference) {
  if (reference.kind === 'KNOWLEDGE') void router.push({ name: 'knowledge', query: { item: reference.itemId, version: reference.versionId } })
  else emit('open', reference.itemId, reference.kind, reference.versionId)
}
function olderDecisions() { if (decisions.data.value?.nextCursor) { decisionsCursors.value.push(decisionsBefore.value); decisionsBefore.value = decisions.data.value.nextCursor } }
function nextImpacts() { if (impacts.data.value?.nextCursor) { impactsCursors.value.push(impactsCursor.value); impactsCursor.value = impacts.data.value.nextCursor } }
</script>

<template>
  <section class="preparation-editor" :aria-label="`${kindLabels[kind]}详情`">
    <div v-if="!creating && detail.isPending.value && !current" class="loading-block" role="status">正在读取准备材料…</div>
    <div v-else-if="!creating && !current" class="notice error" role="alert">{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></div>
    <template v-else>
      <header class="detail-header"><div><p class="section-label">{{ creating ? '新建' : kindLabels[kind] }}{{ current ? ` · v${current.currentVersion.number}` : '' }}</p><h2>{{ creating ? `新建${kindLabels[kind]}` : current?.currentVersion.content.title }}</h2></div><button class="icon-button" aria-label="关闭材料详情" @click="emit('close')">×</button></header>
      <div class="save-state" aria-live="polite"><span :class="{ unsaved: dirty }">{{ writer.busy.value ? '正在保存…' : dirty ? '有本地输入，尚未全部保存' : creating ? '填写后保存首个版本' : `已读取服务器修订 ${current?.revision}` }}</span><span v-if="current?.status === 'ARCHIVED'" class="status-pill">已归档</span><span v-if="current?.verification" class="status-pill">{{ verificationLabels[current.verification] }}</span></div>
      <p v-if="current && detail.error.value" class="notice error" role="alert">最新读取未完成，本地输入继续保留：{{ errorText(detail.error.value) }}<NButton text @click="detail.refetch()">重试读取</NButton></p>
      <p v-if="latestSources?.reviewRequired" class="prepare-warning" role="status">依据已有变化，请复核表达。历史正文保持原样；重新保存前需选择当前有效的确认事实。</p>
      <p v-if="detail.data.value && current && detail.data.value.revision !== current.revision" class="prepare-warning">服务器有更新，本地编辑仍使用原修订。<button class="text-link" @click="compareServer">查看服务器副本</button></p>
      <div v-if="writer.error.value" class="notice error" role="alert"><p>{{ errorText(writer.error.value) }}</p><NButton v-if="writer.pending.value && !writer.busy.value" size="small" @click="writer.retry">用原请求重试</NButton><template v-if="writer.error.value instanceof ApiError && writer.error.value.status === 409 && current"><NButton size="small" :disabled="writer.locked.value" @click="compareServer">查看服务器副本</NButton><NButton size="small" :disabled="writer.locked.value || !!problem" @click="saveSeparate">将本地内容另存</NButton></template></div>
      <p v-if="savedMessage && !contentDirty" class="success-text" role="status">{{ savedMessage }}</p><p v-if="separateCopy" class="notice" role="status">已另存「{{ separateCopy.currentVersion.content.title }}」。<NButton text size="small" @click="emit('open', separateCopy.id, separateCopy.kind)">打开已保存材料</NButton></p>
      <p v-if="serverError" class="notice error" role="alert">{{ serverError }}</p>
      <div v-if="serverCopy" class="comparison-panel"><h3>服务器副本 · 修订 {{ serverCopy.revision }}</h3><p class="field-help">以下只读内容不会自动替换本地输入。</p><div class="server-copy"><PreparationReadout :kind="serverCopy.kind" :content="serverCopy.currentVersion.content" /></div><div class="row-actions"><NButton size="small" :disabled="writer.locked.value" @click="adoptServer">采用服务器副本</NButton><NButton size="small" @click="serverCopy = undefined">保留本地输入</NButton></div></div>
      <nav v-if="current" class="detail-tabs" aria-label="准备材料内容"><button v-for="option in ([['edit', '编辑'], ['read', '阅读'], ['history', '历史'], ['sources', '来源与影响']] as const)" :key="option[0]" :class="{ active: tab === option[0] }" :aria-current="tab === option[0] ? 'page' : undefined" @click="tab = option[0]">{{ option[1] }}</button></nav>
      <div v-show="tab === 'edit' || creating" class="prepare-editor-body">
        <section v-if="kind === 'FACT'" class="prepare-project"><h3>所属项目</h3><template v-if="creating"><p v-if="projectId">已选：{{ projectName }}<NButton text size="small" :disabled="!editable" @click="projectId = null; projectName = ''">重新选择</NButton></p><PreparationPicker v-else :kinds="['PROJECT']" :disabled="!editable" @choose="chooseProject" /></template><p v-else class="field-help">项目 {{ current?.projectId }} · 所属关系固定，调整经历请另建事实。</p></section>
        <div v-if="kind === 'TARGET'" class="expression-generation"><div class="row-actions"><NButton :disabled="!canGeneratePreparationPlan" @click="generatePreparationPlan">生成准备建议</NButton><NButton :disabled="!canGenerateJdRequirements" @click="generateJdRequirements">从已保存 JD 整理要求候选</NButton></div><p class="field-help">准备建议使用当前已保存且活跃的目标版本，在下一页明确日期、预计投入和自述缺口。JD 要求整理仅使用当前已保存版本的标题与原始 JD，先核对清单再授权生成；手工稿不会自动保存或外发，生成也不会自动覆盖已有要求。</p></div>
        <div v-if="kind === 'EXPRESSION'" class="expression-generation"><NButton :disabled="!canGenerateExpression" @click="generateExpression">生成表达素材</NButton><p class="field-help">选择已确认的事实版本，核对外发内容后授权生成候选。当前未保存的编辑输入不会自动带入。</p></div>
        <PreparationContentForm v-model="content" :kind="kind" :references="latestSources?.references" :disabled="!editable" />
        <p v-if="problem" class="field-help" role="status">{{ problem }}</p>
        <div class="editor-actions"><NButton type="primary" :loading="writer.busy.value" :disabled="!editable || !!problem || (!creating && !contentDirty)" @click="save">{{ creating ? '创建并保存' : '保存新版本' }}</NButton><NButton v-if="writer.pending.value && !writer.busy.value" @click="writer.retry">用原请求重试</NButton></div><p class="field-help">{{ kind === 'FACT' ? '每次正文保存形成新版本，并重置为待核实。' : '保存后形成独立版本，既有历史不会改写。' }}所有内容默认不外发模型。</p>
        <section v-if="current && kind === 'FACT'" class="prepare-verification"><h3>核实当前事实版本</h3><p class="field-help">请先保存正文，再对正在显示的版本作决定。确认不扩大职责、不代表技术审阅。</p><p>当前责任：{{ responsibilityLabels[(content as FactContent).responsibility] }}</p><label>核实依据／撤回原因<NInput v-model:value="reason" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :maxlength="2000" :disabled="!editable" placeholder="撤回必须说明原因" /></label><div class="row-actions"><NButton size="small" :disabled="!editable || contentDirty || (content as FactContent).responsibility === 'UNVERIFIED' || current.verification === 'CONFIRMED'" @click="mutate('confirm')">核实本版本</NButton><NButton size="small" :disabled="!editable || contentDirty || !reason.trim() || current.verification === 'WITHDRAWN'" @click="mutate('withdraw')">撤回本版本</NButton><NButton v-if="reason" size="small" text :disabled="writer.locked.value" @click="reason = ''">清空未保存备注</NButton></div></section>
        <div v-if="current" class="secondary-actions"><NButton size="small" :disabled="editorLocked || contentDirty || !store.sessionActive" @click="mutate('favorite')">{{ current.favorite ? '移出收藏' : '收藏' }}</NButton><span class="field-help">{{ new Date(current.updatedAt).toLocaleString('zh-CN') }}</span><NButton size="small" quaternary :disabled="editorLocked || contentDirty || !store.sessionActive" @click="mutate(current.status === 'ACTIVE' ? 'archive' : 'restore')">{{ current.status === 'ACTIVE' ? '归档' : '恢复' }}</NButton></div>
      </div>
      <div v-if="tab === 'read' && current" class="prepare-editor-body"><p class="field-help">{{ contentDirty ? '包含尚未保存的本地输入。' : '正在阅读当前已保存内容。' }}</p><PreparationReadout :kind="kind" :content="content" /><AiExpressionOrigins v-if="kind === 'EXPRESSION'" :expression-id="current.id" :version-id="current.currentVersion.id" :version-number="current.currentVersion.number" :editing="contentDirty" /><AiJdRequirementsOrigins v-if="kind === 'TARGET' && current.id === id" :target-id="current.id" :version-id="current.currentVersion.id" :version-number="current.currentVersion.number" :editing="contentDirty" :disabled="writer.locked.value || detail.isFetching.value || !!detail.error.value" /><AiFactOrigins v-if="kind === 'FACT' && current.id === id && current.projectId" :fact-id="current.id" :project-id="current.projectId" :version-id="current.currentVersion.id" :version-number="current.currentVersion.number" :editing="contentDirty" :disabled="writer.locked.value || detail.isFetching.value || !!detail.error.value" /></div>
      <PreparationHistory v-if="tab === 'history' && current" :id="current.id" :kind="kind" :current-version="current.currentVersion" :project-id="current.projectId ?? undefined" />
      <section v-if="current" v-show="tab === 'sources'" class="prepare-editor-body">
        <ExpressionBasisReview v-if="current.kind === 'EXPRESSION'" :item="current" :visible="tab === 'sources'"
          :editing="contentDirty" :parent-locked="writer.locked.value" :stale="basisReviewStale"
          @state="basisReviewState = $event" @changed="refresh" />
        <PreparationKnowledgeImpacts :item-id="current.id" context="PREPARATION" :visible="tab === 'sources'"
          :disabled="editorLocked" />
        <h3>当前保存版本的确切依据</h3><p class="field-help">标题与版本号取被引用的原版本，状态说明它现在是否仍可用于新材料。</p><p v-if="!latestSources?.references.length" class="field-help">这个版本没有引用其他对象；手工来源备注见阅读页。</p><div v-for="reference in latestSources?.references" :key="reference.versionId" class="prepare-source-row"><button class="text-link" @click="openReference(reference)">{{ reference.title }} · v{{ reference.number }}</button><span class="status-pill">{{ referenceLabels[reference.state] }}</span></div>
        <h3>直接引用影响</h3><p v-if="impacts.error.value" class="notice error">{{ errorText(impacts.error.value) }}<NButton text @click="impacts.refetch()">重试</NButton></p><p v-if="impacts.isPending.value" class="field-help">正在查询引用…</p><p v-if="impacts.data.value && !impacts.data.value.items.length" class="field-help">没有当前表达或目标引用本材料的版本。训练等来源将在对应阶段接入。</p><div v-for="impact in impacts.data.value?.items" :key="`${impact.id}-${impact.versionId}`" class="prepare-source-row"><button class="text-link" @click="emit('open', impact.id, impact.kind, impact.versionId)">{{ impact.title }} · {{ kindLabels[impact.kind] }}</button><span v-if="impact.reviewRequired" class="status-pill">待复核</span></div><div v-if="impactsCursors.length || impacts.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!impactsCursors.length" @click="impactsCursor = impactsCursors.pop()">上一页</NButton><NButton size="small" :disabled="!impacts.data.value?.nextCursor" @click="nextImpacts">下一页</NButton></div>
        <template v-if="kind === 'FACT'"><h3>核实决定历史</h3><p v-if="decisions.error.value" class="notice error">{{ errorText(decisions.error.value) }}<NButton text @click="decisions.refetch()">重试</NButton></p><p v-if="decisions.isPending.value" class="field-help">正在读取决定…</p><p v-if="decisions.data.value && !decisions.data.value.items.length" class="field-help">尚未对本事实作出核实或撤回决定。</p><article v-for="decision in decisions.data.value?.items" :key="decision.id" class="prepare-decision"><p><strong>{{ verificationLabels[decision.decision] }}</strong> · {{ new Date(decision.createdAt).toLocaleString('zh-CN') }}</p><button class="text-link" @click="emit('open', current.id, current.kind, decision.versionId)">查看所依据的版本 {{ decision.versionId.slice(0, 8) }}</button><p>{{ decision.reason || '未填写补充备注' }}</p></article><div v-if="decisionsCursors.length || decisions.data.value?.nextCursor" class="row-actions"><NButton size="small" :disabled="!decisionsCursors.length" @click="decisionsBefore = decisionsCursors.pop()">较新</NButton><NButton size="small" :disabled="!decisions.data.value?.nextCursor" @click="olderDecisions">更早</NButton></div></template>
      </section>
    </template>
  </section>
</template>

<style scoped>
.preparation-editor { min-width: 0; padding: 26px clamp(18px, 3vw, 34px) 32px; overflow-wrap: anywhere; }
.prepare-editor-body { display: grid; gap: 16px; }
.prepare-editor-body .editor-actions { padding-top: 6px; }
.expression-generation { display: grid; justify-items: start; gap: 8px; }
.prepare-warning { padding: 11px 12px; margin: 10px 0; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); font-size: 12px; }
.prepare-project, .prepare-verification { display: grid; gap: 10px; padding: 16px 0; border-bottom: 1px solid var(--line); }
.prepare-verification { border-top: 1px solid var(--line); }
.prepare-verification > label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; }
.prepare-project > p { display: flex; gap: 12px; align-items: center; }
.prepare-source-row { display: flex; align-items: start; justify-content: space-between; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--line); }
.prepare-source-row > button { text-align: left; }
.prepare-decision { display: grid; gap: 7px; padding: 12px 0; border-bottom: 1px solid var(--line); font-size: 12px; }
.prepare-decision > button { text-align: left; }
.prepare-decision > p:last-child { white-space: pre-wrap; }
.server-copy { max-height: 420px; overflow: auto; margin: 12px 0; }
.detail-header .section-label { letter-spacing: 0; }
@media (max-width: 560px) { .preparation-editor { padding: 20px 14px; } .detail-tabs { gap: 0; flex-wrap: wrap; } }
</style>
