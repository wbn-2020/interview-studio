<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, useId, watch } from 'vue'
import { NButton } from 'naive-ui'
import { ApiError, errorText, read } from '../api/client'
import type { EvidencePage, EvidenceQuery, EvidenceSummary } from '../api/growth'
import { evidencePageValid, growthDateRangeValid, growthEvidencePath } from '../api/growth'
import { browserZone, dateKey } from '../api/schedule'
import { todayZoneValid } from '../api/today'
import { trainingModeLabels } from '../api/training'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'

type Choice = { sessionId: string; questionId: string; answerId: string; title: string; createdAt: string;
  condition: 'LEARNING' | 'BEFORE_ASSISTANCE' | 'ASSISTED' }
type DirectoryPage = { cursor?: string; value: EvidencePage }
const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ choose: [value: Choice] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore(), viewId = useId()
const zoneId = todayZoneValid(browserZone()) ? browserZone() : 'UTC'
const formatter = new Intl.DateTimeFormat('zh-CN', { timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
const formatTime = (value: string) => formatter.format(new Date(value))
const to = ref(dateKey(Date.now(), zoneId))
const first = new Date(`${to.value}T12:00:00Z`)
first.setUTCDate(first.getUTCDate() - 27)
const from = ref(first.toISOString().slice(0, 10))
const active = ref(true), currentPage = shallowRef<DirectoryPage>(), loading = ref(false), problem = ref('')
const previous = shallowRef<DirectoryPage[]>([]), applied = ref('')
const page = computed(() => currentPage.value?.value)
const filter = computed(() => JSON.stringify([from.value, to.value, zoneId]))
const readable = computed(() => active.value && store.sessionActive && !props.disabled && !access.checking
  && !access.restricted && !access.challenge && !store.changes.get('training-access-command')?.locked)
const rangeValid = computed(() => growthDateRangeValid(from.value, to.value))
const selectable = computed(() => readable.value && !loading.value && applied.value === filter.value && page.value?.capability.status === 'READY')
const conditionLabels = { LEARNING: '学习条件', BEFORE_ASSISTANCE: '辅助确认前', ASSISTED: '已确认辅助' }
const kindLabels = { MAIN: '主问题', FOLLOW_UP: '同场追问', REPLAY: '独立复练' }
let alive = true, generation = 0, sequence = 0

function reset() {
  generation++; sequence++; currentPage.value = undefined; loading.value = false; problem.value = ''
  previous.value = []; applied.value = ''
}
watch([filter, readable, () => store.sessionEpoch, () => access.active?.id,
  () => access.active?.mode, () => access.active?.materialAccess], reset, { flush: 'sync' })
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++ })

