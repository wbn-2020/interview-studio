import type { RouteLocationRaw } from 'vue-router'
import type { Schema } from './client'
import { materialLifecycleStateValid } from './materialLifecycle'
import { materialReferenceIdValid, materialReferenceSourceKey, materialReferenceSourceValid } from './materialReferences'

export type MaterialKnowledgePreview = Schema['MaterialKnowledgePreview']
export type MaterialKnowledgePreviewNode = Schema['MaterialKnowledgePreviewNode']
export type MaterialKnowledgePreviewEdge = Schema['MaterialKnowledgePreviewEdge']

export const materialKnowledgePreviewPath = (excerptId: string): string =>
  `/materials/excerpts/${encodeURIComponent(excerptId)}/knowledge-adoptions`

const fields = (value: unknown, names: readonly string[]): boolean => Boolean(value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name)))
const positiveInteger = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
function nodeValid(value: unknown): value is MaterialKnowledgePreviewNode {
  const node = value as MaterialKnowledgePreviewNode
  return fields(node, ['itemId', 'versionId', 'versionNumber', 'kind', 'title', 'status', 'current'])
    && materialReferenceIdValid(node.itemId) && materialReferenceIdValid(node.versionId)
    && positiveInteger(node.versionNumber) && node.versionNumber <= 2147483647
    && (node.kind === 'NOTE' || node.kind === 'CONCEPT') && typeof node.title === 'string'
    && node.title.length <= 200
    && /[^\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(node.title)
    && (node.status === 'ACTIVE' || node.status === 'ARCHIVED') && typeof node.current === 'boolean'
}
function edgeValid(value: unknown, excerptId: string): value is MaterialKnowledgePreviewEdge {
  const edge = value as MaterialKnowledgePreviewEdge
  return fields(edge, ['id', 'parentId', 'depth', 'domain', 'sourceItemId', 'sourceVersionId', 'taskId', 'targetItemId', 'targetVersionId'])
    && (edge.domain === 'MATERIAL_EXCERPT' || edge.domain === 'KNOWLEDGE') && positiveInteger(edge.depth)
    && materialReferenceIdValid(edge.taskId) && materialReferenceIdValid(edge.sourceItemId)
    && materialReferenceIdValid(edge.targetItemId) && materialReferenceIdValid(edge.targetVersionId)
    && (edge.domain === 'MATERIAL_EXCERPT'
      ? edge.depth === 1 && edge.parentId === null && edge.sourceVersionId === null && edge.sourceItemId === excerptId
      : edge.depth > 1 && typeof edge.parentId === 'string' && materialReferenceIdValid(edge.sourceVersionId))
    && edge.id === `${edge.domain}~${edge.taskId}~${edge.sourceVersionId ?? excerptId}`
}
export function materialKnowledgePreviewValid(value: unknown, excerptId: string, attachmentId: string): value is MaterialKnowledgePreview {
  try {
    const preview = value as MaterialKnowledgePreview
    if (!fields(preview, ['coverage', 'source', 'lifecycle', 'nodes', 'edges'])
      || preview.coverage !== 'REGISTERED_KNOWLEDGE_ADOPTIONS_ONLY'
      || !materialReferenceSourceValid(preview.source, excerptId, attachmentId)
      || !materialLifecycleStateValid(preview.lifecycle, excerptId, attachmentId)
      || materialReferenceSourceKey(preview.source) !== materialReferenceSourceKey({ ...preview.source, createdAt: preview.lifecycle.createdAt })
      || !Array.isArray(preview.nodes) || preview.nodes.length > 200
      || !Array.isArray(preview.edges) || preview.edges.length > 500) return false
    const nodes = new Map<string, MaterialKnowledgePreviewNode>(), itemVersions = new Set<string>()
    const currentItems = new Set<string>(), itemMetadata = new Map<string, { kind: string; status: string }>()
    let previousNode = ''
    for (const node of preview.nodes) {
      if (!nodeValid(node) || node.versionId <= previousNode || itemVersions.has(`${node.itemId}/${node.versionNumber}`)
        || node.current && currentItems.has(node.itemId)) return false
      const metadata = itemMetadata.get(node.itemId)
      if (metadata && (metadata.kind !== node.kind || metadata.status !== node.status)) return false
      previousNode = node.versionId; nodes.set(node.versionId, node); itemVersions.add(`${node.itemId}/${node.versionNumber}`)
      itemMetadata.set(node.itemId, node)
      if (node.current) currentItems.add(node.itemId)
    }
    const edges = new Map<string, MaterialKnowledgePreviewEdge>(), usedVersions = new Set<string>()
    const taskTargets = new Map<string, string>(), targetTasks = new Map<string, string>()
    let previous: MaterialKnowledgePreviewEdge | undefined
    for (const edge of preview.edges) {
      if (!edgeValid(edge, excerptId) || edges.has(edge.id)
        || previous && (edge.depth < previous.depth || edge.depth === previous.depth && edge.id <= previous.id)) return false
      const target = nodes.get(edge.targetVersionId), binding = `${edge.targetItemId}/${edge.targetVersionId}`
      if (!target || target.itemId !== edge.targetItemId
        || taskTargets.has(edge.taskId) && taskTargets.get(edge.taskId) !== binding
        || targetTasks.has(edge.targetVersionId) && targetTasks.get(edge.targetVersionId) !== edge.taskId) return false
      if (edge.domain === 'KNOWLEDGE') {
        const parent = edges.get(edge.parentId!), source = nodes.get(edge.sourceVersionId!)
        const parentTarget = parent && nodes.get(parent.targetVersionId)
        // 后续人工版只继承保守依赖范围，仍须保留真正选用的源版本，不能伪装成父采用版。
        if (!parent || !parentTarget || edge.depth !== parent.depth + 1 || !source
          || source.itemId !== edge.sourceItemId || edge.sourceItemId !== parent.targetItemId
          || source.versionNumber < parentTarget.versionNumber) return false
        usedVersions.add(source.versionId)
      }
      usedVersions.add(target.versionId); taskTargets.set(edge.taskId, binding); targetTasks.set(edge.targetVersionId, edge.taskId)
      edges.set(edge.id, edge); previous = edge
    }
    return usedVersions.size === nodes.size
  } catch { return false }
}
export function materialKnowledgeVersionRoute(node: MaterialKnowledgePreviewNode): RouteLocationRaw | undefined {
  return materialReferenceIdValid(node.itemId) && materialReferenceIdValid(node.versionId)
    ? { name: 'knowledge', query: { item: node.itemId, version: node.versionId } } : undefined
}
export function materialKnowledgeTaskRoute(edge: MaterialKnowledgePreviewEdge): RouteLocationRaw | undefined {
  return materialReferenceIdValid(edge.taskId) ? { name: 'ai', query: { item: edge.taskId } } : undefined
}
