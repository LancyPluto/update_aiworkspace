# AIGC 平台社区系统开发文档

更新时间：2026-05-31

## 1. 系统定位

社区系统不是泛论坛，也不是素材库的替代品。当前阶段的核心目标是把 AIGC 平台里的优秀生成结果沉淀为可发现、可学习、可收藏、可复用的公开作品资产，最终形成：

生成结果 -> 一键发布 -> 社区曝光 -> 查看 Prompt/工具/作者 -> 灵感收藏 -> 同款创作 -> 回到工作台 -> 创建任务与消费

因此社区系统优先服务三件事：

1. 作品发现：让用户看到真实可复用的优秀案例。
2. Prompt 学习：在作者允许的前提下展示可学习的 Prompt 预览和完整 Prompt。
3. 工具回流：所有“使用同款创作”统一回到 `/dashboard`，复用现有工作台能力。

本阶段明确暂缓评论、关注、私信、动态流和复杂推荐算法，避免审核和社区治理复杂度过早膨胀。

## 2. 产品边界

### 2.1 社区与素材库的关系

素材库和社区保留两个入口，但底层体验复用同一套资产模型和卡片组件。

素材库：

- 面向“我的私有生成资产管理”。
- 只展示当前用户自己的生成任务结果。
- 重点是预览、管理、发布、删除、继续创作。

社区：

- 面向“公开作品发现与传播”。
- 展示通过审核且公开的作品。
- 重点是浏览、点赞、收藏、查看作者、学习 Prompt、同款创作。

收藏社区作品不等于加入素材库。社区收藏定义为“灵感收藏”，后续可以演进为灵感库、收藏夹、灵感专题，但不复制素材文件，也不混入私有素材库。

### 2.2 参考产品取舍

SeaArt 的社区作品页有推荐、热门、最新、话题、同款、作品信息、评论和相关推荐，适合借鉴内容分发和话题运营结构。

Liblib 更偏模型、工作流、工具资产市场和灵感发现，更贴近当前平台阶段：作品服务于工具回流、模板复用和商业转化。

当前实现选择：

- 借鉴 SeaArt 的发现、话题、同款和相关推荐思路。
- 借鉴 Liblib 的“资产发现 -> 去使用”路径。
- 暂不实现评论、关注、私信、复杂内容推荐。

## 3. 当前已实现能力

### 3.1 前台用户侧

已实现模块：

- 社区发现页 `/community`
- 社区作品详情页 `/community/posts/:postId`
- 创作者公开主页 `/u/:userId`
- 灵感收藏夹
- 与素材库统一的 `AssetCard`
- 与素材库统一的 `AssetPreviewItem`
- 同款创作统一跳转 `/dashboard`
- `dashboard_pending_asset` 工作台预填机制

发现页当前结构：

- 精选作品
- 最新作品
- 热门同款
- 动态话题入口
- 全部作品瀑布流
- 模态筛选：全部、图片、视频、文本、音频
- 排序：`QUALITY`、`LATEST`、`POPULAR`、`FAVORITES`、`SAME_STYLE`
- 搜索：标题、标签、工具、Prompt 快照等

作品详情页能力：

- 展示作品媒体、标题、描述、作者、工具、标签、话题、互动数据。
- 根据 `promptVisible` 控制 Prompt 是否公开。
- Prompt 未公开时，仍允许同款创作，但不向工作台写入 Prompt。
- 同款创作会先调用 same-style 计数接口，再通过 `dashboard_pending_asset` 进入工作台。

灵感收藏夹：

- 用户可收藏社区作品。
- 重复收藏幂等。
- 收藏夹内继续复用 `AssetCard`。
- 收藏夹同款创作继续走 `/dashboard`。
- 收藏夹不复制作品到素材库。

### 3.2 管理后台

后台社区作品管理已从“隐藏/恢复”升级为运营工作台方向。

当前支持：

