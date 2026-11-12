<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiOriginPage } from '../../api/ai'
import { aiDate, aiMaterialOriginRoute, aiOriginSourceRoute, aiOriginStateLabels, aiSourceKindLabel } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ questionId: string; versionId: string; versionNumber: number }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const opened = ref(false)
const cursor = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const origins = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'question-origins', props.questionId, props.versionId, cursor.value]),
  enabled: computed(() => opened.value && readable.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const questionId = props.questionId
    const versionId = props.versionId
    const before = cursor.value
    const parameters = new URLSearchParams({ questionId, versionId, limit: '20' })
    if (before !== undefined) parameters.set('beforeVersion', String(before))
    const value = await read<AiOriginPage>(`/ai/question-origins?${parameters}`)
    // 重新登录或切换版本后到达的资料不能进入当前来源面板。
    if (epoch !== store.sessionEpoch || !readable.value || questionId !== props.questionId
      || versionId !== props.versionId || before !== cursor.value) {
      throw new ApiError(0, 'ORIGIN_CONTEXT_CHANGED', '读取条件已变化，请重新读取当前题目版本的来源。')
    }
    return value
  },
})
watch([() => props.questionId, () => props.versionId, () => store.sessionEpoch], () => { cursor.value = undefined; cursors.value = [] }, { flush: 'sync' })
function next() {
  if (!readable.value || origins.data.value?.nextCursor == null) return
  cursors.value.push(cursor.value)
  cursor.value = origins.data.value.nextCursor
}
</script>

<template>
  <section class="question-origins" :aria-label="`题目版本 ${versionNumber} 的 AI 采用来源`">
    <header><div><h3>AI 采用来源 · 题目 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本发生的采用记录。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton></header>
    <template v-if="opened && readable">
      <p class="field-help">下列记录保留出题时外发的知识、准备版本及附件摘录，与题目中手工选择的知识关联分别记录。来源留痕不证明技术正确，也不证明后来改写的每一句文字都由这些材料支持。当前检查不证明接入前的更正历史完整，也不能据此恢复当时的外发授权。</p>
      <p v-if="origins.isPending.value" role="status">正在读取采用来源…</p><p v-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></p>
      <p v-else-if="origins.data.value && !origins.data.value.items.length" class="field-help">截至所选题目版本，尚无 AI 候选采用记录。</p>
      <article v-for="origin in origins.data.value?.items" :key="origin.taskId" class="origin-record">
        <div class="origin-heading"><strong>{{ origin.direct ? '本版本直接采用' : `历史采用 · 保存于 v${origin.targetVersionNumber}` }}</strong><time>{{ aiDate(origin.adoptedAt) }}</time></div>
        <p v-if="!origin.direct" class="field-help">这次采用发生在更早版本，所选版本可能经过后续人工编辑。</p>
        <div class="origin-links"><RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink><RouterLink class="text-link" :to="{ name: 'bank', query: { item: questionId, version: origin.targetVersionId } }">查看采用时保存的 v{{ origin.targetVersionNumber }}</RouterLink></div>
        <template v-if="origin.sources.length"><h4>当次外发的知识与准备版本</h4>
          <ul class="origin-sources"><li v-for="source in origin.sources" :key="`${source.itemId}/${source.versionId}`"><RouterLink v-if="aiOriginSourceRoute(source)" class="text-link" :to="aiOriginSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span><span class="source-state">{{ source.kind == null ? '历史知识来源' : aiSourceKindLabel(source.kind) }} · {{ aiOriginStateLabels[source.state] }}</span>
            <span v-if="source.kind === 'EXPRESSION'" class="source-state">这是用户保存的表达素材，不作为项目事实确认；其关联事实仅参与授权核对，未单独选择的事实原文不会随表达自动外发。</span>
            <span v-if="source.kind === 'EXPRESSION' && source.state === 'REVIEW_REQUIRED'" class="source-state">表达依据待复核，或表达自身、事实依据及所属项目的代次与当时不一致。请回到确切表达核对；历史正文与引用保留在原任务中，原授权不会恢复。</span>
            <span v-if="source.state !== 'UNAVAILABLE' && !aiOriginSourceRoute(source)" class="source-state">来源类型无法识别，请重新读取来源后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></span></li></ul>
        </template>
        <template v-if="origin.materialSources?.length"><h4>当次外发的附件摘录</h4><p class="field-help">这里保留采用时的来源标识；完整固定正文及引用区间见原任务。</p>
          <ul class="origin-sources"><li v-for="source in origin.materialSources ?? []" :key="source.excerptId">
            <RouterLink v-if="aiMaterialOriginRoute(source)" class="text-link" :to="aiMaterialOriginRoute(source)!">{{ source.fileName }} · 查看确切摘录</RouterLink><span v-else>{{ source.fileName }}</span>
            <span class="source-state">{{ source.origin === 'MANUAL' ? '手工摘录' : '解析摘录' }} · {{ source.locator }} · {{ source.state === 'CURRENT' ? '当前可用' : '当前不可用，保留历史来源' }}</span>
            <span class="source-state">摘录 {{ source.excerptId }} · 附件 {{ source.attachmentId }}</span><span class="source-state">原件 SHA-256 {{ source.sha256 }}</span>
            <span v-if="source.origin === 'PARSED'" class="source-state">原解析执行 {{ source.runId }} · 片段 {{ source.segmentId }} · 原片段内 UTF-16 [{{ source.startOffset }}, {{ source.endOffset }})</span><span v-else class="source-state">原文和定位由用户手工提供，不代表自动解析或事实确认。</span>
            <span v-if="source.state === 'CURRENT' && !aiMaterialOriginRoute(source)" class="source-state">来源标识不完整，请重新读取来源后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></span>
          </li></ul>
        </template>
      </article>
      <div v-if="cursors.length || origins.data.value?.nextCursor != null" class="row-actions"><NButton size="small" :disabled="!readable || !cursors.length" @click="cursor = cursors.pop()">较新记录</NButton><NButton size="small" :disabled="!readable || origins.data.value?.nextCursor == null" @click="next">更早记录</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.question-origins { display: grid; gap: 15px; margin-top: 24px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
header, .origin-heading { display: flex; justify-content: space-between; align-items: start; gap: 15px; }
header > div, .origin-record { display: grid; gap: 9px; min-width: 0; }
h3 { margin: 0; font-size: 16px; } h4 { margin: 5px 0 0; font-size: 13px; }
.origin-record { padding-top: 16px; border-top: 1px solid var(--line); } .origin-heading { flex-wrap: wrap; } .origin-heading strong { font-size: 13px; }
time, .source-state { color: var(--muted); font-size: 12px; }
.origin-links { display: flex; gap: 9px 17px; flex-wrap: wrap; font-size: 13px; }
.origin-sources { display: grid; gap: 11px; padding-left: 20px; margin: 3px 0; }
.origin-sources li { padding-left: 3px; font-size: 13px; overflow-wrap: anywhere; } .source-state { display: block; margin-top: 4px; }
@media (max-width: 650px) { header { flex-wrap: wrap; } }
</style>
