<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listSettlements,
  getSettlement,
  listAssessments,
  listEvents,
  createSettlement,
  confirmSettlement,
  registerPayment,
  correctSettlement
} from '../../api'
import { SETTLEMENT_STATUS, ASSESSMENT_CONCLUSION } from '../../api/dicts'
import { fmtDateTime, fmtKwh, fmtYuan, newRequestId } from '../../utils/format'
import DictTag from '../../components/DictTag.vue'
import { getSession } from '../../stores/session'

const session = getSession()
const isPlatform = session.role === 'platform'

const loading = ref(false)
const settlements = ref([])
const events = ref([])
const eventFilter = ref('')

const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const createForm = ref({
  assessmentId: '',
  priceYuanPerKwh: 1.2,
  deductionYuan: 0,
  platformRate: 0.1,
  operatorRate: 0.3,
  stationRate: 0.6,
  ruleVersion: '',
  requestId: ''
})
const confirmableAssessments = ref([])

const detailDrawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const paymentDialogVisible = ref(false)
const paymentSubmitting = ref(false)
const paymentTarget = ref(null)
const paymentRequestId = ref('')

const correctionDialogVisible = ref(false)
const correctionSubmitting = ref(false)
const correctionTarget = ref(null)
const correctionForm = ref({
  correctionRequestId: '',
  confirmedResponseKwh: null,
  deductionYuan: null,
  reason: ''
})

const rateSum = computed(() => {
  const form = createForm.value
  return Number(form.platformRate || 0) + Number(form.operatorRate || 0) + Number(form.stationRate || 0)
})

