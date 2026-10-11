// 时间格式化工具：后端时间字段为 ISO-8601 本地时间字符串（Jackson jsr310 默认形态），
// 曲线/缺口/回执类字段为 epoch 毫秒

export function fmtDateTime(value) {
  if (!value) {
    return '-'
  }
  if (typeof value === 'number') {
    return fmtMs(value)
  }
  // ISO-8601 本地时间（可能带秒小数），展示到秒
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}

export function fmtMs(ms) {
  if (ms === null || ms === undefined) {
    return '-'
  }
  const date = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function fmtMsShort(ms) {
  if (ms === null || ms === undefined) {
    return '-'
  }
  const date = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function fmtKw(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${value} kW`
}

export function fmtKwh(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${value} kWh`
}

export function fmtYuan(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${value} 元`
}

export function fmtRatio(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${(Number(value) * 100).toFixed(1)}%`
}

export function toLocalInputValue(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function newRequestId(prefix) {
  const uuid = crypto.randomUUID().replace(/-/g, '').slice(0, 12)
  return `${prefix}-${uuid}`
}
