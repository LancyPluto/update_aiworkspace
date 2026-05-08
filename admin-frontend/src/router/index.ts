import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { getToken } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/tools' },
      { path: 'tools', name: 'tools', component: () => import('@/views/tools/ToolListView.vue') },
      { path: 'tools/new', name: 'tool-create', component: () => import('@/views/tools/ToolFormView.vue') },
      { path: 'tools/:toolId/edit', name: 'tool-edit', component: () => import('@/views/tools/ToolFormView.vue') },
      { path: 'tools/:toolId/fields', name: 'tool-fields', component: () => import('@/views/tools/FieldSchemaView.vue') },
      { path: 'tools/:toolId/prompts', name: 'tool-prompts', component: () => import('@/views/tools/PromptConfigView.vue') },
      { path: 'tasks', name: 'tasks', component: () => import('@/views/tasks/TaskListView.vue') },
      { path: 'tasks/:taskId', name: 'task-detail', component: () => import('@/views/tasks/TaskDetailView.vue') },
      { path: 'users', name: 'users', component: () => import('@/views/users/UserListView.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && getToken()) {
    return { name: 'tools' }
  }
  return true
})

export default router
