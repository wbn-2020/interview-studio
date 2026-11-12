<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import type { AiOriginPage } from '../../api/ai'
import { aiDate, aiOriginSourceRoute, aiOriginStateLabels } from '../../api/ai'
import { aiCardDraftOriginPageValid, aiCardDraftPaths } from '../../api/aiCardDraft'
import { memoryIdValid } from '../../api/memory'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ cardId: string; versionId: string; versionNumber: number; disabled?: boolean; editing?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const viewId = useId()
const active = ref(true), opened = ref(false), before = ref<number>()
const cursors = ref<(number | undefined)[]>([]), currentPage = shallowRef<AiOriginPage>()
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked
  && memoryIdValid(props.cardId) && memoryIdValid(props.versionId)
  && Number.isSafeInteger(props.versionNumber) && props.versionNumber >= 1 && props.versionNumber <= 1000)
let alive = true, generation = 0

watch([() => props.cardId, () => props.versionId, () => props.versionNumber, () => store.sessionEpoch], () => {
  before.value = undefined; cursors.value = []; currentPage.value = undefined
}, { flush: 'sync' })
watch([readable, opened, before, () => props.cardId, () => props.versionId, () => props.versionNumber, () => store.sessionEpoch], () => {
  generation++; currentPage.value = undefined
}, { flush: 'sync' })

const origins = useQuery({
  // 当前题面与历史页可能同时挂载同一版本，各自接纳响应，避免隐藏面板的闭包影响可见页。
  queryKey: computed(() => ['ai', store.sessionEpoch, 'card-draft-origins', props.cardId, props.versionId, props.versionNumber, before.value, viewId]),
  enabled: computed(() => readable.value && opened.value),
  staleTime: 0,
  queryFn: async () => {
    const stamp = generation, epoch = store.sessionEpoch, cardId = props.cardId
    const versionId = props.versionId, versionNumber = props.versionNumber, cursor = before.value
    const value = await read<AiOriginPage>(aiCardDraftPaths.origins(cardId, versionId, cursor))
    // 只接纳本次可见上下文的新响应；恢复访问或切换版本时不回填旧页，也不读取卡片参考。
    if (!alive || !readable.value || !opened.value || stamp !== generation || epoch !== store.sessionEpoch
      || cardId !== props.cardId || versionId !== props.versionId || versionNumber !== props.versionNumber || cursor !== before.value) {
      throw new ApiError(0, 'AI_CARD_ORIGIN_CONTEXT_CHANGED', '卡片版本或资料访问条件已变化，请重新读取采用来源。')
    }
    if (!aiCardDraftOriginPageValid(value, versionId, versionNumber, cursor)) {
      throw new ApiError(502, 'AI_CARD_ORIGIN_MISMATCH', '采用来源与所选卡片版本不一致，请重新读取。')
    }
    currentPage.value = value
    return value
  },
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; generation++; currentPage.value = undefined })

function older() {
  if (!readable.value || !opened.value || origins.isFetching.value || origins.error.value || currentPage.value?.nextCursor == null) return
  cursors.value.push(before.value); before.value = currentPage.value.nextCursor
}
function newer() {
  if (readable.value && opened.value && !origins.isFetching.value && cursors.value.length) before.value = cursors.value.pop()
}
function refresh() {
  if (!readable.value || !opened.value || origins.isFetching.value) return
  currentPage.value = undefined; void origins.refetch()
}
</script>

