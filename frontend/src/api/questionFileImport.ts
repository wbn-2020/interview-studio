import { FAILSAFE_SCHEMA, load } from 'js-yaml'
import { cloneQuestion, emptyQuestion, questionDimensionLabels, questionProblem, questionTypeLabels } from './bank'
import type { QuestionInput } from './bank'

export type QuestionFileCandidate = {
  id: string; ordinal: number; content: QuestionInput; original: string; notices: string[]; problem: string
}
export type QuestionFilePreview = {
  fileName: string; sha256: string; byteSize: number; candidates: QuestionFileCandidate[]
}
export type QuestionFileDuplicatePolicy = 'SKIP_IDENTICAL' | 'CREATE_COPY'
const fields = new Set(Object.keys(emptyQuestion()))
const metadata = new Set(['id', 'versionId', 'version', 'number', 'standardStatus', 'reviewStatus', 'reviewed',
  'review', 'reviewer', 'reviewedAt', 'status', 'favorite', 'createdAt', 'updatedAt'])
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const invalidText = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/
const unicode = /^(?:[\u0000-\ud7ff\ue000-\uffff]|[\ud800-\udbff][\udc00-\udfff])*$/

function object(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}须为JSON对象。`)
  return value as Record<string, unknown>
}
function text(value: unknown, label: string): string {
  if (typeof value !== 'string' || invalidText.test(value) || !unicode.test(value)) throw new Error(`${label}须为有效文本，不能含二进制控制符或损坏的Unicode。`)
  return value
}
function strings(value: unknown, label: string): string[] {
  if (!Array.isArray(value)) throw new Error(`${label}须为字符串数组。`)
  return value.map(item => text(item, label))
}
function keys(value: Record<string, unknown>, allowed: Set<string>, label: string) {
  const unknown = Object.keys(value).filter(key => !allowed.has(key))
  if (unknown.length) throw new Error(`${label}含不支持字段：${unknown.join('、')}；未静默丢弃。`)
}

function inputCandidate(value: unknown, ordinal: number): QuestionFileCandidate {
  const candidate: QuestionFileCandidate = {
    id: crypto.randomUUID(), ordinal, content: emptyQuestion(), original: JSON.stringify(value, null, 2), notices: [], problem: '',
  }
  try {
    const item = object(value, `第${ordinal}项`)
    const content = Object.hasOwn(item, 'content') ? object(item.content, 'content') : item
    const ignored = Object.keys(item).filter(key => metadata.has(key))
    keys(item, new Set([...(content === item ? fields : []), ...metadata, 'content']), '题目候选')
    if (content !== item) keys(content, fields, 'content')
    if (ignored.length) candidate.notices.push(`外部元数据仅展示且不会写入：${ignored.join('、')}。`)
    const input = emptyQuestion()
    const writable = input as unknown as Record<string, unknown>
    for (const field of ['title', 'prompt', 'conditions', 'technology', 'scenario', 'referenceAnswer'] as const) {
      if (Object.hasOwn(content, field)) writable[field] = text(content[field], field)
    }
    for (const field of ['dimensions', 'topicIds', 'tags'] as const) {
      if (Object.hasOwn(content, field)) writable[field] = strings(content[field], field)
    }
    if (Object.hasOwn(content, 'type')) writable.type = text(content.type, 'type')
    if (Object.hasOwn(content, 'difficulty')) {
      if (content.difficulty !== null && (typeof content.difficulty !== 'number' || !Number.isInteger(content.difficulty))) throw new Error('difficulty须为整数或null。')
      writable.difficulty = content.difficulty
    }
    if (Object.hasOwn(content, 'source')) {
      const source = object(content.source, 'source')
      keys(source, new Set(['title', 'url', 'note']), 'source')
      input.source = { title: Object.hasOwn(source, 'title') ? text(source.title, 'source.title') : '',
        url: Object.hasOwn(source, 'url') ? text(source.url, 'source.url') : '',
        note: Object.hasOwn(source, 'note') ? text(source.note, 'source.note') : '' }
    }
    if (Object.hasOwn(content, 'knowledgeLinks')) {
      if (!Array.isArray(content.knowledgeLinks)) throw new Error('knowledgeLinks须为数组。')
      input.knowledgeLinks = content.knowledgeLinks.map(value => {
        const link = object(value, 'knowledgeLinks项')
        keys(link, new Set(['itemId', 'versionId']), 'knowledgeLinks项')
        const itemId = text(link.itemId, 'itemId'), versionId = text(link.versionId, 'versionId')
        if (!uuid.test(itemId) || !uuid.test(versionId)) throw new Error('知识引用须保留标准小写UUID对象及发布版本，不按标题猜测映射。')
        return { itemId, versionId }
      })
    }
    if (input.topicIds.some(id => !uuid.test(id))) throw new Error('topicIds须为当前工作区的标准小写UUID；不按外部主题名猜测。')
    if (Object.hasOwn(content, 'checkpoints')) {
      if (!Array.isArray(content.checkpoints)) throw new Error('checkpoints须为数组。')
      input.checkpoints = content.checkpoints.map(value => {
        const point = object(value, 'checkpoints项')
        keys(point, new Set(['id', 'text']), 'checkpoints项')
        const id = Object.hasOwn(point, 'id') ? text(point.id, '检查点id') : crypto.randomUUID()
        if (!uuid.test(id)) throw new Error('检查点id须为标准小写UUID，或省略以生成新ID。')
        return { id, text: text(point.text, '检查点text') }
      })
    }
    const defaults = [...fields].filter(field => !Object.hasOwn(content, field))
    if (defaults.length) candidate.notices.push(`缺省字段采用新建题默认值：${defaults.join('、')}。`)
    if (input.knowledgeLinks.length || input.topicIds.length) candidate.notices.push('主题与知识ID按原值保留；服务器会核对本人当前可用来源，无法访问时拒绝该项，不自动映射。')
    candidate.content = cloneQuestion(input)
    candidate.problem = ''
  } catch (error) { candidate.problem = error instanceof Error ? error.message : '候选结构无法识别。' }
  return candidate
}

export async function prepareQuestionFile(fileName: string, buffer: ArrayBuffer,
  stillCurrent: () => boolean): Promise<QuestionFilePreview> {
  if (!stillCurrent()) throw new Error('文件读取条件已变化。')
  if (!/\.(json|md|markdown|txt)$/i.test(fileName) || fileName.length > 200 || /[/\\\u0000-\u001f\u007f]/.test(fileName)
    || buffer.byteLength < 1 || buffer.byteLength > 2_000_000) throw new Error('请选择1至2000000字节的JSON、Markdown或TXT文件，文件名不含路径或控制字符。')
  let source: string
  try { source = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(buffer) }
  catch { throw new Error('文件不是有效UTF-8，未解析任何候选。') }
  if (source.length > 1_000_000 || invalidText.test(source) || !unicode.test(source)) throw new Error('文件超过1000000字符或含不支持字符；未截断导入。')
  let candidates: QuestionFileCandidate[]
  if (/\.json$/i.test(fileName)) {
    // 先要求严格JSON，再用既有安全YAML解析器拒绝重复属性；返回值只从JSON投影白名单字段。
    const json = source.startsWith('\uFEFF') ? source.slice(1) : source
    let value: unknown
    try {
      value = JSON.parse(json, (key, value: unknown) => {
        if (['__proto__', 'prototype', 'constructor'].includes(key)) throw new Error('UNSAFE_KEY')
        return value
      })
      let nodes = 0
      load(json, { schema: FAILSAFE_SCHEMA, json: false, maxDepth: 12, listener(event) {
        if (event === 'open' && ++nodes > 30000) throw new Error('STRUCTURE_LIMIT')
      } })
    } catch { throw new Error('JSON无法安全解析：请检查语法、重复属性、不支持属性名或超限结构；没有部分采用。') }
    const root = !Array.isArray(value) ? object(value, 'JSON顶层') : undefined
    if (root && Object.hasOwn(root, 'questions')) keys(root, new Set(['questions']), 'JSON题目清单')
    const values = Array.isArray(value) ? value : root && Object.hasOwn(root, 'questions') ? root.questions : [value]
    if (!Array.isArray(values) || values.length < 1 || values.length > 50) throw new Error('每份JSON须包含1至50道题，可为题目对象、数组或{"questions":[...]}。')
    candidates = values.map((item, index) => inputCandidate(item, index + 1))
  } else {
    const content = emptyQuestion()
    content.title = fileName.replace(/\.(md|markdown|txt)$/i, '')
    content.prompt = source
    content.source = { title: fileName, url: '', note: '由用户选择的本地文本生成候选；文件名不证明作者、真实性或技术审核。' }
    candidates = [{ id: crypto.randomUUID(), ordinal: 1, content, original: source,
      notices: ['整份文本作为一道候选题干，不猜测标题层级、答案边界或题目分隔。请在保存前核对并补充完整字段。'],
      problem: '' }]
  }
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer)), byte => byte.toString(16).padStart(2, '0')).join('')
  if (!stillCurrent()) throw new Error('文件读取条件已变化。')
  return { fileName, sha256, byteSize: buffer.byteLength, candidates }
}

export function questionCandidateSignature(content: QuestionInput): string {
  return JSON.stringify({ ...content, checkpoints: content.checkpoints.map(point => point.text) })
}

export function questionFileCandidateProblem(content: QuestionInput): string {
  if (!Object.hasOwn(questionTypeLabels, content.type)) return '请选择有效题型。'
  if (content.dimensions.some(value => !Object.hasOwn(questionDimensionLabels, value))) return '请选择有效考察维度。'
  return questionProblem(content)
}
