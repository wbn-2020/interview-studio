import { ApiError, read } from './client'

export type ArchiveState = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED'
export type ArchiveStatus = { enabled: boolean; lifecycleReady: boolean; formatVersion: number; expiryHours: number; maximumBytes: number; maximumRows: number; exclusions: string[] }
export type ArchiveJob = {
  id: string; state: ArchiveState; formatVersion: number; createdAt: string; startedAt: string | null
  completedAt: string | null; expiresAt: string; byteSize: number | null; sha256: string | null
  rowCount: number | null; fileCount: number | null; errorCode: string | null; downloadable: boolean
}
export type ArchivePage = { items: ArchiveJob[] }
export const archiveStates: Record<ArchiveState, string> = { QUEUED: '排队中', RUNNING: '正在生成', READY: '已生成', FAILED: '失败', EXPIRED: '已失效' }

const base = '/lifecycle/archives'
const hash = /^[0-9a-f]{64}$/
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value)
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const fields = (value: Record<string, unknown>, names: readonly string[]) =>
  Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name))
const idValid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
function instant(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 19) === value.slice(0, 19)
}
function invalid(message: string): never { throw new ApiError(502, 'ARCHIVE_RESPONSE_INVALID', message) }
export function checkedArchiveStatus(value: unknown): ArchiveStatus {
  if (!record(value) || !fields(value, ['enabled', 'lifecycleReady', 'formatVersion', 'expiryHours', 'maximumBytes', 'maximumRows', 'exclusions'])
    || typeof value.enabled !== 'boolean' || typeof value.lifecycleReady !== 'boolean' || value.formatVersion !== 1
    || !count(value.expiryHours) || value.expiryHours < 1 || value.expiryHours > 168
    || !count(value.maximumBytes) || value.maximumBytes < 20_971_520 || value.maximumBytes > 4_294_967_296
    || !count(value.maximumRows) || value.maximumRows < 1 || value.maximumRows > 2_000_000
    || !Array.isArray(value.exclusions) || !value.exclusions.length || value.exclusions.length > 32
    || value.exclusions.some(item => typeof item !== 'string' || !item.trim() || item.length > 2048)
    || new Set(value.exclusions).size !== value.exclusions.length) {
    invalid('完整导出的状态、格式或排除范围无法核实，请重新读取；新的导出保持关闭。')
  }
  return value as ArchiveStatus
}
export function checkedArchiveJob(value: unknown): ArchiveJob {
  if (!record(value) || !fields(value, ['id', 'state', 'formatVersion', 'createdAt', 'startedAt', 'completedAt', 'expiresAt',
    'byteSize', 'sha256', 'rowCount', 'fileCount', 'errorCode', 'downloadable'])
    || !idValid(value.id) || typeof value.state !== 'string' || !Object.hasOwn(archiveStates, value.state)
    || value.formatVersion !== 1 || !instant(value.createdAt) || !instant(value.expiresAt)
    || value.startedAt !== null && !instant(value.startedAt) || value.completedAt !== null && !instant(value.completedAt)
    || value.byteSize !== null && !count(value.byteSize) || value.rowCount !== null && !count(value.rowCount)
    || value.fileCount !== null && !count(value.fileCount)
    || value.sha256 !== null && (typeof value.sha256 !== 'string' || !hash.test(value.sha256))
    || value.errorCode !== null && (typeof value.errorCode !== 'string' || !/^[A-Z][A-Z0-9_]{0,79}$/.test(value.errorCode))
    || typeof value.downloadable !== 'boolean') invalid('完整导出作业的身份、状态或字段无法核对，请保留原请求并重新读取。')
  const job = value as ArchiveJob
  const payload = [job.byteSize, job.sha256, job.rowCount, job.fileCount]
  const emptyPayload = payload.every(item => item === null)
  const completePayload = payload.every(item => item !== null) && job.byteSize! > 0
  if (Date.parse(job.expiresAt) <= Date.parse(job.createdAt)
    || job.startedAt !== null && Date.parse(job.startedAt) < Date.parse(job.createdAt)
    || job.completedAt !== null && (job.startedAt === null || Date.parse(job.completedAt) < Date.parse(job.startedAt))
    || !emptyPayload && !completePayload
    || completePayload && (job.startedAt === null || job.completedAt === null || job.errorCode !== null)
    || job.downloadable && job.state !== 'READY'
    || job.state === 'QUEUED' && (job.startedAt !== null || job.completedAt !== null || !emptyPayload || job.errorCode !== null)
    || job.state === 'RUNNING' && (job.startedAt === null || job.completedAt !== null || !emptyPayload || job.errorCode !== null)
    || job.state === 'READY' && !completePayload
    || job.state === 'FAILED' && (job.startedAt === null || job.completedAt === null || !emptyPayload || job.errorCode === null)) {
    invalid('完整导出作业的时间、文件摘要或下载资格与状态不一致，请重新核对原作业。')
  }
  return job
}
export function checkedArchivePage(value: unknown): ArchivePage {
  if (!record(value) || !fields(value, ['items']) || !Array.isArray(value.items) || value.items.length > 50) {
    invalid('完整导出清单的范围无法核对，请重新读取。')
  }
  const items = value.items.map(checkedArchiveJob)
  if (new Set(items.map(job => job.id)).size !== items.length) invalid('完整导出清单含重复作业身份，请重新读取。')
  return { items }
}
export function checkedArchiveCreation(value: unknown, expected: ArchiveStatus, previous?: ArchiveJob): ArchiveJob {
  const job = checkedArchiveJob(value)
  // 创建回执保存的是初次排队快照；原键重试不能换成另一作业或当前后台运行状态。
  if (job.state !== 'QUEUED' || job.formatVersion !== expected.formatVersion
    || Date.parse(job.expiresAt) - Date.parse(job.createdAt) !== expected.expiryHours * 3_600_000
    || previous && (job.id !== previous.id || job.createdAt !== previous.createdAt || job.expiresAt !== previous.expiresAt)) {
    invalid('完整导出创建回执与已确认的格式、有效期或原作业不一致，请用原请求恢复。')
  }
  return job
}
export async function readArchiveStatus(): Promise<ArchiveStatus> { return checkedArchiveStatus(await read<unknown>(`${base}/status`)) }
export async function readArchiveJobs(): Promise<ArchivePage> { return checkedArchivePage(await read<unknown>(base)) }
