import { ApiError, preview, read, write } from './client'

export const aiConfigProtocols = ['CHAT_COMPLETIONS', 'RESPONSES'] as const
export type AiConfigProtocol = typeof aiConfigProtocols[number]
export type AiConfigSource = 'ENV' | 'DATABASE'
export type AiConfigMaxTokensField = 'max_tokens' | 'max_completion_tokens' | 'max_output_tokens'

export type AiConfigValues = {
  enabled: boolean
  protocol: AiConfigProtocol
  provider: string
  endpoint: string
  model: string
  configVersion: string
  priceVersion: string
  currency: string
  inputPriceMicrosPerMillion: number
  outputPriceMicrosPerMillion: number
  dailyBudgetMicros: number
  maxOutputTokens: number
  timeoutSeconds: number
  maxTokensField: AiConfigMaxTokensField
  jsonMode: boolean
  streaming: boolean
}

export type AiConfigNumericField = 'inputPriceMicrosPerMillion' | 'outputPriceMicrosPerMillion'
  | 'dailyBudgetMicros' | 'maxOutputTokens' | 'timeoutSeconds'
export type AiConfigForm = Omit<AiConfigValues, AiConfigNumericField> & Record<AiConfigNumericField, number | null>
export type AiConfigStatus = AiConfigValues & {
  revision: number
  source: AiConfigSource
  canEdit: boolean
  configured: boolean
  apiKeyConfigured: boolean
}
export type AiConfigSaveRequest = AiConfigValues & { expectedRevision: number; apiKey: string }
export type AiConfigTestRequest = Pick<AiConfigValues, 'protocol' | 'endpoint' | 'model' | 'timeoutSeconds'
  | 'maxTokensField' | 'jsonMode'> & { apiKey: string; costAcknowledged: true }
export type AiConfigTestResult = { ok: boolean; status: number; latencyMs: number; errorCode: string | null }
export type AiConfigProblemField = keyof AiConfigValues | 'apiKey' | 'revision'
export type AiConfigProblems = Partial<Record<AiConfigProblemField, string>>

export function hasAiConfigProblems(problems: Readonly<AiConfigProblems>): boolean {
  return Object.values(problems).some(Boolean)
}

export const aiConfigProtocolLabels: Record<AiConfigProtocol, string> = {
  CHAT_COMPLETIONS: 'Chat Completions', RESPONSES: 'Responses',
}
export const aiConfigSourceLabels: Record<AiConfigSource, string> = {
  ENV: 'ENV · 环境变量', DATABASE: '数据库',
}
export const aiConfigMaxTokensFieldLabels: Record<AiConfigMaxTokensField, string> = {
  max_tokens: 'max_tokens', max_completion_tokens: 'max_completion_tokens', max_output_tokens: 'max_output_tokens',
}
export const aiConfigTestErrorLabels: Record<string, string> = {
  AI_CONFIG_TEST_API_KEY_REQUIRED: '端点已经变化，必须填写用于新端点的 API Key。',
  AI_CONFIG_TEST_NOT_CONFIGURED: '协议、端点、模型、密钥或超时配置尚未完整。',
  AI_CONFIG_TEST_ENDPOINT_FORBIDDEN: '端点解析到非公网地址，已被服务器拒绝。',
  AI_CONFIG_TEST_ENDPOINT_INVALID: '端点格式无效，请填写完整的 HTTPS 地址。',
  AI_CONFIG_TEST_DNS_FAILED: '无法解析端点域名。',
  AI_CONFIG_TEST_DNS_TIMEOUT: '端点域名解析超时。',
  AI_CONFIG_TEST_TIMEOUT: '连接测试超时。',
  AI_CONFIG_TEST_TLS_FAILED: 'HTTPS 证书或握手校验失败。',
  AI_CONFIG_TEST_NETWORK_FAILED: '无法连接供应商端点。',
  AI_CONFIG_TEST_REDIRECT_REJECTED: '供应商返回重定向，测试未跟随。',
  AI_CONFIG_TEST_PROVIDER_ERROR: '供应商返回非成功状态。',
  AI_CONFIG_TEST_INVALID_RESPONSE: '供应商响应格式无法识别。',
  AI_CONFIG_TEST_RESPONSE_TOO_LARGE: '供应商响应超过测试读取上限。',
  AI_CONFIG_TEST_UNSUPPORTED_ENCODING: '供应商响应使用了不支持的编码。',
  AI_CONFIG_TEST_INVALID_REQUEST: '测试请求未能被供应商接受。',
  AI_CONFIG_TEST_CANCELED: '连接测试已取消。',
}

