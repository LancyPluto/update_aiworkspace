# 用户端正式路由契约

本文记录当前科创点 AI 用户端的实际上线路由。唯一运行时事实源是
`user-web/src/router/index.ts`；路由 helper 位于 `user-web/src/router/userRoutes.ts`。

2026-06-10 曾引入一套未完成的外部风格前端迁移，其中 `/create`、`/tool`、
`/video`、`/image`、`/assets` 和 PPT 工作区没有进入当前正式产品。本期已删除其
孤立页面、导航数据、拆分路由和测试契约，后续不得依据历史提交恢复这些入口。

## 入口与认证

| 路由 | 认证 | 当前页面 | 说明 |
| --- | --- | --- | --- |
| `/` | 公开 | `Login/Page.vue` | 登录页；已登录用户进入合法 redirect 或 `/home`。 |
| `/login` | 公开 | 重定向 | 兼容别名，保留 query 后跳转 `/`。 |
| `/home` | 登录 | `Home/Page.vue` | 登录后的默认首页。 |
| `/dashboard` | 登录 | `Dashboard/Page.vue` | 正式生成工作台；`tool`、`modality`、`sourcePost` 等 query 必须保留。 |

只接受以 `/` 开头的站内登录回跳地址。空值、登录页自身或非法地址统一回到
`/home`，防止开放重定向和登录循环。

## 正式业务路由

| 路由 | 认证 | 当前页面/行为 |
| --- | --- | --- |
| `/marketplace` | 公开 | `ToolList/Page.vue` 工具列表。 |
| `/agents` | 公开 | `ToolList/Page.vue`，智能体筛选模式。 |
| `/tools/:id` | 公开 | `ToolDetail/Page.vue` 工具详情。 |
| `/tools/:id/use` | 登录 | `ToolUse/Page.vue` 工具动态表单。 |
| `/chat/:toolId` | 公开 | 兼容旧分享链接，携带工具编码进入 `/dashboard`。 |
| `/tasks` | 登录 | `MyTasks/Page.vue` 任务列表。 |
| `/tasks/:taskId/status` | 登录 | 任务状态页。 |
| `/tasks/:taskId/result` | 登录 | 任务结果页。 |
| `/workflow/studio/:taskId` | 登录 | 工作流详情。 |
| `/library` | 登录 | `MaterialLibrary/Page.vue` 素材与生成资产。 |
| `/library/subjects` | 登录 | 主体素材库。 |
| `/subjects` | 登录 | 兼容重定向到 `/library/subjects`。 |
| `/agent` | 登录 | Agent 会话工作区。 |
| `/community` | 公开 | 社区发现页。 |
| `/community/posts/:postId` | 公开 | 社区作品详情。 |
| `/community/inspirations` | 登录 | 灵感收藏夹。 |
| `/u/:userId` | 公开 | 用户公开主页。 |
| `/billing` | 登录 | 会员、算力与充值。 |
| `/referral` | 登录 | 兼容入口：跳转到 `/home` 并打开“邀请有礼”弹窗。 |
| `/profile` | 登录 | 个人资料。 |
| `/legal/privacy` | 公开 | 隐私政策，版本化用户服务文件。 |
| `/legal/terms` | 公开 | 服务条款，版本化用户服务文件。 |
| `/legal/aigc-labeling` | 公开 | AI 生成内容标识说明。 |
| `/legal/refund` | 公开 | 退款范围、申请与核验说明。 |
| `/contact` | 公开 | 客服与内容投诉渠道。 |

## 业务跳转规则

- 首页、工具卡、工具详情、同款创作和素材复用统一进入
  `/dashboard?tool=<toolCode>`。
- 素材复用通过 `dashboard_pending_asset` 在同一标签页传递输入素材，并通过
  query 传递 `modality` 和 `sourcePost`。
- 工具列表返回地址统一为 `/marketplace`，资产返回地址统一为 `/library`。
- 所有内部业务代码优先使用 `userRoutes` 或 `assetReplay`，不分散拼接路径。

## 明确下线的历史入口

以下路径不属于当前产品，不创建页面或兼容重定向：

- `/create`
- `/tool`
- `/video`
- `/image`
- `/assets`
- `/tools/banana_ppt_generator/workspace`
- `/tools/banana_ppt_generator/workspace/:bindingId`

## 验收

1. `/dashboard?tool=gpt_image2` 直接打开和刷新均加载相同工具。
2. 未登录访问受保护页面时，登录后恢复完整 path 和 query。
3. 社区同款、资产复用和工具卡启动均进入 `/dashboard` 且不丢 tool。
4. 所有侧栏和页面内导航目标均在 `router/index.ts` 中存在。
5. `npm test` 和 `npm run build` 均通过；构建产物不包含历史复制版页面 chunk。
6. Logo 使用 `asset/logo.png` 本地资源；登录协议和页脚服务入口不得使用空 `#` 链接。
