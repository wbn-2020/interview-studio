<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../../api/client'
import { aiDate, aiMaterialOriginRoute } from '../../api/ai'
import type { AiFactDraftOriginPage } from '../../api/aiFactDraft'
import { aiFactDraftOriginPageValid, aiFactDraftPaths } from '../../api/aiFactDraft'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ factId: string; projectId: string; versionId: string; versionNumber: number; disabled?: boolean; editing?: boolean }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const active = ref(true), opened = ref(false), before = ref<number>()
const cursors = ref<(number | undefined)[]>([]), currentPage = shallowRef<AiFactDraftOriginPage>()
const validId = (value: string) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const conditionLabels = { LEARNING: '学习模式作答', BEFORE_ASSISTANCE: '查阅辅助前提交', ASSISTED: '已使用辅助条件提交' }
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking && !access.restricted
  && !access.challenge && !store.changes.get('training-access-command')?.locked
  && validId(props.factId) && validId(props.projectId) && validId(props.versionId)
  && Number.isSafeInteger(props.versionNumber) && props.versionNumber >= 1 && props.versionNumber <= 2147483647)
let alive = true, sequence = 0, accessGeneration = 0

watch([() => props.factId, () => props.projectId, () => props.versionId, () => props.versionNumber, () => store.sessionEpoch], () => {
  before.value = undefined; cursors.value = []; currentPage.value = undefined
}, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess], () => {
  accessGeneration++; sequence++; currentPage.value = undefined
}, { flush: 'sync' })
watch([opened, before, () => props.factId, () => props.projectId, () => props.versionId, () => props.versionNumber], () => {
  sequence++; currentPage.value = undefined
}, { flush: 'sync' })

