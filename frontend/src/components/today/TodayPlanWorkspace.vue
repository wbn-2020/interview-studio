<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import type { TodayContinuation, TodaySourceRef } from '../../api/today'
import { todaySourceKey } from '../../api/today'
import { useWorkspaceStore } from '../../state/workspace'
import TodayPlacementDirectory from './TodayPlacementDirectory.vue'
import TodayPlacementEditor from './TodayPlacementEditor.vue'
import TodayBudgetEditor from './TodayBudgetEditor.vue'
import TodaySuggestionEditor from './TodaySuggestionEditor.vue'

const props = defineProps<{ localDate: string; zoneId: string; disabled: boolean; refreshToken: number; intent?: { source: TodaySourceRef; mode: 'edit' | 'candidate'; sequence: number } }>()
const emit = defineEmits<{ changed: []; open: [value: TodayContinuation] }>()
const store = useWorkspaceStore(), tab = ref<'directory' | 'budget' | 'suggestion'>('directory')
const selected = shallowRef<TodaySourceRef>(), addRequest = shallowRef<{ source: TodaySourceRef; sequence: number }>()
const budgetMounted = ref(false), suggestionMounted = ref(false)
const pending = computed(() => ['today-placement-editor', 'today-day-budget', 'today-placement-order', 'today-suggestion'].some(key => store.changes.get(key)?.locked))
let sequence = 0, handledIntent = -1
function edit(source: TodaySourceRef) {
  if (selected.value && todaySourceKey(selected.value) !== todaySourceKey(source) && !store.canLeave(['today-placement-editor'])) return
  selected.value = { ...source }; tab.value = 'directory'
}
function candidate(source: TodaySourceRef) { suggestionMounted.value = true; tab.value = 'suggestion'; addRequest.value = { source: { ...source }, sequence: ++sequence } }
function showBudget() { budgetMounted.value = true; tab.value = 'budget' }
function changed() { emit('changed') }
watch([() => props.intent, () => props.disabled], () => {
  if (props.disabled || !props.intent || handledIntent === props.intent.sequence) return
  handledIntent = props.intent.sequence
  if (props.intent.mode === 'edit') edit(props.intent.source)
  else candidate(props.intent.source)
}, { immediate: true })
</script>

<template>
  <section class="today-plan-workspace" aria-label="安排偏好、预算与建议"><nav aria-label="安排管理分区"><button :aria-current="tab === 'directory' ? 'page' : undefined" :disabled="disabled" @click="tab = 'directory'">安排目录与顺序</button><button :aria-current="tab === 'budget' ? 'page' : undefined" :disabled="disabled" @click="showBudget">每日分钟预算</button><button :aria-current="tab === 'suggestion' ? 'page' : undefined" :disabled="disabled" @click="suggestionMounted = true; tab = 'suggestion'">本次候选建议</button></nav>
    <p v-if="pending" class="notice" role="status">有安排操作正在保存或等待核对结果。请回到对应分区恢复原请求，避免重复提交。</p>
    <div v-show="tab === 'directory'" class="plan-directory-layout"><TodayPlacementDirectory :zone-id="zoneId" :disabled="disabled || tab !== 'directory'" :refresh-token="refreshToken" @edit="edit" @candidate="candidate" @open="emit('open', $event)" @changed="changed" /><TodayPlacementEditor v-if="selected" :key="todaySourceKey(selected)" :source="selected" :zone-id="zoneId" :local-date="localDate" :disabled="disabled || tab !== 'directory'" @close="selected = undefined" @candidate="candidate" @open="emit('open', $event)" @changed="changed" /><p v-else class="field-help">从今日清单点击“安排偏好”，或从目录选择一个来源。首次保存会建立稳定偏好身份。</p></div>
    <TodayBudgetEditor v-if="budgetMounted" v-show="tab === 'budget'" :local-date="localDate" :zone-id="zoneId" :disabled="disabled || tab !== 'budget'" @changed="changed" />
    <TodaySuggestionEditor v-if="suggestionMounted" v-show="tab === 'suggestion'" :local-date="localDate" :zone-id="zoneId" :disabled="disabled || tab !== 'suggestion'" :add-request="addRequest" @changed="changed" @edit="edit" @budget="showBudget" @open="emit('open', $event)" />
  </section>
</template>

<style scoped>
.today-plan-workspace { display: grid; gap: 18px; margin-top: 20px; }nav { display: flex; flex-wrap: wrap; gap: 8px 22px; border-bottom: 1px solid var(--line); }nav button { font-size: 13px; padding: 10px 0; color: var(--muted); border-bottom: 2px solid transparent; }nav button[aria-current=page] { border-bottom-color: var(--brand); color: var(--brand); }.plan-directory-layout { display: grid; gap: 20px; }.plan-directory-layout > .field-help { padding: 12px; }
</style>