async function load(next = false) {
  if (!readable.value || loading.value || !rangeValid.value || next && !page.value?.nextCursor) return
  const origin = currentPage.value, target = next ? origin?.value.nextCursor ?? undefined : undefined
  const query: EvidenceQuery = { kind: 'TRAINING_ANSWER', zoneId, from: from.value, to: to.value,
    limit: 20, targetMode: 'RECORDED_ONLY', topicMode: 'RECORDED_ONLY', ...(target ? { cursor: target } : {}) }
  const epoch = store.sessionEpoch, stamp = generation, ticket = ++sequence, signature = filter.value
  const expectedAsOf = next ? origin?.value.context.asOf : undefined
  loading.value = true; problem.value = ''
  if (!next) { currentPage.value = undefined; previous.value = []; applied.value = '' }
  const current = () => alive && readable.value && epoch === store.sessionEpoch && stamp === generation
    && ticket === sequence && signature === filter.value
  try {
    const result = await read<EvidencePage>(growthEvidencePath(query))
    if (!current()) return
    if (!evidencePageValid(result, query) || expectedAsOf !== undefined && result.context.asOf !== expectedAsOf) {
      throw new ApiError(502, 'ANSWER_DIRECTORY_INVALID', '提交目录与日期范围或分页位置不一致，请重新查询。')
    }
    if (result.nextCursor && next && [...previous.value, ...(origin ? [origin] : [])].some(item => item.cursor === result.nextCursor)) throw new ApiError(502, 'ANSWER_DIRECTORY_CURSOR_INVALID', '提交目录返回了已经经过的分页位置，请重新查询。')
    if (next && origin) previous.value = [...previous.value, origin]
    currentPage.value = { cursor: target, value: result }; applied.value = signature
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function back() {
  if (!readable.value || loading.value || !previous.value.length || applied.value !== filter.value) return
  // 首屏没有可重放原水位的游标；恢复本次查询已核对的元数据页，避免重读产生新的 asOf。
  currentPage.value = previous.value.at(-1); previous.value = previous.value.slice(0, -1); problem.value = ''
}
function choose(value: EvidenceSummary) {
  if (!selectable.value || !page.value?.items.includes(value) || value.conditions.kind !== 'TRAINING_ANSWER'
    || value.route.route !== 'TRAINING_ANSWER') return
  const answer = value.conditions.answer
  emit('choose', { sessionId: answer.sessionId, questionId: answer.questionId, answerId: answer.answerId,
    title: value.title, createdAt: value.occurredAt, condition: answer.condition })
}
</script>

<template>
  <section class="answer-picker" aria-label="选择已有训练提交">
    <p class="field-help">先按日期查看正式提交，再选择需要引用的一次回答。目录只显示标题、时间和作答条件；选择后才读取该次原文。</p>
    <form class="date-range" @submit.prevent="load()">
      <label :for="`${viewId}-from`">起始日期<input :id="`${viewId}-from`" v-model="from" type="date" :disabled="!readable || loading" /></label>
      <label :for="`${viewId}-to`">结束日期<input :id="`${viewId}-to`" v-model="to" type="date" :disabled="!readable || loading" /></label>
      <NButton attr-type="submit" :disabled="!readable || loading || !rangeValid" :loading="loading">查询已提交回答</NButton>
    </form>
    <p class="field-help">{{ zoneId }} · 包含起止日期，每次最多 366 天。要查找更早提交，可调整日期范围。</p>
    <p v-if="!rangeValid" class="notice" role="status">请填写有效的起止日期，范围不得超过 366 天。</p>
    <p v-if="loading" role="status">正在读取提交目录…</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }}</p>
    <template v-if="page && readable">
      <p v-if="page.capability.status !== 'READY'" class="notice" role="status">提交目录本次不可用，请重新查询；不能据此判断没有记录。</p>
      <template v-else>
        <p class="field-help">当前范围 {{ page.scope.window.from }} 至 {{ page.scope.window.to }} · 本页 {{ page.items.length }} 条提交。</p>
        <p v-if="page.scanLimited" class="notice" role="status">本次读取达到扫描边界。可继续翻页，结果不代表全部提交。</p>
        <p v-if="!page.items.length" class="field-help" role="status">当前日期范围的这一页没有返回提交。{{ page.nextCursor ? '仍有后续页，可继续查询。' : '可调整日期范围查找其他记录。' }}</p>
        <ul class="answer-results"><li v-for="item in page.items" :key="item.ref.objectId">
          <template v-if="item.conditions.kind === 'TRAINING_ANSWER'">
            <div><strong>{{ item.title }}</strong><span>{{ formatTime(item.occurredAt) }}</span><small>{{ trainingModeLabels[item.conditions.answer.mode] }} · {{ kindLabels[item.conditions.answer.questionKind] }} · {{ conditionLabels[item.conditions.answer.condition] }}</small></div>
            <NButton size="small" :disabled="!selectable" :aria-label="`选择 ${item.title}，${formatTime(item.occurredAt)} 的提交`" @click="choose(item)">选择这次提交</NButton>
          </template>
        </li></ul>
        <div class="row-actions"><NButton v-if="previous.length" size="small" :disabled="!readable || loading" @click="back">上一页</NButton><NButton v-if="page.nextCursor" size="small" :disabled="!readable || loading" @click="load(true)">下一页</NButton></div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.answer-picker { display: grid; gap: 12px; min-width: 0; padding: 14px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-soft); }
p { margin: 0; }.date-range { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; }.date-range label { display: grid; gap: 6px; min-width: 0; font-size: 13px; }
input { max-width: 100%; box-sizing: border-box; padding: 7px 9px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 5px; font: inherit; }input:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.answer-results { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }.answer-results li { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--line); min-width: 0; }.answer-results li > div { display: grid; gap: 5px; min-width: 0; }.answer-results strong { font-size: 13px; overflow-wrap: anywhere; }.answer-results span, small { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
</style>