const origins = useQuery({
  // 历史与对照可同时查看同一版，各面板独立接纳响应，不能借另一面板的缓存越过访问检查。
  queryKey: computed(() => ['ai', store.sessionEpoch, 'fact-draft-origins', props.factId, props.projectId, props.versionId, props.versionNumber, before.value, viewId]),
  enabled: computed(() => readable.value && opened.value),
  staleTime: 0,
  queryFn: async () => {
    const request = ++sequence, generation = accessGeneration, epoch = store.sessionEpoch
    const factId = props.factId, projectId = props.projectId, versionId = props.versionId, versionNumber = props.versionNumber, cursor = before.value
    const value = await read<AiFactDraftOriginPage>(aiFactDraftPaths.origins(factId, versionId, cursor))
    if (!alive || !readable.value || !opened.value || request !== sequence || generation !== accessGeneration || epoch !== store.sessionEpoch
      || factId !== props.factId || projectId !== props.projectId || versionId !== props.versionId || versionNumber !== props.versionNumber || cursor !== before.value) {
      throw new ApiError(0, 'AI_FACT_ORIGIN_CONTEXT_CHANGED', '事实版本或资料访问条件已变化，请重新读取采用来源。')
    }
    if (!aiFactDraftOriginPageValid(value, versionId, versionNumber, cursor, factId, projectId)) {
      throw new ApiError(502, 'AI_FACT_ORIGIN_MISMATCH', '采用来源与所选事实、项目或版本不一致，请重新读取。')
    }
    currentPage.value = value
    return value
  },
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++; accessGeneration++; currentPage.value = undefined })

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
  <section class="fact-origins" :aria-label="`事实 v${versionNumber} 的 AI 采用来源`">
    <header>
      <div><h3>AI 采用来源 · 事实 v{{ versionNumber }}</h3><p class="field-help">查看本版本及更早版本的资料或训练片段采用记录。</p></div>
      <NButton size="small" :disabled="!readable" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起来源' : '查看采用来源' }}</NButton>
    </header>
    <p v-if="opened && !readable" class="notice" role="status">资料访问恢复后可继续查看事实采用来源。</p>
    <template v-else-if="opened && readable">
      <p v-if="editing" class="notice">上方包含未保存的事实改写。这里保留已保存版本的采用记录，不作为草稿每句话的依据。</p>
      <p class="field-help">采用只记录整理出处，初次保存仍待人工核实。后续改写、归档或核实不会删除这份历史，也不会让原片段自动成为后来内容的逐句证明。</p>
      <p v-if="origins.isFetching.value && !currentPage" role="status">正在读取事实采用来源…</p>
      <p v-if="origins.error.value" class="notice error" role="alert">{{ errorText(origins.error.value) }}<NButton text :disabled="origins.isFetching.value" @click="refresh">重新读取来源</NButton></p>
      <template v-else-if="currentPage">
        <p v-if="!currentPage.items.length" class="field-help">{{ before === undefined ? '截至所选事实版本，尚无 AI 事实候选采用记录。手工填写的来源仍保留在事实内容中。' : '本页没有更早的事实采用记录。' }}</p>
        <article v-for="origin in currentPage.items" :key="origin.taskId" class="origin-record">
          <div class="origin-heading"><strong>{{ origin.direct ? '本版本直接采用' : `更早采用 · 事实 v${origin.targetVersionNumber}` }}</strong><time :datetime="origin.adoptedAt">{{ aiDate(origin.adoptedAt) }}</time></div>
          <p v-if="!origin.direct" class="field-help">这次采用发生在更早版本，所选事实可能已经人工修改或另行核实。</p>
          <div class="origin-links">
            <RouterLink class="text-link" :to="{ name: 'ai', query: { item: origin.taskId } }">查看原任务、候选与引用</RouterLink>
            <RouterLink v-if="!origin.direct" class="text-link" :to="{ name: 'prepare', query: { kind: 'FACT', item: origin.targetItemId, version: origin.targetVersionId } }">查看采用时的事实 v{{ origin.targetVersionNumber }}</RouterLink>
            <RouterLink class="text-link" :to="{ name: 'prepare', query: { kind: 'PROJECT', item: origin.targetProjectId } }">查看所属项目</RouterLink>
          </div>
          <template v-if="origin.sourceKind === 'MATERIAL'">
            <h4>当次选定的材料摘录</h4>
            <ul class="material-origins">
              <li v-for="source in origin.materialSources" :key="source.excerptId">
                <RouterLink v-if="aiMaterialOriginRoute(source)" class="text-link" :to="aiMaterialOriginRoute(source)!">{{ source.fileName }} · 查看确切摘录</RouterLink><span v-else>{{ source.fileName }}</span>
                <p class="source-state">{{ source.origin === 'PARSED' ? '解析摘录' : '手工摘录' }} · {{ source.locator }} · {{ source.state === 'CURRENT' ? '当前可用' : '当前不可用，历史身份保留' }}</p>
                <p class="field-help">摘录 <code>{{ source.excerptId }}</code> · 附件 <code>{{ source.attachmentId }}</code></p>
                <p v-if="source.origin === 'PARSED'" class="field-help">原解析执行 <code>{{ source.runId }}</code> · 片段 <code>{{ source.segmentId }}</code></p>
                <p v-else class="field-help">原文和定位由用户提供，不表示机器已核对附件原文。</p>
              </li>
            </ul>
          </template>
          <section v-else-if="origin.trainingSource" class="training-origin" aria-label="当次确切训练提交">
            <h4>当次选定的训练提交</h4>
            <p class="field-help">第 {{ origin.trainingSource.answerNumber }} 版回答 · {{ conditionLabels[origin.trainingSource.answerCondition] }} · {{ origin.trainingSource.state === 'CURRENT' ? '确切提交当前可用' : '当前不可用，历史身份保留' }}</p>
            <dl><div><dt>训练会话</dt><dd>{{ origin.trainingSource.sessionId }}</dd></div><div><dt>题目实例</dt><dd>{{ origin.trainingSource.questionId }}</dd></div><div><dt>确切回答</dt><dd>{{ origin.trainingSource.answerId }}</dd></div></dl>
            <RouterLink v-if="origin.trainingSource.state === 'CURRENT'" class="text-link" :to="{ name: 'training', query: { item: origin.trainingSource.sessionId, question: origin.trainingSource.questionId, answer: origin.trainingSource.answerId } }">查看第 {{ origin.trainingSource.answerNumber }} 版回答</RouterLink>
            <p class="field-help">后来提交新回答不会替换这次来源。训练中的假设或表述仍需核实，不能直接视为真实项目经历。</p>
          </section>
          <p class="field-help">当前可用只表示来源访问状态，不证明经历、责任边界或技术结论真实。</p>
        </article>
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
.fact-origins { display: grid; gap: 15px; min-width: 0; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); }
header, .origin-heading { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 12px; }
header > div, .origin-record, .training-origin { display: grid; gap: 9px; min-width: 0; }
h3, h4, p { margin: 0; } h3 { font-size: 14px; } h4 { margin-top: 5px; font-size: 13px; }
p { max-width: 75ch; overflow-wrap: anywhere; }
.origin-record { padding-top: 16px; border-top: 1px solid var(--line); }
.origin-heading strong, .training-origin { font-size: 13px; } time, .source-state { color: var(--muted); font-size: 12px; }
.origin-links, .row-actions { display: flex; flex-wrap: wrap; gap: 9px 17px; font-size: 13px; }
.material-origins { display: grid; gap: 14px; margin: 3px 0 0; padding-left: 20px; }
.material-origins li { min-width: 0; padding-left: 3px; font-size: 13px; overflow-wrap: anywhere; }
.material-origins p { margin-top: 6px; } .text-link, code { overflow-wrap: anywhere; }
dl { display: grid; gap: 6px; margin: 0; } dl > div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 10px; }
dt { color: var(--muted); font-size: 12px; } dd { margin: 0; min-width: 0; overflow-wrap: anywhere; font-size: 12px; }
</style>
