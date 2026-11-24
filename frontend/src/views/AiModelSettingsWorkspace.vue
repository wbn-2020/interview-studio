<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NCheckbox, NInput, NInputNumber, NSelect, NSwitch } from 'naive-ui'
import { ApiError, currentTrainingAccessEpoch, errorText } from '../api/client'
import { queryClient } from '../api/queryClient'
import {
  aiConfigFormOf, aiConfigMaxTokensFieldLabels, aiConfigProtocolLabels, aiConfigSaveProblems,
  aiConfigSaveRequest, aiConfigSourceLabels, aiConfigTestErrorLabels, aiConfigTestProblems,
  aiConfigTestRequest, checkedAiConfigSaveReceipt, defaultAiConfigForm, formatAiConfigMicros,
  hasAiConfigProblems, readAiConfig, sameAiConfigForm, testAiConfig,
} from '../api/aiConfig'
import type {
  AiConfigForm, AiConfigProblems, AiConfigProblemField, AiConfigStatus, AiConfigTestResult,
} from '../api/aiConfig'
import { useCommand } from '../composables/useCommand'
import { useTrainingAccessStore } from '../state/trainingAccess'
import { useWorkspaceStore } from '../state/workspace'

const store = useWorkspaceStore()
const access = useTrainingAccessStore()
const writer = useCommand()
const draft = reactive<AiConfigForm>({ ...defaultAiConfigForm })
const apiKey = ref('')
const baseline = shallowRef<AiConfigStatus>(), costAcknowledged = ref(false)
const testing = ref(false), refreshing = ref(false)
const message = ref(''), saveConflict = ref(''), testError = ref('')
const testResult = shallowRef<AiConfigTestResult>()
const maximumSafeInteger = Number.MAX_SAFE_INTEGER
const form = computed(() => draft)
const writerBusy = computed(() => writer.busy.value)
const writerPending = computed(() => Boolean(writer.pending.value))
const writerLocked = computed(() => writer.locked.value)
const writerError = computed(() => writer.error.value)
let alive = true, initialized = false, testSequence = 0
const settingsKey = computed(() => ['ai-config', store.sessionEpoch, 'status'] as const)
const readable = computed(() => store.sessionActive && !access.checking && !access.challenge && !access.restricted
  && !store.changes.get('training-access-command')?.locked)

const query = useQuery({
  queryKey: settingsKey,
  enabled: readable,
  staleTime: 30_000,
  retry: false,
  queryFn: async () => {
    const epoch = store.sessionEpoch, trainingEpoch = currentTrainingAccessEpoch()
    const value = await readAiConfig()
    if (!alive || !readable.value || epoch !== store.sessionEpoch || trainingEpoch !== currentTrainingAccessEpoch())
      throw new ApiError(0, 'AI_CONFIG_CONTEXT_CHANGED', '会话或训练访问条件已变化，请重新读取配置。')
    return value
  },
})

const busy = computed(() => writer.locked.value || testing.value || refreshing.value || query.isFetching.value)
const editable = computed(() => readable.value && Boolean(baseline.value?.canEdit) && !busy.value)
const dirty = computed(() => {
  const status = baseline.value
  if (!status) return false
  return !sameAiConfigForm(draft, aiConfigFormOf(status)) || apiKey.value.length > 0
})
const saveProblems = computed<AiConfigProblems>(() => {
  const status = baseline.value
  return status ? aiConfigSaveProblems(draft, status, apiKey.value) : {}
})
const testProblems = computed<AiConfigProblems>(() => {
  const status = baseline.value
  return status ? aiConfigTestProblems(draft, status, apiKey.value) : {}
})
const shownSaveProblems = computed(() => saveProblems.value)
const canSave = computed(() => editable.value && !hasAiConfigProblems(saveProblems.value))
const canTest = computed(() => readable.value && Boolean(baseline.value) && !busy.value
  && costAcknowledged.value && !hasAiConfigProblems(testProblems.value))
const endpointChangedWithoutKey = computed(() => Boolean(baseline.value)
  && draft.endpoint.trim() !== baseline.value?.endpoint.trim() && !apiKey.value.trim())