<template>
  <section class="card-draft-origins" :aria-label="`卡片 v${versionNumber} 的 AI 采用来源`">
    <header>
      <div><h3>AI 采用来源 · 卡片 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本的候选采用记录。</p></div>
      <NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton>
    </header>
    <p v-if="opened && !readable" class="notice" role="status">资料访问恢复后可继续查看采用来源。</p>
    <template v-else-if="opened && readable">
      <p v-if="editing" class="notice">卡片还有编辑草稿。这里保留已保存版本的采用记录，不能据此认定草稿中的每句改写均有原引用支持。</p>
      <p class="field-help">采用记录保留当次外发的知识版本。来源状态只表示当前可用性，不证明技术结论正确；后续人工编辑仍需逐项核对依据。</p>
      <p v-if="origins.isFetching.value && !currentPage" role="status">正在读取采用来源…</p>
      <p v-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text :disabled="origins.isFetching.value" @click="refresh">重新读取来源</NButton></p>
      <template v-else-if="currentPage">
        <p v-if="!currentPage.items.length" class="field-help">{{ before === undefined ? '截至所选卡片版本，尚无 AI 候选采用记录。手工选择的来源仍显示在「来源依据」中。' : '本页没有更早的 AI 采用记录。' }}</p>
        <article v-for="origin in currentPage.items" :key="origin.taskId" class="origin-record">
          <div class="origin-heading"><strong>{{ origin.direct ? '本版本直接采用' : `更早采用 · 卡片 v${origin.targetVersionNumber}` }}</strong><time :datetime="origin.adoptedAt">{{ aiDate(origin.adoptedAt) }}</time></div>
          <p v-if="!origin.direct" class="field-help">这次采用发生在更早的卡片版本，所选版本可能已经人工改写，不能视为当前每句话的原文依据。</p>
          <div class="origin-links">
            <RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink>
            <RouterLink v-if="!origin.direct" class="text-link" :to="{ name: 'memory-cards', query: { item: cardId, versionId: origin.targetVersionId } }">查看采用时的卡片 v{{ origin.targetVersionNumber }}</RouterLink>
          </div>
          <h4>当次外发的知识版本</h4>
          <ul class="origin-sources">
            <li v-for="source in origin.sources" :key="`${source.itemId}/${source.versionId}`">
              <RouterLink v-if="aiOriginSourceRoute(source)" class="text-link" :to="aiOriginSourceRoute(source)!">{{ source.title }} · v{{ source.number }}</RouterLink>
              <span v-else>{{ source.title }} · v{{ source.number }}</span>
              <span class="source-state">{{ source.kind === 'CONCEPT' ? '概念' : '笔记' }} · {{ aiOriginStateLabels[source.state] }}</span>
            </li>
          </ul>
        </article>
        <p class="field-help">需要核对卡片参考时，请使用所选版本的「查看参考」入口。</p>
      </template>
      <div class="row-actions">
        <NButton v-if="cursors.length" size="small" :disabled="origins.isFetching.value" @click="newer">较新记录</NButton>
        <NButton v-if="currentPage?.nextCursor != null" size="small" :disabled="origins.isFetching.value || !!origins.error.value" @click="older">更早记录</NButton>
        <NButton v-if="currentPage && !origins.error.value" text size="small" :disabled="origins.isFetching.value" @click="refresh">刷新来源状态</NButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
.card-draft-origins { display: grid; gap: 15px; min-width: 0; padding-top: 20px; border-top: 1px solid var(--line); }
header, .origin-heading { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 12px; }
header > div, .origin-record { display: grid; gap: 9px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 14px; } h4 { margin-top: 5px; font-size: 13px; }
p { max-width: 75ch; overflow-wrap: anywhere; }
.origin-record { padding-top: 16px; border-top: 1px solid var(--line); }
.origin-heading strong { font-size: 13px; } time, .source-state { color: var(--muted); font-size: 12px; }
.origin-links, .row-actions { display: flex; flex-wrap: wrap; gap: 9px 17px; font-size: 13px; }
.origin-sources { display: grid; gap: 11px; margin: 3px 0 0; padding-left: 20px; }
.origin-sources li { min-width: 0; padding-left: 3px; font-size: 13px; overflow-wrap: anywhere; }
.source-state { display: block; margin-top: 4px; } .text-link { overflow-wrap: anywhere; }
</style>
