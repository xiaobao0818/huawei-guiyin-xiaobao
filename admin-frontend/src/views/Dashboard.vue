<template>
  <div>
    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :span="4" v-for="item in cards" :key="item.label">
        <el-card shadow="hover">
          <div style="text-align: center">
            <div style="color: #999; font-size: 14px; margin-bottom: 8px">{{ item.label }}</div>
            <div :style="{ color: item.color, fontSize: '28px', fontWeight: 'bold' }">{{ item.value }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="8">
        <el-card>
          <template #header><span>快速操作</span></template>
          <el-button type="primary" style="width: 100%; margin-bottom: 10px" @click="$router.push('/games')">注册新游戏</el-button>
          <el-button style="width: 100%; margin-bottom: 10px" @click="$router.push('/events')">配置事件</el-button>
          <el-button style="width: 100%" @click="$router.push('/attribution')">查询归因数据</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getDashboard } from '../api/attribution'

const dashboard = ref<any>(null)

const cards = computed(() => [
  { label: '今日点击', value: dashboard.value?.todayClicks || 0, color: '#409EFF' },
  { label: '今日激活', value: dashboard.value?.todayActivates || 0, color: '#67C23A' },
  { label: '今日付费次数', value: dashboard.value?.todayPurchases || 0, color: '#E6A23C' },
  { label: '今日收入(元)', value: '¥' + (dashboard.value?.todayRevenue || 0), color: '#F56C6C' },
  { label: '回传成功率', value: ((dashboard.value?.callbackSuccessRate || 0) * 100).toFixed(1) + '%', color: '#409EFF' },
  { label: '游戏总数', value: dashboard.value?.totalGames || 0, color: '#909399' },
])

onMounted(async () => {
  try {
    const res: any = await getDashboard()
    dashboard.value = res.data
  } catch (e) {
    console.error('获取面板数据失败', e)
  }
})
</script>
