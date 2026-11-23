<script setup lang="ts">
import { computed, onUnmounted, reactive, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { useTrainingAccessStore } from '../../state/trainingAccess'
import { useWorkspaceStore } from '../../state/workspace'
import { useCommand } from '../../composables/useCommand'
import { errorText } from '../../api/client'
import type { Schema } from '../../api/client'

const access = useTrainingAccessStore()
const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const writer = useCommand()
const deferred = shallowRef<() => void>()
const lock = reactive({ dirty: false, locked: false })
let epoch = workspace.sessionEpoch
const locked = computed(() => writer.locked.value || Boolean(deferred.value))
watch(locked, value => { lock.locked = value; workspace.changes.set('training-access-command', lock) }, { immediate: true, flush: 'sync' })
onUnmounted(() => { if (workspace.changes.get('training-access-command') === lock) workspace.changes.delete('training-access-command') })
const blocked = computed(() => access.checking || access.challenge
  || route.name === 'data-management' && access.restricted
  || access.needsGuard(route.name, route.query.item, route.query.create))
function returnToTraining() {
  if (!access.active) return
  access.challenge = false
  void router.push({ name: 'training', query: { item: access.active.id } })
}
function confirmAssistance() {
  const current = access.active
  if (!current || locked.value || !workspace.sessionActive) return
  if (!window.confirm('确认查阅站内资料？这场训练后续提交将永久记录辅助条件，已经提交的答案保持原条件。')) return
  epoch = workspace.sessionEpoch
  void writer.run<Schema['TrainingSession']>(`/training/sessions/${current.id}/assistance`, 'POST', {
    expectedRevision: current.revision, reason: '在资料入口确认查阅站内材料。',
  }, () => {
    const apply = () => { access.challenge = false; window.dispatchEvent(new Event('studio:training-changed')) }
    if (epoch !== workspace.sessionEpoch || !workspace.sessionActive) deferred.value = apply
    else apply()
  })
}
function retry() { if (workspace.sessionActive) { epoch = workspace.sessionEpoch; void writer.retry() } }
function receive() { const apply = deferred.value; deferred.value = undefined; apply?.() }
</script>

<template>
  <section v-if="locked || (blocked && (access.checking || access.restricted))" class="training-access-notice" :class="{ 'access-loading': access.checking && !locked && !access.error }" role="status" aria-live="polite" :aria-busy="access.checking && !access.error">
    <template v-if="access.checking && !locked">
      <h2>{{ access.error ? '暂时无法打开工作区' : '正在确认训练状态…' }}</h2>
      <p>{{ access.error ? '未保存内容仍保留在当前页面，连接恢复后可继续。' : '确认后继续显示内容，未保存的编辑会保留。' }}</p>
      <p v-if="access.error" class="notice error" role="alert">{{ access.error }}</p>
      <NButton v-if="access.error" @click="access.refresh">重新检查连接</NButton>
      <div v-else class="access-skeleton" aria-hidden="true"><div /><div /><div /><div /></div>
    </template>
    <template v-else>
      <h2>先确认本次训练的资料使用条件</h2>
      <p>「{{ access.active?.title }}」正在{{ access.active?.status === 'PAUSED' ? '暂停' : '进行' }}。查看知识、参考、材料或旧训练前，请回到这场训练确认辅助；此后的提交会记录辅助条件。</p>
      <p class="muted">已提交答案的条件保持不变。系统只能记录站内操作，无法判断此前已看过的内容或外部资料。</p>
      <p v-if="writer.error.value" class="notice error" role="alert">{{ errorText(writer.error.value) }}</p>
      <p v-if="deferred" class="notice">确认结果已返回，请接收后继续。</p>
      <NButton v-if="deferred" type="primary" @click="receive">接收确认结果</NButton>
      <NButton v-else-if="writer.pending.value" :loading="writer.busy.value" @click="retry">用原请求恢复确认</NButton>
      <template v-else>
        <NButton type="primary" :disabled="locked" @click="returnToTraining">返回当前训练</NButton>
        <NButton :disabled="locked" @click="confirmAssistance">确认辅助并查看资料</NButton>
        <NButton :disabled="locked" @click="access.refresh">重新读取训练状态</NButton>
        <NButton v-if="route.name === 'training' && route.query.item === access.active?.id" :disabled="locked" @click="access.challenge = false">继续作答</NButton>
      </template>
    </template>
  </section>
</template>

<style scoped>
.training-access-notice { max-width: 820px; margin: 0; padding: 22px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); }
.training-access-notice h2 { font-size: 18px; }
.training-access-notice p { margin: 12px 0; font-size: 13px; color: var(--muted); }
.training-access-notice .n-button { margin: 6px 8px 0 0; }
.access-loading { max-width: none; padding: 4px 0; border: 0; background: none; }
.access-loading h2 { font-size: 16px; }
.access-skeleton { display: grid; gap: 14px; margin-top: 28px; padding: 22px; border: 1px solid var(--line); border-radius: 10px; }
.access-skeleton > div { height: 18px; max-width: 620px; border-radius: 4px; background: var(--surface-soft); }
.access-skeleton > div:first-child { width: 35%; }
.access-skeleton > div:last-child { width: 65%; }
@media (max-width: 560px) { .training-access-notice { padding: 16px; } .access-loading { padding: 0; } }
</style>
