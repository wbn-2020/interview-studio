<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { NButton, NCheckbox, NSelect } from 'naive-ui'
import { ApiError, errorText, preview } from '../../api/client'
import type { AiManifest } from '../../api/ai'
import { aiCandidateManifestFingerprint, aiDate } from '../../api/ai'
import type { AiAuthorizationMatches, AiAuthorizationReference } from '../../api/aiAuthorization'
import { aiAuthorizationMatchesValid, aiAuthorizationPaths, observeAiAuthorizationChanges } from '../../api/aiAuthorization'
import { useWorkspaceStore } from '../../state/workspace'
import { useTrainingAccessStore } from '../../state/trainingAccess'

const props = defineProps<{ manifest: AiManifest; manifestHash?: string; disabled?: boolean; checked: boolean; authorization?: AiAuthorizationReference }>()
const emit = defineEmits<{ 'update:checked': [value: boolean]; 'update:authorization': [value: AiAuthorizationReference | undefined] }>()
const store = useWorkspaceStore(), access = useTrainingAccessStore()
const mode = ref<'ONCE' | 'CONTINUING'>('ONCE'), selectedId = ref<string>(), active = ref(true), loading = ref(false), problem = ref('')
const matches = shallowRef<AiAuthorizationMatches>(), now = ref(Date.now())
const identity = computed(() => JSON.stringify([props.manifestHash ?? null, aiCandidateManifestFingerprint(props.manifest)]))
const readable = computed(() => active.value && store.sessionActive)
const selected = computed(() => matches.value?.items.find(item => item.id === selectedId.value))
const usable = computed(() => selected.value?.state === 'ACTIVE' && selected.value.effectiveState === 'ACTIVE' && Date.parse(selected.value.expiresAt) > now.value)
const options = computed(() => matches.value?.items.map(item => ({ value: item.id, label: `${item.id.slice(0, 8)} · 至 ${aiDate(item.expiresAt)}` })) ?? [])
let alive = true, sequence = 0, timer: number | undefined, stopObserving: (() => void) | undefined

function publish(clearConfirmation = true) {
  if (clearConfirmation) emit('update:checked', false)
  emit('update:authorization', mode.value === 'CONTINUING' && usable.value && selected.value
    ? { id: selected.value.id, expectedRevision: selected.value.revision } : undefined)
}
function invalidate() { sequence++; loading.value = false; selectedId.value = undefined; matches.value = undefined; problem.value = ''; publish() }
function changed() { invalidate(); if (readable.value && !props.disabled) void load() }
watch(identity, changed, { flush: 'sync' })
watch([readable, () => store.sessionEpoch, () => access.active?.id, () => access.active?.mode, () => access.active?.materialAccess, () => access.restricted, () => access.challenge], changed, { flush: 'sync' })
watch(() => props.disabled, disabled => { if (!disabled && readable.value && !matches.value && !loading.value) void load() })
watch(usable, () => { if (mode.value === 'CONTINUING') publish() }, { flush: 'sync' })
onMounted(() => {
  publish(); stopObserving = observeAiAuthorizationChanges(changed)
  timer = window.setInterval(() => { now.value = Date.now() }, 1000)
  if (readable.value && !props.disabled) void load()
})
onActivated(() => { active.value = true })
onDeactivated(() => { active.value = false })
onUnmounted(() => { alive = false; sequence++; if (timer !== undefined) window.clearInterval(timer); stopObserving?.() })

async function load() {
  if (!readable.value || props.disabled || loading.value) return
  const epoch = store.sessionEpoch, stamp = identity.value, ticket = ++sequence
  const manifest = JSON.parse(JSON.stringify(props.manifest)) as AiManifest, expectedHash = props.manifestHash
  loading.value = true; problem.value = ''; selectedId.value = undefined; matches.value = undefined
  // 授权目录是辅助读取，迟到结果不能撤销用户刚勾选的单次确认。
  if (mode.value === 'CONTINUING') publish()
  const current = () => alive && readable.value && store.sessionEpoch === epoch && identity.value === stamp && sequence === ticket
  try {
    const value = await preview<AiAuthorizationMatches>(aiAuthorizationPaths.matches, { manifest })
    if (!current()) return
    if (!aiAuthorizationMatchesValid(value, manifest, expectedHash)) throw new ApiError(502, 'AI_AUTHORIZATION_MATCHES_INVALID', '授权匹配结果与本次清单不一致，请重新核对。')
    matches.value = value; now.value = Date.now()
  } catch (error) { if (current()) problem.value = errorText(error) }
  finally { if (current()) loading.value = false }
}
function changeMode(value: 'ONCE' | 'CONTINUING') { if (!props.disabled) { mode.value = value; publish() } }
function choose(value: string) { if (!props.disabled) { selectedId.value = value; publish() } }
function confirm(value: boolean) {
  now.value = Date.now()
  if (props.disabled || !readable.value || mode.value === 'CONTINUING' && !usable.value) return
  publish(false)
  emit('update:checked', value)
}
</script>

<template>
  <section class="authorization-choice" aria-label="本次模型请求的授权方式">
    <label>授权方式<NSelect :value="mode" :disabled="disabled || !readable" :options="[{ label: '仅授权本次请求', value: 'ONCE' }, { label: '使用已确认的持续范围授权', value: 'CONTINUING' }]" aria-label="选择单次或持续范围授权" @update:value="changeMode" /></label>
    <template v-if="mode === 'CONTINUING'">
      <label>匹配这些固定版本与片段的授权<NSelect :value="selectedId" :disabled="disabled || !readable || loading" :options="options" placeholder="请明确选择一条授权" aria-label="选择适用的持续范围授权" @update:value="choose" /></label>
      <p v-if="selected && usable" class="field-help">使用授权 {{ selected.id }}；有效至 {{ aiDate(selected.expiresAt) }}。本次仍只发送上方清单，仍需手动提交。</p>
      <p v-else-if="!loading && matches" class="notice" role="status">{{ matches.items.length ? '请选择一条当前有效授权；先前选择已失效时不会自动换用其他授权。' : '没有匹配的有效授权。可明确切换为单次授权，或从已保存任务的完整清单创建持续授权。' }}</p>
    </template>
    <p v-if="loading" class="field-help" role="status">正在核对持续授权适用范围…</p>
    <p v-if="problem" class="notice error" role="alert">{{ problem }} 单次授权仍可主动选择并确认。</p>
    <NButton text size="small" :disabled="disabled || !readable || loading" @click="load">刷新适用授权</NButton>
    <NCheckbox :checked="checked" :disabled="disabled || !readable || mode === 'CONTINUING' && !usable" @update:checked="confirm"><slot /></NCheckbox>
    <p class="field-help">持续授权限定这些固定版本、片段、用途与供应商配置；更改问题或指令仍需主动预览提交。新增或替换来源须重新确认，服务重启后旧授权不会自动生效。</p>
  </section>
</template>

<style scoped>
.authorization-choice { display: grid; gap: 12px; min-width: 0; padding-top: 14px; border-top: 1px solid var(--line); }
label { display: grid; gap: 8px; min-width: 0; font-size: 13px; }p { margin: 0; overflow-wrap: anywhere; }.n-button { justify-self: start; }
</style>
