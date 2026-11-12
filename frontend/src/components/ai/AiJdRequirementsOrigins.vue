<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { aiDate, aiOriginStateLabels } from '../../api/ai'
import type { AiJdRequirementsOriginPage } from '../../api/aiJdRequirements'
import { aiJdRequirementsOriginPageValid, aiJdRequirementsPaths } from '../../api/aiJdRequirements'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ targetId: string; versionId: string; versionNumber: number; disabled?: boolean; editing?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const active = ref(true), opened = ref(false), before = ref<number>()
const cursors = ref<(number | undefined)[]>([]), currentPage = shallowRef<AiJdRequirementsOriginPage>()
const copyMessage = ref('')
const validId = (value: string) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked
  && validId(props.targetId) && validId(props.versionId)
  && Number.isSafeInteger(props.versionNumber) && props.versionNumber >= 1 && props.versionNumber <= 2147483647)
let alive = true, generation = 0

watch([() => props.targetId, () => props.versionId, () => props.versionNumber, () => store.sessionEpoch], () => {
  before.value = undefined; cursors.value = []; currentPage.value = undefined
}, { flush: 'sync' })
watch([readable, opened, before, () => props.targetId, () => props.versionId, () => props.versionNumber, () => store.sessionEpoch], () => {
  generation++; currentPage.value = undefined; copyMessage.value = ''
}, { flush: 'sync' })

