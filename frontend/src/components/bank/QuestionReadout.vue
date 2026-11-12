<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQueries, useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { Schema, Version } from '../../api/client'
import type { QuestionKnowledgeReference, QuestionPrompt } from '../../api/bank'
import { knowledgeReferenceLabels, questionDimensionLabels, questionTypeLabels, safeQuestionSourceUrl } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
import MarkdownPreview from '../MarkdownPreview.vue'

const props = defineProps<{ content: QuestionPrompt; references?: QuestionKnowledgeReference[] }>()
const router = useRouter()
const store = useWorkspaceStore()
const topics = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'topics']), enabled: computed(() => store.sessionActive && Boolean(props.content.topicIds.length)), queryFn: () => read<Schema['TopicList']>('/knowledge/topics') })
const referencesByVersion = computed(() => new Map(props.references?.map(reference => [reference.versionId, reference])))
// 历史题目只引用当时发布的知识版本，不能以当前草稿标题替换其来源。
const knowledge = useQueries({ queries: computed(() => props.content.knowledgeLinks.map(link => ({
  queryKey: ['knowledge', store.sessionEpoch, 'version', link.itemId, link.versionId],
  enabled: store.sessionActive && !referencesByVersion.value.has(link.versionId),
  queryFn: () => read<Version>(`/knowledge/items/${link.itemId}/versions/${link.versionId}`),
}))) })
const sourceUrl = computed(() => safeQuestionSourceUrl(props.content.source.url))
function topicName(id: string) { return topics.data.value?.items.find(topic => topic.id === id)?.name ?? `主题 ${id.slice(0, 8)}` }
function referenceAt(index: number) { return referencesByVersion.value.get(props.content.knowledgeLinks[index]?.versionId ?? '') }
function titleAt(index: number) { return referenceAt(index)?.title ?? knowledge.value[index]?.data?.title ?? `知识 ${props.content.knowledgeLinks[index]?.itemId.slice(0, 8)}` }
function versionAt(index: number) { const number = referenceAt(index)?.number ?? knowledge.value[index]?.data?.number; return number ? `v${number}` : '确切历史版本' }
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
    <section v-if="content.knowledgeLinks.length" class="question-knowledge"><h4>关联知识</h4><div v-for="(link, index) in content.knowledgeLinks" :key="link.versionId" class="knowledge-row-detail"><button class="text-link" @click="openKnowledge(index)">{{ titleAt(index) }} · {{ versionAt(index) }}</button><span v-if="referenceAt(index)" :class="{ warning: referenceAt(index)?.state !== 'CURRENT' }">{{ knowledgeReferenceLabels[referenceAt(index)!.state] }}</span><p v-if="knowledge[index]?.isFetching" class="field-help" role="status">正在读取确切知识版本…</p><p v-if="knowledge[index]?.error" class="notice error" role="alert">{{ errorText(knowledge[index]?.error) }}<NButton text size="small" @click="knowledge[index]?.refetch()">重试</NButton></p></div><p class="field-help">知识链接固定到保存时的发布版本。打开知识将离开本页。</p></section>
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
