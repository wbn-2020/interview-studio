export function isTextImportPath(value: unknown, fileName: string, legacyFlat = false): value is string {
  if (typeof value !== 'string' || !value.length || value.length > 600 || /[\u0000-\u001f\u007f\\]/.test(value)
    || !/^(?:[\u0000-\ud7ff\ue000-\uffff]|[\ud800-\udbff][\udc00-\udfff])*$/.test(value)) return false
  // 旧平面文件名允许冒号；路径只是元数据，不能用新目录规则阻断旧批次恢复。
  if (legacyFlat && value === fileName && !value.includes('/')) return true
  if (value.includes(':')) return false
  const segments = value.split('/')
  return segments.length <= 32 && segments.every(part => part !== '' && part !== '.' && part !== '..')
    && segments.at(-1) === fileName
}
