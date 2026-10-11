<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listPlans,
  listEvents,
  listCommands,
  buildPlan,
  confirmPlan,
  sendPlan,
  getPlan,
  runPlanDemo
} from '../../api'
import { COMMAND_STATE, COMMAND_STATE_HINT, DEMO_SCENARIO } from '../../api/dicts'
import { fmtDateTime, fmtKw } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const session = getSession()
const isPlatform = session.role === 'platform'
const isOperator = session.role === 'operator'

const loading = ref(false)
const plans = ref([])
const events = ref([])
const eventFilter = ref('')

const buildDialogVisible = ref(false)
const buildSubmitting = ref(false)
const buildEventId = ref('')
const buildableEvents = ref([])

const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const planDetail = ref(null)

const commands = ref([])
const commandsLoading = ref(false)
const commandPlanFilter = ref('')

const demoDialogVisible = ref(false)
const demoSubmitting = ref(false)
const demoScenario = ref('HEALTHY')
const demoDirection = ref('PEAK_SHAVE')

async function loadData() {
  loading.value = true
  commandsLoading.value = true
  try {
    const [planList, eventList, commandList] = await Promise.all([
      listPlans(eventFilter.value || undefined),
      listEvents(),
      listCommands(commandPlanFilter.value ? { planId: commandPlanFilter.value } : {})
    ])
    plans.value = planList
    events.value = eventList
    commands.value = commandList
    const now = Date.now()
    buildableEvents.value = eventList.filter((e) => {
      if (e.lifecycle !== 'PUBLISHED') return false
      if (new Date(e.declareDeadline).getTime() > now) return false
      return !planList.some((p) => p.eventId === e.eventId)
    })
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
    commandsLoading.value = false
  }
}

function openBuildDialog() {
  buildEventId.value = buildableEvents.value.length ? buildableEvents.value[0].eventId : ''
  buildDialogVisible.value = true
}

async function handleBuild() {
  if (!buildEventId.value) {
    ElMessage.warning('请选择事件')
    return
  }
  buildSubmitting.value = true
  try {
    const detail = await buildPlan(buildEventId.value)
    ElMessage.success(`计划已构建：${detail.planId}（缺口 ${detail.gapKw} kW${detail.gapKw > 0 ? '，禁止确认/下发' : ''}）`)
    buildDialogVisible.value = false
    await loadData()
  } catch {
    // 服务端拒绝原因已原样展示（未发布/未截止等）
  } finally {
    buildSubmitting.value = false
  }
}

