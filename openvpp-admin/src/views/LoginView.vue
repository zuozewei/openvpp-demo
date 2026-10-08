<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { setSession } from '../stores/session'
import { ROLE_HOME } from '../router'
import { DEMO_ACCOUNTS } from '../api/mock/accounts'
import { USE_MOCK } from '../api/request'

const route = useRoute()
const router = useRouter()

const form = ref({
  account: '',
  password: ''
})
const loading = ref(false)

const demoAccounts = DEMO_ACCOUNTS.map(({ account, password, roleName, displayName }) => ({
  account,
  password,
  roleName,
  displayName
}))

function fillAccount(item) {
  form.value.account = item.account
  form.value.password = item.password
}

async function handleLogin() {
  if (!form.value.account || !form.value.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const session = await login({
      account: form.value.account,
      password: form.value.password
    })
    setSession(session)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : ROLE_HOME[session.role])
  } catch {
    // 统一错误提示已在请求层处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">openvpp-admin 运营后台</div>
      <div class="login-subtitle">充电桩需求响应运营实战专题 · 轻量后台脚手架</div>
      <el-form :model="form" label-position="top" size="large" @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input v-model="form.account" placeholder="请输入账号" clearable />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="loading" @click="handleLogin">
          登 录
        </el-button>
      </el-form>
      <el-divider content-position="center">演示账号（Mock 模式 {{ USE_MOCK ? '已开启' : '未开启' }}）</el-divider>
      <div class="demo-accounts">
        <el-button
          v-for="item in demoAccounts"
          :key="item.account"
          size="small"
          plain
          @click="fillAccount(item)"
        >
          {{ item.roleName }}
        </el-button>
      </div>
      <div class="demo-hint">点击上方角色按钮自动填充演示账号，密码统一为 Openvpp@2026</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #409eff 100%);
}

.login-card {
  width: 400px;
}

.login-title {
  font-size: 20px;
  font-weight: 600;
  text-align: center;
  color: #303133;
}

.login-subtitle {
  margin: 8px 0 24px;
  text-align: center;
  font-size: 13px;
  color: #909399;
}

.login-button {
  width: 100%;
}

.demo-accounts {
  display: flex;
  justify-content: center;
  gap: 8px;
}

.demo-hint {
  margin-top: 8px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