const keyPlaceholder = computed(() => baseline.value?.apiKeyConfigured
  ? '已保存 API Key；留空保留原密钥，输入新密钥仅用于替换'
  : '填写 API Key；保存后不会回显')
const sourceNotice = computed(() => {
  if (!baseline.value) return ''
  if (!baseline.value.canEdit) return baseline.value.source === 'ENV'
    ? '当前生效配置来自环境变量，且服务器未开放 Web 编辑。请由服务器管理员调整环境配置。'
    : '数据库配置当前不可编辑，请检查服务器 AI 配置主密钥。'
  return baseline.value.source === 'ENV'
    ? '当前生效配置来自环境变量；保存后数据库配置将覆盖当前 ENV 生效值。'
    : '当前生效配置来自数据库；保存会递增修订并使旧配置版本失效。'
})
const statusSummary = computed(() => {
  if (!baseline.value) return '尚未读取'
  if (!baseline.value.enabled) return '已停用'
  return baseline.value.configured ? '已启用 · 配置完整' : '已启用 · 配置未完成'
})
const maxTokensOptions = computed(() => draft.protocol === 'RESPONSES'
  ? [{ label: aiConfigMaxTokensFieldLabels.max_output_tokens, value: 'max_output_tokens' as const }]
  : [
    { label: aiConfigMaxTokensFieldLabels.max_tokens, value: 'max_tokens' as const },
    { label: aiConfigMaxTokensFieldLabels.max_completion_tokens, value: 'max_completion_tokens' as const },
  ])
const testBlockReason = computed(() => Object.values(testProblems.value).find(Boolean)
  ?? (costAcknowledged.value ? '' : '请先确认本次测试可能产生费用。'))
const testResultText = computed(() => {
  const result = testResult.value
  if (!result) return ''
  const http = result.status ? `供应商 HTTP ${result.status}` : '未收到供应商 HTTP 状态'
  const latency = `${result.latencyMs.toLocaleString('zh-CN')} ms`
  if (result.ok) return `连接测试成功：${http}，耗时 ${latency}。本次测试仍可能产生费用。`
  const reason = result.errorCode ? aiConfigTestErrorLabels[result.errorCode] ?? '供应商未返回可识别的成功响应。' : '连接测试未通过。'
  return `连接测试未通过：${http}，耗时 ${latency}。${reason} 本次测试仍可能产生费用。`
})
const protocolOptions = [
  { label: aiConfigProtocolLabels.CHAT_COMPLETIONS, value: 'CHAT_COMPLETIONS' as const },
  { label: aiConfigProtocolLabels.RESPONSES, value: 'RESPONSES' as const },
]
const changeState = reactive({ dirty: false, locked: false })

watch(() => store.sessionEpoch, () => {
  initialized = false; testSequence++; testing.value = false; baseline.value = undefined; testResult.value = undefined
  Object.assign(draft, defaultAiConfigForm); apiKey.value = ''; costAcknowledged.value = false
  message.value = ''; saveConflict.value = ''; testError.value = ''
}, { flush: 'sync' })
watch(query.data, value => {
  if (!alive || !value) return
  if (baseline.value && value.revision < baseline.value.revision) return
  const wasDirty = dirty.value, first = !initialized
  baseline.value = value
  // 后台刷新只推进服务器基线；本地输入和新密钥不能被迟到响应覆盖。
  if (first || !wasDirty) Object.assign(draft, aiConfigFormOf(value))
  initialized = true
}, { immediate: true })
watch([draft, apiKey], () => {
  costAcknowledged.value = false; testResult.value = undefined; testError.value = ''
  if (!writer.locked.value && !testing.value) {
    writer.error.value = undefined; saveConflict.value = ''; message.value = ''
  }
}, { deep: true, flush: 'sync' })
watch([dirty, writer.locked, testing], ([isDirty, isLocked, isTesting]) => {
  changeState.dirty = isDirty; changeState.locked = isLocked || isTesting
  if (changeState.dirty || changeState.locked) store.changes.set('ai-model-config', changeState)
  else if (store.changes.get('ai-model-config') === changeState) store.changes.delete('ai-model-config')
}, { immediate: true, flush: 'sync' })
onUnmounted(() => {
  alive = false; testSequence++
  if (store.changes.get('ai-model-config') === changeState) store.changes.delete('ai-model-config')
})

