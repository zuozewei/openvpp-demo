// Mock 数据层：VITE_USE_MOCK=true 时接管全部 /api/v1/operations 请求，
// 以内存状态机模拟后端全链路（响应字段名与后端响应视图严格一致，camelCase）。
// 数据全部为虚构演示数据；守卫口径是后端教学规则的简化版（拒绝信息同风格）。

import { DEMO_ACCOUNTS } from './accounts'

const MOCK_DELAY_MS = 250
const TENANT = 'tenant-openvpp'
const STATIONS = ['cs-station-01', 'cs-station-02']

// 演示能力快照（与后端 ChargeDemoFixture 种入值一致，虚构数据）
const SNAPSHOTS = {
  'cs-station-01': {
    stationId: 'cs-station-01', resourceId: 'ev-cs-station-01',
    ratedPowerKw: 2400, baselineKw: 900, crediblePeakKw: 600, credibleValleyKw: 800,
    assessVersion: 'demo-v1', sessionFingerprint: 'fp-cs01-sess-a'
  },
  'cs-station-02': {
    stationId: 'cs-station-02', resourceId: 'ev-cs-station-02',
    ratedPowerKw: 720, baselineKw: 300, crediblePeakKw: 180, credibleValleyKw: 240,
    assessVersion: 'demo-v1', sessionFingerprint: 'fp-cs02-sess-a'
  }
}

const ROLE_OF_TOKEN = /mock-token-(platform|operator|station)-/

let db = null
let seq = 0

function now() {
  return new Date()
}

function nowIso() {
  return toIso(now())
}

