import { computed, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ApiError, currentTrainingAccessEpoch, errorText, read } from '../api/client'
import type { Version } from '../api/client'
import type { KnowledgeReferenceEntry, KnowledgeReferenceKind, KnowledgeReferencePage } from '../api/knowledgeReferences'
import { knowledgeReferencePageValid, knowledgeReferenceStatusLabel } from '../api/knowledgeReferences'
import { memoryAttemptDetailValid, memoryAttemptStateLabels } from '../api/memoryAttempt'
import { memoryUtcMicros } from '../api/memoryReview'
import { kindLabels, responsibilityLabels } from '../api/preparation'
import type { PreparationItem, PreparationKind, PreparationVersion } from '../api/preparation'
import type { TrainingAnswer, TrainingQuestion } from '../api/training'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

type Category = 'ANSWER' | 'FACT' | 'MEMORY' | 'PREPARATION'
export type DetailEvidence = {
  entry: KnowledgeReferenceEntry; category: Category; label: string; summary: string
  occurredAt?: string; tags: string[]; problem?: string; preparationKind?: PreparationKind
}
const directories = [
  { kind: 'TRAINING_ANSWER_DIAGRAMS', category: 'ANSWER', label: '回答图解' },
  { kind: 'PREPARATION_VERSIONS', category: 'PREPARATION', label: '准备资料' },
  { kind: 'MEMORY_ATTEMPT_SOURCES', category: 'MEMORY', label: '主动回忆' },
] satisfies { kind: KnowledgeReferenceKind; category: Category; label: string }[]
const invalid = () => new ApiError(502, 'KNOWLEDGE_EVIDENCE_SOURCE_INVALID', '出处的确切身份、版本或时间无法核对，请重新读取。')
const timestamp = (value: string) => {
  if (memoryUtcMicros(value) === undefined) throw invalid()
  return value
}
function newestFirst(left: DetailEvidence, right: DetailEvidence): number {
  const a = left.occurredAt ? memoryUtcMicros(left.occurredAt) : undefined, b = right.occurredAt ? memoryUtcMicros(right.occurredAt) : undefined
  if (a !== undefined && b !== undefined) return a === b
    ? left.entry.referenceId.localeCompare(right.entry.referenceId) : a > b ? -1 : 1
  return a !== undefined ? -1 : b !== undefined ? 1
    : left.entry.referenceId.localeCompare(right.entry.referenceId)
}

