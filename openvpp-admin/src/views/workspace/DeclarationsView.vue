<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDeclarations,
  listEvents,
  submitDeclaration,
  confirmDeclaration,
  withdrawDeclaration,
  fetchMe
} from '../../api'
import { DECLARATION_STATUS } from '../../api/dicts'
import { fmtDateTime, fmtKw, newRequestId } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const session = getSession()
const isStation = session.role === 'station'
const isOperator = session.role === 'operator'

const loading = ref(false)
const declarations = ref([])
const myStationIds = ref([])

// 场站可申报事件：已发布、截止未过、已授权本场站（前端预筛，最终以服务端守门为准）
const declarableEvents = ref([])

const submitDialogVisible = ref(false)
const submitSubmitting = ref(false)
const submitForm = ref({
  eventId: '',
  stationId: '',
  declaredKw: null,
  snapshotVersion: 'demo-v1',
  requestId: ''
})

async function loadData() {
  loading.value = true
  try {
    const [list, me, events] = await Promise.all([
      listDeclarations(),
      fetchMe(),
      listEvents()
    ])
    declarations.value = list
    myStationIds.value = me.stationIds || []
    const now = Date.now()
    declarableEvents.value = events.filter((e) => {
      if (e.lifecycle !== 'PUBLISHED') return false
      if (new Date(e.declareDeadline).getTime() <= now) return false
      return e.authorizedStationIds.some((id) => myStationIds.value.includes(id))
    })
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}

function openSubmitDialog() {
  submitForm.value = {
    eventId: declarableEvents.value.length ? declarableEvents.value[0].eventId : '',
    stationId: myStationIds.value[0] || '',
    declaredKw: null,
    snapshotVersion: 'demo-v1',
    requestId: newRequestId('REQ')
  }
  submitDialogVisible.value = true
}

const selectedEvent = computed(() =>
  declarableEvents.value.find((e) => e.eventId === submitForm.value.eventId)
)

const capacityHint = computed(() => {
  const event = selectedEvent.value
  if (!event) return ''
  return '申报容量不得超过所引用能力快照在事件方向上的可信容量列，超出将被服务端拒绝（409）'
})

async function handleSubmit() {
  const form = submitForm.value
  if (!form.eventId || !form.stationId || !form.snapshotVersion || !form.requestId) {
    ElMessage.warning('请完整填写申报信息')
    return
  }
  if (!(Number(form.declaredKw) > 0)) {
    ElMessage.warning('申报容量必须为正（kW）')
    return
  }
  submitSubmitting.value = true
  try {
    const created = await submitDeclaration({
      eventId: form.eventId,
      stationId: form.stationId,
      declaredKw: Number(form.declaredKw),
      snapshotVersion: form.snapshotVersion,
      requestId: form.requestId
    })
    ElMessage.success(`申报已提交：${created.declarationId}（requestId 幂等键 ${created.requestId}）`)
    submitDialogVisible.value = false
    await loadData()
  } catch {
    // 服务端拒绝原因（截止守门/容量守门/快照失效/未授权场站）已原样展示
  } finally {
    submitSubmitting.value = false
  }
}

async function handleConfirm(declaration) {
  try {
    await ElMessageBox.confirm(
      `确认场站 ${declaration.stationId} 的申报 ${declaration.declaredKw} kW？仅已确认申报进入后续派单。`,
      '确认申报',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await confirmDeclaration(declaration.declarationId)
    ElMessage.success('申报已确认')
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  }
}

async function handleWithdraw(declaration) {
  try {
    await ElMessageBox.confirm(
      `确认撤回申报 ${declaration.declarationId}？容量预占即释放（台账留痕）。`,
      '撤回申报',
      { confirmButtonText: '撤回', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await withdrawDeclaration(declaration.declarationId)
    ElMessage.success('申报已撤回')
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  }
}

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>聚合申报</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button
            v-if="isStation"
            type="primary"
            size="small"
            :disabled="!declarableEvents.length"
            @click="openSubmitDialog"
          >提交申报</el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      申报锚定租户与场站归属一律取服务端会话；requestId 为提交幂等键，重复请求返回原申报单。
      服务端守门（截止/容量/快照版本/参与授权）拒绝时，原因会在页面上方原样弹出。
    </el-alert>

    <el-table v-loading="loading" :data="declarations" style="width: 100%">
      <el-table-column prop="declarationId" label="申报单号" min-width="140" show-overflow-tooltip />
      <el-table-column prop="eventId" label="事件" min-width="130" show-overflow-tooltip />
      <el-table-column prop="stationId" label="场站" width="130" />
      <el-table-column label="申报容量" width="110">
        <template #default="{ row }">{{ fmtKw(row.declaredKw) }}</template>
      </el-table-column>
      <el-table-column prop="snapshotVersion" label="快照版本" width="100" />
      <el-table-column prop="requestId" label="请求标识（幂等键）" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <DictTag :dict="DECLARATION_STATUS" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="提交时间" min-width="150">
        <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="迁移时间/原因" min-width="160">
        <template #default="{ row }">
          <template v-if="row.decidedAt">
            {{ fmtDateTime(row.decidedAt) }}
            <span v-if="row.decisionReason" class="decision-reason">（{{ row.decisionReason }}）</span>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="isOperator && row.status === 'SUBMITTED'"
            link
            type="success"
            size="small"
            @click="handleConfirm(row)"
          >确认</el-button>
          <el-button
            v-if="isStation && ['SUBMITTED', 'CONFIRMED'].includes(row.status)"
            link
            type="danger"
            size="small"
            @click="handleWithdraw(row)"
          >撤回</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无申报记录" />
      </template>
    </el-table>

    <el-dialog v-model="submitDialogVisible" title="提交申报（场站）" width="520px">
      <el-alert type="warning" :closable="false" class="dialog-tip">
        {{ capacityHint }}
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="事件（仅列出已发布、截止未过且已授权本场站的事件）" required>
          <el-select v-model="submitForm.eventId" style="width: 100%">
            <el-option
              v-for="event in declarableEvents"
              :key="event.eventId"
              :label="`${event.eventId}（${event.directionLabel} · 截止 ${fmtDateTime(event.declareDeadline)}）`"
              :value="event.eventId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申报场站（取会话绑定场站）" required>
          <el-select v-model="submitForm.stationId" style="width: 100%">
            <el-option v-for="station in myStationIds" :key="station" :label="station" :value="station" />
          </el-select>
        </el-form-item>
        <el-form-item label="申报容量（kW，正数）" required>
          <el-input-number v-model="submitForm.declaredKw" :min="1" :precision="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="能力快照版本" required>
          <el-input v-model="submitForm.snapshotVersion" placeholder="演示环境预置版本 demo-v1" />
        </el-form-item>
        <el-form-item label="请求标识（幂等键，重复提交返回原申报单）">
          <el-input v-model="submitForm.requestId" readonly>
            <template #append>
              <el-button @click="submitForm.requestId = newRequestId('REQ')">重新生成</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitSubmitting" @click="handleSubmit">提交申报</el-button>
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

.page-tip,
.dialog-tip {
  margin-bottom: 12px;
}

.decision-reason {
  color: #909399;
}
</style>
