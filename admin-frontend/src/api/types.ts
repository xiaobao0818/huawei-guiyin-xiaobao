/** 统一响应格式 */
export interface R<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 分页数据 */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 游戏配置 */
export interface GameConfig {
  id: number
  gameId: string
  gameName: string
  platforms: string
  secretKey: string
  attributionWindowDays: number
  callbackRetryMax: number
  fingerprintFallback: boolean
  status: boolean
  createdAt: string
  updatedAt: string
}

/** 游戏配置表单 */
export interface GameConfigForm {
  gameId: string
  gameName: string
  platforms: string
  secretKey: string
  attributionWindowDays: number
  callbackRetryMax: number
  fingerprintFallback: boolean
  status: boolean
}

/** 事件定义 */
export interface EventDefinition {
  id: number
  gameId: string
  eventName: string
  displayName: string
  conversionType: string | null
  paramSchema: string | null
  isPreset: boolean
  enabled: boolean
  createdAt: string
  updatedAt: string
}

/** 事件配置表单 */
export interface EventConfigForm {
  eventName: string
  displayName: string
  conversionType: string
  paramSchema: string
  enabled: boolean
}

/** 归因记录 */
export interface AttributionRecord {
  id: number
  gameId: string
  oaid: string
  eventType: string
  conversionType: string | null
  revenue: number
  platform: string
  attributionType: string
  callbackStatus: string
  retryCount: number
  createdAt: string
}

/** 看板数据 */
export interface DashboardData {
  todayClicks: number
  todayActivates: number
  todayPurchases: number
  todayRevenue: number
  totalGames: number
  callbackSuccessRate: number
}

/** 归因查询参数 */
export interface AttributionQuery {
  gameId: string
  oaid: string
  eventType: string
  callbackStatus: string
  page: number
  size: number
}

/** 游戏统计 */
export interface GameStats {
  activates: number
  purchases: number
  revenue: number
  registers: number
  startDate: string
  endDate: string
}

/** 回传日志 */
export interface CallbackLog {
  id: number
  attributionId: number
  gameId: string
  requestUrl: string
  requestBody: string
  responseCode: number
  responseBody: string
  resultCode: number | null
  durationMs: number
  createdAt: string
}