export function useKnowledgeDetailEvidence(itemId: () => string, version: ComputedRef<Version | undefined>, readable: ComputedRef<boolean>) {
  const store = useWorkspaceStore(), access = useTrainingAccessStore(), generation = ref(0)
  watch([readable, itemId, () => version.value?.id, () => store.sessionEpoch, () => access.active?.id,
    () => access.active?.mode, () => access.active?.materialAccess], () => generation.value++, { flush: 'sync' })
  const query = useQuery({
    queryKey: computed(() => ['knowledge', store.sessionEpoch, 'detail-evidence', itemId(), version.value?.id, generation.value]),
    enabled: computed(() => readable.value && Boolean(version.value)), retry: false,
    queryFn: async () => {
      const stamp = generation.value, epoch = currentTrainingAccessEpoch(), id = itemId(), selected = version.value!
      const live = () => readable.value && stamp === generation.value && epoch === currentTrainingAccessEpoch()
      const ensureLive = () => { if (!live()) throw new ApiError(0, 'KNOWLEDGE_EVIDENCE_CHANGED', '知识版本或资料访问已变化。') }
      const pages = await Promise.all(directories.map(async directory => {
        try {
          const value = await read<unknown>(`/knowledge/items/${id}/references?${new URLSearchParams({ versionId: selected.id, kind: directory.kind, limit: '20' })}`)
          ensureLive()
          if (!knowledgeReferencePageValid(value, id, selected.id, selected.number, directory.kind)) throw invalid()
          return { directory, page: value as KnowledgeReferencePage, problem: '' }
        } catch (error) { ensureLive(); return { directory, page: undefined, problem: errorText(error) } }
      }))
      const sources = pages.flatMap(({ directory, page }) => (page?.items ?? []).map(entry => ({ directory, entry })))
      const rows: DetailEvidence[] = []
      // 每类只读已登记的20项；逐批核对原始身份，不用当前版本、相邻节点或标题匹配补造出处。
      for (let offset = 0; offset < sources.length; offset += 4) {
        ensureLive()
        const batch = await Promise.all(sources.slice(offset, offset + 4).map(async ({ directory, entry }): Promise<DetailEvidence> => {
          const base: DetailEvidence = { entry, category: directory.category, label: directory.label,
            summary: knowledgeReferenceStatusLabel(entry, directory.kind),
            tags: [...(entry.targetVersionNumber ? [`固定 v${entry.targetVersionNumber}`] : []), ...(!entry.targetCurrent ? ['历史版本'] : [])] }
          try {
            if (entry.targetKind === 'TRAINING_ANSWER') {
              const path = `/training/sessions/${entry.anchorItemId}/questions/${entry.otherItemId}`
              const answer = await read<TrainingAnswer>(`${path}/answers/${entry.targetId}`)
              ensureLive()
              if (answer.id !== entry.targetId || answer.questionId !== entry.otherItemId || typeof answer.content.text !== 'string'
                || !['LEARNING', 'BEFORE_ASSISTANCE', 'ASSISTED'].includes(answer.condition)
                || !answer.content.diagram.nodes.some(node => node.reference?.kind === 'KNOWLEDGE'
                  && node.reference.itemId === id && node.reference.versionId === selected.id)) throw invalid()
              const question = await read<TrainingQuestion>(path)
              ensureLive()
              if (question.id !== entry.otherItemId || question.sessionId !== entry.anchorItemId) throw invalid()
              return { ...base, label: question.kind === 'REPLAY' ? '独立复练' : '正式回答',
                summary: answer.content.text, occurredAt: timestamp(answer.createdAt), tags: ['固定版本',
                  ...(answer.condition === 'LEARNING' ? ['学习条件'] : answer.condition === 'ASSISTED' ? ['已确认辅助'] : ['辅助确认前'])] }
            }
            if (entry.targetKind === 'PREPARATION') {
              const item = await read<PreparationItem>(`/prepare/items/${entry.targetId}`)
              ensureLive()
              if (item.id !== entry.targetId || !Object.hasOwn(kindLabels, item.kind)) throw invalid()
              const saved = await read<PreparationVersion>(`/prepare/items/${entry.targetId}/versions/${entry.targetVersionId}`)
              ensureLive()
              if (saved.id !== entry.targetVersionId || saved.number !== entry.targetVersionNumber || saved.content.title !== entry.title) throw invalid()
              const content = saved.content
              if (item.kind === 'FACT' && !('responsibility' in content && Object.hasOwn(responsibilityLabels, content.responsibility))) throw invalid()
              const summary = 'action' in content ? content.action || content.scenario : 'body' in content ? content.body
                : 'overview' in content ? content.overview : content.jd
              if (typeof summary !== 'string') throw invalid()
              return { ...base, category: item.kind === 'FACT' ? 'FACT' : 'PREPARATION', label: item.kind === 'FACT' ? '项目事实' : kindLabels[item.kind],
                summary, occurredAt: timestamp(saved.createdAt), preparationKind: item.kind,
                tags: [`固定 v${saved.number}`, ...(item.currentVersion.id !== saved.id ? ['历史版本'] : []),
                  ...('responsibility' in content ? [responsibilityLabels[content.responsibility]] : []),
                  ...(item.status === 'ARCHIVED' ? ['当前已归档'] : []),
                  ...(item.kind === 'FACT' && item.verification !== 'CONFIRMED' ? [item.verification === 'WITHDRAWN' ? '当前已撤回' : '当前待核实'] : [])] }
            }
            const value = await read<unknown>(`/memory/attempts/${entry.targetId}`)
            ensureLive()
            if (!memoryAttemptDetailValid(value, entry.targetId, entry.anchorItemId ?? undefined)
              || value.version.id !== entry.anchorVersionId || !value.attempt.start.sources.some(source => source.itemId === id && source.versionId === selected.id)) throw invalid()
            return { ...base, summary: value.attempt.response || memoryAttemptStateLabels[value.attempt.state],
              occurredAt: timestamp(value.attempt.startedAt), tags: ['回忆记录', memoryAttemptStateLabels[value.attempt.state]] }
          } catch (error) { ensureLive(); return { ...base, problem: errorText(error) } }
        }))
        rows.push(...batch)
      }
      ensureLive()
      const priority: Record<Category, number> = { ANSWER: 0, FACT: 1, PREPARATION: 2, MEMORY: 3 }
      rows.sort((a, b) => priority[a.category] - priority[b.category] || newestFirst(a, b))
      return { stamp, pages, rows }
    },
  })
  const data = computed(() => readable.value && query.data.value?.stamp === generation.value ? query.data.value : undefined)
  const rows = computed(() => data.value?.rows ?? [])
  const incomplete = computed(() => !data.value || data.value.pages.some(value => value.problem || value.page?.nextCursor) || rows.value.some(row => row.problem))
  const recent = computed(() => [...rows.value].filter(row => row.occurredAt).sort(newestFirst)[0])
  const counts = computed(() => {
    const pages = data.value?.pages
    const count = (kind: KnowledgeReferenceKind) => pages?.find(value => value.directory.kind === kind)?.page
    const answers = count('TRAINING_ANSWER_DIAGRAMS'), preparations = count('PREPARATION_VERSIONS'), memories = count('MEMORY_ATTEMPT_SOURCES')
    return {
      ALL: { value: pages?.every(value => value.page) ? rows.value.length : undefined, more: pages?.some(value => value.page?.nextCursor) ?? false },
      ANSWER: { value: answers?.items.length, more: Boolean(answers?.nextCursor) },
      FACT: { value: preparations ? rows.value.filter(row => row.category === 'FACT').length : undefined,
        more: Boolean(preparations?.nextCursor || rows.value.some(row => row.category === 'PREPARATION' && row.problem)) },
      MEMORY: { value: memories?.items.length, more: Boolean(memories?.nextCursor) },
    }
  })
  const problems = computed(() => data.value?.pages.filter(value => value.problem).map(value => `${value.directory.label}：${value.problem}`) ?? [])
  return { query, data, rows, recent, counts, incomplete, problems }
}
