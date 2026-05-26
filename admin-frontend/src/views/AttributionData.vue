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
    </el-table>

    <div style="margin-top: 16px; display: flex; justify-content: flex-end">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="search"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { queryAttributions } from '../api/attribution'

const records = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
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
    const res: any = await queryAttributions({ ...query })
    if (res.data) {
      records.value = res.data.content || []
      total.value = res.data.totalElements || 0
    }
  } finally { loading.value = false }
}

function reset() {
  query.gameId = ''
  query.oaid = ''
  query.eventType = ''
  query.callbackStatus = ''
  query.page = 1
  search()
}

search()
</script>
