<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getEvent,
  listDeclarations,
  listPlans,
  listCommands,
  listExecutions,
  listAlerts,
  listAssessments,
  listSettlements
} from '../../api'
import {
  EVENT_LIFECYCLE,
  EVENT_TIME_STATUS,
  DECLARATION_STATUS,
  COMMAND_STATE,
  EXECUTION_CHECK_STATUS,
  ASSESSMENT_CONCLUSION,
  SETTLEMENT_STATUS
} from '../../api/dicts'
import { fmtDateTime, fmtKw, fmtKwh, fmtYuan } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'

const route = useRoute()
const eventId = route.params.eventId

const loading = ref(false)
const event = ref(null)

// 各阶段独立加载：单段失败（404/越角色）不影响其余段落，错误按段展示
const sections = ref({
  declarations: { loading: false, data: [], error: '' },
  plans: { loading: false, data: [], error: '' },
  commands: { loading: false, data: [], error: '' },
  executions: { loading: false, data: [], error: '' },
  alerts: { loading: false, data: [], error: '' },
  assessments: { loading: false, data: [], error: '' },
  settlements: { loading: false, data: [], error: '' }
})

async function loadSection(key, loader) {
  sections.value[key].loading = true
  sections.value[key].error = ''
  try {
    sections.value[key].data = await loader()
  } catch (error) {
    sections.value[key].error =
      (error.response && error.response.data && error.response.data.message) || error.message || '加载失败'
  } finally {
    sections.value[key].loading = false
  }
}

async function loadAll() {
  loading.value = true
  try {
    event.value = await getEvent(eventId)
  } catch {
    event.value = null
  } finally {
    loading.value = false
  }
  if (!event.value) {
    return
  }
  await Promise.allSettled([
    loadSection('declarations', async () => {
      const all = await listDeclarations()
      return all.filter((d) => d.eventId === eventId)
    }),
    loadSection('plans', () => listPlans(eventId)),
    loadSection('executions', () => listExecutions({ eventId })),
    loadSection('alerts', () => listAlerts({ eventId })),
    loadSection('assessments', () => listAssessments(eventId)),
    loadSection('settlements', () => listSettlements(eventId))
  ])
  // 指令段依赖计划段结果（按 planId 过滤），须在计划段完成后加载
  await loadSection('commands', async () => {
    const planIds = sections.value.plans.data.map((p) => p.planId)
    const all = await listCommands({})
    return all.filter((c) => planIds.includes(c.planId))
  })
}

// ---------------- 时间线聚合 ----------------

