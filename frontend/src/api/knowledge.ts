import type { Schema } from './client'
export type Organization = Schema['Organization']
export type Connection = Schema['Connection']
export type ConnectionPage = Schema['ConnectionPage']
export type ConnectionType = Schema['ConnectionKind']
export type SearchHit = Schema['SearchHit']
export type SearchState = Schema['SearchState']
export type SearchResponse = Schema['SearchResponse']
export type SearchStatus = Schema['SearchStatus']
export type OpenKnowledge = { id: string; versionId?: string; lineStart?: number; lineEnd?: number }
