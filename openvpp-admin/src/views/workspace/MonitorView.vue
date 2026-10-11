<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import {
  listExecutions,
  listAlerts,
  listEvents,
  confirmAlert,
  runMonitoringDemo
} from '../../api'
import {
  COMMAND_STATE,
  EXECUTION_CHECK_STATUS,
  ALERT_STATE,
  SAMPLE_QUALITY_MARK,
  DEMO_SCENARIO
} from '../../api/dicts'
import { fmtDateTime, fmtKw, fmtMsShort } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const session = getSession()
const isPlatform = session.role === 'platform'

const events = ref([])
const eventFilter = ref('')
const executions = ref([])
const executionsLoading = ref(false)
const selectedCommandNo = ref('')

const alerts = ref([])
const alertsLoading = ref(false)

const demoDialogVisible = ref(false)
const demoSubmitting = ref(false)
const demoScenario = ref('HEALTHY')
const demoDirection = ref('PEAK_SHAVE')

const chartRef = ref(null)
let chartInstance = null

const selectedExecution = computed(() =>
  executions.value.find((e) => e.commandNo === selectedCommandNo.value) || null
)

const stationOptions = computed(() => [...new Set(executions.value.map((e) => e.stationId))])

async function loadData() {
  executionsLoading.value = true
  alertsLoading.value = true
  try {
    const [eventList, executionList, alertList] = await Promise.all([
      listEvents(),
      listExecutions(eventFilter.value ? { eventId: eventFilter.value } : {}),
      listAlerts(eventFilter.value ? { eventId: eventFilter.value } : {})
    ])
    events.value = eventList
    executions.value = executionList
    alerts.value = alertList
    if (selectedCommandNo.value && !executionList.some((e) => e.commandNo === selectedCommandNo.value)) {
      selectedCommandNo.value = ''
    }
    if (!selectedCommandNo.value && executionList.length) {
      selectedCommandNo.value = executionList[0].commandNo
    }
    await nextTick()
    renderChart()
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    executionsLoading.value = false
    alertsLoading.value = false
  }
}

function markSizeOf(point) {
  return point.qualityMarks.includes('DUPLICATE') ? 7 : 9
}

