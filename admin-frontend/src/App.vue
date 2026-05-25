<template>
  <el-container v-if="authed" style="min-height: 100vh">
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
        <div style="display: flex; align-items: center; gap: 12px">
          <span style="color: #999">v1.0.0</span>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main style="background: #f5f7fa">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <div v-else style="min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f5f7fa">
    <el-card style="width: 360px">
      <h3 style="margin: 0 0 20px 0; text-align: center">鲸鸿动能归因平台</h3>
      <el-form :model="loginForm" @keyup.enter="login">
        <el-form-item>
          <el-input v-model="loginForm.username" placeholder="管理员账号" autocomplete="username" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="loginForm.password" type="password" show-password placeholder="管理员密码" autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loggingIn" @click="login">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clearAuthCredentials, getDashboard, hasAuthCredentials, setAuthCredentials } from './api/attribution'

const route = useRoute()
const currentRoute = computed(() => route.path)
const authed = ref(hasAuthCredentials())
const loggingIn = ref(false)
const loginForm = reactive({ username: '', password: '' })

const pageTitle = computed(() => {
  const map: Record<string, string> = {
    '/dashboard': '概览面板',
    '/games': '游戏管理',
    '/events': '事件配置',
    '/attribution': '归因查询',
  }
  return map[route.path] || '概览面板'
})

async function login() {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.error('请输入管理员账号和密码')
    return
  }
  loggingIn.value = true
  setAuthCredentials(loginForm.username, loginForm.password)
  try {
    await getDashboard()
    authed.value = true
  } catch (e: any) {
    clearAuthCredentials()
    ElMessage.error(e?.message || '登录失败')
  } finally {
    loggingIn.value = false
  }
}

function logout() {
  clearAuthCredentials()
  authed.value = false
  loginForm.password = ''
}

onMounted(async () => {
  if (!authed.value) return
  try {
    await getDashboard()
  } catch {
    clearAuthCredentials()
    authed.value = false
  }
})
</script>
