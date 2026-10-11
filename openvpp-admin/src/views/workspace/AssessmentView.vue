<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAssessments,
  listEvents,
  applyAssessment,
  reviewAssessment,
  runAssessmentDemo
} from '../../api'
import { ASSESSMENT_CONCLUSION, DEMO_SCENARIO } from '../../api/dicts'
import { fmtDateTime, fmtKwh, fmtMs, fmtRatio } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const session = getSession()
const isPlatform = session.role === 'platform'
const isOperator = session.role === 'operator'

const loading = ref(false)
const assessments = ref([])
const events = ref([])
const eventFilter = ref('')

const applyDialogVisible = ref(false)
const applySubmitting = ref(false)
const applyEventId = ref('')
const applicableEvents = ref([])

const reviewDialogVisible = ref(false)
const reviewSubmitting = ref(false)
const reviewTarget = ref(null)
const reviewNote = ref('')

const demoDialogVisible = ref(false)
const demoSubmitting = ref(false)
const demoScenario = ref('HEALTHY')
const demoDirection = ref('PEAK_SHAVE')

async function loadData() {
  loading.value = true
  try {
    const [list, eventList] = await Promise.all([
      listAssessments(eventFilter.value || undefined),
      listEvents()
    ])
    assessments.value = list
    events.value = eventList
    applicableEvents.value = eventList.filter((e) => e.lifecycle === 'ENDED')
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}

function openApplyDialog() {
  applyEventId.value = applicableEvents.value.length ? applicableEvents.value[0].eventId : ''
  applyDialogVisible.value = true
}

async function handleApply() {
  if (!applyEventId.value) {
    ElMessage.warning('请选择已结束事件')
    return
  }
  applySubmitting.value = true
  try {
    const records = await applyAssessment(applyEventId.value)
    const manual = records.filter((r) => r.conclusion === 'MANUAL_REVIEW').length
    ElMessage.success(`评估已生成 ${records.length} 条（转人工复核 ${manual} 条）`)
    applyDialogVisible.value = false
    await loadData()
  } catch {
    // 服务端拒绝原因（事件未结束/无已冻结基线链路等）已原样展示
  } finally {
    applySubmitting.value = false
  }
}

function openReviewDialog(record) {
  reviewTarget.value = record
  reviewNote.value = ''
  reviewDialogVisible.value = true
}

async function handleReview() {
  reviewSubmitting.value = true
  try {
    await reviewAssessment(reviewTarget.value.assessmentId, reviewNote.value || undefined)
    ElMessage.success('复核留痕已登记')
    reviewDialogVisible.value = false
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    reviewSubmitting.value = false
  }
}

async function handleDemoRun() {
  demoSubmitting.value = true
  try {
    const result = await runAssessmentDemo({ scenario: demoScenario.value, direction: demoDirection.value })
    ElMessage.success(`演示编排完成：事件 ${result.eventId}，评估 ${result.assessments.length} 条`)
    demoDialogVisible.value = false
    eventFilter.value = result.eventId
    await loadData()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    demoSubmitting.value = false
  }
}

const manualCount = computed(() => assessments.value.filter((a) => a.conclusion === 'MANUAL_REVIEW').length)

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>效果评估（{{ assessments.length }} 条 · 转人工复核 {{ manualCount }} 条）</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button
            v-if="isOperator"
            type="primary"
            size="small"
            :disabled="!applicableEvents.length"
            @click="openApplyDialog"
          >申请评估</el-button>
          <el-button v-if="isPlatform" size="small" @click="demoDialogVisible = true">评估演示编排</el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      核定响应量（结算依据）与看板估算（不作核定依据）严格分列；证据不足一律转人工复核，
      待复核原因逐条呈现，不把缺证据包装成核定成功。仅已结束事件可申请评估，响应前基线冻结是评估前置。
    </el-alert>

    <div class="filter-bar">
      <el-select v-model="eventFilter" placeholder="按事件过滤（全部）" clearable style="width: 280px" @change="loadData">
        <el-option v-for="event in events" :key="event.eventId" :label="event.eventId" :value="event.eventId" />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="assessments" style="width: 100%">
      <el-table-column prop="assessmentId" label="评估标识" min-width="135" show-overflow-tooltip />
      <el-table-column prop="eventId" label="事件" min-width="125" show-overflow-tooltip />
      <el-table-column prop="stationId" label="场站" width="125" />
      <el-table-column prop="resourceId" label="资源（桩）" width="130" show-overflow-tooltip />
      <el-table-column label="方向" width="75">
        <template #default="{ row }">{{ row.directionLabel }}</template>
      </el-table-column>
      <el-table-column label="追溯三要素（规则/基线/来源）" min-width="190">
        <template #default="{ row }">
          {{ row.ruleVersion }} / {{ row.baselineVersion }} / {{ row.sampleSource }}
          <br>
          <span class="muted">冻结 {{ fmtDateTime(row.frozenAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="计量区间与质量" min-width="180">
        <template #default="{ row }">
          {{ fmtMs(row.meteringStartMs) }} ~ {{ fmtMs(row.meteringEndMs) }}
          <br>
          <span class="muted">
            有效 {{ row.eligibleSamples }} / 剔除 {{ row.excludedSamples }} / 缺失 {{ row.missingSamples }}
            · 完整度 {{ fmtRatio(row.completenessRatio) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="核定响应量（结算依据）" width="150">
        <template #default="{ row }">
          <span class="confirmed-kwh">{{ fmtKwh(row.confirmedResponseKwh) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="看板估算（不作核定依据）" width="150">
        <template #default="{ row }">
          <span class="dashboard-kwh">{{ fmtKwh(row.dashboardEstimateKwh) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结论" width="110">
        <template #default="{ row }">
          <DictTag :dict="ASSESSMENT_CONCLUSION" :code="row.conclusion" />
        </template>
      </el-table-column>
      <el-table-column label="待复核原因" min-width="180">
        <template #default="{ row }">
          <ul v-if="row.reviewReasons && row.reviewReasons.length" class="review-reasons">
            <li v-for="(reason, index) in row.reviewReasons" :key="index">{{ reason }}</li>
          </ul>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="复核留痕" min-width="160">
        <template #default="{ row }">
          <template v-if="row.reviewedAt">
            {{ row.reviewedBy }} · {{ fmtDateTime(row.reviewedAt) }}
            <br>
            <span v-if="row.reviewNote" class="muted">意见：{{ row.reviewNote }}</span>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="isPlatform"
            link
            type="primary"
            size="small"
            @click="openReviewDialog(row)"
          >复核</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无评估记录（运营商对已结束事件发起申请）" />
      </template>
    </el-table>

    <el-dialog v-model="applyDialogVisible" title="申请效果评估（运营商）" width="480px">
      <el-alert type="warning" :closable="false" class="dialog-tip">
        仅已结束事件可申请；未冻结基线的资源跳过，事件无已冻结链路时服务端拒绝（409）。
      </el-alert>
      <el-select v-model="applyEventId" style="width: 100%">
        <el-option
          v-for="event in applicableEvents"
          :key="event.eventId"
          :label="`${event.eventId}（${event.directionLabel} · 结束于 ${fmtDateTime(event.endedAt)}）`"
          :value="event.eventId"
        />
      </el-select>
      <template #footer>
        <el-button @click="applyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="applySubmitting" @click="handleApply">申请评估</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewDialogVisible" title="平台复核留痕" width="480px">
      <p class="dialog-tip">
        评估 {{ reviewTarget?.assessmentId }}（{{ reviewTarget?.conclusionLabel }}）：人工定性留痕（人/时刻/意见），重复复核幂等返回首条记录。
      </p>
      <el-input
        v-model="reviewNote"
        type="textarea"
        :rows="3"
        placeholder="复核意见（可选）"
      />
      <template #footer>
        <el-button @click="reviewDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewSubmitting" @click="handleReview">登记复核</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="demoDialogVisible" title="效果评估演示编排（平台）" width="520px">
      <el-alert type="info" :closable="false" class="dialog-tip">
        一次运行走完「事件发布 → 快照 → 申报 → 确认 → 响应前基线冻结 → 派单下发 → 模拟遥测 → 事件结束 → 评估」链路。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="演示场景" required>
          <el-select v-model="demoScenario" style="width: 100%">
            <el-option
              v-for="(item, key) in DEMO_SCENARIO"
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

.page-tip,
.dialog-tip {
  margin-bottom: 12px;
}

.filter-bar {
  margin-bottom: 12px;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.confirmed-kwh {
  font-weight: 600;
  color: #67c23a;
}

.dashboard-kwh {
  color: #909399;
}

.review-reasons {
  margin: 0;
  padding-left: 16px;
  color: #e6a23c;
}
</style>
