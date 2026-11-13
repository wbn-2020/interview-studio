<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { QuestionPrompt, QuestionReference as Reference, QuestionVersion, QuestionVersionPage } from '../../api/bank'
import { questionFieldLabels } from '../../api/bank'
import { useWorkspaceStore } from '../../state/workspace'
import AiQuestionOrigins from '../ai/AiQuestionOrigins.vue'
import RecollectionQuestionOrigin from '../review/RecollectionQuestionOrigin.vue'
import QuestionReadout from './QuestionReadout.vue'
import QuestionReference from './QuestionReference.vue'

const props = defineProps<{ id: string; currentVersion: QuestionVersion; reference?: Reference; locked: boolean }>()
const emit = defineEmits<{ reference: [version: QuestionVersion] }>()
const route = useRoute()
const router = useRouter()
const store = useWorkspaceStore()
const before = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const selectedId = computed(() => typeof route.query.version === 'string' ? route.query.version : props.currentVersion.id)
const comparisonId = ref<string>()
const list = useQuery({ queryKey: computed(() => ['bank', store.sessionEpoch, 'versions', props.id, before.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<QuestionVersionPage>(`/bank/questions/${props.id}/versions?limit=20${before.value ? `&before=${before.value}` : ''}`) })
const selected = useQuery({ queryKey: computed(() => ['bank', store.sessionEpoch, 'version', props.id, selectedId.value]), enabled: computed(() => store.sessionActive), queryFn: () => read<QuestionVersion>(`/bank/questions/${props.id}/versions/${selectedId.value}`) })
const comparison = useQuery({ queryKey: computed(() => ['bank', store.sessionEpoch, 'version', props.id, comparisonId.value]), enabled: computed(() => store.sessionActive && Boolean(comparisonId.value)), queryFn: () => read<QuestionVersion>(`/bank/questions/${props.id}/versions/${comparisonId.value}`) })
const changedFields = computed(() => {
  const first = selected.data.value?.content
  const second = comparison.data.value?.content
  if (!first || !second) return []
  return (Object.keys(questionFieldLabels) as (keyof QuestionPrompt)[]).filter(key => JSON.stringify(first[key]) !== JSON.stringify(second[key])).map(key => questionFieldLabels[key])
})
watch(() => store.sessionEpoch, () => { before.value = undefined; cursors.value = [] })
function choose(id: string) { void router.push({ name: 'bank', query: { item: props.id, version: id } }) }
function setComparison(id?: string) {
  const keys = [...store.changes.keys()].filter(key => key.startsWith('standard-review:'))
  if (store.canLeave(keys)) comparisonId.value = id
}
function older() { if (list.data.value?.nextCursor) { cursors.value.push(before.value); before.value = list.data.value.nextCursor } }
</script>

<template>
  <section class="question-history">
    <p class="field-help">历史显示当时保存的题干、条件和知识引用。参考内容需单独打开；切换版本不会修改当前编辑输入。</p>
    <p v-if="list.isPending.value" class="field-help" role="status">正在读取题目版本…</p><p v-if="list.error.value" class="notice error" role="alert">{{ errorText(list.error.value) }}<NButton text @click="list.refetch()">重试</NButton></p>
    <div v-for="version in list.data.value?.items" :key="version.id" class="version-row"><button class="text-link" :aria-current="selectedId === version.id ? 'true' : undefined" @click="choose(version.id)">v{{ version.number }} · {{ version.title }}<small>{{ new Date(version.createdAt).toLocaleString('zh-CN') }}</small></button><NButton size="small" text :disabled="selectedId === version.id" @click="setComparison(version.id)">设为对照</NButton></div>
    <p v-if="list.data.value && !list.data.value.items.length" class="field-help">本页没有更早的版本。</p>
    <div class="row-actions"><NButton size="small" :disabled="!cursors.length" @click="before = cursors.pop()">较新</NButton><NButton size="small" :disabled="!list.data.value?.nextCursor" @click="older">更早</NButton><NButton v-if="comparisonId" size="small" @click="setComparison()">关闭对照</NButton></div>
    <p v-if="selected.error.value" class="notice error" role="alert">所选版本读取失败：{{ errorText(selected.error.value) }}<NButton text @click="selected.refetch()">重试</NButton></p><p v-if="comparisonId && comparison.error.value" class="notice error" role="alert">对照版本读取失败：{{ errorText(comparison.error.value) }}<NButton text @click="comparison.refetch()">重试</NButton></p>
    <p v-if="selected.isFetching.value || (comparisonId && comparison.isFetching.value)" class="field-help" role="status">正在读取确切版本…</p>
    <p v-if="selected.data.value && comparisonId && comparison.data.value" class="question-differences">{{ changedFields.length ? `题干内容变化：${changedFields.join('、')}` : '两版题干及元数据相同。' }}<span>未比较隐藏参考，不据此判定技术正确性。</span></p>
    <div class="question-version-columns" :class="{ paired: comparisonId }">
      <article v-if="selected.data.value"><p class="version-label">所选版本 v{{ selected.data.value.number }}</p><QuestionReadout :content="selected.data.value.content" :question-id="id" :version-id="selected.data.value.id" /><div v-if="reference?.versionId !== selected.data.value.id" class="reference-action"><p class="field-help">{{ selected.data.value.hasReference ? '本版本保存有个人参考或检查点。' : '本版本未填写个人参考或检查点。' }}打开会记录查看事件。</p><NButton size="small" :disabled="locked || !store.sessionActive" @click="emit('reference', selected.data.value)">打开本版本参考并记录查看</NButton></div><AiQuestionOrigins :question-id="id" :version-id="selected.data.value.id" :version-number="selected.data.value.number" /><RecollectionQuestionOrigin :question-id="id" :version-id="selected.data.value.id" :disabled="locked" /></article>
      <article v-if="comparisonId && comparisonId !== selectedId && comparison.data.value"><p class="version-label">对照版本 v{{ comparison.data.value.number }}</p><QuestionReadout :content="comparison.data.value.content" :question-id="id" :version-id="comparison.data.value.id" /><div v-if="reference?.versionId !== comparison.data.value.id" class="reference-action"><p class="field-help">参考仅在显式打开后显示，并记录对应版本的查看事件。</p><NButton size="small" :disabled="locked || !store.sessionActive" @click="emit('reference', comparison.data.value)">打开对照参考并记录查看</NButton></div><AiQuestionOrigins :question-id="id" :version-id="comparison.data.value.id" :version-number="comparison.data.value.number" /><RecollectionQuestionOrigin :question-id="id" :version-id="comparison.data.value.id" :disabled="locked" /></article>
    </div>
    <!-- 参考与审阅持有独立请求；历史列表因重登录重读时，不卸载原键恢复入口。 -->
    <QuestionReference v-if="reference && (reference.versionId === selectedId || reference.versionId === comparisonId)" :reference="reference" :disabled="locked" />
  </section>
</template>

<style scoped>
.question-history { display: grid; gap: 13px; min-width: 0; }
.version-row > button:first-child { min-width: 0; text-align: left; overflow-wrap: anywhere; }
.version-row small { display: block; margin-top: 5px; font-size: 12px; font-weight: 400; }
.question-version-columns { display: grid; gap: 28px; min-width: 0; }
.question-version-columns.paired { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.question-version-columns article { min-width: 0; }
.version-label { font-size: 12px; color: var(--muted); margin-bottom: 15px; }
.question-differences { padding: 12px; font-size: 12px; border-radius: 6px; background: var(--surface-soft); }
.question-differences span { display: block; margin-top: 6px; color: var(--muted); }
.reference-action { display: grid; gap: 10px; justify-items: start; margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--line); }
@media (max-width: 1100px) { .question-version-columns.paired { grid-template-columns: 1fr; } }
</style>
