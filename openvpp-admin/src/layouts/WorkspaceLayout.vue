<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getSession, clearSession } from '../stores/session'

const route = useRoute()
const router = useRouter()
const session = getSession()

const roleName = computed(() => {
  const record = route.matched.find((item) => item.meta.roleName)
  return record ? record.meta.roleName : '工作台'
})

const menuItems = [
  { index: 'events', title: '事件管理', icon: 'Bell' },
  { index: 'declarations', title: '申报管理', icon: 'Document' },
  { index: 'dispatch', title: '派单管理', icon: 'Position' },
  { index: 'monitor', title: '运行监测', icon: 'Odometer' },
  { index: 'assessment', title: '效果评估', icon: 'DataAnalysis' },
  { index: 'settlement', title: '结算管理', icon: 'Wallet' }
]

const basePath = computed(() => `/${route.path.split('/')[1]}`)
const activeMenu = computed(() => route.path)

function resolveMenuPath(index) {
  return index ? `${basePath.value}/${index}` : basePath.value
}

async function handleLogout() {
  await ElMessageBox.confirm('确认退出登录吗？', '退出登录', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning'
  })
  clearSession()
  router.push('/login')
}
</script>

<template>
  <el-container class="workspace-layout">
    <el-aside width="220px" class="workspace-aside">
      <div class="workspace-logo">openvpp-admin</div>
      <div class="workspace-role">{{ roleName }}</div>
      <el-menu :default-active="activeMenu" router class="workspace-menu">
        <el-menu-item :index="basePath">
          <el-icon><HomeFilled /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item v-for="item in menuItems" :key="item.index" :index="resolveMenuPath(item.index)">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="workspace-header">
        <span class="workspace-header-title">{{ roleName }}</span>
        <el-dropdown trigger="click" @command="handleLogout">
          <span class="workspace-user">
            {{ session.displayName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="workspace-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.workspace-layout {
  height: 100%;
}

.workspace-aside {
  background-color: #ffffff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.workspace-logo {
  padding: 18px 0 4px;
  text-align: center;
  font-size: 18px;
  font-weight: 600;
  color: #409eff;
}

.workspace-role {
  padding-bottom: 12px;
  text-align: center;
  font-size: 12px;
  color: #909399;
}

.workspace-menu {
  border-right: none;
  flex: 1;
}

.workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #ffffff;
  border-bottom: 1px solid #e4e7ed;
}

.workspace-header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.workspace-user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: #606266;
}

.workspace-main {
  padding: 16px;
}
</style>
