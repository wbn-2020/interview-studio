<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber, NSelect } from 'naive-ui'
import type { TodayItem } from '../../api/today'
import type { PlacementContent } from '../../api/todayPlan'
import { dateKinds, estimateKinds } from './planUi'

const props = defineProps<{ modelValue: PlacementContent; item: TodayItem; disabled: boolean; localDate: string; zoneId: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: PlacementContent] }>()
const hasDate = computed(() => dateKinds.has(props.item.source.kind))
const canLock = computed(() => hasDate.value ? Boolean(props.modelValue.plannedDate) : props.item.source.kind === 'PREPARATION_TASK' && props.item.time.kind === 'LOCAL_DATE')
const visibilityOptions = [{ label: '正常显示推荐', value: 'VISIBLE' }, { label: '暂停这项安排推荐', value: 'PAUSED' }]
function set<K extends keyof PlacementContent>(key: K, value: PlacementContent[K]) { if (!props.disabled) emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function date(value: string) { if (!props.disabled) emit('update:modelValue', { ...props.modelValue, plannedDate: value || null, planZoneId: value ? props.modelValue.planZoneId ?? props.zoneId : null, dateLocked: value ? props.modelValue.dateLocked : false }) }
function requiredNumber(key: 'priority' | 'orderValue', value: number | null) { set(key, value ?? Number.NaN) }
function visibility(value: unknown) { if (value === 'VISIBLE' || value === 'PAUSED') set('visibility', value) }
</script>

<template>
  <div class="placement-fields"><template v-if="hasDate"><label>安排日期<input :value="modelValue.plannedDate ?? ''" type="date" min="1000-01-01" max="9999-12-31" :disabled="disabled" @input="date(($event.target as HTMLInputElement).value)" /></label><label>安排时区<NInput :value="modelValue.planZoneId ?? ''" :disabled="disabled || !modelValue.plannedDate" placeholder="Asia/Shanghai" @update:value="value => set('planZoneId', value)" /></label></template><p v-else class="wide field-help">日期与到期时间由原来源保存，修改请前往来源页面。</p>
    <label v-if="estimateKinds.has(item.source.kind)">预计学习分钟（空表示未知）<NInputNumber :value="modelValue.estimatedMinutes" :min="1" :max="1440" :precision="0" clearable :disabled="disabled" @update:value="value => set('estimatedMinutes', value)" /></label><p v-else class="field-help">{{ item.source.kind === 'PREPARATION_TASK' ? `估时沿用任务：${item.estimate.minutes ?? '未知'} 分钟` : '面试时长不计为学习估时。' }}</p>
    <label>建议优先级（5 最高）<NInputNumber :value="modelValue.priority" :min="1" :max="5" :precision="0" :disabled="disabled" @update:value="value => requiredNumber('priority', value)" /></label><label>显示顺序（越小越靠前）<NInputNumber :value="modelValue.orderValue" :min="0" :max="1000000" :precision="0" :disabled="disabled" @update:value="value => requiredNumber('orderValue', value)" /></label><label v-if="hasDate">推荐状态<NSelect :value="modelValue.visibility" :options="visibilityOptions" :disabled="disabled" @update:value="visibility" /></label>
    <div v-if="canLock || modelValue.dateLocked" class="wide"><NCheckbox :checked="modelValue.dateLocked" :disabled="disabled || (!canLock && !modelValue.dateLocked)" @update:checked="value => set('dateLocked', value)">锁定安排日期，自动建议保留原日期</NCheckbox><p v-if="!canLock && modelValue.dateLocked" class="field-help">原任务已经清空日期，可取消旧的日期锁定后保存。</p></div>
    <div class="wide suppression"><p class="field-help">{{ modelValue.suppressedOn ? `已设置暂不推荐：${modelValue.suppressedOn.localDate} · ${modelValue.suppressedOn.zoneId}` : '没有设置单日暂不推荐。' }}</p><div class="row-actions"><NButton size="small" :disabled="disabled || !localDate" @click="set('suppressedOn', { localDate, zoneId })">{{ localDate }} 暂不推荐</NButton><NButton v-if="modelValue.suppressedOn" text size="small" :disabled="disabled" @click="set('suppressedOn', null)">清除单日限制</NButton></div><p class="field-help">不会隐藏必须保留的续作与面试提醒；不改变回忆到期时间或来源状态。</p></div>
  </div>
</template>

<style scoped>
.placement-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }label { display: grid; gap: 7px; min-width: 0; font-size: 12px; color: var(--muted); }input { width: 100%; min-height: 34px; padding: 5px 10px; box-sizing: border-box; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); color: var(--ink); }.wide { grid-column: 1 / -1; }.suppression { display: grid; gap: 8px; padding-top: 12px; border-top: 1px solid var(--line); }@media (max-width: 600px) { .placement-fields { grid-template-columns: 1fr; } }
</style>
