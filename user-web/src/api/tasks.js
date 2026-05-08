//任务接口封装
import { http } from './http'

//创建任务接口
export function createTask(body) {
  return http.post('/tasks', body).then((r) => r.data)
}
//获取任务任务列表
export function listTasks(params = {}) {
  return http.get('/tasks', { params }).then((r) => unwrapList(r.data))
}
//查询单个任务状态
export function getTaskStatus(taskId) {
  return http
    .get(`/tasks/${encodeURIComponent(taskId)}/status`)
    .then((r) => r.data)
}
//获取单个任务详情
export function getTask(taskId) {
  return http.get(`/tasks/${encodeURIComponent(taskId)}`).then((r) => r.data)
}
//自动适配列表格式
function unwrapList(data) {
  if (Array.isArray(data)) return data
  if (Array.isArray(data?.data)) return data.data
  if (Array.isArray(data?.items)) return data.items
  if (data?.list && Array.isArray(data.list)) return data.list
  return []
}
