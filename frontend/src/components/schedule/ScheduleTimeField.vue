<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NButton, NInput, NInputNumber } from 'naive-ui'
import { errorText, preview } from '../../api/client'
import type { ScheduleTimeInput, TimeResolution } from '../../api/schedule'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled?: boolean }>()
const model = defineModel<ScheduleTimeInput>({ required: true })
const emit = defineEmits<{ ready: [value: boolean] }>()
const store = useWorkspaceStore()
const resolution = ref<TimeResolution>()
const resolvedKey = ref('')
const busy = ref(false)
const error = ref('')
let alive = true
let requestNumber = 0
const key = computed(() => JSON.stringify([model.value.localStart, model.value.zoneId]))
const validDuration = computed(() => Number.isInteger(model.value.durationMinutes) && model.value.durationMinutes >= 1 && model.value.durationMinutes <= 1440)
const ready = computed(() => validDuration.value && resolvedKey.value === key.value && Boolean(resolution.value?.options.some(option => option.offset === model.value.offset)))
watch(ready, value => emit('ready', value), { immediate: true })
watch(key, () => { resolution.value = undefined; resolvedKey.value = ''; model.value.offset = null; error.value = ''; requestNumber++ })
watch(() => store.sessionEpoch, () => { resolution.value = undefined; resolvedKey.value = ''; requestNumber++ })
onUnmounted(() => { alive = false; requestNumber++ })

async function resolve() {
  if (props.disabled || !store.sessionActive || busy.value || !model.value.localStart || !model.value.zoneId.trim()) return
  const requestedKey = key.value
  const epoch = store.sessionEpoch
  const request = ++requestNumber
  busy.value = true
  error.value = ''
  resolution.value = undefined
  resolvedKey.value = ''
  try {
    const value = await preview<TimeResolution>('/schedule/time/resolve', { localStart: model.value.localStart, zoneId: model.value.zoneId })
    // 时间解析可能跨过输入修改、保存或重新登录，迟到结果不能替用户选定另一个时刻。
    if (!alive || props.disabled || !store.sessionActive || store.sessionEpoch !== epoch || requestedKey !== key.value || request !== requestNumber) return
    resolution.value = value
    resolvedKey.value = requestedKey
    if (value.state === 'UNIQUE') model.value.offset = value.options[0]?.offset ?? null
    else model.value.offset = null
  } catch (reason) {
    if (alive && store.sessionActive && store.sessionEpoch === epoch && request === requestNumber) error.value = errorText(reason)
  } finally { if (alive) busy.value = false }
}
</script>

<template>
  <fieldset class="schedule-time-fields" :disabled="disabled">
    <legend>面试时间</legend>
    <div class="time-pair"><label>本地日期与时间<input type="datetime-local" :value="model.localStart" :disabled="disabled" step="60" @input="model.localStart = ($event.target as HTMLInputElement).value" /></label><label>IANA 时区<NInput v-model:value="model.zoneId" :disabled="disabled" placeholder="例如 Asia/Shanghai" :maxlength="100" /></label></div>
    <label>预计持续分钟数<NInputNumber :value="model.durationMinutes" :disabled="disabled" :min="1" :max="1440" :precision="0" :show-button="false" @update:value="value => model.durationMinutes = value ?? 0" /></label>
    <div class="row-actions"><NButton size="small" :loading="busy" :disabled="disabled || !store.sessionActive || !model.localStart || !model.zoneId.trim()" @click="resolve">核对时区与时刻</NButton><span v-if="ready" class="success-text">时刻已核对</span></div>
    <p class="field-help">日期按填写的时区解释。默认带入浏览器时区，可在保存前修改。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="resolution?.state === 'GAP'" class="notice error" role="alert">这个本地时间因时区调整而不存在，请选择其他时间。</p>
    <fieldset v-if="resolution?.state === 'OVERLAP'" class="offset-options"><legend>这个本地时间出现两次，请选择</legend><label v-for="option in resolution.options" :key="option.offset"><input v-model="model.offset" type="radio" :value="option.offset" :disabled="disabled" /> UTC{{ option.offset === 'Z' ? '+00:00' : option.offset }} · {{ option.startAt }}</label></fieldset>
    <p v-else-if="ready && resolution?.options[0]" class="field-help">对应 UTC：{{ resolution.options[0].startAt }}</p>
    <p v-if="!validDuration" class="field-help" role="status">持续时间应为 1–1440 的整数分钟。</p>
  </fieldset>
</template>

<style scoped>
.schedule-time-fields { display: grid; gap: 11px; min-width: 0; margin: 0; padding: 14px; border: 1px solid var(--line); border-radius: 7px; }
.schedule-time-fields > legend { padding: 0 6px; font-size: 13px; color: var(--ink); }
.schedule-time-fields label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; }
.time-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
input[type=datetime-local] { width: 100%; box-sizing: border-box; min-height: 34px; padding: 5px 9px; background: var(--surface); border: 1px solid var(--line); border-radius: 4px; color: var(--ink); }
.offset-options { display: grid; gap: 8px; margin: 0; padding: 12px; border: 1px solid var(--line); }
.offset-options label { display: flex; align-items: center; gap: 7px; }
@media (max-width: 740px) { .time-pair { grid-template-columns: 1fr; } }
</style>
