import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getSession, clearSession } from '../stores/session'
import { mockRequest } from './mock'

// Mock 开关由 import.meta.env 注入：VITE_USE_MOCK=true 时全部请求走本地 Mock
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const http = axios.create({
  baseURL: '/api/v1/operations',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const session = getSession()
  if (session && session.token) {
    config.headers.Authorization = `Bearer ${session.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response && error.response.status
    const message =
      (error.response && error.response.data && error.response.data.message) ||
      error.message ||
      '请求失败，请稍后重试'
    // 401：会话缺失/无效/越角色 —— 回到登录页重新认证（登录请求自身除外）
    if (status === 401 && error.config && error.config.url !== '/auth/login') {
      clearSession()
      if (!location.pathname.startsWith('/login')) {
        ElMessage.error(`${message}，请重新登录`)
        location.href = '/login'
        return new Promise(() => {})
      }
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default function request(config) {
  if (USE_MOCK) {
    return mockRequest(config)
  }
  return http(config)
}
