<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { Schema } from '../../api/client'
import type { QuestionKnowledgeReference, QuestionKnowledgeReferencePage, QuestionPrompt } from '../../api/bank'
import { knowledgeReferenceLabels, questionDimensionLabels, questionTypeLabels, safeQuestionSourceUrl } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps<{ content: QuestionPrompt; references?: QuestionKnowledgeReference[]; questionId?: string; versionId?: string }>()
const router = useRouter()
const store = useWorkspaceStore()
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive && Boolean(props.content.topicIds.length)), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
// 两侧历史题目各用自己的冻结版核对，只取来源元数据，不顺带读取知识全文或个人参考。
const knowledge = useQuery({
  queryKey: computed(() => ['bank', store.sessionEpoch, 'knowledge-references', props.questionId, props.versionId]),
  staleTime: 0,
  enabled: computed(() => store.sessionActive && props.references === undefined && Boolean(props.questionId && props.versionId && props.content.knowledgeLinks.length)),
  queryFn: async () => {
    const questionId = props.questionId
    const versionId = props.versionId
    const links = props.content.knowledgeLinks.map(link => ({ ...link }))
    const value = await read<QuestionKnowledgeReferencePage>(`/bank/questions/${questionId}/versions/${versionId}/knowledge-references`)
    if (value.questionId !== questionId || value.versionId !== versionId || !Array.isArray(value.items)
      || value.items.length !== links.length || value.items.some((item, index) =>
        item.itemId !== links[index]?.itemId || item.versionId !== links[index]?.versionId
        || !Object.hasOwn(knowledgeReferenceLabels, item.state))) {
      throw new ApiError(502, 'QUESTION_KNOWLEDGE_MISMATCH', '知识来源核对与所选题目版本不一致，请重试。')
    }
    return value
  },
})
const referencesByVersion = computed(() => {
  const checked = !knowledge.error.value && !knowledge.isFetching.value
    && knowledge.data.value?.questionId === props.questionId && knowledge.data.value?.versionId === props.versionId
    ? knowledge.data.value?.items : undefined
  return new Map((props.references ?? checked)?.map(reference => [`${reference.itemId}/${reference.versionId}`, reference]))
})
const sourceUrl = computed(() => safeQuestionSourceUrl(props.content.source.url))
function topicName(id: string) { return topics.data.value?.items.find(topic => topic.id === id)?.name ?? `主题 ${id.slice(0, 8)}` }
function referenceAt(index: number) { const link = props.content.knowledgeLinks[index]; return link ? referencesByVersion.value.get(`${link.itemId}/${link.versionId}`) : undefined }
function titleAt(index: number) { return referenceAt(index)?.title ?? `知识 ${props.content.knowledgeLinks[index]?.itemId.slice(0, 8)}` }
function versionAt(index: number) { const number = referenceAt(index)?.number; return number ? `v${number}` : '确切历史版本' }
function openKnowledge(index: number) { const link = props.content.knowledgeLinks[index]; if (link) void router.push({ name: 'knowledge', query: { item: link.itemId, version: link.versionId } }) }
</script>

<template>
  <article class="question-readout">
    <header><h3>{{ content.title }}</h3><div class="question-metadata"><span>{{ questionTypeLabels[content.type] }}</span><span>{{ content.difficulty === null ? '未标个人难度' : `个人难度 ${content.difficulty}` }}</span><span>个人参考／未审</span></div></header>
    <section aria-label="题干"><MarkdownPreview :value="content.prompt" /></section>
    <dl class="question-context">
      <dt>考察维度</dt><dd>{{ content.dimensions.map(value => questionDimensionLabels[value]).join('、') }}</dd>
      <template v-if="content.conditions"><dt>适用条件</dt><dd>{{ content.conditions }}</dd></template>
      <template v-if="content.technology"><dt>技术与版本</dt><dd>{{ content.technology }}</dd></template>
      <template v-if="content.scenario"><dt>场景</dt><dd>{{ content.scenario }}</dd></template>
      <template v-if="content.topicIds.length"><dt>知识主题</dt><dd>{{ content.topicIds.map(topicName).join('、') }}<span v-if="topics.error.value" class="field-help">（主题名称读取失败，暂用标识）</span></dd></template>
      <template v-if="content.tags.length"><dt>标签</dt><dd>{{ content.tags.join(' · ') }}</dd></template>
    </dl>
    <section v-if="content.knowledgeLinks.length" class="question-knowledge">
      <h4>关联知识</h4>
      <div v-if="references === undefined" class="row-actions"><NButton size="small" :disabled="!store.sessionActive || knowledge.isFetching.value || !questionId || !versionId" @click="knowledge.refetch()">重新核对来源</NButton><span class="field-help">状态为上次核对结果，可主动重新核对。</span></div>
      <p v-if="references === undefined && knowledge.isFetching.value" class="field-help" role="status">正在核对本题目版本的知识来源…</p>
      <p v-if="references === undefined && knowledge.error.value" class="notice error" role="alert">来源状态核对失败：{{ errorText(knowledge.error.value) }}<NButton text size="small" :disabled="!store.sessionActive" @click="knowledge.refetch()">重试核对</NButton></p>
      <div v-for="(link, index) in content.knowledgeLinks" :key="link.versionId" class="knowledge-row-detail">
        <button class="text-link" @click="openKnowledge(index)">{{ titleAt(index) }} · {{ versionAt(index) }}</button>
        <span v-if="referenceAt(index)" :class="{ warning: referenceAt(index)?.state !== 'CURRENT' }">{{ knowledgeReferenceLabels[referenceAt(index)!.state] }}</span>
        <span v-else>来源状态待核对</span>
      </div>
      <p class="field-help">知识链接固定到保存时的发布版本；来源状态按本题目版本的保存时间核对。待复核不会改写原题，也不代表已完成技术审阅。打开知识将离开本页。</p>
    </section>
    <section v-if="content.source.title || content.source.url || content.source.note" class="question-source"><h4>来源记录</h4><p v-if="content.source.title">{{ content.source.title }}</p><a v-if="sourceUrl" :href="sourceUrl" target="_blank" rel="noopener noreferrer">{{ content.source.url }}</a><p v-else-if="content.source.url">{{ content.source.url }}</p><p v-if="content.source.note" class="source-note">{{ content.source.note }}</p><p class="field-help">来源由用户记录，系统不抓取网址或替其背书。</p></section>
  </article>
</template>

<style scoped>
.question-readout { display: grid; gap: 22px; min-width: 0; }
h3 { font-size: 20px; overflow-wrap: anywhere; } h4 { font-size: 14px; }
.question-metadata { display: flex; flex-wrap: wrap; gap: 6px 15px; color: var(--muted); font-size: 12px; margin-top: 10px; }
.question-context { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 10px 14px; margin: 0; font-size: 13px; }
dt { color: var(--muted); } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-width: 75ch; }
.question-knowledge, .question-source { display: grid; gap: 10px; border-top: 1px solid var(--line); padding-top: 18px; min-width: 0; }
.knowledge-row-detail { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: baseline; }
.knowledge-row-detail button { text-align: left; overflow-wrap: anywhere; }
.knowledge-row-detail > span { color: var(--muted); font-size: 12px; }
.knowledge-row-detail > .warning { color: var(--danger); }
.question-source a { overflow-wrap: anywhere; color: var(--brand-strong); text-decoration: underline; }
.source-note { white-space: pre-wrap; overflow-wrap: anywhere; max-width: 75ch; }
@media (max-width: 700px) { .question-context { grid-template-columns: 1fr; gap: 5px; } dd { margin-bottom: 8px; } }
</style>
