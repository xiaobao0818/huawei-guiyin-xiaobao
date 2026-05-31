import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import type {
  R, Page, GameConfig, GameConfigForm, EventDefinition, EventConfigForm,
  AttributionRecord, DashboardData, AttributionQuery, GameStats, CallbackLog
} from './types'

const AUTH_STORAGE_KEY = 'attributionAdminAuth'

const api = axios.create({
  baseURL: '/admin/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

function toBase64(input: string) {
  const bytes = new TextEncoder().encode(input)
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary)
}

export function setAuthCredentials(username: string, password: string) {
  sessionStorage.setItem(AUTH_STORAGE_KEY, toBase64(`${username}:${password}`))
}

export function clearAuthCredentials() {
  sessionStorage.removeItem(AUTH_STORAGE_KEY)
}

export function hasAuthCredentials() {
  return !!sessionStorage.getItem(AUTH_STORAGE_KEY)
}

export function errorMessage(error: unknown, fallback = '请求失败') {
  if (error instanceof Error && error.message) {
    return error.message
  }
  if (typeof error === 'string' && error) {
    return error
  }
  return fallback
}

function notifyUnauthorized() {
  const hadCredentials = hasAuthCredentials()
  clearAuthCredentials()
  if (hadCredentials) {
    window.dispatchEvent(new CustomEvent('attribution:unauthorized'))
  }
}

api.interceptors.request.use(config => {
  const token = sessionStorage.getItem(AUTH_STORAGE_KEY)
  if (token) {
    config.headers.Authorization = `Basic ${token}`
  }
  return config
})

api.interceptors.response.use(
  res => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  err => {
    const status = err?.response?.status
    if (status === 401 || status === 403) {
      notifyUnauthorized()
      return Promise.reject(new Error(status === 401 ? '登录已过期或凭证无效' : '无权访问'))
    }
    return Promise.reject(new Error(err?.response?.data?.message || err?.message || '请求失败'))
  }
)

function get<T>(url: string, config?: AxiosRequestConfig) {
  return api.get(url, config) as Promise<T>
}

function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
  return api.post(url, data, config) as Promise<T>
}

function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
  return api.put(url, data, config) as Promise<T>
}

function del<T>(url: string, config?: AxiosRequestConfig) {
  return api.delete(url, config) as Promise<T>
}

export function getDashboard() { return get<R<DashboardData>>('/dashboard') }
export function listGames() { return get<R<GameConfig[]>>('/games') }
export function getGame(id: number) { return get<R<GameConfig>>(`/games/${id}`) }
export function createGame(data: GameConfigForm) { return post<R<GameConfig>>('/games', data) }
export function updateGame(id: number, data: GameConfigForm) { return put<R<GameConfig>>(`/games/${id}`, data) }
export function deleteGame(id: number) { return del<R<void>>(`/games/${id}`) }
export function listEvents(gameId: string) { return get<R<EventDefinition[]>>(`/games/${gameId}/events`) }
export function getEvent(id: number) { return get<R<EventDefinition>>(`/events/${id}`) }
export function createEvent(data: EventConfigForm & { gameId: string }) { return post<R<EventDefinition>>('/events', data) }
export function updateEvent(id: number, data: EventConfigForm) { return put<R<EventDefinition>>(`/events/${id}`, data) }
export function deleteEvent(id: number) { return del<R<void>>(`/events/${id}`) }
export function queryAttributions(params: AttributionQuery) {
  const normalizedParams = { ...params }
  if (typeof normalizedParams.page === 'number') {
    normalizedParams.page = Math.max(0, normalizedParams.page - 1)
  }
  return get<R<Page<AttributionRecord>>>('/attribution', { params: normalizedParams })
}
export function latestAttributions(gameId: string, limit = 50) {
  return get<R<AttributionRecord[]>>(`/attribution/latest/${gameId}`, { params: { limit } })
}
export function getStats(gameId: string, startDate: string, endDate: string) {
  return get<R<GameStats>>(`/stats/${gameId}`, { params: { startDate, endDate } })
}
export function getCallbackLogs(gameId: string, page = 0, size = 20) {
  return get<R<Page<CallbackLog>>>('/callback-logs', { params: { gameId, page, size } })
}
export function getCallbackLogsByAttribution(attributionId: number) {
  return get<R<CallbackLog[]>>(`/callback-logs/${attributionId}`)
}
