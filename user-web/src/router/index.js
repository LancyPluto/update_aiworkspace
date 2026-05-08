//路由  登录守卫 访客重定向
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const LoginView = () => import('../views/LoginView.vue')
const ToolListView = () => import('../views/ToolListView.vue')
const ToolDetailView = () => import('../views/ToolDetailView.vue')
const TaskStatusView = () => import('../views/TaskStatusView.vue')
const TaskResultView = () => import('../views/TaskResultView.vue')
const MyTasksView = () => import('../views/MyTasksView.vue')

//路由设置
export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'login',
      component: LoginView,
      meta: { guestOnly: true, title: '登录' },
    },
    {
      path: '/login',
      redirect: '/',
    },
    //工具列表
    {
      path: '/tools',
      name: 'tools',
      component: ToolListView,
      meta: { requiresAuth: false, title: '工具' },
    },
    //工具详情
    {
      path: '/tools/:toolCode',
      name: 'tool-detail',
      component: ToolDetailView,
      meta: { requiresAuth: true, title: '工具详情' },
    },
    {
      path: '/tasks/:taskId',
      name: 'task-status',
      component: TaskStatusView,
      meta: { requiresAuth: true, title: '任务状态' },
    },
    {
      path: '/tasks/:taskId/result',
      name: 'task-result',
      component: TaskResultView,
      meta: { requiresAuth: true, title: '任务结果' },
    },
    {
      path: '/my-tasks',
      name: 'my-tasks',
      component: MyTasksView,
      meta: { requiresAuth: true, title: '我的任务' },
    },
  ],
})

// 路由守卫：Vue Router 4 推荐直接 return 目标路由，勿在末尾漏掉放行（否则页面空白）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.token) {
    return { name: 'tools' }
  }
  if (to.meta.title) {
    document.title = `${to.meta.title} · AI 工具市场`
  }
})