function fieldError(field: AiConfigProblemField): string | undefined {
  return shownSaveProblems.value[field]
}
function setProtocol(value: unknown) {
  if (!editable.value || value !== 'CHAT_COMPLETIONS' && value !== 'RESPONSES') return
  draft.protocol = value
  if (value === 'RESPONSES') draft.maxTokensField = 'max_output_tokens'
  else if (draft.maxTokensField !== 'max_tokens' && draft.maxTokensField !== 'max_completion_tokens') draft.maxTokensField = 'max_tokens'
}
function setMaxTokensField(value: unknown) {
  if (!editable.value || value !== 'max_tokens' && value !== 'max_completion_tokens' && value !== 'max_output_tokens') return
  if (draft.protocol === 'RESPONSES' ? value === 'max_output_tokens' : value !== 'max_output_tokens')
    draft.maxTokensField = value
}
function updateCurrency(value: string) { if (editable.value) draft.currency = value.toUpperCase() }
async function refreshStatus(notify = true) {
  if (!readable.value || writer.locked.value || testing.value) return false
  refreshing.value = true
  try {
    await query.refetch()
    if (query.error.value) throw query.error.value
    if (notify && alive) message.value = dirty.value ? '已读取最新状态；当前输入已保留。' : '已读取最新状态。'
    return true
  } catch {
    return false
  } finally { refreshing.value = false }
}
async function handleSaveFailure() {
  const reason = writer.error.value
  if (!alive || !(reason instanceof ApiError) || reason.status !== 409) return
  const refreshed = await refreshStatus(false)
  if (!alive) return
  saveConflict.value = refreshed
    ? reason.code === 'AI_CONFIG_REVISION_CONFLICT'
      ? `检测到配置修订冲突，已刷新到服务器修订 ${baseline.value?.revision ?? '未知'}；当前输入已保留，请核对后再次保存。`
      : '服务器拒绝了本次保存并返回冲突；已刷新最新状态，当前输入已保留。'
    : '服务器返回冲突，但最新状态刷新失败；当前输入已保留，请恢复连接后重试读取。'
}
async function save() {
  if (!baseline.value || writer.locked.value || testing.value) return
  if (!canSave.value) return
  const epoch = store.sessionEpoch
  const request = aiConfigSaveRequest(draft, baseline.value, apiKey.value)
  writer.error.value = undefined; saveConflict.value = ''; message.value = ''
  try {
    await writer.run<unknown>('/ai/config', 'PUT', request, value => {
      const receipt = checkedAiConfigSaveReceipt(value, request)
      if (!alive || epoch !== store.sessionEpoch) return
      queryClient.setQueryData(settingsKey.value, receipt)
      if (apiKey.value === request.apiKey) apiKey.value = ''
      message.value = `配置已保存，当前修订 ${receipt.revision}。API Key 不会回显。`
    })
  } catch (reason) { writer.error.value = reason }
  await handleSaveFailure()
}
async function retrySave() {
  if (!readable.value || !writer.pending.value || writer.busy.value) return
  saveConflict.value = ''; message.value = ''
  await writer.retry()
  await handleSaveFailure()
}
function adoptServer() {
  if (!readable.value || !baseline.value || busy.value) return
  if (dirty.value && !window.confirm('采用服务器配置会丢弃当前未保存输入和刚填写的 API Key，确定继续？')) return
  Object.assign(draft, aiConfigFormOf(baseline.value)); apiKey.value = ''
  saveConflict.value = ''
  message.value = '已采用服务器配置，未保存的本地输入已丢弃。'
}
async function runTest() {
  if (!baseline.value || !canTest.value) return
  const epoch = store.sessionEpoch, sequence = ++testSequence
  testing.value = true; testError.value = ''; testResult.value = undefined
  try {
    const result = await testAiConfig(aiConfigTestRequest(draft, baseline.value, apiKey.value))
    if (alive && sequence === testSequence && epoch === store.sessionEpoch) testResult.value = result
  } catch (reason) {
    if (alive && sequence === testSequence && epoch === store.sessionEpoch) testError.value = errorText(reason)
  } finally {
    if (alive && sequence === testSequence && epoch === store.sessionEpoch) {
      testing.value = false; costAcknowledged.value = false
    }
  }
}
</script>

