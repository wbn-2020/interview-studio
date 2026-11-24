import type { components } from './schema'

export type Schema = components['schemas']
export type ItemDetail = Schema['ItemDetail']
export type ItemSummary = Schema['ItemSummary']
export type Topic = Schema['Topic']
export type Relation = Schema['Relation']
export type GraphResponse = Schema['GraphResponse']
export type Version = Schema['Version']

type Csrf = { headerName: string; token: string }
let csrf: Csrf | undefined
let requestSessionEpoch = 0
let trainingAccessEpoch = 0

export function advanceTrainingAccess() { trainingAccessEpoch += 1 }
export function currentTrainingAccessEpoch(): number { return trainingAccessEpoch }

function carriesReference(path: string, method: string): boolean {
  const pathname = path.split('?')[0] ?? path
  const uuid = '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
  // 授权目录、匹配、撤回和使用记录只含元数据；按方法与确切路径豁免，任务及来源正文仍受资料守卫保护。
  if ((method === 'GET' || method === 'POST') && pathname === '/ai/authorizations'
    || method === 'POST' && pathname === '/ai/authorizations/matches'
    || method === 'POST' && new RegExp(`^/ai/authorizations/${uuid}/revoke$`).test(pathname)
    || method === 'GET' && new RegExp(`^/ai/tasks/${uuid}/authorization-uses$`).test(pathname)) return false
  return /^\/(knowledge|prepare|materials|bank|schedule|ai|review|memory|today|growth|search|recent|lifecycle|preferences|data-privacy)(\/|$)/.test(pathname)
    || /\/questions\/[^/]+\/evaluations(?:\/preview)?$/.test(path)
    || path.endsWith('/reference') || path.endsWith('/import-board')
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public currentRevision?: number) {
    super(message)
  }
}

export async function read<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}

export function clearCsrf() { csrf = undefined }

export function advanceRequestSession() {
  requestSessionEpoch += 1
  clearCsrf()
}

async function csrfHeader(): Promise<Record<string, string>> {
  const epoch = requestSessionEpoch
  const value = csrf ?? await read<Csrf>('/auth/csrf')
  if (epoch !== requestSessionEpoch) throw new ApiError(0, 'SESSION_CHANGED', '会话已变化，请在重新登录后用原请求重试。')
  csrf = value
  return { [csrf.headerName]: csrf.token }
}

export async function write<T>(path: string, method: string, body: unknown, key: string): Promise<T> {
  return request<T>(path, {
    method,
    headers: { ...(await csrfHeader()), 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(body),
  })
}

export async function preview<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST', headers: { ...(await csrfHeader()), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function upload<T>(path: string, file: File, sha256: string): Promise<T> {
  // uploadId 已固定本次传输身份；响应未知时复用该路径，不重新创建附件意图。
  return request<T>(path, {
    method: 'PUT', headers: { ...(await csrfHeader()), 'Content-Type': 'application/octet-stream', 'X-Content-SHA256': sha256 },
    body: file,
  }, 120_000)
}

export async function login(username: string, password: string): Promise<void> {
  await request('/auth/login', {
    method: 'POST', headers: await csrfHeader(), body: new URLSearchParams({ username, password }),
  })
  advanceRequestSession()
  await csrfHeader()
}

export async function logout(): Promise<void> {
  await request('/auth/logout', { method: 'POST', headers: await csrfHeader() })
  advanceRequestSession()
}

async function request<T>(path: string, init: RequestInit, timeoutMs = 30_000): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const epoch = requestSessionEpoch
  const trainingEpoch = trainingAccessEpoch
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`/api/v1${path}`, { ...init, credentials: 'same-origin', signal: controller.signal })
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}))
      // 旧请求仍向原调用方报告失败，但不能失活重新登录后的会话或清掉新 CSRF。
      if (epoch === requestSessionEpoch) {
        if (response.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('studio:session-expired'))
        if (response.status === 403) clearCsrf()
        if (problem.code === 'ASSISTANCE_CONFIRMATION_REQUIRED') window.dispatchEvent(new Event('studio:training-access-required'))
      }
      throw new ApiError(response.status, problem.code ?? 'HTTP_ERROR', problem.detail ?? `请求未完成（${response.status}）`, problem.currentRevision)
    }
    const result = response.status === 204 ? undefined as T : await response.json() as T
    // 成功响应也可能晚于退出／重新登录；写入结果仍未知，原键必须保留到当前会话恢复。
    if (epoch !== requestSessionEpoch) {
      throw new ApiError(0, 'SESSION_CHANGED', method === 'GET'
        ? '会话已变化，请重新读取当前内容。'
        : '会话已变化。本次响应暂不展示，写入请在重新登录后用原请求恢复。')
    }
    // 资料请求可能先于另一标签开始训练发出；成功写入仍保留原键重试，不能当作明确未执行。
    if (carriesReference(path, method) && trainingEpoch !== trainingAccessEpoch) {
      throw new ApiError(0, 'TRAINING_ACCESS_CHANGED', method === 'GET'
        ? '训练条件已变化，请重新读取当前内容。'
        : '训练条件已变化。本次响应暂不展示，写入请在确认资料权限后用原请求恢复。')
    }
    return result
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, 'CONNECTION_UNCERTAIN', method === 'GET'
      ? '读取失败或响应超时，请检查连接后重试。'
      : '连接中断或响应超时。写入结果尚未确认，请使用原请求重试。')
  } finally {
    clearTimeout(timer)
  }
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
}
