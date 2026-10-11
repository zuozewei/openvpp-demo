<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listEvents,
  createEvent,
  publishEvent,
  finishEvent,
  authorizeEvent,
  fetchMe
} from '../../api'
import { EVENT_LIFECYCLE, EVENT_TIME_STATUS } from '../../api/dicts'
import { fmtDateTime, fmtKw, toLocalInputValue } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const router = useRouter()
const session = getSession()
const isPlatform = session.role === 'platform'

const loading = ref(false)
const events = ref([])
const meStationIds = ref([])

const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createForm = ref({
  eventId: '',
  direction: 'PEAK_SHAVE',
  windowRange: [],
  targetAdjustKw: null,
  declareDeadline: ''
})

const authorizeDialogVisible = ref(false)
const authorizeSubmitting = ref(false)
const authorizeEventId = ref('')
const authorizeStationIds = ref([])

const stationOptions = computed(() => (isPlatform ? ['cs-station-01', 'cs-station-02'] : meStationIds.value))

async function loadData() {
  loading.value = true
  try {
    events.value = await listEvents()
    if (!isPlatform) {
      const me = await fetchMe()
      meStationIds.value = me.stationIds || []
    }
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  const start = new Date(Date.now() + 3 * 3600000)
  const end = new Date(Date.now() + 4 * 3600000)
  createForm.value = {
    eventId: '',
    direction: 'PEAK_SHAVE',
    windowRange: [toLocalInputValue(start), toLocalInputValue(end)],
    targetAdjustKw: null,
    declareDeadline: toLocalInputValue(new Date(Date.now() + 2 * 3600000))
  }
  createDialogVisible.value = true
}

async function handleCreate() {
  const form = createForm.value
  if (!form.direction || !form.windowRange || form.windowRange.length !== 2 || !form.declareDeadline) {
    ElMessage.warning('请完整填写方向、响应时间窗与申报截止时刻')
    return
  }
  if (!(Number(form.targetAdjustKw) > 0)) {
    ElMessage.warning('目标调节量必须为正（kW）')
    return
  }
  createSubmitting.value = true
  try {
    const created = await createEvent({
      eventId: form.eventId || undefined,
      direction: form.direction,
      windowStart: form.windowRange[0],
      windowEnd: form.windowRange[1],
      targetAdjustKw: Number(form.targetAdjustKw),
      declareDeadline: form.declareDeadline
    })
    ElMessage.success(`事件已建立：${created.eventId}`)
    createDialogVisible.value = false
    await loadData()
  } catch {
    // 服务端拒绝原因已由请求层原样展示
  } finally {
    createSubmitting.value = false
  }
}

async function handlePublish(event) {
  try {
    await ElMessageBox.confirm(
      `确认发布事件 ${event.eventId}？发布后方可参与授权与场站申报。`,
      '发布事件',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await publishEvent(event.eventId)
    ElMessage.success('事件已发布')
    await loadData()
  } catch {
    // 409（如申报截止已过）的拒绝原因已原样展示
  }
}

async function handleFinish(event) {
  try {
    await ElMessageBox.confirm(
      `确认结束事件 ${event.eventId}？结束后申报入口关闭，且为效果评估前置条件之一。`,
      '结束事件',
      { confirmButtonText: '结束', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await finishEvent(event.eventId)
    ElMessage.success('事件已结束')
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  }
}

function openAuthorizeDialog(event) {
  authorizeEventId.value = event.eventId
  authorizeStationIds.value = [...event.authorizedStationIds]
  authorizeDialogVisible.value = true
}

async function handleAuthorize() {
  if (!authorizeStationIds.value.length) {
    ElMessage.warning('请至少勾选一个场站')
    return
  }
  authorizeSubmitting.value = true
  try {
    await authorizeEvent(authorizeEventId.value, authorizeStationIds.value)
    ElMessage.success('参与授权已登记')
    authorizeDialogVisible.value = false
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    authorizeSubmitting.value = false
  }
}

function goDetail(event) {
  router.push(`${router.currentRoute.value.path}/${event.eventId}`)
}

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>需求响应事件</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button v-if="isPlatform" type="primary" size="small" @click="openCreateDialog">
            新建事件
          </el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      事件清单按当前登录身份的数据范围返回（服务端会话解析）；跨角色操作按钮仅作入口提示，授权边界以服务端校验为准。
    </el-alert>

    <el-table v-loading="loading" :data="events" style="width: 100%">
      <el-table-column prop="eventId" label="事件标识" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row)">{{ row.eventId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="调节方向" width="90">
        <template #default="{ row }">{{ row.directionLabel || row.direction }}</template>
      </el-table-column>
      <el-table-column label="响应时间窗" min-width="200">
        <template #default="{ row }">
          {{ fmtDateTime(row.windowStart) }} ~ {{ fmtDateTime(row.windowEnd) }}
        </template>
      </el-table-column>
      <el-table-column label="目标调节量" width="110">
        <template #default="{ row }">{{ fmtKw(row.targetAdjustKw) }}</template>
      </el-table-column>
      <el-table-column label="申报截止" min-width="150">
        <template #default="{ row }">{{ fmtDateTime(row.declareDeadline) }}</template>
      </el-table-column>
      <el-table-column label="生命周期" width="100">
        <template #default="{ row }">
          <DictTag :dict="EVENT_LIFECYCLE" :code="row.lifecycle" />
        </template>
      </el-table-column>
      <el-table-column label="时间状态" width="100">
        <template #default="{ row }">
          <DictTag :dict="EVENT_TIME_STATUS" :code="row.timeStatus" />
        </template>
      </el-table-column>
      <el-table-column label="已授权场站" width="110">
        <template #default="{ row }">{{ row.authorizedStationIds.length }} 个</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="goDetail(row)">详情</el-button>
          <template v-if="isPlatform">
            <el-button
              v-if="row.lifecycle === 'CREATED'"
              link
              type="success"
              size="small"
              @click="handlePublish(row)"
            >发布</el-button>
            <el-button
              v-if="row.lifecycle === 'PUBLISHED'"
              link
              type="warning"
              size="small"
              @click="openAuthorizeDialog(row)"
            >授权场站</el-button>
            <el-button
              v-if="row.lifecycle === 'PUBLISHED'"
              link
              type="danger"
              size="small"
              @click="handleFinish(row)"
            >结束</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无事件" />
      </template>
    </el-table>

    <el-dialog v-model="createDialogVisible" title="新建需求响应事件（平台）" width="520px">
      <el-form label-position="top">
        <el-form-item label="事件标识（可选，缺省由服务端生成）">
          <el-input v-model="createForm.eventId" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="调节方向" required>
          <el-radio-group v-model="createForm.direction">
            <el-radio-button label="PEAK_SHAVE">削峰</el-radio-button>
            <el-radio-button label="VALLEY_FILL">填谷</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="响应时间窗" required>
          <el-date-picker
            v-model="createForm.windowRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="窗口开始"
            end-placeholder="窗口结束"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="目标调节量（kW，正数）" required>
          <el-input-number v-model="createForm.targetAdjustKw" :min="1" :precision="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="申报截止时刻" required>
          <el-date-picker
            v-model="createForm.declareDeadline"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="须早于响应窗口开始"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleCreate">建立事件</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="authorizeDialogVisible" title="参与授权（平台）" width="480px">
      <p class="dialog-tip">
        事件 {{ authorizeEventId }}：勾选的场站将被纳入申报范围（每次授权递增事件版本，服务端硬规则：仅已发布事件可变更参与范围）。
      </p>
      <el-checkbox-group v-model="authorizeStationIds">
        <el-checkbox v-for="station in stationOptions" :key="station" :label="station" :value="station">
          {{ station }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="authorizeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="authorizeSubmitting" @click="handleAuthorize">确认授权</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-tip {
  margin-bottom: 12px;
}

.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #606266;
}
</style>
