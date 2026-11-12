import { ApiError } from './client'
import type { Schema } from './client'
import type { AiManifest } from './ai'
import { aiPurposeLabels } from './ai'

export type AiAuthorizationReference = Schema['AiAuthorizationReference']
export type AiAuthorizationEntry = Schema['AiAuthorizationEntry']
export type AiAuthorizationPage = Schema['AiAuthorizationPage']
export type AiAuthorizationMatches = Schema['AiAuthorizationMatches']
export type AiAuthorizationCreate = Schema['AiAuthorizationCreate']
export type AiAuthorizationRevoke = Schema['AiAuthorizationRevoke']
export type AiAuthorizationRevocation = Schema['AiAuthorizationRevocation']
export type AiAuthorizationUsage = Schema['AiAuthorizationUsage']
export type AiAuthorizationUsagePage = Schema['AiAuthorizationUsagePage']

export const aiAuthorizationChangedEvent = 'studio:ai-authorizations-changed'
let authorizationChannel: BroadcastChannel | undefined, authorizationObservers = 0
export function observeAiAuthorizationChanges(listener: () => void): () => void {
  window.addEventListener(aiAuthorizationChangedEvent, listener)
  window.addEventListener('focus', listener)
  authorizationObservers++
  if (!authorizationChannel && typeof BroadcastChannel !== 'undefined') {
    authorizationChannel = new BroadcastChannel('studio:ai-authorizations')
    authorizationChannel.onmessage = event => { if (event.data === 'changed') window.dispatchEvent(new Event(aiAuthorizationChangedEvent)) }
  }
  return () => {
    window.removeEventListener(aiAuthorizationChangedEvent, listener); window.removeEventListener('focus', listener)
    authorizationObservers--
    if (authorizationObservers === 0) { authorizationChannel?.close(); authorizationChannel = undefined }
  }
}
export function notifyAiAuthorizationsChanged() {
  // 跨标签只广播失效信号；不传播清单、授权对象或凭据，接收后由各页重新核对。
  window.dispatchEvent(new Event(aiAuthorizationChangedEvent)); authorizationChannel?.postMessage('changed')
}
export const aiAuthorizationStateLabels: Record<AiAuthorizationEntry['effectiveState'], string> = {
  ACTIVE: '当前有效', REVOKED: '已撤回', EXPIRED: '已到期', RECONFIRM_REQUIRED: '服务重启后待重新确认',
  CONFIG_CHANGED: '供应商配置已变化', UNAVAILABLE: '当前不可用',
}
export const aiAuthorizationPaths = {
  create: '/ai/authorizations', matches: '/ai/authorizations/matches',
  page: (cursor?: string) => `/ai/authorizations?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
  revoke: (id: string) => `/ai/authorizations/${encodeURIComponent(id)}/revoke`,
  uses: (taskId: string) => `/ai/tasks/${encodeURIComponent(taskId)}/authorization-uses`,
} as const
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
export const aiAuthorizationIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const hash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const integer = (value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number'
  && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const text = (value: unknown, maximum: number): value is string => typeof value === 'string' && Array.from(value).length <= maximum
  && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(value) && !/[\u0000\uD800-\uDFFF]/u.test(value)
const timestamp = (value: unknown): value is string => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value))
const state = (value: unknown): value is AiAuthorizationEntry['effectiveState'] => typeof value === 'string' && Object.hasOwn(aiAuthorizationStateLabels, value)

export function aiAuthorizationReferenceValid(value: unknown): value is AiAuthorizationReference {
  const reference = value as AiAuthorizationReference
  return fields(reference, ['id', 'expectedRevision']) && aiAuthorizationIdValid(reference.id) && integer(reference.expectedRevision, 0)
}
export function aiAuthorizationRequestFields(reference?: AiAuthorizationReference | null): { authorization?: AiAuthorizationReference } {
  if (reference == null) return {}
  if (!aiAuthorizationReferenceValid(reference)) throw new ApiError(400, 'AI_AUTHORIZATION_REFERENCE_INVALID', '授权引用不完整，请重新核对。')
  return { authorization: { id: reference.id, expectedRevision: reference.expectedRevision } }
}

export function aiAuthorizationEntryValid(value: unknown): value is AiAuthorizationEntry {
  const entry = value as AiAuthorizationEntry
  return fields(entry, ['id', 'purpose', 'provider', 'model', 'protocol', 'configVersion', 'priceVersion', 'sourceTaskId', 'sourceManifestHash', 'scopeHash',
    'revision', 'state', 'effectiveState', 'expiresAt', 'createdAt', 'revokedAt']) && aiAuthorizationIdValid(entry.id)
    && Object.hasOwn(aiPurposeLabels, entry.purpose) && text(entry.provider, 120) && text(entry.model, 200) && ['CHAT_COMPLETIONS', 'RESPONSES'].includes(entry.protocol)
    && text(entry.configVersion, 80) && text(entry.priceVersion, 80) && aiAuthorizationIdValid(entry.sourceTaskId) && hash(entry.sourceManifestHash) && hash(entry.scopeHash)
    && state(entry.effectiveState) && timestamp(entry.createdAt) && timestamp(entry.expiresAt) && Date.parse(entry.expiresAt) > Date.parse(entry.createdAt)
    && Date.parse(entry.expiresAt) - Date.parse(entry.createdAt) <= 90 * 86400000
    && (entry.state === 'ACTIVE' ? entry.revision === 0 && entry.revokedAt === null && entry.effectiveState !== 'REVOKED'
      : entry.state === 'REVOKED' && entry.revision === 1 && timestamp(entry.revokedAt) && Date.parse(entry.revokedAt) >= Date.parse(entry.createdAt) && entry.effectiveState === 'REVOKED')
}

export function aiAuthorizationIdentity(entry: AiAuthorizationEntry): string {
  // 生效状态随时间、配置和进程变化；不可变授权身份用于核对旧命令回执，不能要求回执仍然有效。
  return JSON.stringify([entry.id, entry.purpose, entry.provider, entry.model, entry.protocol, entry.configVersion, entry.priceVersion,
    entry.sourceTaskId, entry.sourceManifestHash, entry.scopeHash, entry.expiresAt, entry.createdAt])
}
export function aiAuthorizationConfigurationMatches(entry: AiAuthorizationEntry, manifest: Pick<AiManifest, 'purpose' | 'configuration'>): boolean {
  const configuration = manifest.configuration
  return entry.purpose === manifest.purpose && entry.provider === configuration.provider && entry.model === configuration.model
    && entry.protocol === configuration.protocol && entry.configVersion === configuration.configVersion && entry.priceVersion === configuration.priceVersion
}
export function aiAuthorizationMatchesValid(value: unknown, manifest: AiManifest, expectedHash?: string): value is AiAuthorizationMatches {
  const result = value as AiAuthorizationMatches
  return fields(result, ['manifestHash', 'scopeHash', 'items']) && hash(result.manifestHash) && hash(result.scopeHash)
    && (expectedHash === undefined || result.manifestHash === expectedHash) && Array.isArray(result.items) && result.items.length <= 50
    && result.items.every(entry => aiAuthorizationEntryValid(entry) && entry.state === 'ACTIVE' && entry.effectiveState === 'ACTIVE'
      && entry.scopeHash === result.scopeHash && aiAuthorizationConfigurationMatches(entry, manifest))
    && result.items.every((entry, index) => index === 0 || result.items[index - 1]!.id < entry.id)
}
export function aiAuthorizationPageValid(value: unknown, cursor?: string): value is AiAuthorizationPage {
  const page = value as AiAuthorizationPage
  if (!fields(page, ['items', 'nextCursor']) || !Array.isArray(page.items) || page.items.length > 20
    || page.nextCursor !== null && !aiAuthorizationIdValid(page.nextCursor) || cursor !== undefined && !aiAuthorizationIdValid(cursor)) return false
  let previous = cursor
  for (const item of page.items) {
    if (!aiAuthorizationEntryValid(item) || previous !== undefined && item.id <= previous) return false
    previous = item.id
  }
  return page.nextCursor === null || page.items.length === 20 && page.nextCursor === page.items.at(-1)?.id
}
export function aiAuthorizationCreateReceiptValid(value: unknown, request: AiAuthorizationCreate, manifest: Pick<AiManifest, 'purpose' | 'configuration'>, scopeHash: string): value is AiAuthorizationEntry {
  if (!aiAuthorizationEntryValid(value) || !aiAuthorizationConfigurationMatches(value, manifest) || !integer(request.validDays, 1, 90)) return false
  return value.sourceTaskId === request.taskId && value.sourceManifestHash === request.expectedManifestHash && value.scopeHash === scopeHash
    && value.state === 'ACTIVE' && value.revision === 0 && value.revokedAt === null
    && Date.parse(value.expiresAt) - Date.parse(value.createdAt) === request.validDays * 86400000
}
export function aiAuthorizationRevocationValid(value: unknown, entry: AiAuthorizationEntry): value is AiAuthorizationRevocation {
  const result = value as AiAuthorizationRevocation
  return fields(result, ['authorization', 'queuedStopped', 'runningStopRequested']) && aiAuthorizationEntryValid(result.authorization)
    && aiAuthorizationIdentity(result.authorization) === aiAuthorizationIdentity(entry)
    && result.authorization.state === 'REVOKED' && result.authorization.revision === 1
    && integer(result.queuedStopped, 0, 20) && integer(result.runningStopRequested, 0, 20) && result.queuedStopped + result.runningStopRequested <= 20
}
export function aiAuthorizationUsagePageValid(value: unknown): value is AiAuthorizationUsagePage {
  const page = value as AiAuthorizationUsagePage
  if (!fields(page, ['items']) || !Array.isArray(page.items) || page.items.length > 3) return false
  let previous = 0
  for (const item of page.items) {
    if (!fields(item, ['attemptId', 'attemptNumber', 'mode', 'authorizationId', 'authorizationRevision', 'scopeHash', 'effectiveState', 'createdAt'])
      || !aiAuthorizationIdValid(item.attemptId) || !integer(item.attemptNumber, 1, 3) || item.attemptNumber <= previous || !timestamp(item.createdAt)) return false
    if (item.mode === 'ONCE' ? item.authorizationId !== null || item.authorizationRevision !== null || item.scopeHash !== null || item.effectiveState !== null
      : item.mode !== 'CONTINUING' || !aiAuthorizationIdValid(item.authorizationId) || item.authorizationRevision !== 0 || !hash(item.scopeHash) || !state(item.effectiveState)) return false
    previous = item.attemptNumber
  }
  return new Set(page.items.map(item => item.attemptId)).size === page.items.length
}
