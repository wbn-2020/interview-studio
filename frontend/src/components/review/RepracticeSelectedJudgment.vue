<script setup lang="ts">
import { computed, onUnmounted, shallowRef, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { RepracticeComparison, RepracticeJudgment, RepracticeJudgmentPage } from '../../api/repractice'
import { repracticeCitationValid, repracticeJudgmentValid, repracticeOutcomeLabels } from '../../api/repractice'
import { reviewDate } from '../../api/review'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ comparison: RepracticeComparison; judgmentId: string; sourceId: string; revision: number; disabled?: boolean }>()
const store = useWorkspaceStore()
const readable = computed(() => store.sessionActive && !props.disabled)
const value = shallowRef<RepracticeJudgment>(), error = shallowRef<unknown>(), busy = shallowRef(false)
let alive = true, sequence = 0

async function load() {
  value.value = undefined; error.value = undefined
  const stamp = ++sequence
  if (!readable.value) { busy.value = false; return }
  const epoch = store.sessionEpoch, comparisonId = props.comparison.comparison.id
  const { judgmentId, sourceId, revision } = props
  busy.value = true
  try {
    const page = await read<RepracticeJudgmentPage>(`/review/sources/${sourceId}/judgments?before=${revision + 1}&limit=1`)
    if (!alive || !readable.value || stamp !== sequence || epoch !== store.sessionEpoch) return
    const saved = page?.items?.[0]
    // 以确切修订查历史，再核对四重身份；不能把最新判断当作成长目录中选中的旧判断。
    if (!page || !Array.isArray(page.items) || page.items.length !== 1 || !saved
      || !repracticeJudgmentValid(saved, sourceId) || saved.id !== judgmentId || saved.revision !== revision
      || saved.comparisonId !== comparisonId || saved.answerId !== props.comparison.current.answer.answerId
      || !props.comparison.repractice.sources.some(source => source.sourceId === sourceId)
      || !saved.citations.every(citation => repracticeCitationValid(props.comparison.current, citation))) {
      throw new ApiError(502, 'REVIEW_JUDGMENT_MISMATCH', '未能读取与所选身份一致的历史判断，请返回成长目录重新核对。')
    }
    value.value = saved
  } catch (reason) { if (alive && stamp === sequence && readable.value && epoch === store.sessionEpoch) error.value = reason }
  finally { if (alive && stamp === sequence) busy.value = false }
}

watch([readable, () => store.sessionEpoch, () => props.comparison, () => props.judgmentId, () => props.sourceId, () => props.revision], () => { void load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; sequence++ })
</script>

<template>
  <section v-show="readable" class="selected-judgment" aria-label="成长目录选中的确切历史判断" :inert="!readable || undefined">
    <header><div><p class="section-label">成长证据 / 历史判断</p><h3>选中的第 {{ revision }} 次判断</h3></div><NButton size="small" :disabled="!readable || busy" @click="load">重新核对</NButton></header>
    <p v-if="busy" role="status">正在读取确切历史判断…</p>
    <p v-if="error" class="notice error" role="alert">{{ errorText(error) }}</p>
    <template v-if="value"><p><strong>{{ repracticeOutcomeLabels[value.outcome] }}</strong> · {{ reviewDate(value.createdAt) }}</p><p class="judgment-reason">{{ value.reason }}</p><blockquote v-for="(citation, index) in value.citations" :key="index">{{ citation.quote }}</blockquote><p class="field-help">这是当时保存的判断。下方来源区域展示当前进度；历史判断不会自动变成当前仍适用的结论。</p></template>
  </section>
</template>

<style scoped>
.selected-judgment { display: grid; gap: 14px; padding: 20px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
header { display: flex; gap: 16px; justify-content: space-between; align-items: start; flex-wrap: wrap; } h3 { margin: 6px 0 0; } p { margin: 0; }
.judgment-reason, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; } blockquote { margin: 0; padding: 12px 16px; border: 1px solid var(--line); background: var(--surface); }
</style>
