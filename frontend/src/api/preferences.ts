import { ApiError, read } from './client'

export type PreferenceTheme = 'LIGHT' | 'DARK' | 'SYSTEM'
export type PreferenceMotion = 'SYSTEM' | 'REDUCED' | 'FULL'
export type Preferences = {
  revision: number; theme: PreferenceTheme; fontSize: number; contentWidth: number; motion: PreferenceMotion
  timeZone: string; saveRecoveryNotice: boolean; updatedAt: string | null
}
export type PreferenceValues = Omit<Preferences, 'revision' | 'updatedAt'>
export type SavePreferences = PreferenceValues & { expectedRevision: number }
export type PreferencesCacheRequest = SavePreferences & { base: PreferenceValues }
export type PreferencesCache = {
  version: 1; values: PreferenceValues; revision: number; updatedAt: string | null
  request?: PreferencesCacheRequest; key?: string
}
export type PreferenceUsage = {
  notes: number; concepts: number; knowledgeVersions: number; boards: number; preparationItems: number
  questions: number; trainingSessions: number; submittedAnswers: number; memoryCards: number; attachments: number
  aiTasks: number; materialReservedBytes: number; materialQuotaBytes: number; materialsEnabled: boolean; checkedAt: string
}

export const preferenceThemes = [
  { label: '跟随系统', value: 'SYSTEM' }, { label: '浅色', value: 'LIGHT' }, { label: '深色', value: 'DARK' },
]
export const preferenceMotions = [
  { label: '跟随系统', value: 'SYSTEM' }, { label: '减少动态效果', value: 'REDUCED' }, { label: '允许动态效果', value: 'FULL' },
]
export const defaultPreferences: Readonly<Preferences> = Object.freeze({
  revision: 0, theme: 'SYSTEM', fontSize: 14, contentWidth: 1280, motion: 'SYSTEM',
  timeZone: 'UTC', saveRecoveryNotice: true, updatedAt: null,
})
const names = ['revision', 'theme', 'fontSize', 'contentWidth', 'motion', 'timeZone', 'saveRecoveryNotice', 'updatedAt'] as const
const valueNames = ['theme', 'fontSize', 'contentWidth', 'motion', 'timeZone', 'saveRecoveryNotice'] as const
const cacheNames = ['version', 'values', 'revision', 'updatedAt'] as const
const usageCounts = ['notes', 'concepts', 'knowledgeVersions', 'boards', 'preparationItems', 'questions',
  'trainingSessions', 'submittedAnswers', 'memoryCards', 'attachments', 'aiTasks', 'materialReservedBytes', 'materialQuotaBytes'] as const