const steps = computed(() => {
  const d = sections.value.declarations.data
  const plans = sections.value.plans.data
  const commands = sections.value.commands.data
  const executions = sections.value.executions.data
  const alerts = sections.value.alerts.data
  const assessments = sections.value.assessments.data
  const settlements = sections.value.settlements.data

  const confirmedKw = d.filter((x) => x.status === 'CONFIRMED').reduce((sum, x) => sum + x.declaredKw, 0)
  const commandStats = commands.reduce((stats, c) => {
    stats[c.state] = (stats[c.state] || 0) + 1
    return stats
  }, {})
  const attainedCount = executions.filter((e) => e.attained).length
  const openAlerts = alerts.filter((a) => a.state === 'OPEN').length
  const manualReview = assessments.filter((a) => a.conclusion === 'MANUAL_REVIEW').length
  const totalNet = settlements.reduce((sum, s) => sum + s.netYuan, 0)

  const planOk = plans.length > 0 && plans.every((p) => p.gapKw === 0)
  const steps = [
    {
      title: '事件建立',
      status: event.value ? 'success' : 'wait',
      description: event.value
        ? `${event.value.directionLabel || event.value.direction} · 目标 ${fmtKw(event.value.targetAdjustKw)} · v${event.value.version}`
        : '未加载'
    },
    {
      title: '发布与授权',
      status: !event.value || event.value.lifecycle === 'CREATED' ? 'wait'
        : event.value.authorizedStationIds.length ? 'success' : 'process',
      description: event.value
        ? `${event.value.lifecycle === 'CREATED' ? '未发布' : '已发布'} · 已授权场站 ${event.value.authorizedStationIds.length} 个`
        : ''
    },
    {
      title: '聚合申报',
      status: d.length === 0 ? 'wait' : d.some((x) => x.status === 'CONFIRMED') ? 'success' : 'process',
      description: `申报 ${d.length} 笔 · 已确认 ${fmtKw(confirmedKw)}（仅已确认进入派单）`
    },
    {
      title: '计划分配',
      status: plans.length === 0 ? 'wait' : planOk ? 'success' : 'error',
      description: plans.length
        ? (plans.some((p) => p.gapKw > 0)
          ? `存在缺口 ${fmtKw(Math.max(...plans.map((p) => p.gapKw)))}，禁止确认/下发`
          : `计划 ${plans.length} 份 · 缺口 0`)
        : '未构建'
    },
    {
      title: '指令下发',
      status: commands.length === 0 ? 'wait'
        : commandStats.NO_RECEIPT ? 'error'
        : commandStats.ACKED ? 'success' : 'process',
      description: commands.length
        ? `指令 ${commands.length} 条（受理 ${commandStats.ACKED || 0} / 拒绝 ${commandStats.REJECTED || 0} / 无回执 ${commandStats.NO_RECEIPT || 0}）`
        : '未下发'
    },
    {
      title: '执行监测',
      status: executions.length === 0 ? 'wait'
        : openAlerts > 0 ? 'error'
        : attainedCount === executions.length ? 'success' : 'process',
      description: executions.length
        ? `执行链路 ${executions.length} 条 · 达标 ${attainedCount} 条 · 待处理告警 ${openAlerts} 条`
        : '无监测关联'
    },
    {
      title: '效果评估',
      status: assessments.length === 0 ? 'wait'
        : manualReview > 0 ? 'warning'
        : 'success',
      description: assessments.length
        ? `评估 ${assessments.length} 条 · 核定通过 ${assessments.length - manualReview} 条 · 转人工复核 ${manualReview} 条`
        : '未申请'
    },
    {
      title: '结算',
      status: settlements.length === 0 ? 'wait'
        : settlements.every((s) => s.status === 'PAID') ? 'success' : 'process',
      description: settlements.length
        ? `结算单 ${settlements.length} 份 · 最新净实收合计 ${fmtYuan(totalNet)}`
        : '未生成'
    }
  ]
  return steps
})

