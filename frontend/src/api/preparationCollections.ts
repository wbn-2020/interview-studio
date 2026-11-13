import { ApiError, read } from './client'
import type { Collection, CollectionKind, CollectionPage, CollectionVersion } from './collections'
import { collectionProblem } from './collections'
import type { ChosenVersion, ReferenceState } from './preparation'

export type PreparationCollectionChoice = ChosenVersion & { kind: 'COLLECTION' }
export type PreparationCollectionSnapshot = {
  id: string
  kind: CollectionKind
  state: ReferenceState
  version: CollectionVersion
}

const validId = (value: unknown): value is string => typeof value === 'string'
  && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const validNumber = (value: unknown): value is number => typeof value === 'number'
  && Number.isInteger(value) && value >= 1 && value <= 2_147_483_647
const mismatch = () => new ApiError(502, 'PREPARATION_COLLECTION_MISMATCH', '题单或路线内容与所选确切版本不一致，请重新读取。')

function versionValid(value: CollectionVersion, kind: CollectionKind): boolean {
  try {
    return !!value && validId(value.id) && validNumber(value.number) && typeof value.createdAt === 'string'
      && !!value.content && typeof value.content.title === 'string' && typeof value.content.purpose === 'string'
      && Array.isArray(value.content.steps) && value.content.steps.length <= 50 && value.content.steps.every(step =>
        !!step && validId(step.id) && validId(step.questionId) && validId(step.questionVersionId)
        && typeof step.purpose === 'string' && typeof step.checkpoint === 'string'
        && Array.isArray(step.dependsOn) && step.dependsOn.every(validId))
      && !collectionProblem(kind, value.content)
  } catch { return false }
}

export function preparationCollectionPageValid(value: CollectionPage, kind: CollectionKind): boolean {
  return !!value && Array.isArray(value.items) && value.items.length <= 20
    && new Set(value.items.map(item => item?.id)).size === value.items.length
    && value.items.every(item => !!item && validId(item.id) && item.kind === kind && item.status === 'ACTIVE'
      && validId(item.versionId) && validNumber(item.versionNumber) && typeof item.title === 'string'
      && item.title.trim().length >= 1 && item.title.length <= 200
      && Number.isInteger(item.stepCount) && item.stepCount >= 0 && item.stepCount <= 50)
    && (value.nextCursor === null || validId(value.nextCursor))
}

export async function readPreparationCollection(id: string, versionId?: string): Promise<PreparationCollectionSnapshot> {
  if (!validId(id) || (versionId !== undefined && !validId(versionId))) throw mismatch()
  const [current, selected] = await Promise.all([
    read<Collection>(`/bank/collections/${id}`),
    versionId ? read<CollectionVersion>(`/bank/collections/${id}/versions/${versionId}`) : Promise.resolve(undefined),
  ])
  if (!current || current.id !== id || !['LIST', 'ROUTE'].includes(current.kind)
    || !['ACTIVE', 'ARCHIVED'].includes(current.status) || !versionValid(current.currentVersion, current.kind)) throw mismatch()
  const version = selected ?? current.currentVersion
  if (!versionValid(version, current.kind) || (versionId !== undefined && version.id !== versionId)) throw mismatch()
  return { id, kind: current.kind, version, state: current.status === 'ARCHIVED' ? 'ARCHIVED'
    : version.id === current.currentVersion.id ? 'CURRENT' : 'SUPERSEDED' }
}
