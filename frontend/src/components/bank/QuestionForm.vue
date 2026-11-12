<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSelect } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { ItemDetail, ItemSummary, Schema } from '../../api/client'
import type { QuestionInput, QuestionKnowledgeReference } from '../../api/bank'
import { knowledgeReferenceLabels, questionDifficultyOptions, questionDimensionOptions, questionTextLength, questionTypeOptions } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
import KnowledgePicker from '../KnowledgePicker.vue'

const MarkdownEditor = defineAsyncComponent(() => import('../MarkdownEditor.vue'))
const props = defineProps<{ modelValue: QuestionInput; disabled?: boolean; references?: QuestionKnowledgeReference[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: QuestionInput] }>()
const content = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const store = useWorkspaceStore()
const pickerOpen = ref(false)
const choosing = ref(false)
const choiceError = ref('')
const choiceNames = reactive<Record<string, { title: string; number: number }>>({})
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const topicOptions = computed(() => {
  const options = topics.data.value?.items.map(topic => ({ label: topic.name, value: topic.id })) ?? []
  for (const id of content.value.topicIds) if (!options.some(option => option.value === id)) options.push({ value: id, label: `已存主题 ${id.slice(0, 8)}` })
  return options
})
const tagOptions = computed(() => content.value.tags.map(tag => ({ value: tag, label: tag })))
const blocked = computed(() => Boolean(props.disabled) || !store.sessionActive)
let alive = true
let choiceRequest = 0
function cancelChoice() { choiceRequest++; choosing.value = false; choiceError.value = '' }
watch([blocked, () => store.sessionEpoch, () => props.modelValue], cancelChoice, { flush: 'sync' })
onUnmounted(() => { alive = false; choiceRequest++ })

function referenceFor(versionId: string) { return props.references?.find(reference => reference.versionId === versionId) }
function knowledgeName(itemId: string, versionId: string) { return referenceFor(versionId)?.title ?? choiceNames[versionId]?.title ?? `知识 ${itemId.slice(0, 8)}` }
function knowledgeVersion(versionId: string) { const number = referenceFor(versionId)?.number ?? choiceNames[versionId]?.number; return number ? `v${number}` : '已保存版本' }
async function chooseKnowledge(item: ItemSummary) {
  if (blocked.value || choosing.value || content.value.knowledgeLinks.length >= 20) return
  const epoch = store.sessionEpoch
  const request = ++choiceRequest
  choosing.value = true
  choiceError.value = ''
  try {
    const value = await read<ItemDetail>(`/knowledge/items/${item.id}`)
    // 选择器显示标题不等于版本承诺；读发布版本后再次核对表单、会话与写入锁。
    if (!alive || request !== choiceRequest || epoch !== store.sessionEpoch || blocked.value) return
    if (value.status !== 'ACTIVE' || !value.published || !['NOTE', 'CONCEPT'].includes(value.kind)) { choiceError.value = '该知识已不可用，请重新选择当前有效的发布版本。'; return }
    if (content.value.knowledgeLinks.some(link => link.itemId === value.id) || content.value.knowledgeLinks.length >= 20) return
    choiceNames[value.published.id] = { title: value.published.title, number: value.published.number }
    content.value.knowledgeLinks.push({ itemId: value.id, versionId: value.published.id })
    pickerOpen.value = false
  } catch (error) {
    if (alive && request === choiceRequest && epoch === store.sessionEpoch && !blocked.value) choiceError.value = errorText(error)
  } finally { if (alive && request === choiceRequest) choosing.value = false }
}
function removeKnowledge(itemId: string, versionId: string) { if (!blocked.value) content.value.knowledgeLinks = content.value.knowledgeLinks.filter(link => link.itemId !== itemId || link.versionId !== versionId) }
function addCheckpoint() { if (!blocked.value && content.value.checkpoints.length < 30) content.value.checkpoints.push({ id: crypto.randomUUID(), text: '' }) }
function removeCheckpoint(id: string) { if (!blocked.value) content.value.checkpoints = content.value.checkpoints.filter(point => point.id !== id) }
</script>