<template>
  <section class="ai-model-settings" aria-labelledby="ai-model-settings-title">
    <header class="page-heading">
      <div><h1 id="ai-model-settings-title">AI 模型</h1><p class="muted">配置服务端文字模型、价格与请求边界。API Key 只写不回显。</p></div>
      <NButton :disabled="!readable || writerLocked || testing" :loading="refreshing || query.isFetching.value" @click="refreshStatus()">刷新状态</NButton>
    </header>
    <nav class="settings-navigation" aria-label="设置分类">
      <RouterLink to="/settings" :class="{ selected: $route.path === '/settings' }" :aria-current="$route.path === '/settings' ? 'page' : undefined">外观与阅读</RouterLink>
      <RouterLink to="/settings/ai" :class="{ selected: $route.path === '/settings/ai' }" :aria-current="$route.path === '/settings/ai' ? 'page' : undefined">AI 模型</RouterLink>
      <RouterLink to="/settings/data" :class="{ selected: $route.path === '/settings/data' }" :aria-current="$route.path === '/settings/data' ? 'page' : undefined">数据与隐私</RouterLink>
      <RouterLink to="/settings/security" :class="{ selected: $route.path === '/settings/security' }" :aria-current="$route.path === '/settings/security' ? 'page' : undefined">安全与授权</RouterLink>
    </nav>

    <p v-if="!readable" class="notice" role="status">会话或训练访问条件暂不可用，配置详情已遮挡；当前页面输入会保留。</p>
    <template v-else-if="baseline">
      <dl class="status-summary" aria-label="当前 AI 配置状态">
        <div><dt>配置来源</dt><dd>{{ aiConfigSourceLabels[baseline.source] }}</dd></div>
        <div><dt>修订</dt><dd>{{ baseline.revision }}</dd></div>
        <div><dt>编辑状态</dt><dd>{{ baseline.canEdit ? '可编辑' : '只读' }}</dd></div>
        <div><dt>运行状态</dt><dd>{{ statusSummary }}</dd></div>
        <div><dt>API Key</dt><dd>{{ baseline.apiKeyConfigured ? '已配置' : '未配置' }}</dd></div>
      </dl>
      <p class="notice" role="status">{{ sourceNotice }}</p>
    </template>
    <p v-else-if="query.isPending.value" class="notice" role="status">正在读取 AI 配置…</p>
    <p v-if="query.error.value" class="notice error" role="alert">{{ errorText(query.error.value) }} <NButton text :disabled="!readable || refreshing" @click="refreshStatus()">重新读取</NButton></p>

    <div v-show="readable && baseline" :inert="!readable || !baseline || undefined" class="settings-layout">
      <form class="config-form" @submit.prevent="save">
        <section class="config-section" aria-labelledby="connection-title">
          <header><div><p class="section-kicker">连接</p><h2 id="connection-title">供应商与端点</h2></div></header>
          <div class="switch-row"><div><strong>启用 AI</strong><p class="field-help">停用时仍可保存配置；启用前必须完整填写端点、模型、版本、价格、预算和有效密钥。</p></div><NSwitch v-model:value="form.enabled" :disabled="!editable" aria-label="启用 AI" /></div>
          <div class="field-grid">
            <label class="field"><span>供应商</span><NInput :value="form.provider" :disabled="!editable" :maxlength="120" placeholder="例如 OpenAI 兼容供应商" @update:value="form.provider = $event" /><small v-if="fieldError('provider')" class="field-error">{{ fieldError('provider') }}</small></label>
            <label class="field"><span>协议</span><NSelect :value="form.protocol" :options="protocolOptions" :disabled="!editable" @update:value="setProtocol" /><small v-if="fieldError('protocol')" class="field-error">{{ fieldError('protocol') }}</small></label>
            <label class="field field-wide"><span>完整 HTTPS 端点</span><NInput :value="form.endpoint" :disabled="!editable" :maxlength="2048" placeholder="https://api.example.com/v1" :input-props="{ spellcheck: false, autocapitalize: 'none' }" @update:value="form.endpoint = $event" /><small class="field-help">填写供应商 API 的完整地址，不能包含账号、查询参数或片段；服务器会拒绝非公网地址。</small><small v-if="fieldError('endpoint')" class="field-error">{{ fieldError('endpoint') }}</small></label>
            <label class="field"><span>模型</span><NInput :value="form.model" :disabled="!editable" :maxlength="200" placeholder="供应商模型 ID" :input-props="{ spellcheck: false }" @update:value="form.model = $event" /><small v-if="fieldError('model')" class="field-error">{{ fieldError('model') }}</small></label>
            <label class="field"><span>API Key</span><NInput v-model:value="apiKey" type="password" :disabled="!editable" :maxlength="8192" :placeholder="keyPlaceholder" :input-props="{ autocomplete: 'new-password', autocapitalize: 'none', spellcheck: false }" /><small class="field-help">留空表示保留服务器已有密钥。新密钥不会在保存或刷新后回显。</small><small v-if="fieldError('apiKey')" class="field-error">{{ fieldError('apiKey') }}</small></label>
          </div>
        </section>

        <section class="config-section" aria-labelledby="pricing-title">
          <header><div><p class="section-kicker">成本</p><h2 id="pricing-title">版本与价格</h2></div></header>
          <div class="field-grid">
            <label class="field"><span>配置版本</span><NInput :value="form.configVersion" :disabled="!editable" :maxlength="80" placeholder="例如 2026-11-24.1" @update:value="form.configVersion = $event" /><small class="field-help">轮换 API Key 时必须同时修改配置版本。</small><small v-if="fieldError('configVersion')" class="field-error">{{ fieldError('configVersion') }}</small></label>
            <label class="field"><span>价格版本</span><NInput :value="form.priceVersion" :disabled="!editable" :maxlength="80" placeholder="例如 2026-11-24" @update:value="form.priceVersion = $event" /><small v-if="fieldError('priceVersion')" class="field-error">{{ fieldError('priceVersion') }}</small></label>
            <label class="field"><span>币种</span><NInput :value="form.currency" :disabled="!editable" :maxlength="3" placeholder="CNY / USD" :input-props="{ autocapitalize: 'characters', spellcheck: false }" @update:value="updateCurrency" /><small v-if="fieldError('currency')" class="field-error">{{ fieldError('currency') }}</small></label>
            <label class="field"><span>输入单价（微单位／百万 tokens）</span><NInputNumber v-model:value="form.inputPriceMicrosPerMillion" :disabled="!editable" :min="-1" :max="maximumSafeInteger" :precision="0" :step="1000" /><small class="field-help">折合 {{ formatAiConfigMicros(form.inputPriceMicrosPerMillion, form.currency) }}／百万输入 tokens；-1 表示未知。</small><small v-if="fieldError('inputPriceMicrosPerMillion')" class="field-error">{{ fieldError('inputPriceMicrosPerMillion') }}</small></label>
            <label class="field"><span>输出单价（微单位／百万 tokens）</span><NInputNumber v-model:value="form.outputPriceMicrosPerMillion" :disabled="!editable" :min="-1" :max="maximumSafeInteger" :precision="0" :step="1000" /><small class="field-help">折合 {{ formatAiConfigMicros(form.outputPriceMicrosPerMillion, form.currency) }}／百万输出 tokens；-1 表示未知。</small><small v-if="fieldError('outputPriceMicrosPerMillion')" class="field-error">{{ fieldError('outputPriceMicrosPerMillion') }}</small></label>
            <label class="field field-wide"><span>日预算（微单位）</span><NInputNumber v-model:value="form.dailyBudgetMicros" :disabled="!editable" :min="0" :max="maximumSafeInteger" :precision="0" :step="100000" /><small class="field-help">折合 {{ formatAiConfigMicros(form.dailyBudgetMicros, form.currency) }}／天。1,000,000 微单位等于 1 个币种单位，例如 2.5 元应填写 2,500,000。</small><small v-if="fieldError('dailyBudgetMicros')" class="field-error">{{ fieldError('dailyBudgetMicros') }}</small></label>
          </div>
        </section>

        <section class="config-section" aria-labelledby="request-title">
          <header><div><p class="section-kicker">请求</p><h2 id="request-title">输出与兼容选项</h2></div></header>
          <div class="field-grid">
            <label class="field"><span>最大输出 tokens</span><NInputNumber v-model:value="form.maxOutputTokens" :disabled="!editable" :min="0" :max="16384" :precision="0" :step="256" /><small class="field-help">范围 0–16,384；启用 AI 时必须大于 0。</small><small v-if="fieldError('maxOutputTokens')" class="field-error">{{ fieldError('maxOutputTokens') }}</small></label>
            <label class="field"><span>超时（秒）</span><NInputNumber v-model:value="form.timeoutSeconds" :disabled="!editable" :min="0" :max="120" :precision="0" /><small class="field-help">范围 0–120 秒；连接测试要求 1–120 秒。</small><small v-if="fieldError('timeoutSeconds')" class="field-error">{{ fieldError('timeoutSeconds') }}</small></label>
            <label class="field field-wide"><span>max tokens 字段</span><NSelect :value="form.maxTokensField" :options="maxTokensOptions" :disabled="!editable" @update:value="setMaxTokensField" /><small class="field-help">按供应商协议选择实际请求字段；Responses 固定使用 max_output_tokens。</small><small v-if="fieldError('maxTokensField')" class="field-error">{{ fieldError('maxTokensField') }}</small></label>
          </div>
          <div class="switch-row"><div><strong>JSON mode</strong><p class="field-help">要求模型返回 JSON 对象；供应商仍需支持相应 JSON 格式。</p></div><NSwitch v-model:value="form.jsonMode" :disabled="!editable" aria-label="JSON mode" /></div>
          <div class="switch-row"><div><strong>Streaming</strong><p class="field-help">控制支持流式输出的 AI 用途；连接测试始终发送非流式最小请求。</p></div><NSwitch v-model:value="form.streaming" :disabled="!editable" aria-label="Streaming" /></div>
        </section>

        <p v-if="saveConflict" class="notice" role="status">{{ saveConflict }}</p>
        <p v-if="hasAiConfigProblems(saveProblems)" class="notice error" role="alert">请修正表单中标记的问题后保存。<span v-if="saveProblems.revision"> {{ saveProblems.revision }}</span></p>
        <p v-if="writerError" class="notice error" role="alert">{{ errorText(writerError) }}</p>
        <p v-if="message" class="notice" role="status">{{ message }}</p>
        <div class="form-actions">
          <div class="row-actions">
            <NButton v-if="writerPending && !writerBusy" type="primary" :disabled="!readable || testing" @click="retrySave">用原请求核对结果</NButton>
            <NButton v-else attr-type="submit" type="primary" :disabled="!canSave" :loading="writerBusy">保存配置</NButton>
            <NButton :disabled="!dirty || !readable || busy" @click="adoptServer">采用服务器配置</NButton>
          </div>
          <span class="save-state" aria-live="polite">{{ writerBusy ? '正在保存…' : writerPending ? '结果尚未确认，请用原请求核对' : dirty ? '有未保存修改' : `服务器修订 ${baseline?.revision ?? '未读取'}` }}</span>
        </div>
      </form>

      <aside class="test-panel" aria-labelledby="test-title">
        <p class="section-kicker">连接检查</p><h2 id="test-title">测试当前配置</h2>
        <p class="cost-warning"><strong>可能产生费用</strong>测试会向供应商发送一条最小请求，可能产生供应商费用。测试不保存配置，也不读取或展示供应商正文。</p>
        <p v-if="endpointChangedWithoutKey" class="notice error" role="alert">端点已变化且未填写新 API Key。为避免把原密钥发送到新端点，测试已阻止；请填写用于新端点的密钥。</p>
        <NCheckbox v-model:checked="costAcknowledged" :disabled="!readable || busy">我已知悉本次连接测试可能产生供应商费用</NCheckbox>
        <p v-if="testBlockReason && readable" class="field-help">{{ testBlockReason }}</p>
        <NButton type="primary" :disabled="!canTest" :loading="testing" @click="runTest">测试连接（可能产生费用）</NButton>
        <p v-if="testError" class="notice error" role="alert">{{ testError }}</p>
        <p v-if="testResult" class="notice" :class="{ error: !testResult.ok }" :role="testResult.ok ? 'status' : 'alert'">{{ testResultText }}</p>
        <p class="field-help">测试使用当前表单中的协议、端点、模型、超时和 JSON 选项；留空密钥时复用服务器已有密钥。服务器限制每分钟最多 5 次测试。</p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.ai-model-settings { max-width: none; }
