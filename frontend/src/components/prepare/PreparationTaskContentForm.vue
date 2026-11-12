<script setup lang="ts">
import { NButton, NInput, NInputNumber } from 'naive-ui'
import type { PreparationTaskContent } from '../../api/preparationTask'
defineProps<{ disabled?: boolean }>()
const model = defineModel<PreparationTaskContent>({ required: true })
</script>

<template>
  <div class="task-content-form">
    <label>任务标题<NInput v-model:value="model.title" :disabled="disabled" placeholder="例如：整理缓存更新失败时的补偿流程" /></label>
    <label>具体准备事项 <small>{{ model.detail.length }} / 1000</small><NInput v-model:value="model.detail" :disabled="disabled" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" placeholder="写清要完成的内容，以及你准备怎样确认已经做完" /></label>
    <div class="task-form-pair"><label>预计工作量（分钟）<NInputNumber :value="model.minutes" :disabled="disabled" :show-button="false" @update:value="value => model.minutes = value ?? 0" /></label><label>计划日期（可留空）<input type="date" min="1000-01-01" max="9999-12-31" :value="model.plannedDate ?? ''" :disabled="disabled" @input="model.plannedDate = ($event.target as HTMLInputElement).value || null" /></label></div>
    <div v-if="model.plannedDate" class="row-actions"><NButton text size="small" :disabled="disabled" @click="model.plannedDate = null">清空计划日期</NButton></div>
    <label>任务时区<NInput v-model:value="model.zoneId" :disabled="disabled" placeholder="Asia/Shanghai 或 UTC" /></label>
    <p class="field-help">日期按任务时区记录，可选择过去日期，也可修改为稍后的日期；不会自动顺延。预计分钟仅表示工作量，不是计时结果或日历占用时段。</p>
  </div>
</template>

<style scoped>
.task-content-form, .task-content-form label { display: grid; gap: 8px; min-width: 0; }
.task-content-form { gap: 15px; } label { color: var(--muted); font-size: 12px; } label small { font-size: 11px; }
.task-form-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
input[type=date] { width: 100%; min-height: 34px; padding: 5px 10px; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: 4px; }
@media (max-width: 600px) { .task-form-pair { grid-template-columns: 1fr; } }
</style>