const origins = useQuery({
  // 当前版、历史版和对照版可同时挂载同一目标版本，每个面板独立接纳本次读取结果。
  queryKey: computed(() => ['ai', store.sessionEpoch, 'jd-requirement-origins', props.targetId, props.versionId, props.versionNumber, before.value, viewId]),
  enabled: computed(() => readable.value && opened.value),
  staleTime: 0,
  queryFn: async () => {
    const stamp = generation, epoch = store.sessionEpoch, targetId = props.targetId
    const versionId = props.versionId, versionNumber = props.versionNumber, cursor = before.value
    const value = await read<AiJdRequirementsOriginPage>(aiJdRequirementsPaths.origins(targetId, versionId, cursor))
    // 身份、版本或访问改变后丢弃迟到元数据；来源面板始终不读取 JD 或完整目标正文。
    if (!alive || !readable.value || !opened.value || stamp !== generation || epoch !== store.sessionEpoch
      || targetId !== props.targetId || versionId !== props.versionId || versionNumber !== props.versionNumber || cursor !== before.value) {
      throw new ApiError(0, 'AI_JD_ORIGIN_CONTEXT_CHANGED', '目标版本或资料访问条件已变化，请重新读取要求采用来源。')
    }
    if (!aiJdRequirementsOriginPageValid(value, versionId, versionNumber, cursor, targetId)) {
      throw new ApiError(502, 'AI_JD_ORIGIN_MISMATCH', '要求采用来源与所选目标版本不一致，请重新读取。')
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
  currentPage.value = undefined; copyMessage.value = ''; void origins.refetch()
}
async function copyRequirement(requirementId: string) {
  if (!readable.value || !opened.value || origins.error.value
    || !currentPage.value?.items.some(origin => origin.requirements.some(link => link.requirementId === requirementId))) return
  const stamp = generation, epoch = store.sessionEpoch
  try {
    await navigator.clipboard.writeText(requirementId)
    if (alive && readable.value && opened.value && stamp === generation && epoch === store.sessionEpoch) copyMessage.value = '已复制正式要求标识。'
  } catch {
    if (alive && readable.value && opened.value && stamp === generation && epoch === store.sessionEpoch) copyMessage.value = '浏览器未允许复制，请选中下方正式要求标识手动复制。'
  }
}
</script>

<template>
  <section class="jd-requirements-origins" :aria-label="`目标 v${versionNumber} 的 JD 要求采用来源`">
    <header>
      <div><h3>JD 要求采用来源 · 目标 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本追加要求时的候选与正式要求映射。</p></div>
      <NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton>
    </header>
    <p v-if="opened && !readable" class="notice" role="status">资料访问恢复后可继续查看要求采用来源。</p>
    <template v-else-if="opened && readable">
      <p v-if="editing" class="notice">上方包含未保存的目标改写。这里展示已保存版本的采用记录，草稿中的要求尚未保存。</p>
      <p class="field-help">这里记录当时追加了哪些要求。后来沿用同一要求标识修改文字，也需要重新核对依据；采用映射不证明修改后的每句话均由原 JD 支持。</p>
      <p v-if="origins.isFetching.value && !currentPage" role="status">正在读取要求采用来源…</p>
      <p v-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text :disabled="origins.isFetching.value" @click="refresh">重新读取来源</NButton></p>
      <template v-else-if="currentPage">
        <p v-if="!currentPage.items.length" class="field-help">{{ before === undefined ? '截至所选目标版本，尚无 JD 要求候选采用记录。手工填写的要求仍保留在目标内容中。' : '本页没有更早的要求采用记录。' }}</p>
        <article v-for="origin in currentPage.items" :key="origin.taskId" class="origin-record">
          <div class="origin-heading"><strong>{{ origin.direct ? '本版本追加的要求' : `更早采用 · 目标 v${origin.targetVersionNumber}` }}</strong><time :datetime="origin.adoptedAt">{{ aiDate(origin.adoptedAt) }}</time></div>
          <p v-if="!origin.direct" class="field-help">这次采用发生在更早版本，所选版本中的要求可能已经人工修改。</p>
          <div class="origin-links">
            <RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink>
            <RouterLink v-if="!origin.direct" class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: targetId, version: origin.targetVersionId } }">查看采用时的目标 v{{ origin.targetVersionNumber }}</RouterLink>
          </div>
          <section class="jd-source" aria-label="当次 JD 来源版本">
            <h4>当次 JD 来源</h4>
            <RouterLink v-if="origin.source.state !== 'UNAVAILABLE'" class="text-link" :to="{ name: 'prepare', query: { kind: 'TARGET', item: origin.source.itemId, version: origin.source.versionId } }">{{ origin.source.title }} · v{{ origin.source.number }}</RouterLink>
            <span v-else>{{ origin.source.title }} · v{{ origin.source.number }}</span>
            <p class="source-state">{{ aiOriginStateLabels[origin.source.state] }}。状态只表示当前可用性，不代表要求或引用已获技术认证。</p>
            <p v-if="origin.source.state === 'SUPERSEDED'" class="field-help">采用要求会追加目标新版本，因此原 JD 来源显示“已有新版本”是正常的历史状态。</p>
          </section>
          <h4>当时追加的 {{ origin.requirements.length }} 项要求</h4>
          <ul class="requirement-links">
            <li v-for="link in origin.requirements" :key="link.requirementId">
              <span class="candidate-label">候选 {{ link.candidateNumber }} → 正式要求</span>
              <code>{{ link.requirementId }}</code>
              <NButton text size="small" :aria-label="`复制候选 ${link.candidateNumber} 对应的正式要求标识`" @click="copyRequirement(link.requirementId)">复制标识</NButton>
            </li>
          </ul>
        </article>
      </template>
      <p v-if="copyMessage" class="field-help" role="status">{{ copyMessage }}</p>
      <div class="row-actions">
        <NButton v-if="cursors.length" size="small" :disabled="origins.isFetching.value" @click="newer">较新记录</NButton>
        <NButton v-if="currentPage?.nextCursor != null" size="small" :disabled="origins.isFetching.value || !!origins.error.value" @click="older">更早记录</NButton>
        <NButton v-if="currentPage && !origins.error.value" text size="small" :disabled="origins.isFetching.value" @click="refresh">刷新来源状态</NButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
.jd-requirements-origins { display: grid; gap: 15px; min-width: 0; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }
header, .origin-heading { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 12px; }
header > div, .origin-record, .jd-source { display: grid; gap: 9px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 14px; } h4 { margin-top: 5px; font-size: 13px; }
p { max-width: 75ch; overflow-wrap: anywhere; }
.origin-record { padding-top: 16px; border-top: 1px solid var(--line); }
.origin-heading strong, .jd-source { font-size: 13px; } time, .source-state { color: var(--muted); font-size: 12px; }
.origin-links, .row-actions { display: flex; flex-wrap: wrap; gap: 9px 17px; font-size: 13px; }
.requirement-links { display: grid; gap: 10px; padding: 0; margin: 0; list-style: none; }
.requirement-links li { display: flex; align-items: baseline; flex-wrap: wrap; gap: 5px 10px; min-width: 0; font-size: 12px; }
.candidate-label { flex-basis: 100%; color: var(--muted); } code { overflow-wrap: anywhere; user-select: all; }
.text-link { overflow-wrap: anywhere; }
</style>