export const defaultAiConfigForm: Readonly<AiConfigForm> = Object.freeze({
  enabled: false, protocol: 'CHAT_COMPLETIONS', provider: '', endpoint: '', model: '',
  configVersion: '', priceVersion: '', currency: '', inputPriceMicrosPerMillion: -1,
  outputPriceMicrosPerMillion: -1, dailyBudgetMicros: 0, maxOutputTokens: 1024,
  timeoutSeconds: 60, maxTokensField: 'max_tokens', jsonMode: false, streaming: false,
})

const maxSafe = Number.MAX_SAFE_INTEGER
const valueKeys = ['enabled', 'protocol', 'provider', 'endpoint', 'model', 'configVersion', 'priceVersion',
  'currency', 'inputPriceMicrosPerMillion', 'outputPriceMicrosPerMillion', 'dailyBudgetMicros',
  'maxOutputTokens', 'timeoutSeconds', 'maxTokensField', 'jsonMode', 'streaming'] as const
const statusKeys = ['revision', 'source', 'canEdit', 'configured', ...valueKeys, 'apiKeyConfigured'] as const
const testResultKeys = ['ok', 'status', 'latencyMs', 'errorCode'] as const
const controlCharacters = /[\u0000-\u001f\u007f-\u009f]/u

function invalidStatus(): never {
  throw new ApiError(502, 'AI_CONFIG_RESPONSE_INVALID', '服务器返回的 AI 配置状态无效，请重新读取。')
}
function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
function exactFields(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
}
function integer(value: unknown, minimum: number, maximum = maxSafe): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
}
function text(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && [...value].length <= maximum && !controlCharacters.test(value)
}
function protocolValid(value: unknown): value is AiConfigProtocol {
  return typeof value === 'string' && aiConfigProtocols.includes(value as AiConfigProtocol)
}
function maxTokensFieldValid(protocol: AiConfigProtocol, value: unknown): value is AiConfigMaxTokensField {
  return protocol === 'RESPONSES' ? value === 'max_output_tokens'
    : value === 'max_tokens' || value === 'max_completion_tokens'
}
function endpointProblem(value: string): string | undefined {
  if (!value) return undefined
  if (/\s/u.test(value)) return '端点不能包含空白字符。'
  try {
    const url = new URL(value), schemeEnd = value.indexOf('://')
    const authority = value.slice(schemeEnd + 3).split(/[/?#]/u, 1)[0] ?? ''
    if (url.protocol !== 'https:' || !url.hostname || authority.includes('@') || url.port === '0'
      || value.includes('?') || value.includes('#') || value.includes('\\')
      || !authority || !/^https:\/\/[^/]+\//iu.test(value))
      return '请填写完整 HTTPS 端点（含路径），且不能包含账号、查询参数或片段。'
  } catch { return '请填写完整 HTTPS 端点，例如 https://api.example.com/v1。' }
  return undefined
}
function endpointIdentity(value: string): string {
  try { return new URL(value.trim()).toString() } catch { return value.trim() }
}
function keyProblem(value: string): string | undefined {
  if (!value) return undefined
  if (value.length > 8192 || [...value].some(character => {
    const code = character.charCodeAt(0)
    return code < 33 || code > 126
  })) return 'API Key 必须为 1–8192 个可打印 ASCII 字符，且不能包含空白。'
  return undefined
}
function numberProblem(value: number | null, minimum: number, maximum: number, label: string): string | undefined {
  return integer(value, minimum, maximum) ? undefined : `${label}必须为 ${minimum} 至 ${maximum} 的整数。`
}

export function aiConfigFormOf(value: Readonly<AiConfigStatus>): AiConfigForm {
  return {
    enabled: value.enabled, protocol: value.protocol, provider: value.provider, endpoint: value.endpoint,
    model: value.model, configVersion: value.configVersion, priceVersion: value.priceVersion,
    currency: value.currency, inputPriceMicrosPerMillion: value.inputPriceMicrosPerMillion,
    outputPriceMicrosPerMillion: value.outputPriceMicrosPerMillion, dailyBudgetMicros: value.dailyBudgetMicros,
    maxOutputTokens: value.maxOutputTokens, timeoutSeconds: value.timeoutSeconds,
    maxTokensField: value.maxTokensField, jsonMode: value.jsonMode, streaming: value.streaming,
  }
}
export function sameAiConfigForm(left: Readonly<AiConfigForm>, right: Readonly<AiConfigForm>): boolean {
  return left.enabled === right.enabled && left.protocol === right.protocol
    && left.provider.trim() === right.provider.trim() && endpointIdentity(left.endpoint) === endpointIdentity(right.endpoint)
    && left.model.trim() === right.model.trim() && left.configVersion.trim() === right.configVersion.trim()
    && left.priceVersion.trim() === right.priceVersion.trim() && left.currency.trim() === right.currency.trim()
    && left.inputPriceMicrosPerMillion === right.inputPriceMicrosPerMillion
    && left.outputPriceMicrosPerMillion === right.outputPriceMicrosPerMillion
    && left.dailyBudgetMicros === right.dailyBudgetMicros && left.maxOutputTokens === right.maxOutputTokens
    && left.timeoutSeconds === right.timeoutSeconds && left.maxTokensField === right.maxTokensField
    && left.jsonMode === right.jsonMode && left.streaming === right.streaming
}
export function formatAiConfigMicros(value: number | null, currency = ''): string {
  if (value === null) return '待填写'
  if (value < 0) return '价格未知'
  const amount = (value / 1_000_000).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
  return `${amount} ${currency || '币种单位'}`
}

export function aiConfigSaveProblems(value: Readonly<AiConfigForm>, status: Readonly<AiConfigStatus>, apiKey: string): AiConfigProblems {
  const problems: AiConfigProblems = {}
  if (status.revision >= maxSafe) problems.revision = '配置修订号已达到上限，请联系服务器管理员。'
  if (!protocolValid(value.protocol)) problems.protocol = '请选择 Chat Completions 或 Responses 协议。'
  if (!text(value.provider, 120)) problems.provider = '供应商名称不能超过 120 个字符或包含控制字符。'
  if (!text(value.endpoint, 2048)) problems.endpoint = '端点不能超过 2048 个字符或包含控制字符。'
  else if (value.endpoint.trim()) problems.endpoint = endpointProblem(value.endpoint.trim())
  if (!text(value.model, 200)) problems.model = '模型名称不能超过 200 个字符或包含控制字符。'
  if (!text(value.configVersion, 80)) problems.configVersion = '配置版本不能超过 80 个字符或包含控制字符。'
  if (!text(value.priceVersion, 80)) problems.priceVersion = '价格版本不能超过 80 个字符或包含控制字符。'
  if (!text(value.currency, 3) || value.currency.trim() && !/^[A-Z]{3}$/.test(value.currency.trim()))
    problems.currency = '币种请填写 3 位大写字母，例如 CNY 或 USD。'
  problems.inputPriceMicrosPerMillion = numberProblem(value.inputPriceMicrosPerMillion, -1, maxSafe, '输入单价')
  problems.outputPriceMicrosPerMillion = numberProblem(value.outputPriceMicrosPerMillion, -1, maxSafe, '输出单价')
  problems.dailyBudgetMicros = numberProblem(value.dailyBudgetMicros, 0, maxSafe, '日预算')
  problems.maxOutputTokens = numberProblem(value.maxOutputTokens, 0, 16_384, '最大输出 tokens')
  problems.timeoutSeconds = numberProblem(value.timeoutSeconds, 0, 120, '超时时间')
  if (protocolValid(value.protocol) && !maxTokensFieldValid(value.protocol, value.maxTokensField))
    problems.maxTokensField = value.protocol === 'RESPONSES' ? 'Responses 协议必须使用 max_output_tokens。'
      : 'Chat Completions 协议请选择 max_tokens 或 max_completion_tokens。'
  const keyIssue = keyProblem(apiKey)
  if (keyIssue) problems.apiKey = keyIssue
  if (value.enabled) {
    if (!value.provider.trim()) problems.provider = '启用 AI 前必须填写供应商。'
    if (!value.endpoint.trim()) problems.endpoint = '启用 AI 前必须填写完整 HTTPS 端点。'
    if (!value.model.trim()) problems.model = '启用 AI 前必须填写模型名称。'
    if (!value.configVersion.trim()) problems.configVersion = '启用 AI 前必须填写配置版本。'
    if (!value.priceVersion.trim()) problems.priceVersion = '启用 AI 前必须填写价格版本。'
    if (!value.currency.trim()) problems.currency = '启用 AI 前必须填写币种。'
    if (integer(value.inputPriceMicrosPerMillion, 0, maxSafe) === false) problems.inputPriceMicrosPerMillion = '启用 AI 前必须填写已知的非负输入单价。'
    if (integer(value.outputPriceMicrosPerMillion, 0, maxSafe) === false) problems.outputPriceMicrosPerMillion = '启用 AI 前必须填写已知的非负输出单价。'
    if (integer(value.dailyBudgetMicros, 1, maxSafe) === false) problems.dailyBudgetMicros = '启用 AI 前日预算必须大于 0。'
    if (integer(value.maxOutputTokens, 1, 16_384) === false) problems.maxOutputTokens = '启用 AI 前最大输出必须大于 0。'
    if (integer(value.timeoutSeconds, 1, 120) === false) problems.timeoutSeconds = '启用 AI 前超时时间必须大于 0。'
    if (!apiKey && !status.apiKeyConfigured) problems.apiKey = problems.apiKey ?? '启用 AI 前必须配置 API Key。'
  }
  return problems
}

export function aiConfigTestProblems(value: Readonly<AiConfigForm>, status: Readonly<AiConfigStatus>, apiKey: string): AiConfigProblems {
  const problems: AiConfigProblems = {}
  if (!protocolValid(value.protocol)) problems.protocol = '请选择有效的连接协议。'
  if (!text(value.endpoint, 2048)) problems.endpoint = '请填写有效的完整 HTTPS 端点。'
  else if (!value.endpoint.trim()) problems.endpoint = '测试前必须填写完整 HTTPS 端点。'
  else problems.endpoint = endpointProblem(value.endpoint.trim())
  if (!text(value.model, 200) || !value.model.trim()) problems.model = '测试前必须填写模型名称。'
  problems.timeoutSeconds = numberProblem(value.timeoutSeconds, 1, 120, '超时时间')
  if (protocolValid(value.protocol) && !maxTokensFieldValid(value.protocol, value.maxTokensField))
    problems.maxTokensField = 'max tokens 字段与当前协议不匹配。'
  const keyIssue = keyProblem(apiKey)
  if (keyIssue) problems.apiKey = keyIssue
  else if (!apiKey && endpointIdentity(value.endpoint) !== endpointIdentity(status.endpoint))
    problems.apiKey = '端点已变化且未填写新 API Key；为避免把原密钥发送到新端点，请先填写新密钥。'
  else if (!apiKey && !status.apiKeyConfigured) problems.apiKey = '当前没有已配置的 API Key，请填写后再测试。'
  return problems
}

export function checkedAiConfigStatus(value: unknown): AiConfigStatus {
  if (!record(value) || !exactFields(value, statusKeys) || !integer(value.revision, 0)
    || value.source !== 'ENV' && value.source !== 'DATABASE' || typeof value.canEdit !== 'boolean'
    || typeof value.configured !== 'boolean' || typeof value.apiKeyConfigured !== 'boolean'
    || typeof value.enabled !== 'boolean' || typeof value.jsonMode !== 'boolean' || typeof value.streaming !== 'boolean'
    || !protocolValid(value.protocol) || !text(value.provider, 120) || !text(value.endpoint, 2048)
    || !text(value.model, 200) || !text(value.configVersion, 80) || !text(value.priceVersion, 80)
    || !text(value.currency, 3) || value.currency !== '' && !/^[A-Z]{3}$/.test(value.currency)
    || !integer(value.inputPriceMicrosPerMillion, -1) || !integer(value.outputPriceMicrosPerMillion, -1)
    || !integer(value.dailyBudgetMicros, 0) || !integer(value.maxOutputTokens, 0, 16_384)
    || !integer(value.timeoutSeconds, 0, 120) || !maxTokensFieldValid(value.protocol, value.maxTokensField)
    || value.endpoint !== '' && endpointProblem(value.endpoint)) invalidStatus()
  return value as unknown as AiConfigStatus
}
export function checkedAiConfigSaveReceipt(value: unknown, request: Readonly<AiConfigSaveRequest>): AiConfigStatus {
  const receipt = checkedAiConfigStatus(value)
  if (request.expectedRevision >= maxSafe || receipt.revision !== request.expectedRevision + 1
    || receipt.source !== 'DATABASE' || !sameAiConfigForm(receipt, request)
    || request.apiKey && !receipt.apiKeyConfigured)
    throw new ApiError(502, 'AI_CONFIG_RECEIPT_INVALID', '保存回执与原请求不一致。输入已保留，请用原请求核对结果。')
  return receipt
}
export function checkedAiConfigTestResult(value: unknown): AiConfigTestResult {
  if (!record(value) || !exactFields(value, testResultKeys) || typeof value.ok !== 'boolean'
    || !integer(value.status, 0, 599) || !integer(value.latencyMs, 0)
    || value.errorCode !== null && (!text(value.errorCode, 80) || !value.errorCode)
    || value.ok && (value.errorCode !== null || value.status < 200 || value.status >= 300)
    || !value.ok && value.errorCode === null)
    throw new ApiError(502, 'AI_CONFIG_TEST_RESPONSE_INVALID', '服务器返回的连接测试结果无效，请重新测试。')
  return value as unknown as AiConfigTestResult
}

export function aiConfigSaveRequest(value: Readonly<AiConfigForm>, status: Readonly<AiConfigStatus>, apiKey: string): AiConfigSaveRequest {
  if (hasAiConfigProblems(aiConfigSaveProblems(value, status, apiKey)))
    throw new ApiError(422, 'AI_CONFIG_INPUT_INVALID', '请先修正表单中的配置问题。')
  return {
    enabled: value.enabled, protocol: value.protocol, provider: value.provider.trim(), endpoint: value.endpoint.trim(),
    model: value.model.trim(), configVersion: value.configVersion.trim(), priceVersion: value.priceVersion.trim(),
    currency: value.currency.trim(), inputPriceMicrosPerMillion: value.inputPriceMicrosPerMillion!,
    outputPriceMicrosPerMillion: value.outputPriceMicrosPerMillion!, dailyBudgetMicros: value.dailyBudgetMicros!,
    maxOutputTokens: value.maxOutputTokens!, timeoutSeconds: value.timeoutSeconds!, maxTokensField: value.maxTokensField,
    jsonMode: value.jsonMode, streaming: value.streaming, expectedRevision: status.revision, apiKey,
  }
}
export function aiConfigTestRequest(value: Readonly<AiConfigForm>, status: Readonly<AiConfigStatus>, apiKey: string): AiConfigTestRequest {
  if (hasAiConfigProblems(aiConfigTestProblems(value, status, apiKey)))
    throw new ApiError(422, 'AI_CONFIG_TEST_INPUT_INVALID', '请先修正连接测试需要的配置。')
  return {
    protocol: value.protocol, endpoint: value.endpoint.trim(), model: value.model.trim(),
    timeoutSeconds: value.timeoutSeconds!, maxTokensField: value.maxTokensField, jsonMode: value.jsonMode,
    apiKey, costAcknowledged: true,
  }
}

export async function readAiConfig(): Promise<AiConfigStatus> {
  return checkedAiConfigStatus(await read<unknown>('/ai/config'))
}
export async function saveAiConfig(request: Readonly<AiConfigSaveRequest>, key: string): Promise<AiConfigStatus> {
  return checkedAiConfigSaveReceipt(await write<unknown>('/ai/config', 'PUT', request, key), request)
}
export async function testAiConfig(request: Readonly<AiConfigTestRequest>): Promise<AiConfigTestResult> {
  return checkedAiConfigTestResult(await preview<unknown>('/ai/config/test', request))
}
