import type { ImportLink, ImportLinkPlan } from './knowledgeTextLinks'
import { isTextImportPath } from './knowledgeTextPath'

export type ImportLinkFile = {
  id: string
  sourcePath: string
  fileName: string
  state: 'PENDING' | 'IMPORTED' | 'SKIPPED'
  itemId?: string
}
export type ImportLinkCandidate = {
  reason: string
  matches: ImportLinkFile[]
  entryId?: string
}
const textExtension = /\.(md|markdown|txt)$/i

function matchesFor(link: ImportLink, sourcePath: string, files: readonly ImportLinkFile[]): ImportLinkCandidate {
  const target = link.target
  if (link.kind === 'KNOWLEDGE') return { matches: [], reason: '旧站内地址不是批次文件身份，请人工核对目标。' }
  if (target.length > 500 || /[\u0000-\u001f\u007f\\:%?#^]/.test(target) || target.startsWith('/')) {
    return { matches: [], reason: '此地址不支持字面路径候选；不解码、修剪或猜测，请人工核对。' }
  }
  if (link.kind === 'RELATIVE') {
    if (!textExtension.test(target)) return { matches: [], reason: '相对链接须包含受支持的文本扩展名，请人工核对。' }
    const resolved = sourcePath.split('/').slice(0, -1)
    for (const segment of target.split('/')) {
      if (!segment) return { matches: [], reason: '相对地址含空路径段，请人工核对。' }
      if (segment === '.') continue
      if (segment === '..') {
        if (!resolved.length) return { matches: [], reason: '相对地址越出批次逻辑根，不读取外部文件，请人工核对。' }
        resolved.pop()
      } else resolved.push(segment)
    }
    const path = resolved.join('/')
    const matches = files.filter(file => file.sourcePath === path)
    return { matches, reason: `按源文件所在目录解析字面路径：${path}`
      + (matches.length ? '' : ' 本批次没有匹配文件；不跨批次或全库检索，请人工核对。') }
  }
  if (target.split('/').some(segment => !segment || segment === '.' || segment === '..')) {
    return { matches: [], reason: 'Wiki候选只接受完整批次路径或字面文件名，不推断相对目录。' }
  }
  const fullPath = target.includes('/'), explicitExtension = textExtension.test(target)
  const matches = files.filter(file => {
    const identity = fullPath ? file.sourcePath : file.fileName
    return (explicitExtension ? identity : identity.replace(textExtension, '')) === target
  })
  const reason = fullPath ? '按完整批次路径匹配；省略扩展名时仅去掉受支持的文本后缀，不剥离所选根目录。'
    : '按字面文件名匹配；省略扩展名时仅去掉受支持的文本后缀，不使用知识标题或别名。'
  return { matches, reason: reason + (matches.length ? '' : ' 本批次没有匹配文件；不跨批次或全库检索，请人工核对。') }
}

export function suggestImportLinks(plan: ImportLinkPlan, sourceId: string,
  files: readonly ImportLinkFile[]): Record<string, ImportLinkCandidate> {
  if (plan.problem) return {}
  if (!files.length || files.length > 20 || plan.links.length > 200
    || new Set(files.map(file => file.id)).size !== files.length
    || new Set(files.map(file => file.sourcePath)).size !== files.length
    || files.some(file => !isTextImportPath(file.sourcePath, file.fileName, true))) {
    throw new Error('候选所需的完整批次路径无法核对；请保留原文或使用人工映射。')
  }
  const source = files.find(file => file.id === sourceId)
  if (!source) throw new Error('当前文件不在候选批次内，请重新读取批次；人工映射不受影响。')
  return Object.fromEntries(plan.links.map(link => {
    const candidate = matchesFor(link, source.sourcePath, files), matches = candidate.matches
    // 歧义必须基于全批判断；先排除待处理、已跳过或自身会把重名误报为唯一身份。
    if (matches.length > 1) candidate.reason += ' 找到多个文件，存在歧义；请按完整路径人工选择。'
    else if (matches.length === 1) {
      const file = matches[0]!
      if (file.id === sourceId) candidate.reason += ' 唯一匹配是当前文件，不提供自身映射。'
      else if (file.state === 'PENDING') candidate.reason += ' 唯一匹配尚未导入，请先处理该文件再重新读取批次。'
      else if (file.state === 'SKIPPED') candidate.reason += ' 唯一匹配已跳过，不自动新建或重新打开。'
      else if (!file.itemId) candidate.reason += ' 导入记录缺少真实知识身份，请重新读取批次。'
      else { candidate.entryId = file.id; candidate.reason += ' 唯一匹配且已有导入记录；请核对路径后主动选用。' }
    }
    return [link.key, candidate]
  }))
}