const fields = (value: unknown, keys: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object'
  && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const integer = (value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
const zone = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_+./:-]{1,64}$/.test(value)
function timestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d{1,6})?Z$/.exec(value)
  if (!match || Number(value.slice(0, 4)) < 1000) return false
  const parsed = Date.parse(`${match[1]}Z`)
  return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 19) === match[1]
}
function settingValues(value: Record<string, unknown>): boolean {
  return typeof value.theme === 'string' && ['LIGHT', 'DARK', 'SYSTEM'].includes(value.theme)
    && typeof value.motion === 'string' && ['SYSTEM', 'REDUCED', 'FULL'].includes(value.motion)
    && integer(value.fontSize, 12, 24) && integer(value.contentWidth, 720, 1600)
    && zone(value.timeZone) && typeof value.saveRecoveryNotice === 'boolean'
}
export function preferencesValid(value: unknown): value is Preferences {
  if (!fields(value, names) || !integer(value.revision, 0) || !settingValues(value)) return false
  if (value.revision > 0) return timestamp(value.updatedAt)
  return value.updatedAt === null && value.theme === defaultPreferences.theme && value.fontSize === defaultPreferences.fontSize
    && value.contentWidth === defaultPreferences.contentWidth && value.motion === defaultPreferences.motion
    && value.timeZone === defaultPreferences.timeZone && value.saveRecoveryNotice === defaultPreferences.saveRecoveryNotice
}
export function savePreferencesProblem(value: unknown): string | undefined {
  if (!fields(value, ['expectedRevision', 'theme', 'fontSize', 'contentWidth', 'motion', 'timeZone', 'saveRecoveryNotice'])
    || !integer(value.expectedRevision, 0) || !settingValues(value)) return '请选择有效的显示选项，字号为12至24，宽度为720至1600，并填写时区名称。'
  if (value.expectedRevision === Number.MAX_SAFE_INTEGER) return '个人设置修订已达到上限。'
  return undefined
}
export function preferenceValuesValid(value: unknown): value is PreferenceValues {
  return fields(value, valueNames) && settingValues(value)
}
export function samePreferenceValues(left: PreferenceValues, right: PreferenceValues): boolean {
  return valueNames.every(name => left[name] === right[name])
}
export function preferencesCacheValid(value: unknown): value is PreferencesCache {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const record = value as Record<string, unknown>, hasRequest = Object.hasOwn(record, 'request'), hasKey = Object.hasOwn(record, 'key')
  if (hasRequest !== hasKey || !fields(record, hasRequest ? [...cacheNames, 'request', 'key'] : cacheNames) || record.version !== 1
    || !preferenceValuesValid(record.values) || !integer(record.revision, 0)) return false
  if (record.revision === 0 ? record.updatedAt !== null : !timestamp(record.updatedAt)) return false
  if (!hasRequest) return true
  if (typeof record.key !== 'string'
      || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(record.key)
      || !record.request || typeof record.request !== 'object'
      || !fields(record.request, ['expectedRevision', ...valueNames, 'base'])
      || !preferenceValuesValid(record.request.base)) return false
  const request = record.request
  return !savePreferencesProblem({ expectedRevision: request.expectedRevision, theme: request.theme,
    fontSize: request.fontSize, contentWidth: request.contentWidth, motion: request.motion,
    timeZone: request.timeZone, saveRecoveryNotice: request.saveRecoveryNotice })
}
export function preferenceUsageValid(value: unknown): value is PreferenceUsage {
  return fields(value, [...usageCounts, 'materialsEnabled', 'checkedAt']) && usageCounts.every(key => integer(value[key], 0))
    && integer(value.materialQuotaBytes, 20 * 1024 * 1024) && typeof value.materialsEnabled === 'boolean' && timestamp(value.checkedAt)
}
export function samePreferences(left: Preferences, right: Preferences): boolean {
  return names.every(name => left[name] === right[name])
}
export function preferenceReceiptValid(value: unknown, request: SavePreferences): value is Preferences {
  return preferencesValid(value) && value.revision === request.expectedRevision + 1 && value.theme === request.theme
    && value.fontSize === request.fontSize && value.contentWidth === request.contentWidth && value.motion === request.motion
    && value.timeZone === request.timeZone && value.saveRecoveryNotice === request.saveRecoveryNotice
}
export async function readPreferences(): Promise<Preferences> {
  const value = await read<unknown>('/preferences')
  if (!preferencesValid(value)) throw new ApiError(502, 'PREFERENCES_RESPONSE_INVALID', '服务器返回的个人设置字段或修订无效，请重新读取。')
  return value
}
export async function readPreferenceUsage(): Promise<PreferenceUsage> {
  const value = await read<unknown>('/preferences/usage')
  if (!preferenceUsageValid(value)) throw new ApiError(502, 'PREFERENCES_USAGE_INVALID', '服务器返回的用量统计无效，请重新读取。')
  return value
}
export function formatPreferenceBytes(value: number): string {
  if (value < 1024) return `${value} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const exponent = Math.min(units.length, Math.floor(Math.log(value) / Math.log(1024)))
  return `${(value / 1024 ** exponent).toFixed(2)} ${units[exponent - 1]}`
}
