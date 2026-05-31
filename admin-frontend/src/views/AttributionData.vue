<template>
  <div>
    <h3 style="margin: 0 0 16px 0">归因数据查询</h3>

    <el-card style="margin-bottom: 16px">
      <el-form :inline="true" :model="query" size="default">
        <el-form-item label="游戏">
          <el-input v-model="query.gameId" placeholder="游戏ID" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="OAID">
          <el-input v-model="query.oaid" placeholder="设备OAID" clearable style="width: 250px" />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select v-model="query.eventType" placeholder="全部" clearable style="width: 140px">
            <el-option label="激活" value="activate" />
            <el-option label="注册" value="register" />
            <el-option label="付费" value="purchase" />
            <el-option label="次留" value="retain_1d" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="回传状态">
          <el-select v-model="query.callbackStatus" placeholder="全部" clearable style="width: 130px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="待回传" value="pending" />
            <el-option label="未匹配" value="unmatched" />
            <el-option label="无需回传" value="no_callback" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-table :data="records" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="gameId" label="游戏" width="120" />
      <el-table-column prop="oaid" label="OAID" min-width="200">
        <template #default="{ row }">
          <el-text truncated style="max-width: 200px">{{ row.oaid }}</el-text>
        </template>
      </el-table-column>
      <el-table-column prop="eventType" label="事件" width="100" />
      <el-table-column prop="conversionType" label="conversion_type" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.conversionType" size="small" type="primary">{{ row.conversionType }}</el-tag>
          <span v-else style="color: #909399">-</span>
        </template>
      </el-table-column>
      <el-table-column label="付费金额" width="100">
        <template #default="{ row }">¥{{ row.revenue || 0 }}</template>
      </el-table-column>
      <el-table-column prop="platform" label="平台" width="80" />
      <el-table-column label="归因方式" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="row.attributionType === 'oaid' ? 'success' : 'warning'">
            {{ row.attributionType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="回传状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.callbackStatus === 'success' ? 'success' : row.callbackStatus === 'failed' ? 'danger' : row.callbackStatus === 'unmatched' ? 'warning' : 'info'">
            {{ row.callbackStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="重试" width="60">
        <template #default="{ row }">{{ row.retryCount || 0 }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="180">
        <template #default="{ row }">{{ row.createdAt }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openLogs(row)">日志</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 16px; display: flex; justify-content: flex-end">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next"
      />
    </div>

    <el-drawer v-model="logDrawerVisible" title="回传日志" size="720px">
      <el-table :data="callbackLogs" border stripe v-loading="logLoading" empty-text="暂无回传日志">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="responseCode" label="HTTP" width="80" />
        <el-table-column prop="resultCode" label="resultCode" width="100" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="响应" min-width="220">
          <template #default="{ row }">
            <el-text truncated style="max-width: 220px">{{ row.responseBody || '-' }}</el-text>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { errorMessage, queryAttributions, getCallbackLogsByAttribution } from '../api/attribution'
import type { AttributionRecord, AttributionQuery, CallbackLog } from '../api/types'
import { ElMessage } from 'element-plus'

const records = ref<AttributionRecord[]>([])
const total = ref(0)
const loading = ref(false)
const logDrawerVisible = ref(false)
const logLoading = ref(false)
const callbackLogs = ref<CallbackLog[]>([])
let searchTimer: ReturnType<typeof setTimeout> | null = null

const query = reactive<AttributionQuery>({
  gameId: '',
  oaid: '',
  eventType: '',
  callbackStatus: '',
  page: 1,
  size: 20
})

async function search() {
  loading.value = true
  try {
    const res = await queryAttributions({ ...query })
    if (res.data) {
      records.value = res.data.content || []
      total.value = res.data.totalElements || 0
    }
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '查询归因数据失败'))
  } finally { loading.value = false }
}

function reset() {
  const shouldSearchNow = query.page === 1
  query.gameId = ''
  query.oaid = ''
  query.eventType = ''
  query.callbackStatus = ''
  query.page = 1
  if (shouldSearchNow) {
    search()
  }
}

async function openLogs(row: AttributionRecord) {
  logDrawerVisible.value = true
  logLoading.value = true
  callbackLogs.value = []
  try {
    const res = await getCallbackLogsByAttribution(row.id)
    callbackLogs.value = res.data || []
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '加载回传日志失败'))
  } finally {
    logLoading.value = false
  }
}

watch(
  () => [query.page, query.size],
  () => {
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(search, 100)
  }
)

onUnmounted(() => {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
})

search()
</script>
