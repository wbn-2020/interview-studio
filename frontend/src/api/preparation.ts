import type { Schema } from './client'

export type PreparationKind = Schema['PrepareKind']
export type Verification = Schema['PrepareVerification']
export type ReferenceKind = Schema['PrepareVersionLink']['kind']
export type ReferenceState = Schema['PrepareReferenceState']
export type Source = Schema['PrepareSource']
export type Requirement = Schema['PrepareRequirement']
export type VersionLink = Schema['PrepareVersionLink']
export type TargetContent = Schema['PrepareTargetContent']
export type ProjectContent = Schema['PrepareProjectContent']
export type FactContent = Schema['PrepareFactContent']
export type ExpressionContent = Schema['PrepareExpressionContent']
export type PreparationContent = TargetContent | ProjectContent | FactContent | ExpressionContent
export type PreparationVersion = Schema['PrepareVersion']
export type PreparationReference = Schema['PrepareReference']
export type PreparationItem = Schema['PrepareItem']
export type PreparationSummary = Schema['PrepareSummary']
export type PreparationPage = Schema['PreparePage']
export type PreparationVersionSummary = Schema['PrepareVersionSummary']
export type PreparationVersionPage = Schema['PrepareVersionPage']
export type PreparationDecision = Schema['PrepareDecision']
export type PreparationDecisionPage = Schema['PrepareDecisionPage']
export type PreparationImpact = Schema['PrepareImpact']
export type PreparationImpactPage = Schema['PrepareImpactPage']
export type PreparationFocus = Schema['PrepareFocus']
export type ChosenVersion = VersionLink & { title: string; number: number }

export const kindLabels: Record<PreparationKind, string> = { TARGET: '目标与 JD', PROJECT: '项目资料', FACT: '项目事实', EXPRESSION: '表达素材' }
export const verificationLabels: Record<Verification, string> = { UNVERIFIED: '待核实', CONFIRMED: '已由用户核实', WITHDRAWN: '已撤回' }
export const referenceLabels: Record<ReferenceState, string> = { CURRENT: '当前有效', SUPERSEDED: '来源已改版', WITHDRAWN: '来源已撤回', ARCHIVED: '来源已归档', UNVERIFIED: '来源待核实' }
export const responsibilityLabels = { OWNER: '本人负责', PARTICIPANT: '参与', FAMILIAR: '仅了解', UNVERIFIED: '职责待核实' }
export const dimensionOptions: { label: string; value: Schema['PrepareDimension'] }[] = [{ label: '解释', value: 'EXPLAIN' }, { label: '排错', value: 'DEBUG' }, { label: '取舍', value: 'TRADEOFF' }, { label: '职责', value: 'OWNERSHIP' }, { label: '表达', value: 'COMMUNICATION' }, { label: '其他', value: 'OTHER' }]
export const expressionOptions = [{ label: '自我介绍', value: 'INTRO' }, { label: '简短项目介绍', value: 'PROJECT_SHORT' }, { label: '完整项目介绍', value: 'PROJECT_LONG' }, { label: 'STAR 表达', value: 'STAR' }, { label: '技术取舍', value: 'TRADEOFF' }]

export function emptyContent(kind: PreparationKind): PreparationContent {
  const source = { title: '', url: '', note: '', excerptIds: [] as string[] }
  switch (kind) {
    case 'TARGET': return { title: '', company: '', jd: '', sourceUrl: '', capturedOn: null, weeklyMinutes: null, requirements: [], links: [] }
    case 'PROJECT': return { title: '', overview: '', technology: '', source }
    case 'FACT': return { title: '', scenario: '', problem: '', action: '', result: '', technology: '', responsibility: 'UNVERIFIED', source, tags: [] }
    case 'EXPRESSION': return { title: '', type: 'PROJECT_SHORT', body: '', factVersionIds: [], tags: [] }
  }
}

export function contentProblem(kind: PreparationKind, content: PreparationContent): string {
  if (!content.title.trim() || content.title.length > 200) return '请填写不超过 200 字符的标题。'
  const httpUrl = (value: string) => { if (!value) return true; try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && Boolean(url.hostname) && !url.username && !url.password && value.length <= 2000 } catch { return false } }
  if (kind === 'TARGET') {
    const value = content as TargetContent
    if (value.jd.length > 50000 || value.company.length > 200) return 'JD 最多 50000 字符，公司备注最多 200 字符。'
    if (!httpUrl(value.sourceUrl)) return '来源网址须为空或有效的 HTTP(S) 地址，最多 2000 字符。'
    if (value.weeklyMinutes !== null && (!Number.isInteger(value.weeklyMinutes) || value.weeklyMinutes < 0 || value.weeklyMinutes > 10080)) return '每周预算应为 0–10080 的整数分钟。'
    if (value.requirements.length > 50 || value.requirements.some(item => !item.text.trim() || item.text.length > 2000 || !item.dimensions.length || !Number.isInteger(item.priority) || item.priority < 1 || item.priority > 5)) return '每项要求需填写内容、考察维度和 1–5 的优先级，最多 50 项。'
    if (value.links.length > 100) return '每个目标最多引用 100 项材料版本。'
  }
  if (kind === 'PROJECT' || kind === 'FACT') {
    const value = content as ProjectContent | FactContent
    if (value.technology.length > 2000 || value.source.title.length > 200 || value.source.note.length > 5000 || !httpUrl(value.source.url)) return '请检查技术版本、来源标题、网址和备注的长度与格式。'
    const excerptIds = (value.source as Source).excerptIds ?? []
    if (excerptIds.length > 20 || new Set(excerptIds).size !== excerptIds.length) return '每份来源最多引用 20 个不同的已保存摘录。'
    if (kind === 'PROJECT' && (value as ProjectContent).overview.length > 20000) return '项目概述最多 20000 字符。'
    if (kind === 'FACT' && ['scenario', 'problem', 'action', 'result'].some(key => (value as FactContent)[key as 'scenario'].length > 10000)) return '事实的场景、问题、动作与结果各最多 10000 字符。'
  }
  if (kind === 'EXPRESSION') {
    const value = content as ExpressionContent
    if (value.body.length > 50000 || !value.factVersionIds.length || value.factVersionIds.length > 50) return '表达最多 50000 字符，并需选择 1–50 个当前已核实的事实版本。'
  }
  if ('tags' in content && (content.tags.length > 20 || content.tags.some(tag => !tag.trim() || tag.trim().length > 40))) return '最多 20 个标签，每个标签为 1–40 字符。'
  return ''
}

export function cloneContent(content: PreparationContent): PreparationContent { return JSON.parse(JSON.stringify(content)) as PreparationContent }