function stepClass(status) {
  return {
    wait: 'step-wait',
    process: 'step-process',
    success: 'step-success',
    error: 'step-error',
    warning: 'step-warning'
  }[status] || ''
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading">
    <el-card v-if="!event && !loading">
      <el-empty description="事件不存在或不在当前数据范围内（未登记与无权限口径一致，服务端返回 404）" />
    </el-card>

    <template v-else-if="event">
      <el-card class="detail-head">
        <template #header>
          <div class="card-header">
            <span>
              事件 {{ event.eventId }}
              <DictTag class="head-tag" :dict="EVENT_LIFECYCLE" :code="event.lifecycle" />
              <DictTag class="head-tag" :dict="EVENT_TIME_STATUS" :code="event.timeStatus" />
            </span>
            <el-button size="small" @click="loadAll">刷新</el-button>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="调节方向">{{ event.directionLabel || event.direction }}</el-descriptions-item>
          <el-descriptions-item label="目标调节量">{{ fmtKw(event.targetAdjustKw) }}</el-descriptions-item>
          <el-descriptions-item label="事件版本">v{{ event.version }}</el-descriptions-item>
          <el-descriptions-item label="响应时间窗">
            {{ fmtDateTime(event.windowStart) }} ~ {{ fmtDateTime(event.windowEnd) }}
          </el-descriptions-item>
          <el-descriptions-item label="申报截止">{{ fmtDateTime(event.declareDeadline) }}</el-descriptions-item>
          <el-descriptions-item label="已授权场站">
            {{ event.authorizedStationIds.length ? event.authorizedStationIds.join('、') : '未授权' }}
          </el-descriptions-item>
          <el-descriptions-item label="建立时间">{{ fmtDateTime(event.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ fmtDateTime(event.endedAt) }}</el-descriptions-item>
          <el-descriptions-item label="组织方租户">{{ event.organizerTenantId }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="detail-section">
        <template #header>全链路状态</template>
        <div class="steps-wrap">
          <div
            v-for="(step, index) in steps"
            :key="step.title"
            class="chain-step"
            :class="stepClass(step.status)"
          >
            <div class="step-index">{{ index + 1 }}</div>
            <div class="step-body">
              <div class="step-title">{{ step.title }}</div>
              <div class="step-desc">{{ step.description }}</div>
            </div>
          </div>
        </div>
        <el-alert type="info" :closable="false">
          「指令下发」显示已下发仅证明平台侧发送证据成立，不等于设备已执行；受理回执、达标判定以指令证据列与执行监测为准。
        </el-alert>
      </el-card>

      <el-card class="detail-section">
        <template #header>聚合申报（{{ sections.declarations.data.length }} 笔）</template>
        <el-table v-loading="sections.declarations.loading" :data="sections.declarations.data" size="small">
          <el-table-column prop="declarationId" label="申报单号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="stationId" label="场站" width="130" />
          <el-table-column label="申报容量" width="110">
            <template #default="{ row }">{{ fmtKw(row.declaredKw) }}</template>
          </el-table-column>
          <el-table-column prop="snapshotVersion" label="快照版本" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <DictTag :dict="DECLARATION_STATUS" :code="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="提交时间" min-width="150">
            <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无申报记录" :image-size="60" /></template>
        </el-table>
        <el-alert v-if="sections.declarations.error" type="error" :closable="false" class="section-error">
          申报段落加载失败：{{ sections.declarations.error }}
        </el-alert>
      </el-card>

      <el-row :gutter="16" class="detail-section">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>计划分配（{{ sections.plans.data.length }} 份）</template>
            <el-table v-loading="sections.plans.loading" :data="sections.plans.data" size="small">
              <el-table-column prop="planId" label="计划标识" min-width="120" show-overflow-tooltip />
              <el-table-column label="分配量" width="100">
                <template #default="{ row }">{{ fmtKw(row.adjustKw) }}</template>
              </el-table-column>
              <el-table-column label="缺口" width="130">
                <template #default="{ row }">
                  <el-tag v-if="row.gapKw > 0" type="danger">缺口 {{ row.gapKw }} kW</el-tag>
                  <el-tag v-else type="success">缺口 0</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="确认/下发" width="150">
                <template #default="{ row }">
                  <span v-if="row.confirmedAt">已确认<br></span>
                  <span v-if="row.sentAt">已下发<br></span>
                  <span v-if="!row.confirmedAt && !row.sentAt">未确认</span>
                </template>
              </el-table-column>
              <template #empty><el-empty description="暂无计划" :image-size="60" /></template>
            </el-table>
            <el-alert v-if="sections.plans.error" type="error" :closable="false" class="section-error">
              计划段落加载失败：{{ sections.plans.error }}
            </el-alert>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>指令证据（{{ sections.commands.data.length }} 条）</template>
            <el-table v-loading="sections.commands.loading" :data="sections.commands.data" size="small">
              <el-table-column prop="commandNo" label="指令编号" min-width="120" show-overflow-tooltip />
              <el-table-column prop="resourceId" label="资源" width="130" show-overflow-tooltip />
              <el-table-column label="指令状态" width="150">
                <template #default="{ row }">
                  <DictTag :dict="COMMAND_STATE" :code="row.state" />
                </template>
              </el-table-column>
              <el-table-column label="发送时标" min-width="150">
                <template #default="{ row }">{{ fmtDateTime(row.sentAt) }}</template>
              </el-table-column>
              <el-table-column label="回执时标" min-width="150">
                <template #default="{ row }">
                  {{ row.receiptAckedAtMs ? fmtDateTime(row.receiptAckedAtMs) : '无回执' }}
                </template>
              </el-table-column>
              <template #empty><el-empty description="暂无指令" :image-size="60" /></template>
            </el-table>
            <el-alert v-if="sections.commands.error" type="error" :closable="false" class="section-error">
              指令段落加载失败：{{ sections.commands.error }}
            </el-alert>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16" class="detail-section">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>执行评估（{{ sections.executions.data.length }} 条链路）</template>
            <el-table v-loading="sections.executions.loading" :data="sections.executions.data" size="small">
              <el-table-column prop="resourceId" label="资源" width="130" show-overflow-tooltip />
              <el-table-column label="指令状态" width="130">
                <template #default="{ row }">
                  <DictTag :dict="COMMAND_STATE" :code="row.commandState" />
                </template>
              </el-table-column>
              <el-table-column label="回执/到位/连续达标" width="150">
                <template #default="{ row }">
                  {{ row.receiptReceived ? '有' : '无' }} / {{ row.attained ? '达标' : '未达标' }} /
                  {{ row.steadyAttained ? '达标' : '未达标' }}
                </template>
              </el-table-column>
              <el-table-column label="核查状态" width="120">
                <template #default="{ row }">
                  <DictTag :dict="EXECUTION_CHECK_STATUS" :code="row.checkStatus" />
                </template>
              </el-table-column>
              <el-table-column label="偏差" width="120">
                <template #default="{ row }">
                  <span v-if="row.deviationKw === null || row.deviationKw === undefined">-</span>
                  <el-tag v-else :type="row.deviationExceeded ? 'danger' : 'success'" size="small">
                    {{ row.deviationKw }} kW
                  </el-tag>
                </template>
              </el-table-column>
              <template #empty><el-empty description="暂无执行评估" :image-size="60" /></template>
            </el-table>
            <el-alert v-if="sections.executions.error" type="error" :closable="false" class="section-error">
              执行评估段落加载失败：{{ sections.executions.error }}
            </el-alert>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>效果评估（{{ sections.assessments.data.length }} 条）</template>
            <el-table v-loading="sections.assessments.loading" :data="sections.assessments.data" size="small">
              <el-table-column prop="assessmentId" label="评估标识" min-width="120" show-overflow-tooltip />
              <el-table-column prop="resourceId" label="资源" width="120" show-overflow-tooltip />
              <el-table-column label="核定响应量" width="120">
                <template #default="{ row }">{{ fmtKwh(row.confirmedResponseKwh) }}</template>
              </el-table-column>
              <el-table-column label="结论" width="120">
                <template #default="{ row }">
                  <DictTag :dict="ASSESSMENT_CONCLUSION" :code="row.conclusion" />
                </template>
              </el-table-column>
              <el-table-column label="待复核原因" min-width="160">
                <template #default="{ row }">
                  <span v-if="row.reviewReasons && row.reviewReasons.length" class="review-reason">
                    {{ row.reviewReasons.join('；') }}
                  </span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <template #empty><el-empty description="暂无评估记录" :image-size="60" /></template>
            </el-table>
            <el-alert v-if="sections.assessments.error" type="error" :closable="false" class="section-error">
              效果评估段落加载失败：{{ sections.assessments.error }}
            </el-alert>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="detail-section">
        <template #header>结算单（{{ sections.settlements.data.length }} 份）</template>
        <el-table v-loading="sections.settlements.loading" :data="sections.settlements.data" size="small">
          <el-table-column prop="settlementId" label="结算单号" min-width="140" show-overflow-tooltip />
          <el-table-column label="账单版本" width="90">
            <template #default="{ row }">v{{ row.billVersion }}</template>
          </el-table-column>
          <el-table-column prop="resourceId" label="资源" width="130" show-overflow-tooltip />
          <el-table-column label="净实收" width="120">
            <template #default="{ row }">{{ fmtYuan(row.netYuan) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <DictTag :dict="SETTLEMENT_STATUS" :code="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="生成时间" min-width="150">
            <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无结算单" :image-size="60" /></template>
        </el-table>
        <el-alert v-if="sections.settlements.error" type="error" :closable="false" class="section-error">
          结算段落加载失败：{{ sections.settlements.error }}
        </el-alert>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-tag {
  margin-left: 8px;
}

.detail-section {
  margin-top: 16px;
}

.section-error {
  margin-top: 8px;
}

.review-reason {
  color: #e6a23c;
}

.steps-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.chain-step {
  flex: 1 1 200px;
  display: flex;
  gap: 8px;
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fafafa;
}

.step-index {
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: #dcdfe6;
  color: #fff;
  font-size: 12px;
  flex-shrink: 0;
}

.step-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.step-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
}

.chain-step.step-success .step-index {
  background: #67c23a;
}

.chain-step.step-process .step-index {
  background: #409eff;
}

.chain-step.step-error {
  border-color: #f56c6c;
}

.chain-step.step-error .step-index {
  background: #f56c6c;
}

.chain-step.step-warning {
  border-color: #e6a23c;
}

.chain-step.step-warning .step-index {
  background: #e6a23c;
}
</style>
