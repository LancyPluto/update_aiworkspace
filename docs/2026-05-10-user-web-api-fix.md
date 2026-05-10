# 2026-05-10 用户端 user-web API 联调修复

> 范围：修复用户前端（user-web）与后端 API 的数据同步问题，对齐 `docs/api/openapi.yml` 契约。  
> 关联分支：`user-web`

---

## 1. 问题概述

用户前端展示的工具、任务、算力等数据与后端不一致，原因：
- 前端类型定义（`types.ts`）字段名与后端返回不匹配（如 `username` vs `account`、`name` vs `categoryName`）
- 分页接口返回类型未正确使用 `PageResult<T>` 包装
- 所有页面使用硬编码 Mock 数据，未调用真实 API
- 登录页面无表单绑定/无 API 调用/无 Token 持久化

---

## 2. 修复清单

### 2.1 API 类型定义对齐（`user-web/src/api/types.ts`）

| 类型 | 修复内容 |
|------|----------|
| `LoginRequest` | `username` → `account`（与后端一致） |
| `LoginResponse` | 兼容 `token` 和 `accessToken` 两种返回 |
| `ToolCategory` | `name` → `categoryName`，新增 `categoryCode` |
| `ToolSummary` | 新增 `categoryName` 字段 |
| `ToolDetail` | 新增 `fields: ToolField[]`，对齐工具动态字段 |
| `CreditAccount` | `id` → `accountId`，新增 `available` |
| `AiTask` → `TaskDetail` | 完全重写，对齐契约 TaskDetail 结构 |
| 新增类型 | `PageResult<T>`, `ToolField`, `ToolFieldOption`, `TaskResult`, `ListTasksQuery`, `CreditLog` |

### 2.2 API 调用层修复

| 文件 | 修复内容 |
|------|----------|
| `authApi.ts` | `login()` 使用 `LoginRequest { account, password }` |
| `toolApi.ts` | `fetchTools()` 返回 `PageResult<ToolSummary>`；`searchTools()` 新增 |
| `taskApi.ts` | `fetchTasks()` 返回 `PageResult<TaskDetail>`；新增 `cancelTask()` |
| `creditApi.ts` | `fetchCreditAccount()` 返回 `CreditAccount` |
| `userApi.ts` | 新增 `getCurrentUser()` → `GET /api/v1/users/me` |

### 2.3 登录态管理（新增 `user-web/src/store/authStore.ts`）

- Pinia Store 管理 `token` + `user`
- `localStorage` 持久化 Token
- 应用启动时自动恢复登录态

### 2.4 前端页面联调

| 页面 | 接入的 API |
|------|-----------|
| `Login/Page.vue` | `login()` — 表单绑定 + API 调用 + Token 持久化 + 跳转 |
| `ToolList/Page.vue` | `fetchToolCategories()` + `fetchTools()` / `searchTools()` |
| `ToolDetail/Page.vue` | `fetchToolByCode()` — 含动态字段渲染 |
| `ToolUse/Page.vue` | `fetchToolByCode()` + `createTask()` |
| `MyTasks/Page.vue` | `fetchTasks()` + `cancelTask()` — 带状态过滤 |
| `TaskStatus/Page.vue` | `fetchTaskStatus()` — 3 秒轮询 |
| `TaskResult/Page.vue` | `fetchTaskById()` — 结果渲染 |
| `AppShell.vue` | `fetchCreditAccount()` — 真实算力数据 + 用户信息 |

### 2.5 基础设施

- 路由守卫（`router/index.ts`）：未登录自动跳转登录页
- `userRoutes` 路由常量：统一管理页面路由
- `user-web/.env`：配置后端 API 地址 `http://localhost:8080`
- `DynamicForm.vue`：根据后端返回的动态字段定义渲染表单
- 全部通过 TypeScript 类型检查（`vue-tsc --noEmit` 零错误）

---

## 3. 验证

- `cd user-web && npx vue-tsc --noEmit` → **0 errors**
- `cd user-web && npm run build` → **成功**

---

## 4. 变更文件清单

```
modified:   user-web/.env
modified:   user-web/src/api/authApi.ts
modified:   user-web/src/api/creditApi.ts
modified:   user-web/src/api/index.ts
modified:   user-web/src/api/taskApi.ts
modified:   user-web/src/api/toolApi.ts
modified:   user-web/src/api/types.ts
modified:   user-web/src/api/userApi.ts
modified:   user-web/src/components/AppShell.vue
modified:   user-web/src/components/DynamicForm/DynamicForm.vue
modified:   user-web/src/main.ts
modified:   user-web/src/pages/Login/Page.vue
modified:   user-web/src/pages/MyTasks/Page.vue
modified:   user-web/src/pages/TaskResult/Page.vue
modified:   user-web/src/pages/TaskStatus/Page.vue
modified:   user-web/src/pages/ToolDetail/Page.vue
modified:   user-web/src/pages/ToolList/Page.vue
modified:   user-web/src/pages/ToolUse/Page.vue
modified:   user-web/src/router/index.ts
modified:   user-web/src/router/userRoutes.ts
new file:  user-web/src/store/authStore.ts
```
