<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { InterviewEvent, ScheduleMaterial } from '../../api/schedule'
import { formatScheduleTime, scheduleSourceLabels, scheduleStatusLabels } from '../../api/schedule'
const props = defineProps<{ event: InterviewEvent }>()
const router = useRouter()
const meetingLink = computed(() => {
  try { const url = new URL(props.event.meetingUrl); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined } catch { return undefined }
})
function openMaterial(value: ScheduleMaterial) {
  void router.push(value.kind === 'KNOWLEDGE'
    ? { name: 'knowledge', query: { item: value.itemId, version: value.versionId } }
    : { name: 'prepare', query: { kind: value.kind, item: value.itemId, version: value.versionId } })
}
</script>

<template>
  <article class="schedule-readout">
    <h3>{{ event.title }}</h3>
    <p><span class="status-pill">{{ scheduleStatusLabels[event.status] }}</span><span v-if="event.round"> {{ event.round }}</span></p>
    <dl><div><dt>当地时间</dt><dd>{{ event.time.localStart.replace('T', ' ') }} · {{ event.time.zoneId }} · UTC{{ event.time.offset === 'Z' ? '+00:00' : event.time.offset }}</dd></div><div><dt>持续时间</dt><dd>{{ event.time.durationMinutes }} 分钟，至 {{ formatScheduleTime(event.time.endAt, event.time.zoneId) }}</dd></div><div><dt>提醒</dt><dd>{{ formatScheduleTime(event.remindAt, event.time.zoneId) }} · 提前 {{ event.remindBeforeMinutes }} 分钟{{ event.reminderAcknowledgedAt ? ' · 已确认看过' : '' }}</dd></div><div v-if="event.location"><dt>地点</dt><dd>{{ event.location }}</dd></div><div v-if="meetingLink"><dt>会议链接</dt><dd><a class="text-link" :href="meetingLink" target="_blank" rel="noopener noreferrer">{{ event.meetingUrl }}</a></dd></div><div v-if="event.targetId"><dt>目标</dt><dd><button class="text-link" @click="router.push({ name: 'prepare', query: { kind: 'TARGET', item: event.targetId, version: event.targetVersionId ?? undefined } })">查看关联目标版本</button><span v-if="event.targetState" class="status-pill">{{ scheduleSourceLabels[event.targetState] }}</span></dd></div></dl>
    <section v-if="event.notes"><h4>备注</h4><p class="event-notes">{{ event.notes }}</p></section>
    <section v-if="event.materialReferences.length"><h4>准备材料</h4><ul><li v-for="material in event.materialReferences" :key="material.versionId"><button class="text-link" @click="openMaterial(material)">{{ material.title }} · v{{ material.number }}</button><span class="status-pill">{{ scheduleSourceLabels[material.state] }}</span></li></ul></section>
  </article>
</template>

<style scoped>
.schedule-readout { display: grid; gap: 15px; min-width: 0; }
.schedule-readout h3 { font-size: 18px; overflow-wrap: anywhere; }
.schedule-readout h4 { margin-bottom: 8px; font-size: 13px; }
.schedule-readout dl { display: grid; gap: 10px; margin: 0; }
.schedule-readout dl > div { display: grid; grid-template-columns: 75px minmax(0, 1fr); gap: 10px; font-size: 12px; }
.schedule-readout dt { color: var(--muted); }
.schedule-readout dd { margin: 0; overflow-wrap: anywhere; }
.schedule-readout dd .status-pill { margin-left: 8px; }
.event-notes { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.schedule-readout ul { display: grid; gap: 9px; list-style: none; padding: 0; margin: 0; }
.schedule-readout li { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; }
.schedule-readout li button { min-width: 0; text-align: left; overflow-wrap: anywhere; }
</style>