function toIso(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function nextId(prefix) {
  seq += 1
  return `${prefix}-${String(seq).padStart(4, '0')}`
}

function audit(action, targetType, targetId, targetVersion, result, detail) {
  db.auditLogs.unshift({
    auditId: nextId('AUDIT'),
    accountId: db.role === 'platform' ? 'platform_admin' : db.role === 'operator' ? 'operator_admin' : 'station_admin',
    tenantId: TENANT,
    role: db.role || '-',
    action,
    targetType,
    targetId,
    targetVersion,
    result,
    detail,
    createdMs: Date.now()
  })
}

function reject(code, message) {
  const error = new Error(message)
  error.code = code
  throw error
}

function requireRole(...roles) {
  if (!roles.includes(db.role)) {
    reject(401, '当前角色无权执行该操作（服务端授权边界，Mock 拦截）')
  }
}

// ---------------- 内存状态装配 ----------------

function buildFullChain({ scenario, direction, ended }) {
  const dir = direction || (scenario === 'VALLEY_BIAS' ? 'VALLEY_FILL' : 'PEAK_SHAVE')
  const peak = dir === 'PEAK_SHAVE'
  const created = now()
  const deadline = new Date(created.getTime() + 30 * 60 * 1000)
  const windowStart = new Date(deadline.getTime() + 60 * 60 * 1000)
  const windowEnd = new Date(windowStart.getTime() + 30 * 60 * 1000)
  const eventId = nextId('EVT')
  const targetAdjustKw = 500

  const event = {
    eventId, organizerTenantId: TENANT,
    direction: dir, directionLabel: peak ? '削峰' : '填谷',
    windowStart: toIso(windowStart), windowEnd: toIso(windowEnd),
    targetAdjustKw, declareDeadline: toIso(deadline),
    lifecycle: 'PUBLISHED', version: 3,
    timeStatus: ended ? 'ENDED' : 'PENDING',
    authorizedStationIds: [...STATIONS],
    createdAt: toIso(created), endedAt: ended ? toIso(windowEnd) : null
  }
  db.events.push(event)

  const declarations = STATIONS.map((stationId) => {
    const snap = SNAPSHOTS[stationId]
    const declaredKw = Math.min(peak ? snap.crediblePeakKw : snap.credibleValleyKw, 400)
    const declaration = {
      declarationId: nextId('DECL'), eventId, tenantId: TENANT, stationId,
      declaredKw, snapshotVersion: snap.assessVersion, requestId: nextId('REQ'),
      status: 'CONFIRMED', createdAt: toIso(created), decidedAt: toIso(deadline), decisionReason: null
    }
    db.declarations.push(declaration)
    return declaration
  })

  const planId = nextId('PLAN')
  const sumKw = declarations.reduce((sum, d) => sum + d.declaredKw, 0)
  const gapKw = Math.max(0, targetAdjustKw - sumKw)
  const lines = declarations.map((d) => {
    const snap = SNAPSHOTS[d.stationId]
    return {
      subjectId: d.stationId, stationId: d.stationId,
      adjustKw: d.declaredKw, baselineKw: snap.baselineKw,
      targetPowerKw: peak ? snap.baselineKw - d.declaredKw : snap.baselineKw + d.declaredKw,
      snapshotVersion: snap.assessVersion
    }
  })
  const offline = scenario === 'OFFLINE'
  const sentAt = toIso(new Date(windowStart.getTime() - 5 * 60 * 1000))
  const plan = {
    planId, parentPlanId: null, eventId, organizerTenantId: TENANT,
    level: 'TOP', version: 1, direction: dir, directionLabel: peak ? '削峰' : '填谷',
    adjustKw: sumKw, baselineKw: null, gapKw,
    dispatchable: gapKw === 0,
    confirmedBy: 'operator_admin', confirmedAt: toIso(new Date(deadline.getTime() + 5 * 60 * 1000)),
    sentAt, ackedAt: offline ? null : sentAt,
    createdBy: 'platform_admin', createdAt: toIso(deadline),
    lineCount: lines.length, lines,
    versions: [{
      version: 1, adjustKw: sumKw, gapKw,
      confirmedBy: 'operator_admin', confirmedAt: toIso(new Date(deadline.getTime() + 5 * 60 * 1000)),
      sentAt, ackedAt: offline ? null : sentAt,
      createdBy: 'platform_admin', createdAt: toIso(deadline)
    }]
  }
  db.plans.push(plan)

  const commands = lines.map((line) => {
    const command = {
      commandNo: nextId('CMD'), planId, planVersion: 1,
      resourceId: SNAPSHOTS[line.stationId].resourceId, stationId: line.stationId,
      adjustKw: line.adjustKw, baselineKw: line.baselineKw, targetPowerKw: line.targetPowerKw,
      originalCommandNo: null,
      state: offline ? 'NO_RECEIPT' : 'ACKED',
      createdAt: plan.createdAt, sentAt,
      receiptAckedAtMs: offline ? null : new Date(sentAt).getTime() + 800,
      receiptStatus: offline ? null : 'ACCEPTED',
      rejectReason: null
    }
    db.commands.push(command)
    return command
  })

  // 曲线：窗口内 61 个点（30 秒间隔），指令前为基线、指令后为目标的确定性模拟
  const windowStartMs = windowStart.getTime()
  const intervalMs = 30 * 1000
  const sampleCount = 61
  const late = scenario === 'LATE_STREAM'
  const missing = scenario === 'MISSING_POINTS'
  const duplicate = scenario === 'DUPLICATE_POINTS'
  const outOfOrder = scenario === 'OUT_OF_ORDER_POINTS'
  const bias = scenario === 'PEAK_BIAS' ? 10 : scenario === 'VALLEY_BIAS' ? -10 : 0
  const zeroTarget = scenario === 'ZERO_TARGET'

  const executions = []
  const alerts = []
  let samplesIngested = 0
  let gapsDetected = 0

  for (const command of commands) {
    const points = []
    for (let i = 0; i < sampleCount; i += 1) {
      if (missing && (i === 5 || i === 6)) {
        continue
      }
      const sampledAtMs = windowStartMs + i * intervalMs
      const beforeCommand = sampledAtMs < new Date(sentAt).getTime()
      const base = zeroTarget ? 0 : command.targetPowerKw
      const powerKw = beforeCommand
        ? command.baselineKw
        : Math.round((base + 2 * Math.sin(i / 3) + bias + (zeroTarget ? 8 : 0)) * 100) / 100
      const qualityMarks = []
      if (late && !beforeCommand) qualityMarks.push('LATE')
      if (duplicate && !beforeCommand && i % 3 === 0) qualityMarks.push('DUPLICATE')
      if (outOfOrder && !beforeCommand && i % 2 === 0) qualityMarks.push('OUT_OF_ORDER')
      points.push({
        seq: i + 1,
        sampledAtMs,
        receivedAtMs: sampledAtMs + (late && !beforeCommand ? 60 * 1000 : 0),
        powerKw,
        source: 'SIMULATED',
        qualityMarks,
        evidenceEligible: !beforeCommand && !(late && !beforeCommand),
        beforeCommandSent: beforeCommand
      })
      samplesIngested += 1
      if (duplicate && !beforeCommand && i % 3 === 0 && i > 0) {
        points.push({ ...points[points.length - 1], seq: i + 1 })
        samplesIngested += 1
      }
    }
    const gaps = missing ? [{
      fromSeq: 5, toSeq: 6, missingCount: 2,
      afterSampledAtMs: windowStartMs + 4 * intervalMs,
      beforeSampledAtMs: windowStartMs + 7 * intervalMs
    }] : []
    gapsDetected += gaps.length

    const eligible = points.filter((p) => p.evidenceEligible && !p.qualityMarks.includes('DUPLICATE'))
    const avgKw = eligible.length
      ? eligible.reduce((sum, p) => sum + p.powerKw, 0) / eligible.length
      : null
    const toleranceKw = 5
    const attained = avgKw !== null && (peak ? avgKw <= command.targetPowerKw + toleranceKw : avgKw >= command.targetPowerKw - toleranceKw)
    const deviationKw = avgKw === null ? null : Math.round((avgKw - command.targetPowerKw) * 100) / 100
    const deviationExceeded = deviationKw !== null && Math.abs(deviationKw) > toleranceKw

    const execution = {
      eventId, organizerTenantId: TENANT,
      stationId: command.stationId, resourceId: command.resourceId,
      commandNo: command.commandNo, planId, planVersion: 1,
      direction: dir, directionLabel: peak ? '削峰' : '填谷',
      adjustKw: command.adjustKw, baselineKw: command.baselineKw, targetPowerKw: command.targetPowerKw,
      commandState: command.state, sentAt: command.sentAt, receiptAckedAtMs: command.receiptAckedAtMs,
      receiptReceived: command.state === 'ACKED',
      attained: command.state === 'ACKED' ? attained : false,
      steadyAttained: command.state === 'ACKED' ? attained : false,
      checkStatus: offline ? 'OFFLINE_INVESTIGATION' : 'MONITORING',
      deviationKw: command.state === 'ACKED' ? deviationKw : null,
      deviationExceeded: command.state === 'ACKED' ? deviationExceeded : null,
      deviationBasis: zeroTarget && command.state === 'ACKED' ? 'ABSOLUTE_ZERO_TARGET' : 'DIRECTIONAL',
      toleranceKw,
      curve: points, gaps, alerts: []
    }
    db.executions.push(execution)
    executions.push(execution)

    if (offline) {
      const alert = {
        alertId: nextId('ALERT'), type: 'OFFLINE_INVESTIGATION', typeLabel: '失联核查',
        eventId, organizerTenantId: TENANT,
        stationId: command.stationId, resourceId: command.resourceId, commandNo: command.commandNo,
        direction: dir, directionLabel: peak ? '削峰' : '填谷',
        deviationKw: null, basis: null, basisLabel: null, toleranceKw: null,
        evidence: null, state: 'OPEN',
        raisedAt: toIso(new Date(new Date(sentAt).getTime() + 60 * 1000)),
        confirmedBy: null, confirmedAt: null
      }
      db.alerts.push(alert)
      executions[executions.length - 1].alerts.push(alert)
    } else if ((scenario === 'PEAK_BIAS' || scenario === 'VALLEY_BIAS' || scenario === 'ZERO_TARGET') && deviationExceeded) {
      const alert = {
        alertId: nextId('ALERT'), type: 'DIRECTIONAL_DEVIATION', typeLabel: '方向性超差',
        eventId, organizerTenantId: TENANT,
        stationId: command.stationId, resourceId: command.resourceId, commandNo: command.commandNo,
        direction: dir, directionLabel: peak ? '削峰' : '填谷',
        deviationKw, basis: zeroTarget ? 'ABSOLUTE_ZERO_TARGET' : 'DIRECTIONAL',
        basisLabel: zeroTarget ? '绝对偏差（零目标）' : '方向性偏差',
        toleranceKw,
        evidence: eligible.length ? {
          sampledAtMs: eligible[eligible.length - 1].sampledAtMs,
          receivedAtMs: eligible[eligible.length - 1].receivedAtMs,
          powerKw: eligible[eligible.length - 1].powerKw
        } : null,
        state: 'OPEN',
        raisedAt: toIso(windowEnd),
        confirmedBy: null, confirmedAt: null
      }
      db.alerts.push(alert)
      executions[executions.length - 1].alerts.push(alert)
    }
  }

  const assessmentRecords = []
  if (ended) {
    event.lifecycle = 'ENDED'
    for (const execution of executions) {
      const manual = scenario === 'MISSING_POINTS' || scenario === 'OFFLINE'
      const eligible = execution.curve.filter((p) => p.evidenceEligible && !p.qualityMarks.includes('DUPLICATE')).length
      const missingCount = execution.gaps.reduce((sum, g) => sum + g.missingCount, 0)
      const hours = (windowEnd - windowStart) / 3600000
      const confirmedKwh = Math.round(execution.adjustKw * hours * 0.95 * 100) / 100
      const record = {
        assessmentId: nextId('ASSESS'), eventId, organizerTenantId: TENANT,
        stationId: execution.stationId, resourceId: execution.resourceId,
        direction: dir, directionLabel: peak ? '削峰' : '填谷',
        ruleVersion: 'rule-demo-v1', baselineVersion: 'baseline-demo-v1',
        sampleSource: 'SIMULATED', frozenAt: toIso(new Date(windowStart.getTime() - 30 * 60 * 1000)),
        meteringStartMs: windowStartMs, meteringEndMs: windowEnd.getTime(),
        eligibleSamples: eligible, excludedSamples: execution.curve.length - eligible,
        missingSamples: missingCount,
        completenessRatio: Math.round((eligible / (eligible + missingCount)) * 1000) / 1000,
        confirmedResponseKwh: manual ? 0 : confirmedKwh,
        dashboardEstimateKwh: Math.round(execution.adjustKw * hours * 100) / 100,
        conclusion: manual ? 'MANUAL_REVIEW' : 'CONFIRMED',
        conclusionLabel: manual ? '转人工复核' : '核定通过',
        reviewReasons: manual
          ? (scenario === 'OFFLINE'
            ? ['指令无回执进入失联核查，核定电量不能采信自动结论']
            : ['窗口内存在序号缺口，采样完整度未达到自动核定门槛'])
          : [],
        reviewedBy: null, reviewedAt: null, reviewNote: null,
        createdAt: toIso(windowEnd)
      }
      db.assessments.push(record)
      assessmentRecords.push(record)
    }
  }

  return { event, plan, commands, executions, alerts, assessments: assessmentRecords }
}

function seed() {
  db = {
    events: [], declarations: [], plans: [], commands: [],
    executions: [], alerts: [], assessments: [],
    settlements: new Map(), auditLogs: []
  }
  // 事件一：已发布、申报进行中 —— 供场站申报/运营商确认演示
  const created = now()
  const eventA = {
    eventId: 'EVT-MOCK-DECL', organizerTenantId: TENANT,
    direction: 'PEAK_SHAVE', directionLabel: '削峰',
    windowStart: toIso(new Date(created.getTime() + 3 * 3600000)),
    windowEnd: toIso(new Date(created.getTime() + 4 * 3600000)),
    targetAdjustKw: 500,
    declareDeadline: toIso(new Date(created.getTime() + 2 * 3600000)),
    lifecycle: 'PUBLISHED', version: 2, timeStatus: 'PENDING',
    authorizedStationIds: [...STATIONS],
    createdAt: toIso(created), endedAt: null
  }
  db.events.push(eventA)
  // 事件二：全链路已走完（含结算更正与模拟付款）—— 供各页开箱有数据
  buildFullChain({ scenario: 'HEALTHY', direction: 'PEAK_SHAVE', ended: true })

  const assessment = db.assessments[0]
  const settlementId = 'SET-MOCK-0001'
  const gross = Math.round(assessment.confirmedResponseKwh * 1.2 * 100) / 100
  const v1 = {
    settlementId, billVersion: 1, eventId: assessment.eventId, organizerTenantId: TENANT,
    stationId: assessment.stationId, resourceId: assessment.resourceId,
    assessmentId: assessment.assessmentId, assessmentRuleVersion: 'rule-demo-v1', baselineVersion: 'baseline-demo-v1',
    confirmedResponseKwh: assessment.confirmedResponseKwh, priceYuanPerKwh: 1.2,
    grossYuan: gross, deductionYuan: 10, netYuan: gross - 10,
    ruleSnapshot: { ruleVersion: 'sharing-teaching-v1', platformRate: 0.1, operatorRate: 0.3, stationRate: 0.6 },
    allocations: [
      { party: 'PLATFORM', partyLabel: '平台', partyId: 'platform', rate: 0.1, amountYuan: Math.round((gross - 10) * 0.1 * 100) / 100 },
      { party: 'OPERATOR', partyLabel: '运营商', partyId: 'operator', rate: 0.3, amountYuan: Math.round((gross - 10) * 0.3 * 100) / 100 },
      { party: 'STATION', partyLabel: '场站', partyId: assessment.stationId, rate: 0.6, amountYuan: 0 }
    ],
    status: 'CONFIRMED', statusLabel: '已确认',
    confirmedBy: 'platform_admin', confirmedAt: toIso(new Date(new Date(assessment.createdAt).getTime() + 3600000)),
    createdBy: 'platform_admin', createdAt: toIso(new Date(new Date(assessment.createdAt).getTime() + 1800000))
  }
  v1.allocations[2].amountYuan = Math.round((v1.netYuan - v1.allocations[0].amountYuan - v1.allocations[1].amountYuan) * 100) / 100
  // 更正生成 v2（净实收下调 20 元），再登记模拟付款
  const v2 = {
    ...v1,
    billVersion: 2,
    confirmedResponseKwh: Math.round((v1.confirmedResponseKwh - 20 / 1.2) * 100) / 100,
    grossYuan: Math.round((gross - 20 / 1.2 * 1.2) * 100) / 100,
    netYuan: gross - 10 - 20,
    status: 'PAID', statusLabel: '已登记模拟付款',
    confirmedBy: v1.confirmedBy, confirmedAt: v1.confirmedAt,
    createdBy: 'platform_admin',
    createdAt: toIso(new Date(new Date(v1.createdAt).getTime() + 2 * 3600000))
  }
  v2.allocations = v1.allocations.map((line) => ({
    ...line,
    amountYuan: Math.round(v2.netYuan * line.rate * 100) / 100
  }))
  const residual = Math.round((v2.netYuan - v2.allocations.reduce((sum, l) => sum + l.amountYuan, 0)) * 100) / 100
  v2.allocations[v2.allocations.length - 1].amountYuan = Math.round((v2.allocations[v2.allocations.length - 1].amountYuan + residual) * 100) / 100
  db.settlements.set(settlementId, {
    versions: [v2, v1],
    corrections: [{
      correctionRequestId: 'CORR-MOCK-0001', settlementId,
      fromVersion: 1, toVersion: 2, diffYuan: -20,
      operator: 'platform_admin',
      correctedAt: v2.createdAt,
      reason: '复核后核定电量下调（教学演示更正链）'
    }],
    payments: [{
      paymentRequestId: 'PAY-MOCK-0001', settlementId, billVersion: 2,
      amountYuan: v2.netYuan, channel: 'SIMULATED',
      channelLabel: '模拟付款（不接真实资金通道）',
      registeredBy: 'platform_admin', registeredAt: toIso(new Date(new Date(v2.createdAt).getTime() + 3600000))
    }]
  })
}

function ensureDb() {
  if (!db) {
    seed()
  }
}

// ---------------- 查询辅助 ----------------

function eventView(e) {
  return { ...e }
}

function planSummary(p) {
  const { lines, versions, ...rest } = p
  return rest
}

function planDetail(p) {
  return { ...planSummary(p), lines: p.lines, versions: p.versions }
}

function inScopeStation(stationId) {
  if (db.role === 'platform') return true
  const scope = db.role === 'operator' ? STATIONS : ['cs-station-01']
  return scope.includes(stationId)
}

function visibleEvents() {
  if (db.role === 'platform') return db.events
  return db.events.filter((e) => e.lifecycle !== 'CREATED' && e.authorizedStationIds.some(inScopeStation))
}

// ---------------- 请求分发 ----------------

const routes = []

function route(method, pattern, handler) {
  const keys = []
  const regex = new RegExp('^' + pattern.replace(/:([a-zA-Z]+)/g, (_, key) => {
    keys.push(key)
    return '([^/]+)'
  }) + '$')
  routes.push({ method, regex, keys, handler })
}

// 认证
route('POST', '/auth/login', async ({ data }) => {
  const account = DEMO_ACCOUNTS.find((item) => item.account === data.account)
  if (!account || account.password !== data.password) {
    reject(401, '账号或密码错误')
  }
  db.role = account.role
  audit('LOGIN', 'ACCOUNT', account.account, '-', 'SUCCESS', '登录成功')
  return {
    token: `mock-token-${account.role}-${Date.now()}`,
    role: account.role,
    roleName: account.roleName,
    displayName: account.displayName
  }
})

route('GET', '/auth/me', async () => {
  const stationIds = db.role === 'operator' ? [...STATIONS] : db.role === 'station' ? ['cs-station-01'] : []
  const allowed = {
    platform: ['event:create', 'event:publish', 'event:close', 'allocation:assign', 'assessment:review', 'settlement:manage', 'settlement:correct', 'audit:view'],
    operator: ['declaration:confirm', 'dispatch:manage', 'execution:monitor', 'assessment:submit', 'settlement:view'],
    station: ['declaration:submit', 'resource:maintain', 'execution:feedback', 'settlement:view-own']
  }
  return {
    accountId: db.role === 'platform' ? 'platform_admin' : db.role === 'operator' ? 'operator_admin' : 'station_admin',
    role: db.role,
    roleName: db.role === 'platform' ? '平台运营' : db.role === 'operator' ? '运营商' : '场站运营',
    tenantId: TENANT,
    stationIds,
    allowedOperations: allowed[db.role] || []
  }
})

// 事件
route('POST', '/events', async ({ data }) => {
  requireRole('platform')
  const { eventId, direction, windowStart, windowEnd, targetAdjustKw, declareDeadline } = data
  if (!direction) reject(400, '调节方向 direction 不能为空（PEAK_SHAVE/VALLEY_FILL）')
  const dir = direction === 'VALLEY_FILL' || direction === '填谷' ? 'VALLEY_FILL' : 'PEAK_SHAVE'
  if (!windowStart || !windowEnd || !declareDeadline) reject(400, '响应时间窗与申报截止时刻不能为空')
  if (new Date(declareDeadline) >= new Date(windowStart)) reject(400, '申报截止时刻必须早于响应窗口开始')
  if (!(Number(targetAdjustKw) > 0)) reject(400, '目标调节量必须为正')
  const id = eventId && String(eventId).trim() ? String(eventId).trim() : nextId('EVT')
  if (db.events.some((e) => e.eventId === id)) reject(409, '事件已存在: ' + id)
  const event = {
    eventId: id, organizerTenantId: TENANT,
    direction: dir, directionLabel: dir === 'PEAK_SHAVE' ? '削峰' : '填谷',
    windowStart, windowEnd, targetAdjustKw, declareDeadline,
    lifecycle: 'CREATED', version: 1, timeStatus: 'PENDING',
    authorizedStationIds: [], createdAt: nowIso(), endedAt: null
  }
  db.events.push(event)
  audit('EVENT_CREATE', 'CHARGE_DR_EVENT', id, '1', 'SUCCESS', event.directionLabel + ' 事件建立')
  return eventView(event)
})

route('GET', '/events', async () => visibleEvents().map(eventView))

route('GET', '/events/:eventId', async ({ params }) => {
  const event = visibleEvents().find((e) => e.eventId === params.eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + params.eventId)
  return eventView(event)
})

route('POST', '/events/:eventId/publish', async ({ params }) => {
  requireRole('platform')
  const event = db.events.find((e) => e.eventId === params.eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + params.eventId)
  if (event.lifecycle !== 'CREATED') reject(409, '非法事件生命周期迁移: ' + event.lifecycle + ' → PUBLISHED')
  if (now() > new Date(event.declareDeadline)) reject(409, '申报截止已过，禁止发布: ' + event.eventId)
  event.lifecycle = 'PUBLISHED'
  event.version += 1
  audit('EVENT_PUBLISH', 'CHARGE_DR_EVENT', event.eventId, String(event.version), 'SUCCESS', '事件发布')
  return eventView(event)
})

route('POST', '/events/:eventId/finish', async ({ params }) => {
  requireRole('platform')
  const event = db.events.find((e) => e.eventId === params.eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + params.eventId)
  if (event.lifecycle !== 'PUBLISHED') reject(409, '非法事件生命周期迁移: ' + event.lifecycle + ' → ENDED')
  event.lifecycle = 'ENDED'
  event.version += 1
  event.endedAt = nowIso()
  audit('EVENT_FINISH', 'CHARGE_DR_EVENT', event.eventId, String(event.version), 'SUCCESS', '事件结束')
  return eventView(event)
})

route('POST', '/events/:eventId/participations', async ({ params, data }) => {
  requireRole('platform')
  const event = db.events.find((e) => e.eventId === params.eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + params.eventId)
  if (event.lifecycle !== 'PUBLISHED') reject(409, '仅已发布事件可变更参与范围')
  const stationIds = (data.stationIds || []).filter(Boolean)
  if (!stationIds.length) reject(400, '参与授权场站清单不能为空')
  event.authorizedStationIds = [...new Set([...event.authorizedStationIds, ...stationIds])]
  event.version += 1
  audit('EVENT_AUTHORIZE', 'CHARGE_DR_EVENT', event.eventId, String(event.version), 'SUCCESS', '参与授权场站 ' + stationIds)
  return eventView(event)
})

// 申报
route('POST', '/declarations', async ({ data }) => {
  requireRole('station')
  const { eventId, stationId, declaredKw, snapshotVersion, requestId } = data
  if (!eventId || !stationId || !snapshotVersion || !requestId) reject(400, '事件/场站/快照版本/请求标识不能为空')
  const duplicated = db.declarations.find((d) => d.requestId === requestId)
  if (duplicated) return { ...duplicated }
  const event = db.events.find((e) => e.eventId === eventId)
  if (!event || event.lifecycle === 'CREATED') reject(404, '事件不存在或不在当前数据范围内: ' + eventId)
  if (event.lifecycle !== 'PUBLISHED') reject(409, '事件已结束，申报入口已关闭: ' + eventId)
  if (now() > new Date(event.declareDeadline)) reject(409, '申报已截止（含截止时刻本身）: ' + event.declareDeadline)
  if (!event.authorizedStationIds.includes(stationId)) reject(409, '场站未获本事件参与授权: ' + stationId)
  if (!inScopeStation(stationId)) reject(409, '申报场站不在当前数据范围内: ' + stationId)
  const snap = SNAPSHOTS[stationId]
  if (!snap || snap.assessVersion !== snapshotVersion) reject(409, '能力快照版本已失效（会话指纹变化）: ' + snapshotVersion)
  const capacity = event.direction === 'PEAK_SHAVE' ? snap.crediblePeakKw : snap.credibleValleyKw
  if (!(Number(declaredKw) > 0)) reject(400, '申报容量必须为正')
  if (Number(declaredKw) > capacity) reject(409, `申报容量 ${declaredKw} kW 超过可信容量 ${capacity} kW（方向：${event.directionLabel}）`)
  const declaration = {
    declarationId: nextId('DECL'), eventId, tenantId: TENANT, stationId,
    declaredKw: Number(declaredKw), snapshotVersion, requestId,
    status: 'SUBMITTED', createdAt: nowIso(), decidedAt: null, decisionReason: null
  }
  db.declarations.push(declaration)
  audit('DECLARATION_SUBMIT', 'CHARGE_DECLARATION', declaration.declarationId, '-', 'SUCCESS', `事件 ${eventId} 场站 ${stationId} 申报 ${declaredKw} kW`)
  return { ...declaration }
})

route('GET', '/declarations', async () => {
  if (db.role === 'platform') return db.declarations
  return db.declarations.filter((d) => inScopeStation(d.stationId))
})

route('POST', '/declarations/:declarationId/confirm', async ({ params }) => {
  requireRole('operator')
  const declaration = db.declarations.find((d) => d.declarationId === params.declarationId)
  if (!declaration || !inScopeStation(declaration.stationId)) reject(404, '申报单不存在或不在当前数据范围内: ' + params.declarationId)
  if (declaration.status !== 'SUBMITTED') reject(409, '非法申报状态迁移: ' + declaration.status + ' → CONFIRMED')
  declaration.status = 'CONFIRMED'
  declaration.decidedAt = nowIso()
  audit('DECLARATION_CONFIRM', 'CHARGE_DECLARATION', declaration.declarationId, '-', 'SUCCESS', `事件 ${declaration.eventId} 场站 ${declaration.stationId}`)
  return { ...declaration }
})

route('POST', '/declarations/:declarationId/withdraw', async ({ params }) => {
  requireRole('station')
  const declaration = db.declarations.find((d) => d.declarationId === params.declarationId)
  if (!declaration || !inScopeStation(declaration.stationId)) reject(404, '申报单不存在或不在当前数据范围内: ' + params.declarationId)
  if (!['SUBMITTED', 'CONFIRMED'].includes(declaration.status)) reject(409, '非法申报状态迁移: ' + declaration.status + ' → WITHDRAWN')
  declaration.status = 'WITHDRAWN'
  declaration.decidedAt = nowIso()
  declaration.decisionReason = '场站主动撤回'
  audit('DECLARATION_WITHDRAW', 'CHARGE_DECLARATION', declaration.declarationId, '-', 'SUCCESS', `事件 ${declaration.eventId} 场站 ${declaration.stationId}`)
  return { ...declaration }
})

// 能力快照（平台登记）
route('POST', '/capability-snapshots', async ({ data }) => {
  requireRole('platform')
  const { stationId, resourceId, assessVersion, sessionFingerprint } = data
  if (!stationId || !resourceId || !assessVersion || !sessionFingerprint) reject(400, '场站/资源/评估版本/会话指纹不能为空')
  const snap = SNAPSHOTS[stationId]
  if (snap && snap.assessVersion === assessVersion) reject(409, `同场站同版本重复登记: ${stationId}/${assessVersion}（版本不可覆盖）`)
  const snapshot = {
    stationId, resourceId,
    ratedPowerKw: Number(data.ratedPowerKw), baselineKw: Number(data.baselineKw),
    crediblePeakKw: Number(data.crediblePeakKw), credibleValleyKw: Number(data.credibleValleyKw),
    assessVersion, assessedAt: data.assessedAt || nowIso(), sessionFingerprint
  }
  SNAPSHOTS[stationId] = snapshot
  audit('SNAPSHOT_REGISTER', 'CAPABILITY_SNAPSHOT', `${stationId}/${assessVersion}`, '-', 'SUCCESS', '能力快照登记')
  return { ...snapshot }
})

// 派单
route('POST', '/plans', async ({ data }) => {
  requireRole('platform')
  const eventId = data && data.eventId
  if (!eventId) reject(400, '事件标识 eventId 不能为空')
  const event = db.events.find((e) => e.eventId === eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + eventId)
  if (event.lifecycle !== 'PUBLISHED') reject(409, '仅已发布事件可构建计划: ' + eventId)
  if (now() < new Date(event.declareDeadline)) reject(409, '申报未截止，禁止构建计划: ' + event.declareDeadline)
  const confirmed = db.declarations.filter((d) => d.eventId === eventId && d.status === 'CONFIRMED')
  const sumKw = confirmed.reduce((sum, d) => sum + d.declaredKw, 0)
  const gapKw = Math.max(0, event.targetAdjustKw - sumKw)
  const peak = event.direction === 'PEAK_SHAVE'
  const lines = confirmed.map((d) => {
    const snap = SNAPSHOTS[d.stationId]
    return {
      subjectId: d.stationId, stationId: d.stationId,
      adjustKw: d.declaredKw, baselineKw: snap.baselineKw,
      targetPowerKw: peak ? snap.baselineKw - d.declaredKw : snap.baselineKw + d.declaredKw,
      snapshotVersion: snap.assessVersion
    }
  })
  const plan = {
    planId: nextId('PLAN'), parentPlanId: null, eventId, organizerTenantId: TENANT,
    level: 'TOP', version: 1, direction: event.direction, directionLabel: event.directionLabel,
    adjustKw: sumKw, baselineKw: null, gapKw, dispatchable: gapKw === 0,
    confirmedBy: null, confirmedAt: null, sentAt: null, ackedAt: null,
    createdBy: 'platform_admin', createdAt: nowIso(),
    lineCount: lines.length, lines,
    versions: [{
      version: 1, adjustKw: sumKw, gapKw,
      confirmedBy: null, confirmedAt: null, sentAt: null, ackedAt: null,
      createdBy: 'platform_admin', createdAt: nowIso()
    }]
  }
  db.plans.push(plan)
  audit('PLAN_BUILD', 'CHARGE_PLAN', plan.planId, 'v1', 'SUCCESS',
    `事件 ${eventId} ${event.directionLabel} 目标 ${event.targetAdjustKw} kW 分配 ${lines.length} 行` + (gapKw > 0 ? `（缺口 ${gapKw} kW，禁止确认/下发）` : '（缺口 0）'))
  return planDetail(plan)
})

route('GET', '/plans', async ({ params }) => {
  let plans = db.plans.filter((p) => p.lines.some((l) => inScopeStation(l.stationId)))
  if (params.eventId) plans = plans.filter((p) => p.eventId === params.eventId)
  return plans.map(planSummary)
})

route('GET', '/plans/:planId', async ({ params }) => {
  const plan = db.plans.find((p) => p.planId === params.planId)
  if (!plan || !plan.lines.some((l) => inScopeStation(l.stationId))) reject(404, '计划不存在或不在当前数据范围内: ' + params.planId)
  return planDetail(plan)
})

route('POST', '/plans/:planId/confirm', async ({ params }) => {
  requireRole('operator')
  const plan = db.plans.find((p) => p.planId === params.planId)
  if (!plan || !plan.lines.some((l) => inScopeStation(l.stationId))) reject(404, '计划不存在或不在当前数据范围内: ' + params.planId)
  if (plan.gapKw > 0) reject(409, `计划存在缺口 ${plan.gapKw} kW，禁止确认（可拒绝事件 / 补申报重新分配 / 降目标修订）`)
  if (plan.confirmedBy) return planDetail(plan)
  plan.confirmedBy = 'operator_admin'
  plan.confirmedAt = nowIso()
  plan.versions = plan.versions.map((v) => (v.version === plan.version ? { ...v, confirmedBy: plan.confirmedBy, confirmedAt: plan.confirmedAt } : v))
  audit('PLAN_CONFIRM', 'CHARGE_PLAN', plan.planId, 'v' + plan.version, 'SUCCESS', '人工确认留痕（确认人 operator_admin）')
  return planDetail(plan)
})

route('POST', '/plans/:planId/send', async ({ params }) => {
  requireRole('operator')
  const plan = db.plans.find((p) => p.planId === params.planId)
  if (!plan || !plan.lines.some((l) => inScopeStation(l.stationId))) reject(404, '计划不存在或不在当前数据范围内: ' + params.planId)
  if (!plan.confirmedBy) reject(409, '计划未确认，禁止下发（先完成人工确认）')
  if (plan.sentAt) reject(409, '重复下发被幂等拦截：计划已下发（指令不重发、不覆盖发送/回执时标）')
  const sentAt = nowIso()
  const commands = plan.lines.map((line) => {
    const command = {
      commandNo: nextId('CMD'), planId: plan.planId, planVersion: plan.version,
      resourceId: SNAPSHOTS[line.stationId].resourceId, stationId: line.stationId,
      adjustKw: line.adjustKw, baselineKw: line.baselineKw, targetPowerKw: line.targetPowerKw,
      originalCommandNo: null,
      state: 'ACKED', createdAt: plan.createdAt, sentAt,
      receiptAckedAtMs: Date.now() + 800, receiptStatus: 'ACCEPTED', rejectReason: null
    }
    db.commands.push(command)
    return { ...command }
  })
  plan.sentAt = sentAt
  plan.ackedAt = sentAt
  plan.versions = plan.versions.map((v) => (v.version === plan.version ? { ...v, sentAt, ackedAt: sentAt } : v))
  audit('PLAN_SEND', 'CHARGE_PLAN', plan.planId, 'v' + plan.version, 'SUCCESS', `下发 ${commands.length} 条指令（受理/拒绝/无回执 = ${commands.length}/0/0）`)
  return {
    plan: planDetail(plan),
    commands,
    summary: { commandCount: commands.length, sentPlans: 1, acked: commands.length, rejected: 0, noReceipt: 0 }
  }
})

route('GET', '/commands', async ({ params }) => {
  let commands = db.commands.filter((c) => inScopeStation(c.stationId))
  if (params.planId) commands = commands.filter((c) => c.planId === params.planId)
  if (params.resourceId) commands = commands.filter((c) => c.resourceId === params.resourceId)
  if (params.state) commands = commands.filter((c) => c.state === params.state.toUpperCase())
  return commands
})

route('POST', '/plans/demo-runs', async ({ data }) => {
  requireRole('platform')
  const scenario = (data && data.scenario) || 'HEALTHY'
  if (!['HEALTHY', 'OFFLINE'].includes(scenario)) reject(400, '派单链路演示仅支持 HEALTHY / OFFLINE 场景（遥测类故障注入见 POST /monitoring/demo-runs）')
  const chain = buildFullChain({ scenario, direction: data && data.direction, ended: false })
  audit('PLAN_DEMO_RUN', 'PLAN_DEMO_RUN', chain.event.eventId, '-', 'SUCCESS',
    `${scenario} ${chain.event.direction} 计划 ${chain.plan.planId} 下发 ${chain.commands.length} 条指令`)
  return {
    runId: nextId('RUN'),
    eventId: chain.event.eventId,
    direction: chain.event.direction,
    scenario,
    planId: chain.plan.planId,
    gapKw: chain.plan.gapKw,
    baselineFreezes: 2,
    sentPlans: [planSummary(chain.plan)],
    commands: chain.commands
  }
})

// 监测
route('GET', '/monitoring/executions', async ({ params }) => {
  let list = db.executions.filter((e) => inScopeStation(e.stationId))
  if (params.eventId) list = list.filter((e) => e.eventId === params.eventId)
  if (params.stationId) list = list.filter((e) => e.stationId === params.stationId)
  if (params.resourceId) list = list.filter((e) => e.resourceId === params.resourceId)
  return list.map((e) => ({ ...e }))
})

route('GET', '/monitoring/alerts', async ({ params }) => {
  let list = db.alerts.filter((a) => inScopeStation(a.stationId))
  if (params.eventId) list = list.filter((a) => a.eventId === params.eventId)
  if (params.state) list = list.filter((a) => a.state === params.state.toUpperCase())
  return list
})

route('POST', '/monitoring/alerts/:alertId/confirm', async ({ params }) => {
  const alert = db.alerts.find((a) => a.alertId === params.alertId)
  if (!alert || !inScopeStation(alert.stationId)) reject(404, '告警不存在或不在当前数据范围内: ' + params.alertId)
  if (!alert.confirmedBy) {
    alert.state = 'CONFIRMED'
    alert.confirmedBy = db.role === 'platform' ? 'platform_admin' : db.role === 'operator' ? 'operator_admin' : 'station_admin'
    alert.confirmedAt = nowIso()
    audit('ALERT_CONFIRM', 'MONITOR_ALERT', alert.alertId, '-', 'SUCCESS', alert.type + ' 告警人工确认')
  }
  return { ...alert }
})

route('POST', '/monitoring/demo-runs', async ({ data }) => {
  requireRole('platform')
  const scenario = (data && data.scenario) || 'HEALTHY'
  const chain = buildFullChain({ scenario, direction: data && data.direction, ended: false })
  let samplesIngested = 0
  let gapsDetected = 0
  for (const execution of chain.executions) {
    samplesIngested += execution.curve.length
    gapsDetected += execution.gaps.length
  }
  audit('MONITORING_DEMO_RUN', 'MONITORING_DEMO_RUN', chain.event.eventId, '-', 'SUCCESS',
    `${scenario} ${chain.event.direction} 样点 ${samplesIngested}`)
  return {
    runId: nextId('RUN'),
    eventId: chain.event.eventId,
    planId: chain.plan.planId,
    direction: chain.event.direction,
    scenario,
    samplesIngested,
    gapsDetected,
    evaluations: chain.executions,
    alerts: chain.alerts
  }
})

// 评估
route('POST', '/assessments', async ({ data }) => {
  requireRole('operator')
  const eventId = data && data.eventId
  if (!eventId) reject(400, '事件标识 eventId 不能为空')
  const event = db.events.find((e) => e.eventId === eventId)
  if (!event) reject(404, '事件不存在或不在当前数据范围内: ' + eventId)
  if (event.lifecycle !== 'ENDED') reject(409, '仅已结束事件可申请评估: ' + eventId)
  const links = db.executions.filter((e) => e.eventId === eventId && inScopeStation(e.stationId))
  if (!links.length) reject(409, '事件无可评估的执行链路（无监测关联）: ' + eventId)
  const existing = db.assessments.filter((a) => a.eventId === eventId)
  if (existing.length) return existing
  const peak = event.direction === 'PEAK_SHAVE'
  const windowStartMs = new Date(event.windowStart).getTime()
  const windowEndMs = new Date(event.windowEnd).getTime()
  const records = links.map((link) => {
    const eligible = link.curve.filter((p) => p.evidenceEligible && !p.qualityMarks.includes('DUPLICATE'))
    const missing = link.gaps.reduce((sum, g) => sum + g.missingCount, 0)
    const hours = (windowEndMs - windowStartMs) / 3600000
    const manual = missing > 0 || link.checkStatus === 'OFFLINE_INVESTIGATION'
    const record = {
      assessmentId: nextId('ASSESS'), eventId, organizerTenantId: TENANT,
      stationId: link.stationId, resourceId: link.resourceId,
      direction: event.direction, directionLabel: event.directionLabel,
      ruleVersion: 'rule-demo-v1', baselineVersion: 'baseline-demo-v1',
      sampleSource: 'SIMULATED', frozenAt: toIso(new Date(windowStartMs - 30 * 60 * 1000)),
      meteringStartMs: windowStartMs, meteringEndMs: windowEndMs,
      eligibleSamples: eligible.length,
      excludedSamples: link.curve.length - eligible.length,
      missingSamples: missing,
      completenessRatio: Math.round((eligible.length / (eligible.length + missing)) * 1000) / 1000,
      confirmedResponseKwh: manual ? 0 : Math.round(link.adjustKw * hours * 0.95 * 100) / 100,
      dashboardEstimateKwh: Math.round(link.adjustKw * hours * 100) / 100,
      conclusion: manual ? 'MANUAL_REVIEW' : 'CONFIRMED',
      conclusionLabel: manual ? '转人工复核' : '核定通过',
      reviewReasons: manual
        ? (link.checkStatus === 'OFFLINE_INVESTIGATION'
          ? ['指令无回执进入失联核查，核定电量不能采信自动结论']
          : ['窗口内存在序号缺口，采样完整度未达到自动核定门槛'])
        : [],
      reviewedBy: null, reviewedAt: null, reviewNote: null,
      createdAt: nowIso()
    }
    db.assessments.push(record)
    return { ...record }
  })
  const manualCount = records.filter((r) => r.conclusion === 'MANUAL_REVIEW').length
  audit('ASSESSMENT_APPLY', 'CHARGE_ASSESSMENT', eventId, '-', 'SUCCESS', `评估 ${records.length} 条（转复核 ${manualCount} 条）`)
  return records
})

route('GET', '/assessments', async ({ params }) => {
  let list = db.assessments.filter((a) => inScopeStation(a.stationId))
  if (params.eventId) list = list.filter((a) => a.eventId === params.eventId)
  return list
})

route('POST', '/assessments/:assessmentId/review', async ({ params, data }) => {
  requireRole('platform')
  const record = db.assessments.find((a) => a.assessmentId === params.assessmentId)
  if (!record || !inScopeStation(record.stationId)) reject(404, '评估记录不存在或不在当前数据范围内: ' + params.assessmentId)
  if (!record.reviewedBy) {
    record.reviewedBy = 'platform_admin'
    record.reviewedAt = nowIso()
    record.reviewNote = (data && data.note) || null
    audit('ASSESSMENT_REVIEW', 'CHARGE_ASSESSMENT', record.assessmentId, '-', 'SUCCESS', `结论 ${record.conclusionLabel} 平台复核留痕`)
  }
  return { ...record }
})

route('POST', '/assessments/demo-runs', async ({ data }) => {
  requireRole('platform')
  const scenario = (data && data.scenario) || 'HEALTHY'
  const chain = buildFullChain({ scenario, direction: data && data.direction, ended: true })
  audit('ASSESSMENT_DEMO_RUN', 'ASSESSMENT_DEMO_RUN', chain.event.eventId, '-', 'SUCCESS',
    `${scenario} ${chain.event.direction} 评估 ${chain.assessments.length} 条`)
  return {
    runId: nextId('RUN'),
    eventId: chain.event.eventId,
    declarationId: db.declarations.find((d) => d.eventId === chain.event.eventId)?.declarationId || null,
    direction: chain.event.direction,
    scenario,
    samplesIngested: chain.executions.reduce((sum, e) => sum + e.curve.length, 0),
    gapsDetected: chain.executions.reduce((sum, e) => sum + e.gaps.length, 0),
    freezes: chain.executions.map((e) => ({
      eventId: e.eventId, organizerTenantId: TENANT,
      stationId: e.stationId, resourceId: e.resourceId,
      direction: e.direction, directionLabel: e.directionLabel,
      frozenAt: toIso(new Date(e.curve[0].sampledAtMs - 30 * 60 * 1000)),
      windowStart: chain.event.windowStart, windowEnd: chain.event.windowEnd,
      sampleSource: 'SIMULATED', ruleVersion: 'rule-demo-v1', baselineVersion: 'baseline-demo-v1',
      points: e.curve.filter((p) => p.beforeCommandSent).map((p) => ({ sampledAtMs: p.sampledAtMs, powerKw: p.powerKw }))
    })),
    assessments: chain.assessments
  }
})

// 结算
function round2(n) {
  return Math.round(n * 100) / 100
}

route('POST', '/settlements', async ({ data }) => {
  requireRole('platform')
  const { assessmentId, priceYuanPerKwh, deductionYuan, rule, requestId } = data
  if (!assessmentId) reject(400, '评估标识 assessmentId 不能为空')
  if (priceYuanPerKwh === null || priceYuanPerKwh === undefined) reject(400, '补偿单价 priceYuanPerKwh 不能为空（教学假设输入，须为正）')
  if (!requestId) reject(400, '生成请求标识 requestId 不能为空（幂等键）')
  if (!rule || rule.platformRate === null || rule.operatorRate === null || rule.stationRate === null) {
    reject(400, '分账规则 rule.platformRate/operatorRate/stationRate 不能为空')
  }
  for (const entry of db.settlements.values()) {
    const hit = entry.versions.find((v) => v.requestId === requestId)
    if (hit) return settleDetail(hit.settlementId)
  }
  const assessment = db.assessments.find((a) => a.assessmentId === assessmentId)
  if (!assessment) reject(404, '评估记录不存在或不在当前数据范围内: ' + assessmentId)
  if (assessment.conclusion !== 'CONFIRMED') reject(409, `评估结论为「${assessment.conclusionLabel}」，仅核定通过的评估可入结算`)
  const rates = [Number(rule.platformRate), Number(rule.operatorRate), Number(rule.stationRate)]
  if (Math.abs(rates[0] + rates[1] + rates[2] - 1) > 1e-9) reject(400, '分账比例总和必须为 1: ' + rates.join(' + '))
  const price = Number(priceYuanPerKwh)
  if (!(price > 0)) reject(400, '补偿单价必须为正: ' + priceYuanPerKwh)
  const deduction = deductionYuan === null || deductionYuan === undefined ? 0 : Number(deductionYuan)
  if (deduction < 0) reject(400, '考核扣款不得为负')
  const gross = round2(assessment.confirmedResponseKwh * price)
  if (deduction > gross) reject(409, `考核扣款 ${deduction} 不得超过毛额 ${gross}`)
  const net = round2(gross - deduction)
  const settlementId = nextId('SET')
  const parties = [
    { party: 'PLATFORM', partyLabel: '平台', partyId: 'platform', rate: rates[0] },
    { party: 'OPERATOR', partyLabel: '运营商', partyId: 'operator', rate: rates[1] },
    { party: 'STATION', partyLabel: '场站', partyId: assessment.stationId, rate: rates[2] }
  ]
  const allocations = parties.map((p) => ({ ...p, amountYuan: round2(net * p.rate) }))
  const residual = round2(net - allocations.reduce((sum, l) => sum + l.amountYuan, 0))
  allocations[allocations.length - 1].amountYuan = round2(allocations[allocations.length - 1].amountYuan + residual)
  const order = {
    settlementId, billVersion: 1, requestId,
    eventId: assessment.eventId, organizerTenantId: TENANT,
    stationId: assessment.stationId, resourceId: assessment.resourceId,
    assessmentId, assessmentRuleVersion: assessment.ruleVersion, baselineVersion: assessment.baselineVersion,
    confirmedResponseKwh: assessment.confirmedResponseKwh, priceYuanPerKwh: price,
    grossYuan: gross, deductionYuan: deduction, netYuan: net,
    ruleSnapshot: {
      ruleVersion: rule.ruleVersion || 'sharing-teaching-v1',
      platformRate: rates[0], operatorRate: rates[1], stationRate: rates[2]
    },
    allocations,
    status: 'CREATED', statusLabel: '已生成',
    confirmedBy: null, confirmedAt: null,
    createdBy: 'platform_admin', createdAt: nowIso()
  }
  db.settlements.set(settlementId, { versions: [order], corrections: [], payments: [] })
  audit('SETTLEMENT_GENERATE', 'CHARGE_SETTLEMENT', settlementId, 'v1', 'SUCCESS',
    `毛额 ${gross} − 扣款 ${deduction} = 净实收 ${net}，分账 3 方（规则快照 ${order.ruleSnapshot.ruleVersion}）`)
  return settleDetail(settlementId)
})

function settleDetail(settlementId) {
  const entry = db.settlements.get(settlementId)
  if (!entry) reject(404, '结算单不存在或不在当前数据范围内: ' + settlementId)
  const latest = entry.versions[0]
  const history = entry.versions.map((v) => ({
    billVersion: v.billVersion, status: v.status, statusLabel: v.statusLabel,
    grossYuan: v.grossYuan, deductionYuan: v.deductionYuan, netYuan: v.netYuan,
    confirmedBy: v.confirmedBy, confirmedAt: v.confirmedAt
  }))
  return { ...latest, history, corrections: entry.corrections, payments: entry.payments }
}

route('GET', '/settlements', async ({ params }) => {
  let list = [...db.settlements.values()]
    .map((entry) => entry.versions[0])
    .filter((v) => db.role === 'platform' || inScopeStation(v.stationId))
  if (params.eventId) list = list.filter((v) => v.eventId === params.eventId)
  return list.map((v) => {
    const { requestId, history, corrections, payments, ...rest } = settleDetail(v.settlementId)
    return rest
  })
})

route('GET', '/settlements/:settlementId', async ({ params }) => {
  const entry = db.settlements.get(params.settlementId)
  if (!entry || (db.role !== 'platform' && !inScopeStation(entry.versions[0].stationId))) {
    reject(404, '结算单不存在或不在当前数据范围内: ' + params.settlementId)
  }
  const detail = settleDetail(params.settlementId)
  const { requestId, ...rest } = detail
  return rest
})

route('POST', '/settlements/:settlementId/confirm', async ({ params }) => {
  requireRole('platform')
  const entry = db.settlements.get(params.settlementId)
  if (!entry) reject(404, '结算单不存在或不在当前数据范围内: ' + params.settlementId)
  const latest = entry.versions[0]
  if (latest.status === 'CREATED') {
    latest.status = 'CONFIRMED'
    latest.statusLabel = '已确认'
    latest.confirmedBy = 'platform_admin'
    latest.confirmedAt = nowIso()
    audit('SETTLEMENT_CONFIRM', 'CHARGE_SETTLEMENT', params.settlementId, 'v' + latest.billVersion, 'SUCCESS', `净实收 ${latest.netYuan} 确认留痕`)
  }
  const detail = settleDetail(params.settlementId)
  const { requestId, ...rest } = detail
  return rest
})

route('POST', '/settlements/:settlementId/payments', async ({ params, data }) => {
  requireRole('platform')
  const entry = db.settlements.get(params.settlementId)
  if (!entry) reject(404, '结算单不存在或不在当前数据范围内: ' + params.settlementId)
  const paymentRequestId = data && data.paymentRequestId
  if (!paymentRequestId) reject(400, '付款登记请求标识 paymentRequestId 不能为空（幂等键）')
  const existing = entry.payments.find((p) => p.paymentRequestId === paymentRequestId)
  if (existing) return { ...existing }
  const latest = entry.versions[0]
  if (latest.status === 'CREATED') reject(409, '结算单未确认，禁止登记付款（先确认结算单）')
  const record = {
    paymentRequestId, settlementId: params.settlementId, billVersion: latest.billVersion,
    amountYuan: latest.netYuan, channel: 'SIMULATED',
    channelLabel: '模拟付款（不接真实资金通道）',
    registeredBy: 'platform_admin', registeredAt: nowIso()
  }
  entry.payments.push(record)
  latest.status = 'PAID'
  latest.statusLabel = '已登记模拟付款'
  audit('SETTLEMENT_PAYMENT', 'CHARGE_SETTLEMENT', params.settlementId, 'v' + latest.billVersion, 'SUCCESS', `模拟付款登记 ${record.amountYuan} 元（渠道 SIMULATED）`)
  return { ...record }
})

route('POST', '/settlements/:settlementId/corrections', async ({ params, data }) => {
  requireRole('platform')
  const entry = db.settlements.get(params.settlementId)
  if (!entry) reject(404, '结算单不存在或不在当前数据范围内: ' + params.settlementId)
  const { correctionRequestId, confirmedResponseKwh, deductionYuan, reason } = data || {}
  if (!correctionRequestId) reject(400, '更正请求标识 correctionRequestId 不能为空（幂等键）')
  if (confirmedResponseKwh === null || confirmedResponseKwh === undefined) reject(400, '更正后的核定响应电量 confirmedResponseKwh 不能为空')
  if (!reason || !String(reason).trim()) reject(400, '更正原因 reason 不能为空（留痕必备）')
  const dup = entry.corrections.find((c) => c.correctionRequestId === correctionRequestId)
  if (dup) {
    const detail = settleDetail(params.settlementId)
    const { requestId, ...rest } = detail
    return rest
  }
  const from = entry.versions[0]
  const price = from.priceYuanPerKwh
  const gross = round2(Number(confirmedResponseKwh) * price)
  const deduction = deductionYuan === null || deductionYuan === undefined ? from.deductionYuan : Number(deductionYuan)
  if (deduction > gross) reject(409, `考核扣款 ${deduction} 不得超过毛额 ${gross}`)
  const net = round2(gross - deduction)
  const diffYuan = round2(net - from.netYuan)
  const corrected = {
    ...from,
    billVersion: from.billVersion + 1,
    confirmedResponseKwh: Number(confirmedResponseKwh),
    grossYuan: gross, deductionYuan: deduction, netYuan: net,
    allocations: from.allocations.map((line) => ({ ...line, amountYuan: round2(net * line.rate) })),
    status: 'CREATED', statusLabel: '已生成',
    confirmedBy: null, confirmedAt: null,
    createdBy: 'platform_admin', createdAt: nowIso()
  }
  const residual = round2(net - corrected.allocations.reduce((sum, l) => sum + l.amountYuan, 0))
  corrected.allocations[corrected.allocations.length - 1].amountYuan = round2(corrected.allocations[corrected.allocations.length - 1].amountYuan + residual)
  entry.versions.unshift(corrected)
  entry.corrections.unshift({
    correctionRequestId, settlementId: params.settlementId,
    fromVersion: from.billVersion, toVersion: corrected.billVersion,
    diffYuan, operator: 'platform_admin', correctedAt: nowIso(), reason: String(reason).trim()
  })
  audit('SETTLEMENT_CORRECT', 'CHARGE_SETTLEMENT', params.settlementId, 'v' + corrected.billVersion, 'SUCCESS',
    `更正生成 v${corrected.billVersion}（净实收 ${net}）原因: ${String(reason).trim()}`)
  const detail = settleDetail(params.settlementId)
  const { requestId, ...rest } = detail
  return rest
})

// 审计（平台）
route('GET', '/audit-logs', async () => {
  requireRole('platform')
  return db.auditLogs
})

// ---------------- 分发 ----------------

export async function mockRequest({ url, method = 'get', data, params = {} }) {
  await new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS))
  ensureDb()
  const path = String(url).split('?')[0].replace(/\/+$/, '') || '/'
  const query = String(url).includes('?')
    ? Object.fromEntries(new URLSearchParams(String(url).split('?')[1]))
    : {}
  const mergedParams = { ...query, ...(params || {}) }
  const matched = routes.find((r) => r.method === method.toUpperCase() && r.regex.test(path))
  if (!matched) {
    const error = new Error(`Mock 模式未配置该接口：${method.toUpperCase()} ${path}`)
    throw error
  }
  const match = path.match(matched.regex)
  const pathParams = {}
  matched.keys.forEach((key, index) => {
    pathParams[key] = decodeURIComponent(match[index + 1])
  })
  // 路径参数与查询参数合并下发（处理器统一从 params 取值）
  const finalParams = { ...mergedParams, ...pathParams }
  try {
    return await matched.handler({ data, params: finalParams, pathParams })
  } catch (error) {
    if (error && error.code) {
      throw error
    }
    throw error
  }
}
