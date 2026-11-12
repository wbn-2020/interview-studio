<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NSwitch } from 'naive-ui'
import { read, errorText } from '../api/client'
import type { ItemSummary } from '../api/client'
import type { Organization, ConnectionPage } from '../api/knowledge'
import { queryClient } from '../api/queryClient'
import { useCommand } from '../composables/useCommand'
import { useWorkspaceStore } from '../state/workspace'
import { useTrainingAccessStore } from '../state/trainingAccess'
import KnowledgePicker from './KnowledgePicker.vue'
const props = defineProps<{ id: string; kind: 'NOTE' | 'CONCEPT'; active: boolean }>()
const emit = defineEmits<{ changed: []; open: [id: string] }>()
const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const readable = computed(() => store.sessionActive && !access.checking && !access.restricted && !access.challenge)
const reloading = ref(false)
const reloadError = ref('')
const locked = computed(() => writer.locked.value || reloading.value)
const changeState = reactive({ dirty: false, locked: false })
let reloadGeneration = 0
let editGeneration = 0
let alive = true
const base = ref<Organization>()
const aliases = ref('')
const tags = ref('')
const favorite = ref(false)
const noteIds = ref<string[]>([])
const mainNoteId = ref<string | null>(null)
const noteLabels = ref<Record<string, string>>({})
const lookupOpen = ref(false)
const message = ref('')
const query = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'organization', props.id]), enabled: readable, queryFn: () => read<Organization>(`/knowledge/items/${props.id}/organization`) })
const associations = useQuery({ queryKey: computed(() => ['knowledge', store.sessionEpoch, 'association-preview', props.id]), enabled: readable, queryFn: () => read<ConnectionPage>(`/knowledge/items/${props.id}/connections?type=ASSOCIATION&limit=50`) })
const words = (value: string) => value.split('\n').map(text => text.trim()).filter(Boolean)
const content = computed(() => ({ aliases: props.kind === 'CONCEPT' ? words(aliases.value) : [], tags: words(tags.value), favorite: favorite.value, noteIds: noteIds.value, mainNoteId: mainNoteId.value }))
const dirty = computed(() => base.value !== undefined && JSON.stringify(content.value) !== JSON.stringify({ aliases: base.value.aliases, tags: base.value.tags, favorite: base.value.favorite, noteIds: base.value.noteIds, mainNoteId: base.value.mainNoteId }))
const editable = computed(() => props.active && readable.value && !locked.value)
function accept(value: Organization) { base.value = value; aliases.value = value.aliases.join('\n'); tags.value = value.tags.join('\n'); favorite.value = value.favorite; noteIds.value = [...value.noteIds]; mainNoteId.value = value.mainNoteId }
watch([aliases, tags, favorite, noteIds, mainNoteId], () => { editGeneration++ }, { deep: true, flush: 'sync' })
watch([query.data, readable], ([value, allowed]) => {
  // 重读的采用由对应请求确认；后台旧结果也不能回退已保存的整理修订。
  if (value && allowed && !locked.value && (!base.value || (!dirty.value && value.revision >= base.value.revision))) accept(value)
}, { immediate: true })
watch(associations.data, value => value?.items.forEach(item => { noteLabels.value[item.itemId] = `${item.title}${item.status === 'ARCHIVED' ? '（已归档）' : ''}` }), { immediate: true })
watch([dirty, locked], ([value, waiting]) => {
  changeState.dirty = value
  changeState.locked = waiting
  store.changes.set('organization', changeState)
}, { immediate: true, flush: 'sync' })
watch([() => props.id, () => props.kind, () => props.active, () => store.sessionEpoch, readable], () => {
  reloadGeneration++
  reloading.value = false
  reloadError.value = ''
}, { flush: 'sync' })
onUnmounted(() => {
  alive = false
  reloadGeneration++
  if (store.changes.get('organization') === changeState) store.changes.delete('organization')
})
function addNote(item: ItemSummary) { if (!editable.value) return; if (!noteIds.value.includes(item.id)) noteIds.value.push(item.id); noteLabels.value[item.id] = item.title }
function removeNote(id: string) { if (!editable.value) return; noteIds.value = noteIds.value.filter(value => value !== id); if (mainNoteId.value === id) mainNoteId.value = null }
function save() {
  if (!base.value || !editable.value) return
  void writer.run<Organization>(`/knowledge/items/${props.id}/organization`, 'PUT', { expectedRevision: base.value.revision, ...content.value }, value => {
    // 整理信息拥有自己的修订，不修改正文、主题或草稿的 CAS 基线。
    accept(value); queryClient.setQueryData(['knowledge', store.sessionEpoch, 'organization', props.id], value); message.value = '整理信息已保存'; emit('changed')
  })
}
async function reload() {
  if (!readable.value || locked.value || !window.confirm('放弃本地整理修改，重新读取服务器信息？')) return
  const request = ++reloadGeneration
  const epoch = store.sessionEpoch
  const id = props.id
  const edits = editGeneration
  const current = () => alive && readable.value && request === reloadGeneration && epoch === store.sessionEpoch && id === props.id
  reloading.value = true
  reloadError.value = ''
  try {
    // 单独读取，避免失败重读的缓存副本或其他查询回调替代本次明确确认。
    const value = await read<Organization>(`/knowledge/items/${id}/organization`)
    if (!current()) return
    if (edits !== editGeneration || writer.locked.value || (base.value && value.revision < base.value.revision)) {
      reloadError.value = '整理内容已变化，本次重读没有替换当前输入。请核对后重新读取。'
      return
    }
    accept(value)
    queryClient.setQueryData(['knowledge', epoch, 'organization', id], value)
    writer.error.value = undefined
    message.value = '已重新读取整理信息'
  } catch (reason) {
    if (current()) reloadError.value = errorText(reason)
  } finally {
    if (request === reloadGeneration) reloading.value = false
  }
}
</script>
<template>
  <section class="organization-panel">
    <p v-if="query.isPending.value" role="status">正在读取整理信息…</p><p v-if="query.error.value" class="notice error">{{ errorText(query.error.value) }}</p>
    <template v-if="base">
      <div class="row-actions"><NSwitch v-model:value="favorite" :disabled="!editable" aria-label="收藏此知识" /><span>收藏到我的知识</span><span class="field-help">{{ dirty ? '尚未保存' : `整理修订 ${base.revision}` }}</span></div>
      <label v-if="kind === 'CONCEPT'">概念别名（一行一个，最多 20 个）<NInput v-model:value="aliases" type="textarea" :disabled="!editable" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="例如：CAS\n比较并交换" /></label>
      <label>标签（一行一个，最多 20 个）<NInput v-model:value="tags" type="textarea" :disabled="!editable" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="例如：高频问题\n需要复习" /></label>
      <template v-if="kind === 'CONCEPT'"><h3>关联笔记 · {{ noteIds.length }}/100</h3><p class="field-help">概念摘要与笔记正文分别保存。指定主笔记不会复制或替换正文。</p>
        <div v-for="noteId in noteIds" :key="noteId" class="association-row"><button class="text-link" @click="emit('open', noteId)">{{ noteLabels[noteId] ?? noteId }}</button><label class="inline-check"><input type="radio" :checked="mainNoteId === noteId" :disabled="!editable" @change="mainNoteId = noteId" />主笔记</label><NButton size="small" quaternary :disabled="!editable" @click="removeNote(noteId)">移出关联</NButton></div>
        <p v-if="!noteIds.length" class="field-help">还未关联笔记。</p><div class="row-actions"><NButton size="small" :disabled="!editable || noteIds.length >= 100" @click="lookupOpen = !lookupOpen">选择笔记</NButton><NButton v-if="mainNoteId" size="small" :disabled="!editable" @click="mainNoteId = null">取消主笔记</NButton></div>
        <KnowledgePicker v-if="lookupOpen" kind="NOTE" :disabled="!editable || noteIds.length >= 100" :exclude-ids="noteIds" @choose="addNote" />
      </template>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p><p v-if="reloadError" class="notice error" role="alert">{{ reloadError }}</p><p v-if="message && !dirty" class="success-text" role="status">{{ message }}</p>
      <div class="row-actions"><NButton v-if="writer.pending.value && !writer.busy.value" :disabled="!readable || reloading" @click="writer.retry">用原请求重试</NButton><NButton v-else type="primary" :loading="writer.busy.value" :disabled="!editable || !dirty || words(aliases).length > 20 || words(tags).length > 20" @click="save">保存整理信息</NButton><NButton :loading="reloading" :disabled="!readable || locked" @click="reload">重新读取</NButton></div>
    </template>
  </section>
</template>
