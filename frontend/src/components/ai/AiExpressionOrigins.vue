<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiOriginPage } from '../../api/ai'
import { aiDate, aiOriginStateLabels } from '../../api/ai'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ expressionId: string; versionId: string; versionNumber: number; editing?: boolean }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const opened = ref(false)
const cursor = ref<number>()
const cursors = ref<(number | undefined)[]>([])
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const origins = useQuery({
  queryKey: computed(() => ['ai', store.sessionEpoch, 'expression-origins', props.expressionId, props.versionId, cursor.value]),
  enabled: computed(() => opened.value && readable.value),
  queryFn: async () => {
    const epoch = store.sessionEpoch
    const expressionId = props.expressionId
    const versionId = props.versionId
    const before = cursor.value
    const parameters = new URLSearchParams({ expressionId, versionId, limit: '20' })
    if (before !== undefined) parameters.set('beforeVersion', String(before))
    const value = await read<AiOriginPage>(`/ai/expression-origins?${parameters}`)
    // 来源只属于所查表达版本；切换会话、版本或资料权限后不能接纳迟到响应。
    if (epoch !== store.sessionEpoch || !readable.value || expressionId !== props.expressionId
      || versionId !== props.versionId || before !== cursor.value) {
      throw new ApiError(0, 'ORIGIN_CONTEXT_CHANGED', '读取条件已变化，请重新读取当前表达版本的来源。')
    }
    return value
  },
})
watch([() => props.expressionId, () => props.versionId, () => store.sessionEpoch], () => { cursor.value = undefined; cursors.value = [] }, { flush: 'sync' })
function next() {
  if (!readable.value || origins.data.value?.nextCursor == null) return
  cursors.value.push(cursor.value)
  cursor.value = origins.data.value.nextCursor
}
function factSourceValid(source: AiOriginPage['items'][number]['sources'][number]): boolean {
  // 表达生成历史只来自当次显式选择的事实；通用来源枚举扩展不能扩大这里的范围。
  return source.kind === 'FACT' && ['CURRENT', 'SUPERSEDED', 'ARCHIVED', 'UNAVAILABLE', 'WITHDRAWN', 'PROJECT_ARCHIVED', 'UNVERIFIED', 'REVIEW_REQUIRED'].includes(source.state)
}
</script>

<template>
  <section class="expression-origins" :aria-label="`表达版本 ${versionNumber} 的 AI 采用来源`">
    <header><div><h3>AI 采用来源 · 表达 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本发生的采用记录。</p></div><NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton></header>
    <template v-if="opened && readable">
      <p v-if="editing" class="notice">上方包含本地改写。下列记录属于已保存的表达 v{{ versionNumber }}，不作为当前改写的逐句依据。</p>
      <p class="field-help">下列事实是生成候选时实际外发的版本；表达最终采用的事实依据见正文，二者分别记录。来源留痕不证明经历真实，也不证明人工改写的每句话都由这些事实支持。当前可用仅表示本次检查结果，不证明接入前的更正历史完整，也不能据此恢复当时的外发授权。</p>
      <p v-if="origins.isPending.value" role="status">正在读取采用来源…</p>
      <p v-else-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></p>
      <template v-else-if="origins.data.value">
        <p v-if="origins.isFetching.value" class="field-help" role="status">正在更新来源状态…</p>
        <p v-if="!origins.data.value.items.length" class="field-help">截至所选表达版本，尚无 AI 候选采用记录。</p>
        <article v-for="origin in origins.data.value.items" :key="origin.taskId" class="origin-record">
          <div class="origin-heading"><strong>{{ origin.direct ? '本版本直接采用' : `历史采用 · 保存于 v${origin.targetVersionNumber}` }}</strong><time :datetime="origin.adoptedAt">{{ aiDate(origin.adoptedAt) }}</time></div>
          <p v-if="!origin.direct" class="field-help">这次采用发生在更早版本，所选版本可能经过后续人工编辑。</p>
          <div class="origin-links"><RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink><RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'EXPRESSION', item: expressionId, version: origin.targetVersionId } }">查看采用时保存的 v{{ origin.targetVersionNumber }}</RouterLink></div>
          <h4>当次外发的事实版本</h4>
          <ul class="origin-sources"><li v-for="source in origin.sources" :key="`${source.itemId}/${source.versionId}`">
            <RouterLink v-if="factSourceValid(source) && source.state !== 'UNAVAILABLE'" class="text-link" :to="{ name: 'prepare', query: { kind: 'FACT', item: source.itemId, version: source.versionId } }">{{ source.title }} · v{{ source.number }}</RouterLink><span v-else>{{ source.title }} · v{{ source.number }}</span>
            <span v-if="factSourceValid(source)" class="source-state">事实版本 · {{ aiOriginStateLabels[source.state] }}</span>
            <span v-else class="source-state">来源类型或状态与表达生成范围不符，请重新读取后核对。<NButton text :disabled="!readable" @click="origins.refetch()">重新读取来源</NButton></span>
          </li></ul>
        </article>
      </template>
      <div v-if="cursors.length || origins.data.value?.nextCursor != null" class="row-actions"><NButton size="small" :disabled="!readable || !cursors.length" @click="cursor = cursors.pop()">较新记录</NButton><NButton size="small" :disabled="!readable || origins.data.value?.nextCursor == null" @click="next">更早记录</NButton></div>
    </template>
  </section>
</template>

<style scoped>
.expression-origins { display: grid; gap: 15px; margin-top: 24px; padding-top: 21px; border-top: 1px solid var(--line); min-width: 0; }
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
