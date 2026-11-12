<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { ApiError, errorText } from '../../api/client'
import type { ItemDetail } from '../../api/client'
import { todayIdValid } from '../../api/today'
import { queryClient } from '../../api/queryClient'
import { useCommand } from '../../composables/useCommand'
import { useWorkspaceStore } from '../../state/workspace'

const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ close: []; created: [id: string] }>()
const store = useWorkspaceStore(), writer = useCommand()
const title = ref(''), body = ref(''), created = shallowRef<{ id: string; title: string }>()
const stateKey = 'today-quick-note', state = reactive({ dirty: false, locked: false })
const request = shallowRef<NonNullable<typeof writer.pending.value>>()
const readable = computed(() => store.sessionActive && !props.disabled)
const problem = computed(() => title.value.trim().length > 200 || body.value.length > 100000 ? '标题最多 200 字符，正文最多 100000 字符。' : '')
let alive = true, generation = 0, commandGeneration = 0, commandEpoch = store.sessionEpoch
watch([readable, () => store.sessionEpoch], () => { generation++ }, { flush: 'sync' })
watch(() => store.sessionEpoch, () => { created.value = undefined }, { flush: 'sync' })
watch([title, body, writer.locked], () => {
  state.dirty = Boolean(title.value || body.value); state.locked = writer.locked.value; store.changes.set(stateKey, state)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; generation++; if (store.changes.get(stateKey) === state) store.changes.delete(stateKey) })
function close() { if (!writer.locked.value && store.canLeave([stateKey])) emit('close') }
function receive(value: ItemDetail) {
  // 未知结果仍沿用原创建键；失去访问后不得让迟到回执清空输入或打开另一会话的笔记。
  if (!alive || !readable.value || commandEpoch !== store.sessionEpoch || commandGeneration !== generation) {
    writer.pending.value = request.value
    writer.error.value = new ApiError(0, 'TODAY_NOTE_ACCESS_CHANGED', '访问条件已变化，输入已保留。恢复后用原请求核对创建结果。')
    return
  }
  if (!value || !todayIdValid(value.id) || value.kind !== 'NOTE' || value.status !== 'ACTIVE' || !value.published || !todayIdValid(value.currentVersionId)
    || value.published.id !== value.currentVersionId || value.published.number !== 1 || typeof value.title !== 'string' || value.published.body !== body.value) {
    writer.pending.value = request.value
    writer.error.value = new ApiError(0, 'TODAY_NOTE_RESPONSE_INVALID', '创建回执不完整，输入和原请求已保留，请用原请求核对结果。')
    return
  }
  created.value = { id: value.id, title: value.title }; title.value = ''; body.value = ''; request.value = undefined
  void queryClient.invalidateQueries({ queryKey: ['knowledge'] })
  emit('created', value.id)
}
function save() {
  if (!readable.value || writer.locked.value || !title.value.trim() || problem.value) return
  commandGeneration = generation; commandEpoch = store.sessionEpoch
  void writer.run<ItemDetail>('/knowledge/items', 'POST', { kind: 'NOTE', title: title.value, body: body.value, topicIds: [] }, receive)
  request.value = writer.pending.value
}
function retry() {
  if (!readable.value || writer.busy.value || !writer.pending.value) return
  commandGeneration = generation; commandEpoch = store.sessionEpoch; void writer.retry()
}
</script>

<template>
  <section class="today-quick-note" aria-labelledby="today-note-title"><header><div><p class="section-label">先留住这个想法</p><h2 id="today-note-title">快速记录</h2></div><NButton text :disabled="writer.locked.value" @click="close">收起</NButton></header>
    <p class="field-help">保存为知识库中的独立笔记，先放在未归类中。创建会保存第一个发布版本，之后可继续编辑草稿。</p>
    <form @submit.prevent="save"><label for="today-note-name">标题<NInput id="today-note-name" v-model:value="title" :disabled="!readable || writer.locked.value" :maxlength="200" placeholder="记下一个问题、例子或待整理的线索" /></label><label for="today-note-body">正文（可选，支持 Markdown）<NInput id="today-note-body" v-model:value="body" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" :disabled="!readable || writer.locked.value" :maxlength="100000" /></label>
      <p v-if="problem" class="notice error" role="alert">{{ problem }}</p><p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
      <p v-if="created" class="success-text" role="status">已保存「{{ created.title }}」。可从知识库继续补充与关联。</p>
      <div class="row-actions"><NButton v-if="writer.pending.value && !writer.busy.value" type="primary" :disabled="!readable" @click="retry">用原请求核对结果</NButton><NButton v-else type="primary" attr-type="submit" :loading="writer.busy.value" :disabled="!readable || writer.locked.value || !title.trim() || !!problem">保存笔记</NButton><NButton :disabled="writer.locked.value" @click="close">取消</NButton></div>
    </form>
  </section>
</template>

<style scoped>
.today-quick-note { display: grid; gap: 13px; padding: 22px; margin-bottom: 22px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); }
header { display: flex; align-items: center; justify-content: space-between; gap: 18px; }h2 { margin: 4px 0 0; font-size: 18px; }form { display: grid; gap: 13px; }label { display: grid; gap: 7px; font-size: 12px; color: var(--muted); }.success-text { font-size: 12px; overflow-wrap: anywhere; }
</style>