function renderChart() {
  const execution = selectedExecution.value
  if (!chartRef.value) {
    return
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  if (!execution || !execution.curve.length) {
    chartInstance.clear()
    chartInstance.setOption({
      title: { text: '该执行链路无曲线样点（如失联场景遥测断流）', left: 'center', textStyle: { fontSize: 14 } }
    })
    return
  }

  const points = [...execution.curve].sort((a, b) => a.sampledAtMs - b.sampledAtMs)
  const mainLine = points.filter((p) => !p.qualityMarks.includes('DUPLICATE'))
  const categories = mainLine.map((p) => fmtMsShort(p.sampledAtMs))
  const beforeCommandData = mainLine.map((p) => (p.beforeCommandSent ? p.powerKw : null))
  const afterCommandData = mainLine.map((p) => (p.beforeCommandSent ? null : p.powerKw))

  const markSeries = {
    LATE: { data: [], color: '#e6a23c' },
    DUPLICATE: { data: [], color: '#909399' },
    OUT_OF_ORDER: { data: [], color: '#f56c6c' }
  }
  for (const point of points) {
    for (const mark of point.qualityMarks) {
      if (markSeries[mark]) {
        markSeries[mark].data.push([fmtMsShort(point.sampledAtMs), point.powerKw])
      }
    }
  }

  const gapAreas = (execution.gaps || []).map((gap) => [
    { name: `缺失 ${gap.missingCount} 点`, xAxis: fmtMsShort(gap.afterSampledAtMs) },
    { xAxis: fmtMsShort(gap.beforeSampledAtMs) }
  ])

  const option = {
    title: {
      text: `目标/实际功率曲线 —— ${execution.stationId} / ${execution.resourceId}`,
      subtext: `指令 ${execution.commandNo} · 目标 ${fmtKw(execution.targetPowerKw)} · 样点来源 SIMULATED（模拟遥测，非真实设备）`,
      left: 'center',
      textStyle: { fontSize: 14 }
    },
    tooltip: {
      trigger: 'axis',
      formatter(params) {
        const sampled = params[0] && params[0].axisValue
        const point = points.find((p) => fmtMsShort(p.sampledAtMs) === sampled)
        if (!point) {
          return sampled
        }
        const marks = point.qualityMarks.length
          ? point.qualityMarks.map((m) => SAMPLE_QUALITY_MARK[m]?.label || m).join('、')
          : '无'
        return [
          `序号：${point.seq}`,
          `采样时刻：${fmtMsShort(point.sampledAtMs)}`,
          `接收时刻：${fmtMsShort(point.receivedAtMs)}${point.receivedAtMs > point.sampledAtMs ? '（迟到）' : ''}`,
          `功率：${point.powerKw} kW`,
          `来源：SIMULATED（模拟遥测）`,
          `质量标记：${marks}`,
          `证据资格：${point.evidenceEligible ? '可作达标证据' : '不作达标证据（指令前样点/迟到样点）'}`
        ].join('<br>')
      }
    },
    legend: {
      bottom: 0,
      data: ['指令前基线', '响应段实测', '目标功率', '迟到样点', '重复样点', '乱序样点']
    },
    grid: { left: 50, right: 20, bottom: 60, top: 70 },
    xAxis: { type: 'category', data: categories, name: '采样时刻' },
    yAxis: { type: 'value', name: 'kW' },
    series: [
      {
        name: '指令前基线',
        type: 'line',
        data: beforeCommandData,
        connectNulls: false,
        itemStyle: { color: '#909399' },
        lineStyle: { type: 'dashed' },
        symbolSize: 4
      },
      {
        name: '响应段实测',
        type: 'line',
        data: afterCommandData,
        connectNulls: false,
        itemStyle: { color: '#409eff' },
        symbolSize: 4,
        markLine: {
          symbol: 'none',
          data: [{ yAxis: execution.targetPowerKw, name: '目标功率' }],
          lineStyle: { color: '#67c23a', type: 'solid' },
          label: { formatter: '目标 {c} kW' }
        },
        markArea: gapAreas.length
          ? { itemStyle: { color: 'rgba(245,108,108,0.12)' }, data: gapAreas }
          : undefined
      },
      {
        name: '迟到样点',
        type: 'scatter',
        data: markSeries.LATE.data,
        itemStyle: { color: markSeries.LATE.color },
        symbolSize: 10
      },
      {
        name: '重复样点',
        type: 'scatter',
        data: markSeries.DUPLICATE.data,
        itemStyle: { color: markSeries.DUPLICATE.color },
        symbol: 'triangle',
        symbolSize: 9
      },
      {
        name: '乱序样点',
        type: 'scatter',
        data: markSeries.OUT_OF_ORDER.data,
        itemStyle: { color: markSeries.OUT_OF_ORDER.color },
        symbol: 'diamond',
        symbolSize: 10
      }
    ]
  }
  chartInstance.setOption(option, true)
}

function handleResize() {
  if (chartInstance) {
    chartInstance.resize()
  }
}

async function handleConfirmAlert(alert) {
  try {
    await ElMessageBox.confirm(
      `确认告警 ${alert.alertId}（${alert.typeLabel}）？确认后同一指令同方向同类型不再重复告警，确认人/时间留痕。`,
      '告警人工确认',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await confirmAlert(alert.alertId)
    ElMessage.success('告警已确认')
    await loadData()
  } catch {
    // 404（不在数据范围）等拒绝原因已原样展示
  }
}

async function handleDemoRun() {
  demoSubmitting.value = true
  try {
    const result = await runMonitoringDemo({ scenario: demoScenario.value, direction: demoDirection.value })
    ElMessage.success(
      `演示编排完成：事件 ${result.eventId}，样点 ${result.samplesIngested}，缺口段 ${result.gapsDetected}`
    )
    demoDialogVisible.value = false
    eventFilter.value = result.eventId
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    demoSubmitting.value = false
  }
}

watch(selectedCommandNo, () => nextTick(renderChart))

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>运行监测</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button v-if="isPlatform" type="primary" size="small" @click="demoDialogVisible = true">
            监测演示编排
          </el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      回执 ≠ 到位 ≠ 连续达标三列分列，互不推导；曲线样点来源 SIMULATED 为模拟遥测，缺失段不补 0（缺口留缺并标注），
      迟到/重复/乱序样点显式标记且不作达标证据。
    </el-alert>

    <div class="filter-bar">
      <el-select v-model="eventFilter" placeholder="按事件过滤（全部）" clearable style="width: 280px" @change="loadData">
        <el-option v-for="event in events" :key="event.eventId" :label="event.eventId" :value="event.eventId" />
      </el-select>
    </div>

    <el-table
      v-loading="executionsLoading"
      :data="executions"
      size="small"
      highlight-current-row
      @current-change="(row) => row && (selectedCommandNo = row.commandNo)"
    >
      <el-table-column label="选择" width="60">
        <template #default="{ row }">
          <el-radio :model-value="selectedCommandNo" :value="row.commandNo" @change="selectedCommandNo = row.commandNo">
            <span></span>
          </el-radio>
        </template>
      </el-table-column>
      <el-table-column prop="eventId" label="事件" min-width="130" show-overflow-tooltip />
      <el-table-column prop="stationId" label="场站" width="125" />
      <el-table-column prop="resourceId" label="资源（桩）" width="130" show-overflow-tooltip />
      <el-table-column label="指令状态" width="150">
        <template #default="{ row }">
          <DictTag :dict="COMMAND_STATE" :code="row.commandState" />
        </template>
      </el-table-column>
      <el-table-column label="回执/到位/连续达标" width="150">
        <template #default="{ row }">
          {{ row.receiptReceived ? '有回执' : '无回执' }} /
          {{ row.attained ? '到位' : '未到位' }} /
          {{ row.steadyAttained ? '连续达标' : '未连续达标' }}
        </template>
      </el-table-column>
      <el-table-column label="核查状态" width="120">
        <template #default="{ row }">
          <DictTag :dict="EXECUTION_CHECK_STATUS" :code="row.checkStatus" />
        </template>
      </el-table-column>
      <el-table-column label="偏差/容差" width="130">
        <template #default="{ row }">
          <template v-if="row.deviationKw === null || row.deviationKw === undefined">-</template>
          <el-tag v-else :type="row.deviationExceeded ? 'danger' : 'success'" size="small">
            {{ row.deviationKw }} / ±{{ row.toleranceKw }} kW
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="缺失点" width="90">
        <template #default="{ row }">
          {{ row.gaps.reduce((sum, g) => sum + g.missingCount, 0) || 0 }}
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无执行链路（平台可运行监测演示编排造数）" />
      </template>
    </el-table>

    <el-card class="chart-card" shadow="never">
      <template #header>目标/实际曲线（样点质量、SIMULATED 来源、缺失与迟到点显式呈现）</template>
      <div ref="chartRef" class="monitor-chart" />
    </el-card>

    <el-card class="alerts-card" shadow="never">
      <template #header>监测告警（{{ alerts.length }} 条）</template>
      <el-table v-loading="alertsLoading" :data="alerts" size="small">
        <el-table-column prop="alertId" label="告警标识" min-width="130" show-overflow-tooltip />
        <el-table-column prop="typeLabel" label="类型" width="110" />
        <el-table-column prop="eventId" label="事件" min-width="125" show-overflow-tooltip />
        <el-table-column prop="stationId" label="场站" width="125" />
        <el-table-column prop="resourceId" label="资源（桩）" width="125" show-overflow-tooltip />
        <el-table-column label="方向" width="75">
          <template #default="{ row }">{{ row.directionLabel }}</template>
        </el-table-column>
        <el-table-column label="偏差/口径/容差" width="170">
          <template #default="{ row }">
            <template v-if="row.deviationKw === null || row.deviationKw === undefined">无偏差口径</template>
            <template v-else>
              {{ row.deviationKw }} kW · {{ row.basisLabel || '-' }} · ±{{ row.toleranceKw }} kW
            </template>
          </template>
        </el-table-column>
        <el-table-column label="样点证据" min-width="170">
          <template #default="{ row }">
            <template v-if="row.evidence">
              {{ fmtMsShort(row.evidence.sampledAtMs) }} 采样 / {{ row.evidence.powerKw }} kW
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <DictTag :dict="ALERT_STATE" :code="row.state" />
          </template>
        </el-table-column>
        <el-table-column label="产生/确认" min-width="180">
          <template #default="{ row }">
            {{ fmtDateTime(row.raisedAt) }}
            <template v-if="row.confirmedAt"><br>确认 {{ row.confirmedBy }} {{ fmtDateTime(row.confirmedAt) }}</template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.state === 'OPEN'"
              link
              type="success"
              size="small"
              @click="handleConfirmAlert(row)"
            >确认</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无告警" :image-size="60" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="demoDialogVisible" title="执行监测演示编排（平台）" width="520px">
      <el-alert type="info" :closable="false" class="dialog-tip">
        一次运行走完「计划确认 → 指令下发 → 回执 → 模拟遥测 → 质量判定 → 告警」链路；遥测类故障注入
        （迟到/缺失/重复/乱序/偏差/失联）在曲线与告警中显式呈现。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="演示场景" required>
          <el-select v-model="demoScenario" style="width: 100%">
            <el-option
              v-for="(item, key) in DEMO_SCENARIO"
              v-show="item.monitoring"
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
  margin-bottom: 12px;
}

.filter-bar {
  margin-bottom: 12px;
}

.chart-card,
.alerts-card {
  margin-top: 16px;
}

.monitor-chart {
  height: 420px;
}
</style>
