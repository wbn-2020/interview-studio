<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import type { InterviewEvent } from '../../api/schedule'
import type { LinkStatus } from '../../api/schedulePreparation'
import { errorText } from '../../api/client'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { preparationSourceRoute, preparationStateLabel, usePreparationLinks } from './schedulePreparationUi'
import RIcon from '../../replica/RIcon.vue'

const props = defineProps<{ event: InterviewEvent }>()
const route = useRoute(), router = useRouter(), store = useWorkspaceStore(), access = useTrainingAccessStore()
const active = ref(true), visible = ref(document.visibilityState === 'visible'), status = ref<LinkStatus | ''>('ACTIVE')
const enabled = computed(() => active.value && visible.value && store.sessionActive && !access.checking && !access.challenge && !access.restricted && !store.changes.get('training-access-command')?.locked)
const directory = usePreparationLinks(computed(() => props.event), enabled, status, computed(() => 0))
function visibility() { visible.value = document.visibilityState === 'visible' }
onMounted(() => document.addEventListener('visibilitychange', visibility))
onActivated(() => { active.value = true; visibility() })
onDeactivated(() => { active.value = false })
onUnmounted(() => document.removeEventListener('visibilitychange', visibility))
</script>

<template>
  <section class="schedule-preparation-preview">
    <h2><RIcon name="info" :size="29" />改期前预览</h2><p>只移动未开始且未锁定日期的准备任务</p><hr /><h3>关联任务</h3>
    <p v-if="directory.busy.value" class="field-help" role="status">正在读取准备关联…</p><p v-if="directory.error.value" class="notice error">{{ errorText(directory.error.value) }}</p>
    <template v-if="enabled"><button v-for="entry in directory.entries.value" :key="entry.link.id" class="preparation-preview-row" @click="router.push(preparationSourceRoute(entry.link, entry.sourceStatus.collection?.kind))"><RIcon name="circle" :size="28" /><RIcon :name="entry.link.kind === 'COLLECTION' ? 'route' : entry.link.kind === 'REVIEW_ACTION' ? 'refresh-cw' : 'file-text'" :size="27" /><span><strong>{{ entry.title }}</strong><small>{{ preparationStateLabel(entry.sourceStatus, entry.pendingCount) }}</small></span><RIcon name="chevron-right" :size="23" /></button></template>
    <p v-if="enabled && directory.page.value && !directory.entries.value.length" class="field-help">这次面试尚无有效准备关联。</p><NButton v-if="directory.page.value?.nextCursor" text :disabled="!enabled" @click="directory.load(directory.page.value?.nextCursor ?? undefined)">继续读取关联</NButton>
    <NButton block :disabled="!enabled || event.status !== 'PLANNED'" @click="router.push({ name: 'schedule', query: { ...route.query, item: event.id, panel: 'plan' } })"><RIcon name="arrow-up-down" :size="24" />调整安排</NButton><p class="field-help">具体日期与可移动条件在生成改期预览后核对。</p>
  </section>
</template>

<style scoped>
.schedule-preparation-preview { display: grid; align-content: start; gap: 21px; padding: 27px; border: 1px solid var(--line); border-radius: 21px; background: var(--surface); min-width: 0; }.schedule-preparation-preview h2 { display: flex; gap: 14px; align-items: center; font-size: 27px; margin: 0; }.schedule-preparation-preview > p { font-size: 19px; line-height: 1.65; color: var(--muted); }.schedule-preparation-preview > p.field-help { font-size: 14px; }.schedule-preparation-preview h3 { margin: 0; font-size: 23px; }.schedule-preparation-preview hr { width: 100%; height: 1px; margin: 3px 0; border: 0; background: var(--line); }.preparation-preview-row { display: flex; gap: 18px; align-items: center; padding: 15px 0; width: 100%; border-bottom: 1px solid var(--line); text-align: left; }.preparation-preview-row > svg { flex: none; }.preparation-preview-row > svg:first-child { color: var(--muted); }.preparation-preview-row > svg:last-child { margin-left: auto; color: var(--muted); }.preparation-preview-row span { min-width: 0; }.preparation-preview-row strong { font-size: 21px; font-weight: 500; overflow-wrap: anywhere; }.preparation-preview-row small { display: block; margin-top: 8px; font-size: 15px; color: var(--muted); }.schedule-preparation-preview :deep(.n-button__content) { gap: 12px; }.schedule-preparation-preview :deep(.n-button:not(.n-button--text-type)) { margin-top: 5px; height: 59px; font-size: 22px; border-radius: 18px; }
</style>
