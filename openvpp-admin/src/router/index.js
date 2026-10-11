import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getSession } from '../stores/session'

export const ROLE_HOME = {
  platform: '/platform',
  operator: '/operator',
  station: '/station'
}

const workspaceChildren = [
  {
    path: '',
    component: () => import('../views/workspace/WorkspaceHome.vue'),
    meta: { title: '工作台' }
  },
  {
    path: 'events',
    component: () => import('../views/workspace/EventsView.vue'),
    meta: { title: '事件管理' }
  },
  {
    path: 'events/:eventId',
    component: () => import('../views/workspace/EventDetailView.vue'),
    meta: { title: '事件详情' }
  },
  {
    path: 'declarations',
    component: () => import('../views/workspace/DeclarationsView.vue'),
    meta: { title: '申报管理' }
  },
  {
    path: 'dispatch',
    component: () => import('../views/workspace/DispatchView.vue'),
    meta: { title: '派单管理' }
  },
  {
    path: 'monitor',
    component: () => import('../views/workspace/MonitorView.vue'),
    meta: { title: '运行监测' }
  },
  {
    path: 'assessment',
    component: () => import('../views/workspace/AssessmentView.vue'),
    meta: { title: '效果评估' }
  },
  {
    path: 'settlement',
    component: () => import('../views/workspace/SettlementView.vue'),
    meta: { title: '结算管理' }
  },
  {
    path: 'audit',
    component: () => import('../views/workspace/AuditView.vue'),
    meta: { title: '操作审计', roles: ['platform'] }
  }
]

const routes = [
  {
    path: '/login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/platform',
    component: () => import('../layouts/WorkspaceLayout.vue'),
    meta: { roles: ['platform'], roleName: '平台运营工作台' },
    children: workspaceChildren
  },
  {
    path: '/operator',
    component: () => import('../layouts/WorkspaceLayout.vue'),
    meta: { roles: ['operator'], roleName: '运营商工作台' },
    children: workspaceChildren
  },
  {
    path: '/station',
    component: () => import('../layouts/WorkspaceLayout.vue'),
    meta: { roles: ['station'], roleName: '场站运营工作台' },
    children: workspaceChildren
  },
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/login'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const session = getSession()

  if (to.path === '/login') {
    return session ? ROLE_HOME[session.role] : true
  }

  if (!session) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 页面级角色提示：业务页（含事件详情）三方角色均可访问，数据范围由服务端会话收窄；
  // 带 roles 元信息的路由（如操作审计）在页面层做角色拦截提示
  const rolesRecord = to.matched.find((record) => record.meta.roles)
  if (rolesRecord && !rolesRecord.meta.roles.includes(session.role)) {
    ElMessage.warning('当前角色无权访问该页面（服务端同为硬边界）')
    return ROLE_HOME[session.role]
  }

  return true
})

router.afterEach((to) => {
  const titles = to.matched.map((record) => record.meta.title).filter(Boolean)
  const pageTitle = titles[titles.length - 1]
  document.title = pageTitle ? `${pageTitle} - openvpp-admin 运营后台` : 'openvpp-admin 运营后台'
})

export default router
