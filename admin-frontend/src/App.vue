<template>
  <el-container style="min-height: 100vh">
    <el-aside width="220px" style="background: #304156">
      <div style="padding: 20px; color: #fff; font-size: 16px; font-weight: bold; text-align: center">
        鲸鸿动能归因平台
      </div>
      <el-menu
        :default-active="currentRoute"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>概览面板</span>
        </el-menu-item>
        <el-menu-item index="/games">
          <el-icon><Monitor /></el-icon>
          <span>游戏管理</span>
        </el-menu-item>
        <el-menu-item index="/events">
          <el-icon><Setting /></el-icon>
          <span>事件配置</span>
        </el-menu-item>
        <el-menu-item index="/attribution">
          <el-icon><Search /></el-icon>
          <span>归因查询</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header style="background: #fff; border-bottom: 1px solid #e6e6e6; display: flex; align-items: center; justify-content: space-between">
        <span style="font-size: 18px">{{ pageTitle }}</span>
        <span style="color: #999">v1.0.0</span>
      </el-header>
      <el-main style="background: #f5f7fa">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const currentRoute = computed(() => route.path)

const pageTitle = computed(() => {
  const map: Record<string, string> = {
    '/dashboard': '概览面板',
    '/games': '游戏管理',
    '/events': '事件配置',
    '/attribution': '归因查询',
  }
  return map[route.path] || '概览面板'
})
</script>
