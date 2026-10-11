// 状态字典：后端枚举 code → 中文标签与 Element Plus 标签类型
// 口径来源：openvpp-app Operations*Controller 响应视图（枚举同时给出 code 与中文标签，
// 此处仅对无标签字段（指令状态、核查状态等）做前端映射，其余优先使用响应内 label 字段）

export const EVENT_LIFECYCLE = {
  CREATED: { label: '已建立', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'primary' },
  ENDED: { label: '已结束', type: 'success' }
}

export const EVENT_TIME_STATUS = {
  PENDING: { label: '待响应', type: 'info' },
  RESPONDING: { label: '响应中', type: 'warning' },
  ENDED: { label: '窗口已过', type: 'info' }
}

export const DECLARATION_STATUS = {
  SUBMITTED: { label: '已申报', type: 'primary' },
  CONFIRMED: { label: '已确认', type: 'success' },
  REJECTED: { label: '已拒绝', type: 'danger' },
  WITHDRAWN: { label: '已撤回', type: 'info' }
}

// 指令状态：已下发 ≠ 设备已执行 —— 受理回执（ACKED）只证明设备受理，不证明功率到位
export const COMMAND_STATE = {
  CREATED: { label: '已登记未发送', type: 'info' },
  SENT: { label: '已发送待回执', type: 'warning' },
  ACKED: { label: '设备已受理（回执）', type: 'success' },
  REJECTED: { label: '设备已拒绝', type: 'danger' },
  NO_RECEIPT: { label: '失联无回执（进入核查）', type: 'danger' }
}

export const COMMAND_STATE_HINT = {
  ACKED: '受理回执 ≠ 功率到位，达标以执行监测证据为准',
  NO_RECEIPT: '无回执 ≠ 设备未执行，禁止直接重发，进入核查流程',
  REJECTED: '设备拒绝，原因码见拒绝原因列'
}

export const EXECUTION_CHECK_STATUS = {
  MONITORING: { label: '监测中', type: 'primary' },
  OFFLINE_INVESTIGATION: { label: '失联核查', type: 'danger' }
}

export const ALERT_STATE = {
  OPEN: { label: '待处理', type: 'danger' },
  CONFIRMED: { label: '已确认', type: 'success' }
}

export const ASSESSMENT_CONCLUSION = {
  CONFIRMED: { label: '核定通过', type: 'success' },
  MANUAL_REVIEW: { label: '转人工复核', type: 'warning' }
}

export const SETTLEMENT_STATUS = {
  CREATED: { label: '已生成', type: 'primary' },
  CONFIRMED: { label: '已确认', type: 'success' },
  PAID: { label: '已登记模拟付款', type: 'info' }
}

export const BOOL_TAG = {
  true: { label: '是', type: 'success' },
  false: { label: '否', type: 'info' }
}

export const SAMPLE_QUALITY_MARK = {
  LATE: { label: '迟到样点', type: 'warning' },
  DUPLICATE: { label: '重复样点', type: 'info' },
  OUT_OF_ORDER: { label: '乱序样点', type: 'danger' }
}

// 演示场景（后端 MonitoringDemoScenario 全量，标签取枚举描述口径的中文简写）
export const DEMO_SCENARIO = {
  HEALTHY: { label: '健康设备全链路达标', dispatch: true, monitoring: true },
  PEAK_BIAS: { label: '削峰系统性偏高（方向超差告警）', dispatch: false, monitoring: true },
  VALLEY_BIAS: { label: '填谷系统性偏低（方向超差告警）', dispatch: false, monitoring: true },
  ZERO_TARGET: { label: '零目标仍有实际功率（绝对偏差）', dispatch: false, monitoring: true },
  LATE_STREAM: { label: '遥测整体迟到', dispatch: false, monitoring: true },
  MISSING_POINTS: { label: '遥测序号缺口（缺失不补 0）', dispatch: false, monitoring: true },
  DUPLICATE_POINTS: { label: '遥测重复样点', dispatch: false, monitoring: true },
  OUT_OF_ORDER_POINTS: { label: '遥测乱序到达', dispatch: false, monitoring: true },
  OFFLINE: { label: '设备失联进入核查', dispatch: true, monitoring: true }
}

export function dictLabel(dict, code) {
  const item = dict[code]
  return item ? item.label : code || '-'
}

export function dictType(dict, code) {
  const item = dict[code]
  return item ? item.type : 'info'
}