async function openDetail(plan) {
  detailDrawerVisible.value = true
  detailLoading.value = true
  try {
    planDetail.value = await getPlan(plan.planId)
  } catch {
    planDetail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function handleConfirmPlan(plan) {
  try {
    await ElMessageBox.confirm(
      `确认计划 ${plan.planId}？顶层确认代表整树承诺（确认人/时间留痕）。`,
      '人工确认计划',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await confirmPlan(plan.planId)
    ElMessage.success('计划已确认')
    await loadData()
  } catch {
    // 409（缺口>0 等）拒绝原因已原样展示
  }
}

async function handleSend(plan) {
  try {
    await ElMessageBox.confirm(
      `下发计划 ${plan.planId} 的全部子计划指令？重复下发将被幂等拦截；无回执指令进入核查，禁止直接重发。`,
      '派单下发',
      { confirmButtonText: '下发', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const result = await sendPlan(plan.planId)
    const summary = result.summary
    ElMessage.success(
      `已下发 ${summary.commandCount} 条指令（受理 ${summary.acked} / 拒绝 ${summary.rejected} / 无回执 ${summary.noReceipt}）`
    )
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  }
}

async function handleDemoRun() {
  demoSubmitting.value = true
  try {
    const result = await runPlanDemo({ scenario: demoScenario.value, direction: demoDirection.value })
    ElMessage.success(`演示编排完成：事件 ${result.eventId}，计划 ${result.planId}，下发 ${result.commands.length} 条指令`)
    demoDialogVisible.value = false
    eventFilter.value = result.eventId
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    demoSubmitting.value = false
  }
}

function commandHint(command) {
  return COMMAND_STATE_HINT[command.state] || ''
}

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>分级派单</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button v-if="isPlatform" type="primary" size="small" :disabled="!buildableEvents.length" @click="openBuildDialog">
            构建计划
          </el-button>
          <el-button v-if="isPlatform" size="small" @click="demoDialogVisible = true">派单演示编排</el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      申报不足时计划带缺口出生：缺口 &gt; 0 的计划确认与下发被服务端 409 拒绝（拒绝原因原样展示）。
      指令证据表中「已下发」仅指平台侧发送证据，受理回执 ≠ 功率到位，达标以执行监测为准。
    </el-alert>

    <div class="filter-bar">
      <el-select v-model="eventFilter" placeholder="按事件过滤（全部）" clearable style="width: 280px" @change="loadData">
        <el-option v-for="event in events" :key="event.eventId" :label="event.eventId" :value="event.eventId" />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="plans" style="width: 100%">
      <el-table-column prop="planId" label="计划标识" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.planId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="eventId" label="事件" min-width="130" show-overflow-tooltip />
      <el-table-column label="方向" width="80">
        <template #default="{ row }">{{ row.directionLabel }}</template>
      </el-table-column>
      <el-table-column label="分配量" width="100">
        <template #default="{ row }">{{ fmtKw(row.adjustKw) }}</template>
      </el-table-column>
      <el-table-column label="缺口" width="160">
        <template #default="{ row }">
          <el-tag v-if="row.gapKw > 0" type="danger">缺口 {{ row.gapKw }} kW · 禁止确认/下发</el-tag>
          <el-tag v-else type="success">缺口 0</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="层级/行数" width="100">
        <template #default="{ row }">{{ row.level }} / {{ row.lineCount }} 行</template>
      </el-table-column>
      <el-table-column label="确认状态" width="150">
        <template #default="{ row }">
          <span v-if="row.confirmedAt">{{ row.confirmedBy }}<br>{{ fmtDateTime(row.confirmedAt) }}</span>
          <el-tag v-else type="info" size="small">未确认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下发/回执" width="160">
        <template #default="{ row }">
          <span v-if="row.sentAt">下发 {{ fmtDateTime(row.sentAt) }}<br></span>
          <span v-if="row.ackedAt">回执齐 {{ fmtDateTime(row.ackedAt) }}</span>
          <el-tag v-if="!row.sentAt" type="info" size="small">未下发</el-tag>
          <el-tag v-else-if="!row.ackedAt" type="warning" size="small">回执未齐</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <template v-if="isOperator">
            <el-button
              v-if="!row.confirmedAt"
              link
              type="success"
              size="small"
              @click="handleConfirmPlan(row)"
            >确认</el-button>
            <el-button
              v-if="row.confirmedAt && !row.sentAt"
              link
              type="warning"
              size="small"
              @click="handleSend(row)"
            >下发</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无计划（平台可按事件构建）" />
      </template>
    </el-table>

    <el-card class="commands-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>指令证据（状态 / 发送时标 / 回执时标分列，无回执即核查待处置证据）</span>
          <el-select
            v-model="commandPlanFilter"
            placeholder="按计划过滤（全部）"
            clearable
            size="small"
            style="width: 240px"
            @change="loadData"
          >
            <el-option v-for="plan in plans" :key="plan.planId" :label="plan.planId" :value="plan.planId" />
          </el-select>
        </div>
      </template>
      <el-table v-loading="commandsLoading" :data="commands" size="small">
        <el-table-column prop="commandNo" label="指令编号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="planId" label="计划" min-width="120" show-overflow-tooltip />
        <el-table-column prop="stationId" label="场站" width="120" />
        <el-table-column prop="resourceId" label="资源（桩）" width="130" show-overflow-tooltip />
        <el-table-column label="目标功率" width="100">
          <template #default="{ row }">{{ fmtKw(row.targetPowerKw) }}</template>
        </el-table-column>
        <el-table-column label="指令状态" width="170">
          <template #default="{ row }">
            <el-tooltip :content="commandHint(row)" :disabled="!commandHint(row)" placement="top">
              <span><DictTag :dict="COMMAND_STATE" :code="row.state" /></span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="发送时标（平台侧证据）" min-width="160">
          <template #default="{ row }">{{ fmtDateTime(row.sentAt) }}</template>
        </el-table-column>
        <el-table-column label="受理回执时标（设备侧证据）" min-width="170">
          <template #default="{ row }">
            <span v-if="row.receiptAckedAtMs">{{ fmtDateTime(row.receiptAckedAtMs) }}</span>
            <el-tag v-else type="danger" size="small">无回执</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回执结果/拒绝原因" min-width="150">
          <template #default="{ row }">
            <span v-if="row.receiptStatus">{{ row.receiptStatus }}</span>
            <span v-if="row.rejectReason" class="reject-reason">{{ row.rejectReason }}</span>
            <span v-if="!row.receiptStatus && !row.rejectReason">-</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当前数据范围内暂无指令" :image-size="60" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="buildDialogVisible" title="构建分级计划（平台）" width="480px">
      <p class="dialog-tip">
        选择已发布且申报已截止的事件；构建后按已确认申报分配，申报不足时缺口显性给出。
      </p>
      <el-select v-model="buildEventId" style="width: 100%">
        <el-option
          v-for="event in buildableEvents"
          :key="event.eventId"
          :label="`${event.eventId}（${event.directionLabel} · 目标 ${event.targetAdjustKw} kW）`"
          :value="event.eventId"
        />
      </el-select>
      <template #footer>
        <el-button @click="buildDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="buildSubmitting" @click="handleBuild">构建计划</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="demoDialogVisible" title="分级派单演示编排（平台）" width="480px">
      <el-alert type="info" :closable="false" class="dialog-tip">
        一次运行走完「建事件 → 发布 → 授权 → 快照 → 申报 → 确认 → 构建计划 → 确认 → 下发 → 回执」全链路；失联场景指令无回执进入核查。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="演示场景" required>
          <el-select v-model="demoScenario" style="width: 100%">
            <el-option
              v-for="(item, key) in DEMO_SCENARIO"
              v-show="item.dispatch"
              :key="key"
              :label="item.label"
              :value="key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调节方向" required>
          <el-radio-group v-model="demoDirection">
            <el-radio-button label="PEAK_SHAVE">削峰</el-radio-button>
            <el-radio-button label="VALLEY_FILL">填谷</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="demoDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="demoSubmitting" @click="handleDemoRun">运行演示</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawerVisible" title="计划详情" size="60%">
      <div v-loading="detailLoading">
        <template v-if="planDetail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="计划标识">{{ planDetail.planId }}</el-descriptions-item>
            <el-descriptions-item label="事件">{{ planDetail.eventId }}</el-descriptions-item>
            <el-descriptions-item label="方向">{{ planDetail.directionLabel }}</el-descriptions-item>
            <el-descriptions-item label="层级">{{ planDetail.level }}</el-descriptions-item>
            <el-descriptions-item label="分配量">{{ fmtKw(planDetail.adjustKw) }}</el-descriptions-item>
            <el-descriptions-item label="缺口">
              <el-tag v-if="planDetail.gapKw > 0" type="danger">缺口 {{ planDetail.gapKw }} kW</el-tag>
              <el-tag v-else type="success">缺口 0</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="确认">{{ planDetail.confirmedBy || '未确认' }} {{ fmtDateTime(planDetail.confirmedAt) }}</el-descriptions-item>
            <el-descriptions-item label="下发/回执">
              {{ fmtDateTime(planDetail.sentAt) }} / {{ fmtDateTime(planDetail.ackedAt) }}
            </el-descriptions-item>
          </el-descriptions>

          <h4>分配行（单桩调节量 + 绝对目标功率）</h4>
          <el-table :data="planDetail.lines" size="small">
            <el-table-column prop="subjectId" label="分配对象" min-width="120" show-overflow-tooltip />
            <el-table-column prop="stationId" label="场站" width="130" />
            <el-table-column label="调节量" width="100">
              <template #default="{ row }">{{ fmtKw(row.adjustKw) }}</template>
            </el-table-column>
            <el-table-column label="基线" width="100">
              <template #default="{ row }">{{ fmtKw(row.baselineKw) }}</template>
            </el-table-column>
            <el-table-column label="绝对目标功率" width="120">
              <template #default="{ row }">{{ fmtKw(row.targetPowerKw) }}</template>
            </el-table-column>
            <el-table-column prop="snapshotVersion" label="快照版本" width="100" />
          </el-table>

          <template v-if="planDetail.children && planDetail.children.length">
            <h4>子计划</h4>
            <el-table :data="planDetail.children" size="small">
              <el-table-column prop="planId" label="计划标识" min-width="130" show-overflow-tooltip />
              <el-table-column prop="level" label="层级" width="80" />
              <el-table-column label="分配量" width="100">
                <template #default="{ row }">{{ fmtKw(row.adjustKw) }}</template>
              </el-table-column>
              <el-table-column label="缺口" width="120">
                <template #default="{ row }">
                  <el-tag :type="row.gapKw > 0 ? 'danger' : 'success'" size="small">
                    {{ row.gapKw > 0 ? `缺口 ${row.gapKw} kW` : '缺口 0' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="下发" width="150">
                <template #default="{ row }">{{ fmtDateTime(row.sentAt) }}</template>
              </el-table-column>
            </el-table>
          </template>

          <h4>版本历史</h4>
          <el-table :data="planDetail.versions" size="small">
            <el-table-column label="版本" width="70">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="分配量" width="100">
              <template #default="{ row }">{{ fmtKw(row.adjustKw) }}</template>
            </el-table-column>
            <el-table-column label="缺口" width="110">
              <template #default="{ row }">
                <el-tag :type="row.gapKw > 0 ? 'danger' : 'success'" size="small">
                  {{ row.gapKw > 0 ? `缺口 ${row.gapKw}` : '缺口 0' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="确认人/时间" min-width="160">
              <template #default="{ row }">
                {{ row.confirmedBy || '-' }} {{ fmtDateTime(row.confirmedAt) }}
              </template>
            </el-table-column>
            <el-table-column label="下发/回执" min-width="180">
              <template #default="{ row }">
                {{ fmtDateTime(row.sentAt) }} / {{ fmtDateTime(row.ackedAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="createdBy" label="构建人" width="110" />
            <el-table-column label="构建时间" min-width="150">
              <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!detailLoading" description="计划不存在或不在当前数据范围内" />
      </div>
    </el-drawer>
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

.filter-bar {
  margin-bottom: 12px;
}

.commands-card {
  margin-top: 16px;
}

.reject-reason {
  color: #f56c6c;
}

h4 {
  margin: 16px 0 8px;
  color: #303133;
}
</style>
