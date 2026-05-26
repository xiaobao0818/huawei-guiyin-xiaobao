import axios from 'axios'

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

export function getDashboard() { return api.get('/dashboard') }
export function listGames() { return api.get('/games') }
export function getGame(id: number) { return api.get(`/games/${id}`) }
export function createGame(data: any) { return api.post('/games', data) }
export function updateGame(id: number, data: any) { return api.put(`/games/${id}`, data) }
export function deleteGame(id: number) { return api.delete(`/games/${id}`) }
export function listEvents(gameId: string) { return api.get(`/games/${gameId}/events`) }
export function getEvent(id: number) { return api.get(`/events/${id}`) }
export function createEvent(data: any) { return api.post('/events', data) }
export function updateEvent(id: number, data: any) { return api.put(`/events/${id}`, data) }
export function deleteEvent(id: number) { return api.delete(`/events/${id}`) }
export function queryAttributions(params: any) {
  // Backend uses 0-based page indexing, frontend passes 1-based.
  // Normalize here so views don't need to worry about the offset.
  const normalizedParams = { ...params };
  if (typeof normalizedParams.page === 'number') {
    normalizedParams.page = Math.max(0, normalizedParams.page - 1);
  }
  return api.get('/attribution', { params: normalizedParams })
}
export function latestAttributions(gameId: string, limit = 50) { return api.get(`/attribution/latest/${gameId}`, { params: { limit } }) }
export function getStats(gameId: string, startDate: string, endDate: string) {
  return api.get(`/stats/${gameId}`, { params: { startDate, endDate } })
}
export function getCallbackLogs(gameId: string, page = 0, size = 20) { return api.get('/callback-logs', { params: { gameId, page, size } }) }