- 作品列表
- 状态筛选
- 审核状态筛选
- 搜索
- 查看作品内容
- 查看完整 Prompt 快照
- 查看标题、描述、工具、作者、互动数据
- 人工设置 topic
- 人工设置 tags
- 精选
- 置顶
- 审核通过
- 驳回
- 隐藏
- 恢复
- 数据看板基础统计

后台可看到完整 Prompt 快照；前台仍严格遵守 `promptVisible`，不公开时不返回完整 Prompt。

### 3.3 后端能力

核心接口包括：

- `GET /api/v1/community/posts`
- `GET /api/v1/community/search`
- `GET /api/v1/community/topics`
- `GET /api/v1/community/topics/{topic}`
- `GET /api/v1/community/posts/{postId}`
- `POST /api/v1/community/posts`
- `PATCH /api/v1/community/posts/{postId}`
- `DELETE /api/v1/community/posts/{postId}`
- `POST /api/v1/community/posts/{postId}/like`
- `DELETE /api/v1/community/posts/{postId}/like`
- `POST /api/v1/community/posts/{postId}/favorite`
- `DELETE /api/v1/community/posts/{postId}/favorite`
- `POST /api/v1/community/posts/{postId}/same-style`
- `POST /api/v1/community/events`
- `GET /api/v1/community/collections`
- `POST /api/v1/community/collections`
- `POST /api/v1/community/collections/{collectionId}/items`
- `DELETE /api/v1/community/collections/{collectionId}/items/{postId}`
- `GET /api/v1/community/users/{userId}`
- `GET /api/v1/community/users/{userId}/posts`
- `GET /api/v1/community/creators/{userId}`

管理端接口包括：

- `GET /api/admin/v1/community/posts`
- `GET /api/admin/v1/community/stats`
- `POST /api/admin/v1/community/posts/{postId}/hide`
- `POST /api/admin/v1/community/posts/{postId}/restore`
- `POST /api/admin/v1/community/posts/{postId}/approve`
- `POST /api/admin/v1/community/posts/{postId}/reject`
- `POST /api/admin/v1/community/posts/{postId}/feature`
- `POST /api/admin/v1/community/posts/{postId}/pin`
- `POST /api/admin/v1/community/posts/{postId}/annotate`

## 4. 数据模型

### 4.1 community_posts

核心字段：

- `id`
- `user_id`
- `task_id`
- `modality`
- `cover_url`
- `title`
- `description`
- `prompt_visible`
- `prompt_snapshot`
- `tool_code`
- `tool_name`
- `status`
- `featured`
- `pinned`
- `topic`
- `same_style_count`
- `audit_status`
- `audit_reason`
- `view_count`
- `detail_click_count`
- `share_count`
- `quality_score`
- `like_count`
- `favorite_count`
- `last_featured_at`
- `created_at`
- `updated_at`

公开可见条件：

```sql
status = 'PUBLISHED'
AND COALESCE(audit_status, 'APPROVED') = 'APPROVED'
```

所有前台列表、详情、搜索、专题、创作者主页、收藏夹公开展示都必须遵守该条件。

### 4.2 community_post_tags

用于作品标签。

- `post_id`
- `tag`

标签来源：

- 发布时规则补齐。
- 后台人工调整。
- 用户发布时显式填写。

规则补齐只填空值，不覆盖人工配置。

### 4.3 community_events

用于转化链路埋点。

字段：

- `post_id`
- `user_id`
- `event_type`
- `source`
- `tool_code`
- `task_id`
- `credits`
- `created_at`

事件类型：

- `impression`
- `detail_view`
- `like`
- `favorite`
- `same_style_click`
- `dashboard_open`
- `task_created`
- `credit_spent`
- `share`

### 4.4 community_collections

灵感收藏夹。

- `id`
- `user_id`
- `name`
- `default_collection`
- `item_count`
- `created_at`
- `updated_at`

默认收藏夹自动创建。

### 4.5 community_collection_items

收藏夹作品关系。

- `collection_id`
- `post_id`
- `user_id`
- `created_at`

同一作品重复收藏应保持幂等。

