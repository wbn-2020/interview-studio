export const aiDraftTextLimit = 256 * 1024
export const aiDraftDeltaLimit = 8192
export type AiDraftCloseReason = 'FINISHED' | 'UNAVAILABLE' | 'ACCESS_CHANGED' | 'RECONNECT'
type Identity = { taskId: string; attemptId: string }
export type AiDraftFrame =
  | (Identity & { kind: 'snapshot'; sequence: 0; text: string })
  | (Identity & { kind: 'delta'; sequence: number; offset: number; text: string })
  | (Identity & { kind: 'heartbeat' })
  | (Identity & { kind: 'closed'; reason: AiDraftCloseReason })

export const aiDraftIdValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
export const aiDraftPath = (taskId: string, attemptId: string) => `/api/v1/ai/tasks/${encodeURIComponent(taskId)}/attempts/${encodeURIComponent(attemptId)}/draft-events`
export const aiDraftTextBytes = (value: string) => new TextEncoder().encode(value).length
const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && names.every(name => Object.hasOwn(value, name)) && Object.keys(value).every(name => names.includes(name)))
const integer = (value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): value is number => typeof value === 'number'
  && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const wholeText = (value: unknown, maximum: number): value is string => typeof value === 'string' && value.length <= maximum && !/[\uD800-\uDFFF]/u.test(value)

export function aiDraftFrameFrom(kind: string, data: unknown, taskId: string, attemptId: string): AiDraftFrame | undefined {
  // 先限制编码后的事件大小，再解析；正文仅保留内存，不把尚未校验的 JSON 当作候选对象。
  if (!aiDraftIdValid(taskId) || !aiDraftIdValid(attemptId) || typeof data !== 'string' || data.length > aiDraftTextLimit * 6 + 1024) return undefined
  let value: Record<string, unknown>
  try { value = JSON.parse(data) as Record<string, unknown> } catch { return undefined }
  if (!value || value.taskId !== taskId || value.attemptId !== attemptId) return undefined
  const identity = { taskId, attemptId }
  if (kind === 'snapshot') {
    if (!fields(value, ['taskId', 'attemptId', 'sequence', 'text']) || value.sequence !== 0 || !wholeText(value.text, aiDraftTextLimit)
      || aiDraftTextBytes(value.text) > aiDraftTextLimit) return undefined
    return { ...identity, kind, sequence: 0, text: value.text }
  }
  if (kind === 'delta') {
    if (!fields(value, ['taskId', 'attemptId', 'sequence', 'offset', 'text']) || !integer(value.sequence, 1)
      || !integer(value.offset, 0, aiDraftTextLimit) || !wholeText(value.text, aiDraftDeltaLimit) || !value.text.length) return undefined
    return { ...identity, kind, sequence: value.sequence, offset: value.offset, text: value.text }
  }
  if (kind === 'heartbeat' && fields(value, ['taskId', 'attemptId'])) return { ...identity, kind }
  if (kind === 'closed' && fields(value, ['taskId', 'attemptId', 'reason']) && typeof value.reason === 'string'
    && ['FINISHED', 'UNAVAILABLE', 'ACCESS_CHANGED', 'RECONNECT'].includes(value.reason)) return { ...identity, kind, reason: value.reason as AiDraftCloseReason }
  return undefined
}
