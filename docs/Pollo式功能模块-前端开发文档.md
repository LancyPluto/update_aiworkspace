# Pollo 式功能模块 — 前端开发文档

> **版本**：1.0  
> **更新日期**：2026-05-29  
> **读者**：user-web（Vue 3）、admin-frontend（P1）  
> **关联**：[Pollo式功能模块-后端开发文档.md](./Pollo式功能模块-后端开发文档.md)、[自定义工作台工具平台化接入规范.md](./自定义工作台工具平台化接入规范.md)

---

## 1. 文档目标

在现有 **AI 工具超市**（`/marketplace`）下，增加与 [Pollo AI](https://pollo.ai/) 类似的**栏目模块页**：

1. **上部主创作区**：能力 Tab（可选）→ 模型选择 → 动态表单 → 生成（走标准任务链）。
2. **下部子功能网格**：独立 `ai_tool` 卡片，点击进入既有 `ToolUse` 或自定义工作台。

**原则**：增量接入，不破坏 Dashboard、Agent、PPT、标准 `ToolUse` 行为。

---

## 2. 不影响原有功能的边界

### 2.1 禁止改动行为

| 模块 | 路径 | 要求 |
| --- | --- | --- |
| 标准工具使用 | `pages/ToolUse/Page.vue` | 不修改创建任务核心逻辑 |
| 动态表单 | `components/DynamicForm/*` | 不修改 |
| 超市全量列表 | `pages/ToolList/Page.vue` | 不修改列表 API 与筛选逻辑 |
| 任务链 | `pages/TaskStatus/`、`TaskResult/` | 不修改 |
| Agent | `pages/AgentHome/*` | 不修改 |
| PPT | `pages/PptWorkspace/*` | 不修改 |

### 2.2 允许的最小改动

| 文件 | 改动 |
| --- | --- |
| `router/index.ts` | **追加** `/marketplace/:moduleKey` 路由 |
| `router/userRoutes.ts` | **追加** `marketplaceModule(moduleKey)` |
| `components/AppShell.vue` | AI 工具超市子菜单增加视频/图片/文本 |
| `api/types.ts` | 新增 Feature Module 类型 |
| `api/featureModuleApi.ts` | **新建** |
| `api/index.ts` | export `featureModuleApi` |
| `pages/Marketplace/*` | **新建** 模块页及子组件 |
| `utils/toolEntryRoute.ts` | P2：读 `integration`（P0 保持 PPT 分支） |

---

## 3. 信息架构与路由

### 3.1 路由表

| 路径 | name | 组件 | 说明 |
| --- | --- | --- | --- |
| `/marketplace` | ToolList | `ToolList/Page.vue` | 保留：全量 + 模态 Tab |
| `/marketplace/video` | MarketplaceVideo | `Marketplace/FeatureModulePage.vue` | `moduleKey=video` |
| `/marketplace/image` | MarketplaceImage | 同上 | `moduleKey=image` |
| `/marketplace/text` | MarketplaceText | 同上 | `moduleKey=text` |
| `/marketplace/:moduleKey` | MarketplaceModule | 同上 | 扩展 audio / file |

`FeatureModulePage` 通过路由 `props` 或 `useRoute().params.moduleKey` 拉取 Manifest。

### 3.2 侧栏（AppShell）

在「AI 工具超市」分组下：

| 标签 | 路径 |
| --- | --- |
| 全部 | `/marketplace` |
| 视频 | `/marketplace/video` |
| 图片 | `/marketplace/image` |
| 文本 | `/marketplace/text` |
| 智能体 | `/agents`（已有） |

高亮：`route.path.startsWith('/marketplace')` 时展开 `ai-market` 分组。

### 3.3 页面线框

```text
┌─────────────────────────────────────────────────────────┐
│ ModuleHeader: 标题 + 描述                                │
├─────────────────────────────────────────────────────────┤
│ PrimaryCreationPanel                                     │
│  ├─ SubCapabilityTabs（capabilities[]）                  │
│  ├─ ModelPickerBar（当前 capability 下 tools[]）         │
│  ├─ DynamicForm（fetchToolByCode 的 fields）             │
│  └─ 生成 → createTask → /tasks/:id/status                │
├─────────────────────────────────────────────────────────┤
│ SubFeatureGrid：「常用功能」卡片                          │
│  └─ FeatureToolCard → toolEntryRoute / toolUse           │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 目录结构

```text
src/pages/Marketplace/
  FeatureModulePage.vue
  components/
    PrimaryCreationPanel.vue
    ModelPickerBar.vue
    SubFeatureGrid.vue
    FeatureToolCard.vue
src/composables/
  useFeatureModule.ts
src/api/
  featureModuleApi.ts
```

---

## 5. API 与类型

### 5.1 `featureModuleApi.ts`

```ts
import { apiRequest } from "./client"
import type { FeatureModuleKey, FeatureModuleListItem, FeatureModuleManifest } from "./types"

export function fetchFeatureModuleList(token?: string) {
  return apiRequest<FeatureModuleListItem[]>("GET", "/api/v1/feature-modules", { token })
}

export function fetchFeatureModuleManifest(moduleKey: FeatureModuleKey, token?: string) {
  return apiRequest<FeatureModuleManifest>("GET", `/api/v1/feature-modules/${moduleKey}`, { token })
}
```

### 5.2 `api/types.ts` 增量

```ts
export type FeatureModuleKey = "video" | "image" | "text" | "audio" | "file"

export interface FeatureModuleListItem {
  moduleKey: FeatureModuleKey
  title: string
  description?: string
  sortOrder: number
}

export interface FeatureModuleManifest {
  moduleKey: FeatureModuleKey
  title: string
  description?: string
  primaryGroup: FeaturePrimaryGroup
  subFeatures: FeatureSubTool[]
}

export interface FeaturePrimaryGroup {
  defaultCapabilityCode: string
  capabilities: FeatureCapability[]
}

export interface FeatureCapability {
  code: string
  label: string
  tools: ToolSummary[]
}

export interface FeatureSubTool {
  toolCode: string
  title: string
  description?: string
  coverUrl?: string
  sortOrder: number
  badges?: string[]
  integrationMode?: string
}
```

---

## 6. 组件职责

### 6.1 `FeatureModulePage.vue`

- `onMounted`：`fetchFeatureModuleManifest(moduleKey)`。
- 将 `primaryGroup`、`subFeatures` 传给子组件。
- 处理加载/错误/空状态（无 PRIMARY 时提示「即将上线」）。

### 6.2 `PrimaryCreationPanel.vue`

状态：

- `activeCapabilityCode`：默认 `manifest.primaryGroup.defaultCapabilityCode`。
- `selectedToolCode`：当前 capability 下选中的 tool。
- `toolDetail`：`fetchToolByCode(selectedToolCode)` 含 `fields`。
- `formValues`：与 `ToolUse` 相同的 `buildTaskParams` 逻辑（可抽 `utils/taskParams.ts` P1）。

交互：

1. 切换 capability Tab → 选中该组第一个 tool → 重新拉详情。
2. 切换 model（toolCode）→ 保留字段名交集的参数 → 拉详情。
3. 提交：`createTask({ toolCode, params, clientRequestId })` → `router.push(taskStatus)`。

### 6.3 `ModelPickerBar.vue`

- Props：`tools: ToolSummary[]`、`modelValue: string`（toolCode）。
- 展示：`modelConfigName` / `modelName` / `toolName`、封面缩略图。
- Emit：`update:modelValue`。

### 6.4 `SubFeatureGrid` / `FeatureToolCard`

- 卡片展示 `title`、`description`、`coverUrl`、`badges`。
- 点击：`toolEntryRoute(toolCode, integrationMode)`；`STANDARD_TASK` 等价于 `toolUse(toolCode)`。

---

## 7. `useFeatureModule` composable（可选）

```ts
export function useFeatureModule(moduleKey: Ref<FeatureModuleKey>) {
  const manifest = ref<FeatureModuleManifest | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(token?: string) { /* fetchFeatureModuleManifest */ }

  return { manifest, loading, error, load }
}
```

---

## 8. 交互细则

| 场景 | 行为 |
| --- | --- |
| 模型切换 | 同 `capabilityCode` 下切换 toolCode；`fieldKey` 交集保留 |
| 生成 | 校验 `DynamicForm` → `POST /api/v1/tasks` → 跳转任务状态页 |
| 子功能 | SUB 卡片 → `/tools/:toolCode/use` 或 PPT 工作台 |
| 未登录生成 | 与 ToolUse 一致，路由守卫跳转登录 |
| PPT 工具 | 不出现在 SUB 网格（后端过滤）；若误展示则 `toolEntryRoute` 跳工作台 |

---

## 9. 管理端（P1）

在 `admin-frontend/app/tools/page.tsx` 增加「功能模块」折叠区，字段见后端文档，调用 `PUT /api/admin/v1/tools/{toolId}/feature-module`。

---

## 10. 分阶段交付

| 阶段 | 内容 |
| --- | --- |
| **P0** | `/marketplace/video`、侧栏、Manifest API 对接、主创作 + 子网格 |
| **P1** | image/text 路由、参数保留优化、Admin 表单 |
| **P2** | 模块内 SSE 进度、`toolEntryRoute` 读 integration、Dashboard 导流 |

---

## 11. 回归验证清单

- [ ] `/marketplace` 原列表与模态筛选正常
- [ ] `/marketplace/video` 可切换主模型并创建任务
- [ ] 子功能卡片进入 `/tools/:code/use` 与超市直接进入一致
- [ ] PPT 工具不在视频 SUB 区
- [ ] Dashboard、Agent、PPT 工作台无回归
- [ ] 未登录访问模块页可浏览；生成需登录

---

## 12. 关键文件索引

| 用途 | 路径 |
| --- | --- |
| 路由 | `user-web/src/router/index.ts` |
| 侧栏 | `user-web/src/components/AppShell.vue` |
| 标准使用参考 | `user-web/src/pages/ToolUse/Page.vue` |
| 入口分流 | `user-web/src/utils/toolEntryRoute.ts` |
| 任务 API | `user-web/src/api/taskApi.ts` |