## 5. 规则打标策略

当前采用“人工 + 规则”，不使用自动模型打标。

原因：

- 当前社区内容规模还不足以支撑复杂模型打标投入。
- 早期内容质量更依赖运营判断。
- 模型打标会带来异步任务、置信度、纠错和成本问题。

默认规则：

- 图片、商品图工具 -> `产品图生成`
- 视频、脚本工具 -> `短视频脚本`
- 文案、营销、小红书工具 -> `小红书文案`
- 数字人、口播、音视频工具 -> `数字人案例`
- 文本类兜底 -> `小红书文案`

默认标签：

- 根据 `modality` 补 `图片`、`视频`、`音频`、`文案`
- 根据工具关键字补 `商品图`、`脚本`、`小红书`、`数字人`
- 补充工具名称作为候选标签

约束：

- 用户或后台已填写的 topic 不覆盖。
- 用户或后台已填写的 tags 不覆盖。
- 后台人工打标优先级最高。
- 规则打标只用于初始候选。

## 6. 排序与分发

发现页默认排序为 `QUALITY`，不是简单按最新。

支持排序：

- `QUALITY`：综合质量排序
- `LATEST`：最新发布
- `POPULAR`：最多点赞
- `FAVORITES`：最多收藏
- `SAME_STYLE`：最多同款
- `VIEWS`：最多浏览

`quality_score` 当前综合以下信号：

- 置顶
- 精选
- 封面完整
- 标题完整
- Prompt 公开且存在
- topic 完整
- tags 完整
- 点赞数
- 收藏数
- 同款生成数
- 详情点击数
- 分享数

发现页话题入口不写死展示，而是从 `community_posts.topic` 聚合 Top N，且只聚合公开可见作品。没有内容的话题不展示。

## 7. 同款创作链路

同款生成的唯一主链路是 `/dashboard`。

流程：

1. 用户在发现页、详情页或灵感收藏夹点击同款创作。
2. 前端调用 `POST /api/v1/community/posts/{postId}/same-style`。
3. 后端增加 `same_style_count` 并记录 `same_style_click` 事件。
4. 前端构造 `AssetPreviewItem`。
5. 前端写入 `sessionStorage.dashboard_pending_asset`。
6. 前端跳转 `/dashboard?tool=xxx&modality=xxx&sourcePost=xxx`。
7. 工作台读取 pending asset，预填工具、公开 Prompt 和源媒体。
8. 工作台创建任务时携带 `sourcePostId`。
9. 后续通过社区事件记录 `task_created`、`credit_spent`。

隐私规则：

- `promptVisible = true` 时，允许写入 Prompt。
- `promptVisible = false` 时，不写入 Prompt。
- 私密参数、密钥、上传文件链接不会进入公开 Prompt 快照。
- 图片、视频、音频作品可写入 `sourceAssetUrl`，供支持源素材输入的工具使用。

## 8. 前端实现说明

### 8.1 共享资产模型

统一资产模型：

- `AssetPreviewItem`
- `assetFromTask(...)`
- `assetFromCommunityPost(...)`

统一卡片：

- `AssetCard.vue`

素材库和社区通过 `source="private" | "community"` 区分差异展示。

### 8.2 社区发现页

路径：

- `user-web/src/pages/CommunityDiscover/Page.vue`

职责：

- 拉取运营化分区数据。
- 拉取动态话题。
- 拉取主作品流。
- 展示筛选、排序、搜索、话题入口。
- 使用 `AssetCard` 渲染作品。
- 点击作品进入稳定详情 URL。
- 上报 impression/detail_view 事件。

### 8.3 社区详情页

路径：

- `user-web/src/pages/CommunityPost/Page.vue`

职责：

- 展示作品完整信息。
- 保护 Prompt 可见性。
- 处理点赞、收藏、同款。
- 同款跳转工作台。

### 8.4 灵感收藏夹

路径：

- `user-web/src/pages/InspirationCollections/Page.vue`

职责：

