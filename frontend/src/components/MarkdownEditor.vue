<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, drawSelection, keymap } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const element = ref<HTMLDivElement>()
const editable = new Compartment()
let view: EditorView | undefined
onMounted(() => {
  view = new EditorView({ parent: element.value, state: EditorState.create({
    doc: props.modelValue,
    extensions: [markdown(), history(), drawSelection(), keymap.of([...defaultKeymap, ...historyKeymap]), EditorView.lineWrapping,
      EditorView.contentAttributes.of({ 'aria-label': 'Markdown 正文' }), editable.of([EditorView.editable.of(!props.disabled), EditorState.readOnly.of(Boolean(props.disabled))]),
      EditorView.updateListener.of(update => { if (update.docChanged) emit('update:modelValue', update.state.doc.toString()) }),
    ],
  }) })
})
watch(() => props.modelValue, value => {
  if (view && value !== view.state.doc.toString()) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
})
watch(() => props.disabled, value => view?.dispatch({ effects: editable.reconfigure([EditorView.editable.of(!value), EditorState.readOnly.of(Boolean(value))]) }))
onUnmounted(() => view?.destroy())
</script>
<template><div ref="element" class="markdown-editor" /></template>
