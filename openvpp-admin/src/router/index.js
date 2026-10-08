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
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '事件管理' }
  },
  {
    path: 'declarations',
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '申报管理' }
  },
  {
    path: 'dispatch',
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '派单管理' }
  },
  {
    path: 'monitor',
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '运行监测' }
  },
  {
    path: 'assessment',
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '效果评估' }
  },
  {
    path: 'settlement',
    component: () => import('../views/workspace/PlaceholderView.vue'),
    meta: { title: '结算管理' }
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

  const rolesRecord = to.matched.find((record) => record.meta.roles)
  if (rolesRecord && !rolesRecord.meta.roles.includes(session.role)) {
    ElMessage.warning('当前角色无权访问该工作台')
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
