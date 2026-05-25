import axios from 'axios'

const api = axios.create({
  baseURL: '/admin/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

api.interceptors.response.use(
  res => res.data,
  err => Promise.reject(err)
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
export function queryAttributions(params: any) { return api.get('/attribution', { params }) }
export function latestAttributions(gameId: string, limit = 50) { return api.get(`/attribution/latest/${gameId}`, { params: { limit } }) }
export function getStats(gameId: string, startDate: string, endDate: string) {
  return api.get(`/stats/${gameId}`, { params: { startDate, endDate } })
}
export function getCallbackLogs(gameId: string, page = 0, size = 20) { return api.get('/callback-logs', { params: { gameId, page, size } }) }
