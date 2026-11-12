<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { errorText, read } from '../../api/client'
import type { LinkEntry, LinkHistory } from '../../api/schedulePreparation'
import { preparationLinkChangeLabels, preparationLinkHistoryPageValid } from '../../api/schedulePreparation'
import { useWorkspaceStore } from '../../state/workspace'
import { preparationPath, preparationSourceRoute } from './schedulePreparationUi'

const props = defineProps<{ entry: LinkEntry; disabled: boolean; refreshToken: number }>()
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore(), router = useRouter(), readable = computed(() => store.sessionActive && !props.disabled)
const entries = shallowRef<LinkHistory[]>([]), nextBefore = ref<number | null>(null), busy = ref(false), error = ref('')
let alive = true, generation = 0
watch([readable, () => store.sessionEpoch, () => props.entry.link.id, () => props.refreshToken], refresh, { immediate: true, flush: 'sync' })
onUnmounted(() => { alive = false; generation++ })
function refresh() { generation++; entries.value = []; nextBefore.value = null; busy.value = false; error.value = ''; if (readable.value) void load() }
async function load(before?: number) {
  if (!readable.value || busy.value) return
  const request = ++generation, epoch = store.sessionEpoch, link = props.entry.link
  const query = new URLSearchParams({ limit: '20' }); if (before !== undefined) query.set('before', String(before))
  busy.value = true; error.value = ''
  try {
    const value = await read<unknown>(`${preparationPath(link.eventId)}/preparation-links/${link.id}/history?${query}`)
    if (!alive || !readable.value || request !== generation || epoch !== store.sessionEpoch) return
    if (!preparationLinkHistoryPageValid(value, link.eventId, link.id, before, 20)
      || value.items.some(item => item.snapshot.kind !== link.kind || item.snapshot.sourceId !== link.sourceId || item.snapshot.createdAt !== link.createdAt)
      || before !== undefined && value.items[0]?.snapshot.revision !== before - 1) throw new Error('关联历史分页无法核对，请从最新记录重读。')
    entries.value = before === undefined ? value.items : [...entries.value, ...value.items]; nextBefore.value = value.nextBefore
  } catch (reason) { if (alive && request === generation && epoch === store.sessionEpoch) error.value = errorText(reason) }
  finally { if (alive && request === generation) busy.value = false }
}
</script>

<template>
  <section class="link-history" aria-label="准备关联变更历史"><header><div><h3>{{ entry.title }} · 关联历史</h3><p class="field-help">每条记录保留当时的关联与面试修订；题单版本入口指向该条历史固定的版本。</p></div><NButton text size="small" @click="emit('close')">收起</NButton></header>
    <div class="row-actions"><NButton size="small" :disabled="!readable || busy" @click="refresh">从最新记录重读</NButton></div>
    <p v-if="busy" class="field-help" role="status">正在读取关联历史…</p><p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <ol v-if="readable"><li v-for="item in entries" :key="item.snapshot.revision"><strong>{{ preparationLinkChangeLabels[item.change] }}</strong><p>关联修订 {{ item.snapshot.revision }} · 面试修订 {{ item.eventRevision }} · {{ item.createdAt }}</p><p v-if="item.snapshot.sourceVersionId" class="version-id">固定版本 {{ item.snapshot.sourceVersionId }}</p><div class="row-actions"><NButton text size="small" @click="router.push(preparationSourceRoute(item.snapshot, entry.sourceStatus.collection?.kind))">{{ item.snapshot.kind === 'COLLECTION' ? '查看该确切版本' : '查看来源当前状态' }}</NButton><NButton text size="small" @click="router.push({ name: 'schedule', query: { item: item.snapshot.eventId, version: String(item.eventRevision) } })">查看当时的面试安排</NButton></div></li></ol>
    <NButton v-if="nextBefore !== null" size="small" :disabled="!readable || busy" @click="load(nextBefore ?? undefined)">继续读取更早记录</NButton>
  </section>
</template>

<style scoped>
.link-history { display: grid; gap: 12px; padding: 16px; background: var(--surface-soft); border: 1px solid var(--line); border-radius: 6px; }.link-history header { display: flex; gap: 16px; justify-content: space-between; align-items: start; }h3 { font-size: 14px; }ol { list-style: none; margin: 0; padding: 0; }li { padding: 13px 0; border-top: 1px solid var(--line); }li strong { font-size: 13px; }li p { margin: 7px 0; color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }.version-id { font-family: var(--font-mono, monospace); }
</style>
