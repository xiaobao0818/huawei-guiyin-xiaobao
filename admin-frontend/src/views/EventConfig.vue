<template>
  <div>
    <div style="margin-bottom: 16px; display: flex; justify-content: space-between; align-items: center">
      <div>
        <h3 style="margin: 0 0 10px 0">事件配置</h3>
        <el-select v-model="selectedGame" placeholder="选择游戏" @change="loadEvents" style="width: 250px">
          <el-option v-for="g in games" :key="g.gameId" :label="g.gameName + ' (' + g.gameId + ')'" :value="g.gameId" />
        </el-select>
      </div>
      <el-button type="primary" @click="openDialog()" :disabled="!selectedGame">新增事件</el-button>
    </div>

    <el-table :data="events" border stripe v-loading="loading" v-if="selectedGame">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="eventName" label="事件名" width="150" />
      <el-table-column prop="displayName" label="显示名" width="120" />
      <el-table-column label="conversion_type" width="160">
        <template #default="{ row }">
          <el-tag v-if="row.conversionType" type="primary">{{ row.conversionType }}</el-tag>
          <span v-else style="color: #909399">不回传(仅记录)</span>
        </template>
      </el-table-column>
      <el-table-column prop="paramSchema" label="参数Schema" min-width="200">
        <template #default="{ row }">
          <span style="font-size: 12px; color: #666">{{ row.paramSchema || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="预置" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.isPreset" size="small" type="info">预置</el-tag>
          <span v-else style="color: #909399">自定义</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)" :disabled="row.isPreset">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="请先选择一个游戏" />

    <el-dialog :title="editing ? '编辑事件' : '新增事件'" v-model="dialogVisible" width="550px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="游戏">
          <el-input :value="selectedGame" disabled />
        </el-form-item>
        <el-form-item label="事件名" required>
          <el-input v-model="form.eventName" :disabled="!!editing" placeholder="如 purchase, level_up" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" placeholder="如 付费, 升级" />
        </el-form-item>
        <el-form-item label="conversion_type">
          <el-select v-model="form.conversionType" placeholder="不回传则留空" clearable style="width: 100%">
            <el-option label="activate(激活)" value="activate" />
            <el-option label="register(注册)" value="register" />
            <el-option label="retain(留存)" value="retain" />
            <el-option label="paid(付费)" value="paid" />
            <el-option label="custom(自定义)" value="custom" />
            <el-option label="browse(浏览)" value="browse" />
            <el-option label="form_submit(表单提交)" value="form_submit" />
            <el-option label="addToCart(加入购物车)" value="addToCart" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数Schema">
          <el-input v-model="form.paramSchema" type="textarea" :rows="4"
            placeholder='[{"key":"revenue","type":"number","required":true,"desc":"金额"}]' />
        </el-form-item>
        <el-form-item label="回传规则">
          <el-input v-model="form.callbackRule" type="textarea" :rows="3"
            placeholder='付费阈值: {"type":"threshold","field":"eventParams.revenue","operator":"gte","value":6.0}&#10;时间窗口: {"type":"time_window","window_minutes":1440,"scope":"game:oaid"}&#10;留空表示无条件回传' />
          <div style="font-size:12px;color:#909399;margin-top:4px">JSON格式，留空=无条件回传。类型: threshold / time_window / and / or</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
import { listGames, listEvents, createEvent, updateEvent, deleteEvent } from '../api/attribution'
import type { GameConfig, EventDefinition, EventConfigForm } from '../api/types'
import { ElMessage, ElMessageBox } from 'element-plus'

const games = ref<GameConfig[]>([])
const events = ref<EventDefinition[]>([])
const selectedGame = ref('')
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref<EventDefinition | null>(null)

const defaultForm: EventConfigForm = { eventName: '', displayName: '', conversionType: '', paramSchema: '', callbackRule: '', enabled: true }
const form = ref<EventConfigForm>({ ...defaultForm })

onMounted(async () => {
  try {
    const res = await listGames()
    games.value = res.data || []
  } catch { /* */ }
})

async function loadEvents() {
  if (!selectedGame.value) return
  loading.value = true
  try {
    const res = await listEvents(selectedGame.value)
    events.value = res.data || []
  } finally { loading.value = false }
}

function openDialog(row?: EventDefinition) {
  if (row) {
    editing.value = row
    form.value = {
      eventName: row.eventName,
      displayName: row.displayName,
      conversionType: row.conversionType || '',
      paramSchema: row.paramSchema || '',
      callbackRule: row.callbackRule || '',
      enabled: row.enabled,
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
    const data = { ...form.value, gameId: selectedGame.value }
    if (editing.value) {
      await updateEvent(editing.value.id, data)
      ElMessage.success('更新成功')
    } else {
      await createEvent(data)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadEvents()
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
  } finally { saving.value = false }
}

async function handleDelete(row: EventDefinition) {
  try {
    await ElMessageBox.confirm(`确定删除事件 "${row.displayName || row.eventName}"?`, '确认删除', { type: 'warning' })
    await deleteEvent(row.id)
    ElMessage.success('已删除')
    await loadEvents()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}
</script>