- 展示我的社区收藏。
- 支持收藏夹创建、改名、删除、移除作品。
- 继续复用 `AssetCard` 和同款跳转。

### 8.5 创作者主页

路径：

- `user-web/src/pages/PublicProfile/Page.vue`

职责：

- 展示创作者简介、作品数、获赞数、收藏数、同款数。
- 展示代表作、精选作品、最近发布。
- 仅展示公开且审核通过作品。

## 9. 后台运营实现说明

路径：

- `admin-frontend/app/community-posts/page.tsx`

当前后台核心目标是“看内容后打标”。

运营人员需要能看到：

- 封面
- 视频、音频、文本预览
- 完整 Prompt 快照
- 标题
- 描述
- 工具
- 作者
- 互动数据
- 审核状态
- 推荐状态
- 标签和话题

后台操作：

- 审核通过
- 驳回
- 隐藏
- 恢复
- 精选
- 置顶
- 设置 topic
- 设置 tags

## 10. 安全与可见性

关键规则：

- 用户只能发布自己的成功任务。
- 失败任务、处理中任务、他人任务不能发布。
- 已发布作品再次发布时更新状态和元数据，不重复创建。
- 隐藏、驳回、待审核作品不可出现在任何前台公开入口。
- Prompt 不公开时，前台不返回完整 Prompt。
- 后台可查看完整 Prompt 以方便审核和打标。
- 上传文件链接、密钥、token、password、secret、path 等字段不会进入公开 Prompt 快照。
- 社区事件接口允许匿名上报基础浏览行为，但写操作仍需要登录。

## 11. 测试覆盖

社区接口集成测试覆盖：

- 隐藏、驳回、待审核作品不出现在公开入口。
- 隐藏作品详情不可访问。
- Prompt 不公开时前台不返回 Prompt。
- 收藏夹重复收藏幂等。
- 同款生成增加计数并记录事件。
- 动态话题只聚合公开且审核通过作品。

回归命令：

```bash
cd user-web
npm run build
```

```bash
cd admin-frontend
npm run build
```

```bash
cd backend
mvn test-compile
```

```bash
cd backend
mvn -Dtest=CommunityApiTest test
```

截至 2026-05-31，本轮社区相关验证已通过：

- `user-web npm run build`
- `admin-frontend npm run build`
- `backend mvn test-compile`
- `backend mvn -Dtest=CommunityApiTest test`

## 12. 已知风险

1. 全量后端测试曾存在非社区的 worker/billing 相关失败，需要在社区上线前单独确认是否仍为既有问题。
2. 工作区存在非社区改动，提交社区分支时应避免混入 `.env`、agent-service、agent-files 等无关内容。
3. 规则打标依赖工具名称、工具编码和模态字段，工具命名不规范时可能需要后台人工修正。
4. 社区发现页已经具备运营化分区，但还没有复杂个性化推荐。
5. 评论、关注、私信尚未实现，后续若做必须先补审核、举报、敏感词和风控策略。

## 13. 下一阶段建议

优先级建议：

1. 完善后台审核队列，将作品管理、审核队列、数据看板拆成更清晰的三个视角。
2. 补全工作台任务创建后的社区归因事件，稳定记录 `task_created` 和 `credit_spent`。
3. 增加工具维度的社区案例入口，在工具详情页展示该工具的精选社区作品。
4. 优化灵感收藏夹，支持自定义收藏夹批量管理和按工具/话题筛选。
5. 增加更多社区接口集成测试，覆盖审核状态变化后的搜索、话题、创作者主页、收藏夹展示。
6. 当社区内容规模上来后，再评估模型辅助打标、相关推荐和创作者激励。

暂不建议下一阶段立即做：

- 评论区
- 关注流
- 私信
- 复杂推荐算法
- 独立专题 CMS
- 全自动模型打标

当前最有价值的推进方向仍然是：让作品更容易被发现，让 Prompt 更容易被学习，让同款创作更稳定地回到工作台并产生任务与消费。
