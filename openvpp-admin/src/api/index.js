import request from './request'

// ---------------- 认证 ----------------

export function fetchMe() {
  return request({ url: '/auth/me', method: 'get' })
}

// ---------------- 事件 ----------------

export function createEvent(data) {
  return request({ url: '/events', method: 'post', data })
}

export function listEvents() {
  return request({ url: '/events', method: 'get' })
}

export function getEvent(eventId) {
  return request({ url: `/events/${encodeURIComponent(eventId)}`, method: 'get' })
}

export function publishEvent(eventId) {
  return request({ url: `/events/${encodeURIComponent(eventId)}/publish`, method: 'post' })
}

export function finishEvent(eventId) {
  return request({ url: `/events/${encodeURIComponent(eventId)}/finish`, method: 'post' })
}

export function authorizeEvent(eventId, stationIds) {
  return request({
    url: `/events/${encodeURIComponent(eventId)}/participations`,
    method: 'post',
    data: { stationIds }
  })
}

// ---------------- 申报 ----------------

export function submitDeclaration(data) {
  return request({ url: '/declarations', method: 'post', data })
}

export function listDeclarations() {
  return request({ url: '/declarations', method: 'get' })
}

export function confirmDeclaration(declarationId) {
  return request({ url: `/declarations/${encodeURIComponent(declarationId)}/confirm`, method: 'post' })
}

export function withdrawDeclaration(declarationId) {
  return request({ url: `/declarations/${encodeURIComponent(declarationId)}/withdraw`, method: 'post' })
}

// ---------------- 能力快照（平台登记） ----------------

export function registerSnapshot(data) {
  return request({ url: '/capability-snapshots', method: 'post', data })
}

// ---------------- 分级派单 ----------------

export function buildPlan(eventId) {
  return request({ url: '/plans', method: 'post', data: { eventId } })
}

export function listPlans(eventId) {
  return request({
    url: '/plans',
    method: 'get',
    params: eventId ? { eventId } : undefined
  })
}

export function getPlan(planId) {
  return request({ url: `/plans/${encodeURIComponent(planId)}`, method: 'get' })
}

export function confirmPlan(planId) {
  return request({ url: `/plans/${encodeURIComponent(planId)}/confirm`, method: 'post' })
}

export function sendPlan(planId) {
  return request({ url: `/plans/${encodeURIComponent(planId)}/send`, method: 'post' })
}

export function listCommands(params) {
  return request({ url: '/commands', method: 'get', params })
}

export function runPlanDemo(data) {
  return request({ url: '/plans/demo-runs', method: 'post', data })
}

// ---------------- 执行监测 ----------------

export function listExecutions(params) {
  return request({ url: '/monitoring/executions', method: 'get', params })
}

export function listAlerts(params) {
  return request({ url: '/monitoring/alerts', method: 'get', params })
}

export function confirmAlert(alertId) {
  return request({ url: `/monitoring/alerts/${encodeURIComponent(alertId)}/confirm`, method: 'post' })
}

export function runMonitoringDemo(data) {
  return request({ url: '/monitoring/demo-runs', method: 'post', data })
}

// ---------------- 效果评估 ----------------

export function applyAssessment(eventId) {
  return request({ url: '/assessments', method: 'post', data: { eventId } })
}

export function reviewAssessment(assessmentId, note) {
  return request({
    url: `/assessments/${encodeURIComponent(assessmentId)}/review`,
    method: 'post',
    data: { note }
  })
}

export function listAssessments(eventId) {
  return request({
    url: '/assessments',
    method: 'get',
    params: eventId ? { eventId } : undefined
  })
}

export function runAssessmentDemo(data) {
  return request({ url: '/assessments/demo-runs', method: 'post', data })
}

// ---------------- 结算 ----------------

export function createSettlement(data) {
  return request({ url: '/settlements', method: 'post', data })
}

export function listSettlements(eventId) {
  return request({
    url: '/settlements',
    method: 'get',
    params: eventId ? { eventId } : undefined
  })
}

export function getSettlement(settlementId) {
  return request({ url: `/settlements/${encodeURIComponent(settlementId)}`, method: 'get' })
}

export function confirmSettlement(settlementId) {
  return request({ url: `/settlements/${encodeURIComponent(settlementId)}/confirm`, method: 'post' })
}

export function registerPayment(settlementId, paymentRequestId) {
  return request({
    url: `/settlements/${encodeURIComponent(settlementId)}/payments`,
    method: 'post',
    data: { paymentRequestId }
  })
}

export function correctSettlement(settlementId, data) {
  return request({
    url: `/settlements/${encodeURIComponent(settlementId)}/corrections`,
    method: 'post',
    data
  })
}

// ---------------- 操作审计（平台） ----------------

export function listAuditLogs() {
  return request({ url: '/audit-logs', method: 'get' })
}
