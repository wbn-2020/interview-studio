<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { NButton, NSelect } from 'naive-ui'
import type { AiEvaluationInput } from '../../api/ai'
import { questionTextLength } from '../../api/bank'
import * as o from '../../api/reviewedObservations'

const props = defineProps<{ input: AiEvaluationInput; checkpointId: string; modelValue: o.ObservationCitation[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: o.ObservationCitation[]]; dirty: [value: boolean] }>()
const fields = computed(() => o.observationCitationFields(props.input, props.checkpointId))
const selectedKey = ref('ANSWER_TEXT:'), selection = shallowRef<o.ObservationCitation>(), message = ref('')
const field = computed(() => fields.value.find(row => row.key === selectedKey.value))
const options = computed(() => fields.value.map(row => ({ value: row.key, label: row.kind === 'CHECKPOINT' ? '本次观察的检查项原文' : row.label, disabled: !row.text })))
watch(selection, value => emit('dirty', Boolean(value)), { immediate: true, flush: 'sync' })
watch(() => props.checkpointId, () => { selection.value = undefined; message.value = ''; selectedKey.value = `CHECKPOINT:${props.checkpointId}` })
function chooseField(value: string) {
  if (props.disabled) return
  if (selection.value && !window.confirm('这段已选原文尚未加入。更换原文字段会清除本次选区，确定继续？')) return
  selectedKey.value = value; selection.value = undefined; message.value = ''
}
function capture(event: Event) {
  if (props.disabled || !field.value) return
  const target = event.target as HTMLTextAreaElement, raw = field.value.text
  selection.value = undefined; message.value = ''
  if (target.value !== raw.replace(/\r\n?/g, '\n') || target.selectionStart === target.selectionEnd) return
  // 复用反馈原文定位规则；textarea 的 CRLF 规范化必须映回保存文本的 UTF-16 位置。
  const offset = (position: number) => { let r = 0, d = 0; while (d < position && r < raw.length) { if (raw[r] === '\r' && raw[r + 1] === '\n') r++; r++; d++ } return r }
  const start = offset(target.selectionStart), end = offset(target.selectionEnd)
  const value = { kind: field.value.kind, elementId: field.value.elementId, start, end, quote: raw.slice(start, end) }
  if (!o.observationCitationValid(value,props.input,props.checkpointId)) { message.value = '请选择 1–500 个完整字符，且必须来自本次回答或所选检查项。'; return }
  selection.value = value
}
function add() {
  const value = selection.value
  if (props.disabled || !value || props.modelValue.length >= 6 || !o.observationCitationValid(value,props.input,props.checkpointId)) return
  if (props.modelValue.some(row => row.kind === value.kind && row.elementId === value.elementId && row.start === value.start && row.end === value.end)) { message.value = '这处原文已经加入。'; return }
  emit('update:modelValue', [...props.modelValue, { ...value }]); selection.value = undefined; message.value = '已加入引文；保存观察后才会写入服务器。'
}
function clearSelection() { if (!props.disabled) { selection.value = undefined; message.value = '' } }
function remove(index: number) { if (!props.disabled) emit('update:modelValue', props.modelValue.filter((_, position) => position !== index)) }
</script>

<template>
  <section class="observation-evidence" aria-label="受审观察的确切原文依据">
    <h5>原文依据 <span class="field-help">{{ modelValue.length }} / 6 处</span></h5>
    <p class="field-help">必须包含本次检查项的引文。“原文有提及”还需回答文字或答案图引文；“未提及”只保留检查项引文，不能补造回答。</p>
    <label>原文字段<NSelect :value="selectedKey" :options="options" :disabled="disabled || !checkpointId" aria-label="受审观察引文字段" @update:value="chooseField" /></label>
    <p class="field-help">拖选原文，或按 Shift 加方向键选择，再加入引用。当前选区在临时禁用或重新登录时保留。</p>
    <textarea v-if="field" :value="field.text" rows="7" readonly :disabled="disabled" :aria-label="`${field.label}，选择引文`" @select="capture" @keyup="capture" @mouseup="capture" />
    <blockquote v-if="selection">{{ selection.quote }}</blockquote>
    <div class="row-actions"><NButton size="small" :disabled="disabled || !selection || modelValue.length >= 6" @click="add">加入所选引文</NButton><NButton v-if="selection" text size="small" :disabled="disabled" @click="clearSelection">清除未加入选区</NButton><span v-if="selection" class="field-help">{{ questionTextLength(selection.quote) }} 个字符</span></div>
    <p v-if="message" class="field-help" role="status">{{ message }}</p>
    <ol v-if="modelValue.length"><li v-for="(citation,index) in modelValue" :key="`${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`"><strong>{{ o.observationCitationKindLabels[citation.kind] }}</strong><blockquote>{{ citation.quote }}</blockquote><NButton text size="small" :disabled="disabled" @click="remove(index)">移除这处引文</NButton></li></ol>
  </section>
</template>

<style scoped>
.observation-evidence { display:grid; gap:12px; min-width:0; border-top:1px solid var(--line); padding-top:18px; }h5 { margin:0; font-size:14px; }label { display:grid; gap:7px; font-size:13px; }textarea { width:100%; box-sizing:border-box; resize:vertical; border:1px solid var(--line); border-radius:6px; padding:12px; background:var(--surface-soft); color:var(--ink); font:inherit; font-size:13px; line-height:1.7; }textarea:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }blockquote { margin:0; padding:10px 0; white-space:pre-wrap; overflow-wrap:anywhere; max-width:75ch; font-size:13px; line-height:1.7; }ol { display:grid; gap:14px; margin:0; padding-left:20px; }li { min-width:0; }strong { font-size:12px; }
</style>
