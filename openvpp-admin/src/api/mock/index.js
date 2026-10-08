import { ElMessage } from 'element-plus'
import { DEMO_ACCOUNTS } from './accounts'

const MOCK_DELAY_MS = 300

const mockHandlers = {
  'POST /auth/login': async ({ data }) => {
    const account = DEMO_ACCOUNTS.find((item) => item.account === data.account)
    if (!account || account.password !== data.password) {
      const error = new Error('账号或密码错误')
      error.code = 401
      throw error
    }
    return {
      token: `mock-token-${account.role}-${Date.now()}`,
      role: account.role,
      roleName: account.roleName,
      displayName: account.displayName
    }
  }
}

export async function mockRequest({ url, method = 'get', data }) {
  await new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS))
  const handler = mockHandlers[`${method.toUpperCase()} ${url}`]
  if (!handler) {
    const error = new Error(`Mock 模式未配置该接口：${method.toUpperCase()} ${url}`)
    ElMessage.error(error.message)
    throw error
  }
  try {
    return await handler({ data })
  } catch (error) {
    ElMessage.error(error.message)
    throw error
  }
}