.settings-navigation { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 20px; }
.settings-navigation a { display: flex; align-items: center; justify-content: center; min-height: 40px; padding: 8px 16px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); font-size: 13px; }
.settings-navigation a:hover { background: var(--brand-soft); color: var(--brand-strong); }
.settings-navigation a.selected { border-color: var(--brand); color: var(--brand-strong); background: var(--brand-soft); }
.status-summary { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 1px; margin: 0 0 14px; overflow: hidden; border: 1px solid var(--line); border-radius: 8px; background: var(--line); }
.status-summary > div { min-width: 0; padding: 13px 16px; background: var(--surface); }
.status-summary dt { color: var(--muted); font-size: 11px; }
.status-summary dd { margin: 3px 0 0; overflow-wrap: anywhere; font-size: 13px; }
.settings-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 330px); gap: 20px; align-items: start; margin-top: 20px; }
.config-form { display: grid; gap: 16px; min-width: 0; }
.config-section { display: grid; gap: 20px; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface); }
.config-section > header { padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.section-kicker { margin: 0 0 3px; color: var(--brand); font-size: 11px; letter-spacing: .08em; }
.config-section h2, .test-panel h2 { margin: 0; font-size: 17px; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.field { display: grid; align-content: start; gap: 7px; min-width: 0; font-size: 13px; }
.field-wide { grid-column: 1 / -1; }
.field small { display: block; line-height: 1.6; }
.field-error { color: var(--danger); }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding-top: 16px; border-top: 1px solid var(--line); }
.switch-row strong { font-size: 13px; font-weight: 500; }
.switch-row p { margin: 3px 0 0; }
.form-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 16px 0 4px; }
.save-state { color: var(--muted); font-size: 12px; }
.test-panel { position: sticky; top: 76px; display: grid; gap: 14px; min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface-soft); }
.cost-warning { margin: 0; padding: 12px; border: 1px solid color-mix(in srgb, var(--warm) 45%, var(--line)); border-radius: 6px; color: var(--muted); background: color-mix(in srgb, var(--warm) 8%, var(--surface)); font-size: 12px; line-height: 1.65; }
.cost-warning strong { display: block; margin-bottom: 3px; color: var(--warm); }
@media (max-width: 1100px) {
  .settings-layout { grid-template-columns: minmax(0, 1fr); }
  .test-panel { position: static; }
  .status-summary { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 700px) {
  .settings-navigation { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .settings-navigation a { padding-inline: 8px; }
  .status-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .config-section, .test-panel { padding: 17px; }
  .field-grid { grid-template-columns: minmax(0, 1fr); }
  .field-wide { grid-column: auto; }
  .switch-row { align-items: flex-start; }
  .form-actions { align-items: flex-start; flex-direction: column; }
}
</style>