<template>
  <div class="question-form">
    <label>题目标题<NInput v-model:value="content.title" :disabled="blocked" :maxlength="200" :count-graphemes="questionTextLength" placeholder="用一句话标明需要回答的问题" /></label>
    <div class="question-form-pair"><label>题型<NSelect v-model:value="content.type" :disabled="blocked" :options="questionTypeOptions" /></label><label>个人难度<NSelect v-model:value="content.difficulty" :disabled="blocked" :options="questionDifficultyOptions" clearable placeholder="暂不标记" /></label></div>
    <label>考察维度<NSelect v-model:value="content.dimensions" :disabled="blocked" :options="questionDimensionOptions" multiple placeholder="至少选择一项" /></label>
    <section class="question-markdown"><h3>题干</h3><p class="field-help">支持 Markdown；代码作为题目展示，不在浏览器执行。最多 10000 字符。</p><MarkdownEditor v-model="content.prompt" :disabled="blocked" /></section>
    <label>适用条件<NInput v-model:value="content.conditions" :disabled="blocked" type="textarea" :maxlength="5000" :count-graphemes="questionTextLength" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="例如：单机 MySQL、默认隔离级别、并发访问假设" /></label>
    <label>技术与版本<NInput v-model:value="content.technology" :disabled="blocked" type="textarea" :maxlength="2000" :count-graphemes="questionTextLength" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="影响答案的框架、数据库或语言版本" /></label>
    <label>场景<NInput v-model:value="content.scenario" :disabled="blocked" type="textarea" :maxlength="2000" :count-graphemes="questionTextLength" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="这道题讨论的业务或故障背景" /></label>
    <div class="question-form-pair"><label>知识主题<NSelect v-model:value="content.topicIds" :disabled="blocked" :options="topicOptions" multiple filterable placeholder="可选，最多 20 项" /></label><label>标签<NSelect v-model:value="content.tags" :disabled="blocked" :options="tagOptions" multiple filterable tag placeholder="输入后回车添加" /></label></div>
    <p v-if="topics.error.value" class="notice error" role="alert">主题列表暂未读取：{{ errorText(topics.error.value) }}<NButton text :disabled="!store.sessionActive" @click="topics.refetch()">重试</NButton></p>
    <section class="question-form-section"><header><div><h3>关联知识</h3><p class="field-help">关联笔记或概念的确切发布版本，最多 20 项。旧引用保留原版本，可移除后重新选择。</p></div><NButton size="small" :disabled="blocked || content.knowledgeLinks.length >= 20" @click="pickerOpen = !pickerOpen">{{ pickerOpen ? '收起选择器' : '添加知识' }}</NButton></header>
      <p v-if="!content.knowledgeLinks.length" class="field-help">尚未关联知识，可以独立保存题目。</p>
      <div v-for="link in content.knowledgeLinks" :key="link.versionId" class="question-link-row"><div><strong>{{ knowledgeName(link.itemId, link.versionId) }}</strong><p>{{ knowledgeVersion(link.versionId) }}<span v-if="referenceFor(link.versionId)" :class="{ warning: referenceFor(link.versionId)?.state !== 'CURRENT' }"> · {{ knowledgeReferenceLabels[referenceFor(link.versionId)!.state] }}</span><span v-else> · 待保存引用</span></p></div><NButton size="small" :disabled="blocked" @click="removeKnowledge(link.itemId, link.versionId)">移除</NButton></div>
      <KnowledgePicker v-if="pickerOpen" :disabled="blocked || choosing" :exclude-ids="content.knowledgeLinks.map(link => link.itemId)" @choose="chooseKnowledge" />
      <p v-if="choosing" class="field-help" role="status">正在读取所选知识的发布版本…</p><p v-if="choiceError" class="notice error" role="alert">{{ choiceError }}</p>
    </section>
    <details class="question-source-fields"><summary>来源记录 <span class="field-help">可选</span></summary><label>来源标题<NInput v-model:value="content.source.title" :disabled="blocked" :maxlength="200" :count-graphemes="questionTextLength" /></label><label>来源网址<NInput v-model:value="content.source.url" :disabled="blocked" :maxlength="2000" :count-graphemes="questionTextLength" placeholder="https://…" /></label><label>来源备注<NInput v-model:value="content.source.note" :disabled="blocked" type="textarea" :maxlength="5000" :count-graphemes="questionTextLength" :autosize="{ minRows: 2, maxRows: 6 }" /></label><p class="field-help">仅保存来源记录，不自动抓取网页。</p></details>
    <section class="question-form-section"><header><div><h3>个人参考／未审</h3><p class="field-help">参考与题干一起保存新版本。保留你判断的依据，尚不代表技术审核结论。最多 20000 字符。</p></div></header><MarkdownEditor v-model="content.referenceAnswer" :disabled="blocked" /></section>
    <section class="question-form-section"><header><div><h3>参考检查点</h3><p class="field-help">记录希望覆盖的要点，最多 30 项；它们与参考正文同样受显式查看控制。</p></div><NButton size="small" :disabled="blocked || content.checkpoints.length >= 30" @click="addCheckpoint">添加检查点</NButton></header><p v-if="!content.checkpoints.length" class="field-help">尚未填写检查点。</p><div v-for="(point, index) in content.checkpoints" :key="point.id" class="question-checkpoint"><label :for="`question-checkpoint-${point.id}`">{{ index + 1 }}</label><NInput :id="`question-checkpoint-${point.id}`" v-model:value="point.text" :disabled="blocked" type="textarea" :maxlength="500" :count-graphemes="questionTextLength" :autosize="{ minRows: 2, maxRows: 5 }" :aria-label="`检查点 ${index + 1}`" /><NButton size="small" :disabled="blocked" :aria-label="`移除检查点 ${index + 1}`" @click="removeCheckpoint(point.id)">移除</NButton></div></section>
  </div>
</template>

<style scoped>
.question-form { display: grid; gap: 18px; min-width: 0; }
.question-form label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; min-width: 0; }
.question-form-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.question-markdown, .question-form-section { display: grid; gap: 11px; }
.question-form-section { border-top: 1px solid var(--line); padding-top: 20px; }
h3 { font-size: 15px; }
.question-form-section > header { display: flex; gap: 15px; justify-content: space-between; align-items: start; }
.question-form-section > header > div { min-width: 0; }
.question-form-section > header p { margin-top: 6px; max-width: 70ch; }
.question-form-section > header > :last-child { flex-shrink: 0; }
.question-link-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.question-link-row > div { min-width: 0; } .question-link-row strong { font-size: 13px; overflow-wrap: anywhere; } .question-link-row p { margin-top: 5px; color: var(--muted); font-size: 12px; }
.warning { color: var(--danger); }
.question-source-fields { padding: 16px 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.question-source-fields summary { cursor: pointer; font-size: 14px; } .question-source-fields label { margin-top: 14px; } .question-source-fields > p { margin-top: 10px; }
.question-checkpoint { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: start; gap: 10px; }
.question-checkpoint > label { padding-top: 7px; }
@media (max-width: 700px) { .question-form-pair { grid-template-columns: 1fr; } .question-form-section > header { flex-wrap: wrap; } }
</style>
