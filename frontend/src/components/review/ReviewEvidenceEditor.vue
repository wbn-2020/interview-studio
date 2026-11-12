<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NSelect } from 'naive-ui'
import type { AiEvaluationCitation, AiEvaluationInput } from '../../api/ai'
import { questionTextLength } from '../../api/bank'
import { reviewCitationValid, reviewEvidenceFields, reviewEvidenceLabels } from '../../api/review'

const props = defineProps<{ input: AiEvaluationInput; modelValue: AiEvaluationCitation[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: AiEvaluationCitation[]] }>()
const fields = computed(() => reviewEvidenceFields(props.input))
const selectedKey = ref('ANSWER_TEXT:')
const field = computed(() => fields.value.find(item => item.key === selectedKey.value))
const options = computed(() => fields.value.map(item => ({ value: item.key, label: item.label, disabled: !item.text })))
const selection = ref<AiEvaluationCitation>()
const message = ref('')
watch([selectedKey, () => props.input, () => props.disabled], () => { selection.value = undefined; message.value = '' })

function capture(event: Event) {
  if (props.disabled || !field.value) return
  const target = event.target as HTMLTextAreaElement
  const original = field.value.text
  selection.value = undefined
  message.value = ''
  if (target.value !== original.replace(/\r\n?/g, '\n') || target.selectionStart === target.selectionEnd) return
  // 文本框会规范换行；将选择位置映射回保存原文，保留 UTF-16 与 CRLF 的确切边界。
  const originalOffset = (offset: number) => {
    let raw = 0, displayed = 0
    while (displayed < offset && raw < original.length) {
      if (original.charAt(raw) === '\r' && original.charAt(raw + 1) === '\n') raw++
      raw++; displayed++
    }
    return raw
  }
  const start = originalOffset(target.selectionStart), end = originalOffset(target.selectionEnd)
  const citation: AiEvaluationCitation = { kind: field.value.kind, elementId: field.value.elementId, start, end, quote: original.slice(start, end) }
  if (!reviewCitationValid(props.input, citation)) { message.value = '每段引文需为 1–500 个完整字符，请重新选择。'; return }
  selection.value = citation
}
function add() {
  const citation = selection.value
  if (props.disabled || !citation || props.modelValue.length >= 6 || !reviewCitationValid(props.input, citation)) return
  if (props.modelValue.some(item => item.kind === citation.kind && item.elementId === citation.elementId && item.start === citation.start && item.end === citation.end)) {
    message.value = '这段原文已加入。'
    return
  }
  emit('update:modelValue', [...props.modelValue, { ...citation }])
  selection.value = undefined
  message.value = '已加入这处原文；保存反馈后才会记录到服务器。'
}
function remove(index: number) { if (!props.disabled) emit('update:modelValue', props.modelValue.filter((_, position) => position !== index)) }
</script>

<template>
  <section class="evidence-editor" aria-label="选择反馈原文依据">
    <h4>原文依据 <span class="field-help">{{ modelValue.length }} / 6 处</span></h4>
    <label>选择原文字段<NSelect v-model:value="selectedKey" :options="options" :disabled="disabled" aria-label="引用的原文字段" /></label>
    <p class="field-help">在下方原文中拖选，或使用 Shift 加方向键选择，再加入引用。题干与个人参考用于说明依据；参考仍是个人未审内容。</p>
    <textarea v-if="field" :value="field.text" readonly :disabled="disabled" :aria-label="`${field.label}，选择需要引用的片段`" rows="8" spellcheck="false" @select="capture" @keyup="capture" @mouseup="capture" />
    <div class="row-actions"><NButton size="small" :disabled="disabled || !selection || modelValue.length >= 6" @click="add">加入所选引文</NButton><span v-if="selection" class="field-help">已选 {{ questionTextLength(selection.quote) }} 个字符</span></div>
    <p v-if="message" class="field-help" role="status">{{ message }}</p>
    <ol v-if="modelValue.length"><li v-for="(citation, index) in modelValue" :key="`${citation.kind}:${citation.elementId}:${citation.start}:${citation.end}`"><div><strong>{{ reviewEvidenceLabels[citation.kind] }}</strong><blockquote>{{ citation.quote }}</blockquote></div><NButton text size="small" :disabled="disabled" @click="remove(index)">移除这处引用</NButton></li></ol>
  </section>
</template>

<style scoped>
.evidence-editor { display: grid; gap: 12px; min-width: 0; padding-top: 18px; border-top: 1px solid var(--line); }
h4 { margin: 0; font-size: 14px; } label { display: grid; gap: 7px; font-size: 13px; }
textarea { width: 100%; box-sizing: border-box; min-height: 150px; resize: vertical; border: 1px solid var(--line); border-radius: 6px; padding: 12px; background: var(--surface-soft); color: var(--ink); font: inherit; font-size: 13px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
textarea:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; } textarea:disabled { opacity: .6; }
ol { margin: 0; padding-left: 20px; display: grid; gap: 12px; } li { padding: 8px 0; } li > div { min-width: 0; } strong { font-size: 12px; } blockquote { margin: 6px 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
</style>
