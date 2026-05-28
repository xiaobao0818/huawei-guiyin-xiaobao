import axios from 'axios'
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
  err => Promise.reject(new Error(err?.response?.data?.message || err?.message || '请求失败'))
)

export function getDashboard() { return api.get<any, R<DashboardData>>('/dashboard') }
export function listGames() { return api.get<any, R<GameConfig[]>>('/games') }
export function getGame(id: number) { return api.get<any, R<GameConfig>>(`/games/${id}`) }
export function createGame(data: GameConfigForm) { return api.post<any, R<GameConfig>>('/games', data) }
export function updateGame(id: number, data: GameConfigForm) { return api.put<any, R<GameConfig>>(`/games/${id}`, data) }
export function deleteGame(id: number) { return api.delete<any, R<void>>(`/games/${id}`) }
export function listEvents(gameId: string) { return api.get<any, R<EventDefinition[]>>(`/games/${gameId}/events`) }
export function getEvent(id: number) { return api.get<any, R<EventDefinition>>(`/events/${id}`) }
export function createEvent(data: EventConfigForm & { gameId: string }) { return api.post<any, R<EventDefinition>>('/events', data) }
export function updateEvent(id: number, data: EventConfigForm) { return api.put<any, R<EventDefinition>>(`/events/${id}`, data) }
export function deleteEvent(id: number) { return api.delete<any, R<void>>(`/events/${id}`) }
export function queryAttributions(params: AttributionQuery) {
  const normalizedParams = { ...params }
  if (typeof normalizedParams.page === 'number') {
    normalizedParams.page = Math.max(0, normalizedParams.page - 1)
  }
  return api.get<any, R<Page<AttributionRecord>>>('/attribution', { params: normalizedParams })
}
export function latestAttributions(gameId: string, limit = 50) {
  return api.get<any, R<AttributionRecord[]>>(`/attribution/latest/${gameId}`, { params: { limit } })
}
export function getStats(gameId: string, startDate: string, endDate: string) {
  return api.get<any, R<GameStats>>(`/stats/${gameId}`, { params: { startDate, endDate } })
}
export function getCallbackLogs(gameId: string, page = 0, size = 20) {
  return api.get<any, R<Page<CallbackLog>>>('/callback-logs', { params: { gameId, page, size } })
}
