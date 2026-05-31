<template>
  <div>
    <div style="margin-bottom: 16px; display: flex; justify-content: space-between">
      <h3 style="margin: 0">游戏管理</h3>
      <el-button type="primary" @click="openDialog()">新增游戏</el-button>
    </div>

    <el-table :data="games" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="gameId" label="游戏ID" width="150" />
      <el-table-column prop="gameName" label="游戏名称" width="180" />
      <el-table-column prop="platforms" label="支持平台" width="150" />
      <el-table-column label="归因窗口" width="100">
        <template #default="{ row }">{{ row.attributionWindowDays }}天</template>
      </el-table-column>
      <el-table-column label="最大重试" width="80">
        <template #default="{ row }">{{ row.callbackRetryMax }}次</template>
      </el-table-column>
      <el-table-column label="指纹降级" width="100">
        <template #default="{ row }">{{ row.fingerprintFallback ? '启用' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'danger'">{{ row.status ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="200">
        <template #default="{ row }">
          <el-button size="small" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)" :disabled="!row.status">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="editing ? '编辑游戏' : '新增游戏'" v-model="dialogVisible" width="550px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="游戏ID" required>
          <el-input v-model="form.gameId" :disabled="!!editing" placeholder="唯一标识, 如 bead_master" />
        </el-form-item>
        <el-form-item label="游戏名称" required>
          <el-input v-model="form.gameName" placeholder="如: 串珠大师" />
        </el-form-item>
        <el-form-item label="支持平台">
          <el-input v-model="form.platforms" placeholder="apk,hap,rpk" />
        </el-form-item>
        <el-form-item label="密钥(secretKey)" required>
          <el-input v-model="form.secretKey" type="password" show-password placeholder="鲸鸿动能后台复制的Base64密钥" />
        </el-form-item>
        <el-form-item label="归因窗口(天)">
          <el-input-number v-model="form.attributionWindowDays" :min="7" :max="30" />
        </el-form-item>
        <el-form-item label="最大重试次数">
          <el-input-number v-model="form.callbackRetryMax" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="窗口配置">
          <el-input
            v-model="form.windowConfig"
            type="textarea"
            :rows="4"
            placeholder='{"protection_days":7,"silence_days":0,"retain_days":[1,7],"auto_retention_callback":false}' />
        </el-form-item>
        <el-form-item label="指纹降级匹配">
          <el-switch v-model="form.fingerprintFallback" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.status" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { errorMessage, listGames, createGame, updateGame, deleteGame } from '../api/attribution'
import type { GameConfig, GameConfigForm } from '../api/types'
import { ElMessage, ElMessageBox } from 'element-plus'

const games = ref<GameConfig[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<GameConfig | null>(null)

const defaultForm: GameConfigForm = {
  gameId: '', gameName: '', platforms: 'apk,hap,rpk',
  secretKey: '', attributionWindowDays: 30, callbackRetryMax: 3,
  windowConfig: '{"protection_days":7,"silence_days":0,"retain_days":[1,7],"auto_retention_callback":false}',
  fingerprintFallback: true, status: true
}
const form = ref<GameConfigForm>({ ...defaultForm })

async function loadGames() {
  loading.value = true
  try {
    const res = await listGames()
    games.value = res.data || []
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '加载游戏列表失败'))
  } finally {
    loading.value = false
  }
}

function openDialog(row?: GameConfig) {
  if (row) {
    editing.value = row
    form.value = {
      gameId: row.gameId,
      gameName: row.gameName,
      platforms: row.platforms,
      secretKey: '****',
      attributionWindowDays: row.attributionWindowDays,
      callbackRetryMax: row.callbackRetryMax,
      windowConfig: row.windowConfig || '',
      fingerprintFallback: row.fingerprintFallback,
      status: row.status,
    }
  } else {
    editing.value = null
    form.value = { ...defaultForm }
  }
  dialogVisible.value = true
}

async function handleSave() {
  saving.value = true
  try {
    if (editing.value) {
      await updateGame(editing.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await createGame(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadGames()
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '操作失败'))
  } finally { saving.value = false }
}

async function handleDelete(row: GameConfig) {
  try {
    await ElMessageBox.confirm(`确定停用游戏 "${row.gameName}"?`, '确认', { type: 'warning' })
    await deleteGame(row.id)
    ElMessage.success('已停用')
    await loadGames()
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(errorMessage(e, '停用失败'))
    }
  }
}

onMounted(loadGames)
</script>