async function loadData() {
  loading.value = true
  try {
    const [list, eventList, assessments] = await Promise.all([
      listSettlements(eventFilter.value || undefined),
      listEvents(),
      listAssessments()
    ])
    settlements.value = list
    events.value = eventList
    confirmableAssessments.value = assessments.filter((a) => a.conclusion === 'CONFIRMED')
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = {
    assessmentId: confirmableAssessments.value.length ? confirmableAssessments.value[0].assessmentId : '',
    priceYuanPerKwh: 1.2,
    deductionYuan: 0,
    platformRate: 0.1,
    operatorRate: 0.3,
    stationRate: 0.6,
    ruleVersion: '',
    requestId: newRequestId('REQ')
  }
  createDialogVisible.value = true
}

async function handleCreate() {
  const form = createForm.value
  if (!form.assessmentId) {
    ElMessage.warning('请选择评估记录（仅核定通过的可入结算）')
    return
  }
  if (!(Number(form.priceYuanPerKwh) > 0)) {
    ElMessage.warning('补偿单价必须为正')
    return
  }
  if (Math.abs(rateSum.value - 1) > 1e-9) {
    ElMessage.warning('分账比例总和必须为 1')
    return
  }
  createSubmitting.value = true
  try {
    const created = await createSettlement({
      assessmentId: form.assessmentId,
      priceYuanPerKwh: Number(form.priceYuanPerKwh),
      deductionYuan: Number(form.deductionYuan || 0),
      rule: {
        platformRate: Number(form.platformRate),
        operatorRate: Number(form.operatorRate),
        stationRate: Number(form.stationRate),
        ruleVersion: form.ruleVersion || undefined
      },
      requestId: form.requestId
    })
    ElMessage.success(`结算单已生成：${created.settlementId}（v${created.billVersion}，净实收 ${created.netYuan} 元）`)
    createDialogVisible.value = false
    await loadData()
  } catch {
    // 服务端拒绝原因（结论非核定通过/扣款超毛额/比例和≠1 等）已原样展示
  } finally {
    createSubmitting.value = false
  }
}

async function openDetail(row) {
  detailDrawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getSettlement(row.settlementId)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (detail.value) {
    await openDetail({ settlementId: detail.value.settlementId })
  }
}

async function handleConfirm(row) {
  try {
    await ElMessageBox.confirm(
      `确认结算单 ${row.settlementId}（v${row.billVersion}）？净实收 ${row.netYuan} 元，确认留痕（并发确认幂等，首条为准）。`,
      '确认结算单',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await confirmSettlement(row.settlementId)
    ElMessage.success('结算单已确认')
    await loadData()
    await refreshDetail()
  } catch {
    // 拒绝原因已原样展示
  }
}

function openPaymentDialog(row) {
  paymentTarget.value = row
  paymentRequestId.value = newRequestId('PAY')
  paymentDialogVisible.value = true
}

async function handlePayment() {
  if (!paymentRequestId.value) {
    ElMessage.warning('付款登记请求标识不能为空')
    return
  }
  paymentSubmitting.value = true
  try {
    const record = await registerPayment(paymentTarget.value.settlementId, paymentRequestId.value)
    ElMessage.success(`模拟付款已登记：${record.amountYuan} 元（渠道 SIMULATED，不接真实资金通道）`)
    paymentDialogVisible.value = false
    await loadData()
    await refreshDetail()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    paymentSubmitting.value = false
  }
}

function openCorrectionDialog(row) {
  correctionTarget.value = row
  correctionForm.value = {
    correctionRequestId: newRequestId('CORR'),
    confirmedResponseKwh: row.confirmedResponseKwh,
    deductionYuan: row.deductionYuan,
    reason: ''
  }
  correctionDialogVisible.value = true
}

async function handleCorrection() {
  const form = correctionForm.value
  if (!form.reason || !form.reason.trim()) {
    ElMessage.warning('更正原因不能为空（留痕必备）')
    return
  }
  if (form.confirmedResponseKwh === null || form.confirmedResponseKwh === undefined) {
    ElMessage.warning('更正后的核定响应电量不能为空')
    return
  }
  correctionSubmitting.value = true
  try {
    const corrected = await correctSettlement(correctionTarget.value.settlementId, {
      correctionRequestId: form.correctionRequestId,
      confirmedResponseKwh: Number(form.confirmedResponseKwh),
      deductionYuan: form.deductionYuan === null || form.deductionYuan === undefined
        ? undefined
        : Number(form.deductionYuan),
      reason: form.reason.trim()
    })
    ElMessage.success(`更正已生成 v${corrected.billVersion}（净实收 ${corrected.netYuan} 元），历史版本留存`)
    correctionDialogVisible.value = false
    await loadData()
    await refreshDetail()
  } catch {
    // 拒绝原因已原样展示
  } finally {
    correctionSubmitting.value = false
  }
}

function allocationSum(allocations) {
  return allocations.reduce((sum, line) => sum + line.amountYuan, 0)
}

onMounted(loadData)
</script>

<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span>多级分账结算</span>
        <div>
          <el-button size="small" @click="loadData">刷新</el-button>
          <el-button
            v-if="isPlatform"
            type="primary"
            size="small"
            :disabled="!confirmableAssessments.length"
            @click="openCreateDialog"
          >生成结算单</el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="page-tip">
      仅「核定通过」结论的评估可入结算；分账比例总和必须为 1；考核扣款不得超过毛额；请求标识幂等。
      付款登记为<span class="emphasis">模拟付款（渠道 SIMULATED，不接真实资金通道）</span>；
      更正生成新版本单据，历史账单版本与更正链完整留存。
    </el-alert>

    <div class="filter-bar">
      <el-select v-model="eventFilter" placeholder="按事件过滤（全部）" clearable style="width: 280px" @change="loadData">
        <el-option v-for="event in events" :key="event.eventId" :label="event.eventId" :value="event.eventId" />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="settlements" style="width: 100%">
      <el-table-column prop="settlementId" label="结算单号" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.settlementId }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="eventId" label="事件" min-width="125" show-overflow-tooltip />
      <el-table-column prop="stationId" label="场站" width="125" />
      <el-table-column prop="resourceId" label="资源（桩）" width="130" show-overflow-tooltip />
      <el-table-column label="账单版本" width="90">
        <template #default="{ row }">v{{ row.billVersion }}</template>
      </el-table-column>
      <el-table-column label="核定电量 × 单价" width="150">
        <template #default="{ row }">
          {{ fmtKwh(row.confirmedResponseKwh) }} × {{ row.priceYuanPerKwh }} 元/kWh
        </template>
      </el-table-column>
      <el-table-column label="毛额 − 扣款 = 净实收" min-width="190">
        <template #default="{ row }">
          {{ fmtYuan(row.grossYuan) }} − {{ fmtYuan(row.deductionYuan) }} =
          <span class="net-yuan">{{ fmtYuan(row.netYuan) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <DictTag :dict="SETTLEMENT_STATUS" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="生成" min-width="160">
        <template #default="{ row }">{{ row.createdBy }} · {{ fmtDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <template v-if="isPlatform">
            <el-button
              v-if="row.status === 'CREATED'"
              link
              type="success"
              size="small"
              @click="handleConfirm(row)"
            >确认</el-button>
            <el-button
              v-if="row.status !== 'CREATED' && row.status !== 'PAID'"
              link
              type="warning"
              size="small"
              @click="openPaymentDialog(row)"
            >模拟付款</el-button>
            <el-button link type="danger" size="small" @click="openCorrectionDialog(row)">更正</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前数据范围内暂无结算单" />
      </template>
    </el-table>

    <el-drawer v-model="detailDrawerVisible" title="结算单详情" size="62%">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="结算单号">{{ detail.settlementId }}</el-descriptions-item>
            <el-descriptions-item label="账单版本">
              <el-tag type="primary">v{{ detail.billVersion }}（最新）</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="事件/场站/资源">
              {{ detail.eventId }} / {{ detail.stationId }} / {{ detail.resourceId }}
            </el-descriptions-item>
            <el-descriptions-item label="评估版本三要素">
              {{ detail.assessmentId }} · {{ detail.assessmentRuleVersion }} · {{ detail.baselineVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="核定电量 × 单价">
              {{ fmtKwh(detail.confirmedResponseKwh) }} × {{ detail.priceYuanPerKwh }} 元/kWh
            </el-descriptions-item>
            <el-descriptions-item label="四量">
              毛额 {{ fmtYuan(detail.grossYuan) }} − 考核扣款 {{ fmtYuan(detail.deductionYuan) }} =
              净实收 <span class="net-yuan">{{ fmtYuan(detail.netYuan) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="规则快照">
              {{ detail.ruleSnapshot.ruleVersion }}（平台 {{ detail.ruleSnapshot.platformRate }} /
              运营商 {{ detail.ruleSnapshot.operatorRate }} / 场站 {{ detail.ruleSnapshot.stationRate }}）
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <DictTag :dict="SETTLEMENT_STATUS" :code="detail.status" />
              <span v-if="detail.confirmedAt" class="muted">确认 {{ detail.confirmedBy }} · {{ fmtDateTime(detail.confirmedAt) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4>分账明细（合计恒等于净实收 {{ fmtYuan(detail.netYuan) }}）</h4>
          <el-table :data="detail.allocations" size="small">
            <el-table-column prop="partyLabel" label="分账方" width="90" />
            <el-table-column prop="partyId" label="归属标识" min-width="140" show-overflow-tooltip />
            <el-table-column label="比例" width="90">
              <template #default="{ row }">{{ (row.rate * 100).toFixed(0) }}%</template>
            </el-table-column>
            <el-table-column label="金额" min-width="120">
              <template #default="{ row }">{{ fmtYuan(row.amountYuan) }}</template>
            </el-table-column>
            <el-table-column label="校验" min-width="140">
              <template #default="{ row }">
                <el-tag
                  :type="Math.abs(allocationSum(detail.allocations) - detail.netYuan) < 0.01 ? 'success' : 'danger'"
                  size="small"
                >
                  合计 {{ fmtYuan(allocationSum(detail.allocations)) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <h4>账单版本历史（历史版本留存，不覆盖）</h4>
          <el-table :data="detail.history" size="small">
            <el-table-column label="版本" width="80">
              <template #default="{ row }">
                v{{ row.billVersion }}
                <el-tag v-if="row.billVersion === detail.billVersion" type="primary" size="small">当前</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <DictTag :dict="SETTLEMENT_STATUS" :code="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="毛额 − 扣款 = 净实收" min-width="190">
              <template #default="{ row }">
                {{ fmtYuan(row.grossYuan) }} − {{ fmtYuan(row.deductionYuan) }} = {{ fmtYuan(row.netYuan) }}
              </template>
            </el-table-column>
            <el-table-column label="确认留痕" min-width="170">
              <template #default="{ row }">
                {{ row.confirmedBy || '-' }} {{ fmtDateTime(row.confirmedAt) }}
              </template>
            </el-table-column>
          </el-table>

          <h4>更正链（差额可由前后版本净实收独立复算）</h4>
          <el-table :data="detail.corrections" size="small">
            <el-table-column prop="correctionRequestId" label="更正请求标识" min-width="150" show-overflow-tooltip />
            <el-table-column label="版本迁移" width="100">
              <template #default="{ row }">v{{ row.fromVersion }} → v{{ row.toVersion }}</template>
            </el-table-column>
            <el-table-column label="差额" width="100">
              <template #default="{ row }">
                <el-tag :type="row.diffYuan >= 0 ? 'success' : 'danger'" size="small">{{ row.diffYuan }} 元</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operator" label="操作主体" width="110" />
            <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
            <el-table-column label="时间" min-width="150">
              <template #default="{ row }">{{ fmtDateTime(row.correctedAt) }}</template>
            </el-table-column>
            <template #empty><el-empty description="暂无更正记录" :image-size="60" /></template>
          </el-table>

          <h4>付款登记记录</h4>
          <el-table :data="detail.payments" size="small">
            <el-table-column prop="paymentRequestId" label="付款请求标识" min-width="150" show-overflow-tooltip />
            <el-table-column label="账单版本" width="90">
              <template #default="{ row }">v{{ row.billVersion }}</template>
            </el-table-column>
            <el-table-column label="金额" width="120">
              <template #default="{ row }">{{ fmtYuan(row.amountYuan) }}</template>
            </el-table-column>
            <el-table-column label="渠道" min-width="200">
              <template #default="{ row }">
                <el-tag type="warning" size="small">{{ row.channel }}</el-tag>
                {{ row.channelLabel }}
              </template>
            </el-table-column>
            <el-table-column label="登记人/时间" min-width="180">
              <template #default="{ row }">{{ row.registeredBy }} · {{ fmtDateTime(row.registeredAt) }}</template>
            </el-table-column>
            <template #empty><el-empty description="暂无付款登记记录" :image-size="60" /></template>
          </el-table>
        </template>
        <el-empty v-else-if="!detailLoading" description="结算单不存在或不在当前数据范围内" />
      </div>
    </el-drawer>

    <el-dialog v-model="createDialogVisible" title="生成结算单（平台）" width="540px">
      <el-form label-position="top">
        <el-form-item label="评估记录（仅核定通过可入结算）" required>
          <el-select v-model="createForm.assessmentId" style="width: 100%">
            <el-option
              v-for="item in confirmableAssessments"
              :key="item.assessmentId"
              :label="`${item.assessmentId}（${item.stationId}/${item.resourceId} · 核定 ${item.confirmedResponseKwh} kWh）`"
              :value="item.assessmentId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补偿单价（元/kWh，教学假设输入，须为正）" required>
          <el-input-number v-model="createForm.priceYuanPerKwh" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="考核扣款（元，可选，不得超过毛额）">
          <el-input-number v-model="createForm.deductionYuan" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="分账比例（总和必须为 1）" required>
          <div class="rate-row">
            <span>平台</span>
            <el-input-number v-model="createForm.platformRate" :min="0" :max="1" :step="0.05" :precision="2" />
            <span>运营商</span>
            <el-input-number v-model="createForm.operatorRate" :min="0" :max="1" :step="0.05" :precision="2" />
            <span>场站</span>
            <el-input-number v-model="createForm.stationRate" :min="0" :max="1" :step="0.05" :precision="2" />
          </div>
          <div class="rate-sum" :class="{ invalid: Math.abs(rateSum - 1) > 1e-9 }">
            当前合计 {{ rateSum.toFixed(2) }}（{{ Math.abs(rateSum - 1) <= 1e-9 ? '校验通过' : '必须为 1' }}）
          </div>
        </el-form-item>
        <el-form-item label="规则版本（可选，缺省教学默认版本）">
          <el-input v-model="createForm.ruleVersion" placeholder="留空使用教学默认规则快照版本" />
        </el-form-item>
        <el-form-item label="生成请求标识（幂等键）">
          <el-input v-model="createForm.requestId" readonly>
            <template #append>
              <el-button @click="createForm.requestId = newRequestId('REQ')">重新生成</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="handleCreate">生成结算单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialogVisible" title="付款登记（模拟）" width="480px">
      <el-alert type="warning" :closable="false" class="dialog-tip">
        仅登记模拟结果：渠道固定 SIMULATED，不接真实资金通道。请求标识重试幂等。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="结算单">
          <el-input :model-value="`${paymentTarget?.settlementId}（v${paymentTarget?.billVersion} · 净实收 ${paymentTarget?.netYuan} 元）`" readonly />
        </el-form-item>
        <el-form-item label="付款登记请求标识（幂等键）">
          <el-input v-model="paymentRequestId" readonly>
            <template #append>
              <el-button @click="paymentRequestId = newRequestId('PAY')">重新生成</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paymentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="paymentSubmitting" @click="handlePayment">登记模拟付款</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="correctionDialogVisible" title="更正结算单（平台）" width="540px">
      <el-alert type="info" :closable="false" class="dialog-tip">
        更正生成新版本单据，历史版本留存；更正记录自留前后版本、差额、操作主体、时间与原因，差额可由前后版本净实收独立复算。
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="结算单">
          <el-input :model-value="`${correctionTarget?.settlementId}（当前 v${correctionTarget?.billVersion} · 净实收 ${correctionTarget?.netYuan} 元）`" readonly />
        </el-form-item>
        <el-form-item label="更正后的核定响应电量（kWh）" required>
          <el-input-number v-model="correctionForm.confirmedResponseKwh" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="考核扣款（元，留空沿用原值）">
          <el-input-number v-model="correctionForm.deductionYuan" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="更正原因（留痕必备）" required>
          <el-input v-model="correctionForm.reason" type="textarea" :rows="2" placeholder="必填" />
        </el-form-item>
        <el-form-item label="更正请求标识（幂等键）">
          <el-input v-model="correctionForm.correctionRequestId" readonly>
            <template #append>
              <el-button @click="correctionForm.correctionRequestId = newRequestId('CORR')">重新生成</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="correctionSubmitting" @click="handleCorrection">生成更正版本</el-button>
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

.emphasis {
  color: #e6a23c;
  font-weight: 600;
}

.net-yuan {
  font-weight: 600;
  color: #67c23a;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.rate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.rate-sum {
  margin-top: 4px;
  font-size: 12px;
  color: #67c23a;
}

.rate-sum.invalid {
  color: #f56c6c;
}

h4 {
  margin: 16px 0 8px;
  color: #303133;
}
</style>
