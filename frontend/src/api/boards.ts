import type { Schema } from './client'

export type BoardReferenceKind = 'KNOWLEDGE' | 'QUESTION' | 'ATTACHMENT'
export type BoardNode = { id: string; type: 'REFERENCE' | 'TEXT'; itemId: string | null; text: string; x: number; y: number; parentId: string | null; collapsed: boolean; group: string | null; referenceKind?: BoardReferenceKind | null }
export type BoardEdge = { id: string; source: string; target: string; label: string }
export type BoardViewport = { x: number; y: number; zoom: number }
export type BoardReference = { itemId: string; kind: 'NOTE' | 'CONCEPT' | 'QUESTION' | 'ATTACHMENT'; title: string; status: 'ACTIVE' | 'ARCHIVED' | 'READY'; versionId: string | null; versionNumber: number; body: string; referenceKind?: BoardReferenceKind; attachmentRevision?: number | null }
export type BoardSummary = { id: string; kind: 'MIND_MAP' | 'BOARD'; title: string; revision: number; updatedAt: string }
export type BoardDetail = BoardSummary & { nodes: BoardNode[]; edges: BoardEdge[]; viewport: BoardViewport; references: BoardReference[] }
export type BoardPage = { items: BoardSummary[]; nextCursor: string | null }
export type BoardHistoryPage = { items: { revision: number; title: string; createdAt: string }[]; nextCursor: number | null }
export type BoardSave = Pick<BoardDetail, 'title' | 'nodes' | 'edges' | 'viewport'> & { expectedRevision: number }
export type BoardRelationType = Schema['CreateRelationRequest']['type']

export function boardPayload(board: BoardDetail): BoardSave {
  return JSON.parse(JSON.stringify({ expectedRevision: board.revision, title: board.title,
    nodes: board.nodes.map(node => ({ ...node, referenceKind: node.type === 'REFERENCE' ? node.referenceKind ?? 'KNOWLEDGE' : null })),
    edges: board.edges, viewport: board.viewport }))
}

export const boardReferenceLabels: Record<BoardReferenceKind, string> = { KNOWLEDGE: '知识引用', QUESTION: '题目引用', ATTACHMENT: '附件引用' }
export function boardReferenceKey(itemId: string, kind?: BoardReferenceKind | null) { return `${kind ?? 'KNOWLEDGE'}:${itemId}` }
export function boardNodeReference(board: Pick<BoardDetail, 'references'>, node: BoardNode) {
  return node.type === 'REFERENCE' ? board.references.find(reference => reference.itemId === node.itemId
    && (reference.referenceKind ?? 'KNOWLEDGE') === (node.referenceKind ?? 'KNOWLEDGE')) : undefined
}
