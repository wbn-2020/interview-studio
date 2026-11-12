import type { Schema } from './client'

export type BoardNode = { id: string; type: 'REFERENCE' | 'TEXT'; itemId: string | null; text: string; x: number; y: number; parentId: string | null; collapsed: boolean; group: string | null }
export type BoardEdge = { id: string; source: string; target: string; label: string }
export type BoardViewport = { x: number; y: number; zoom: number }
export type BoardReference = { itemId: string; kind: 'NOTE' | 'CONCEPT'; title: string; status: 'ACTIVE' | 'ARCHIVED'; versionId: string; versionNumber: number; body: string }
export type BoardSummary = { id: string; kind: 'MIND_MAP' | 'BOARD'; title: string; revision: number; updatedAt: string }
export type BoardDetail = BoardSummary & { nodes: BoardNode[]; edges: BoardEdge[]; viewport: BoardViewport; references: BoardReference[] }
export type BoardPage = { items: BoardSummary[]; nextCursor: string | null }
export type BoardHistoryPage = { items: { revision: number; title: string; createdAt: string }[]; nextCursor: number | null }
export type BoardSave = Pick<BoardDetail, 'title' | 'nodes' | 'edges' | 'viewport'> & { expectedRevision: number }
export type BoardRelationType = Schema['CreateRelationRequest']['type']

export function boardPayload(board: BoardDetail): BoardSave {
  return JSON.parse(JSON.stringify({ expectedRevision: board.revision, title: board.title, nodes: board.nodes, edges: board.edges, viewport: board.viewport }))
}
