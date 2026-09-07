# Pollo 式功能模块 — 后端开发文档

> **版本**：1.0  
> **更新日期**：2026-05-29  
> **读者**：backend（Spring Boot）  
> **关联**：[Pollo式功能模块-前端开发文档.md](./Pollo式功能模块-前端开发文档.md)、[AI模型与模态配置指南.md](./AI模型与模态配置指南.md)

---

## 1. 文档目标

为 Pollo 式「功能栏目聚合页」提供后端能力：

1. **配置契约** `feature-module`（写入 `ai_tools.config_note`）。
2. **只读聚合 API**：按栏目返回主创作分组 + 子功能列表。
3. **不改变** 标准任务执行链（`POST /api/v1/tasks` → worker）。

子功能仍为 **独立 `ai_tools` 记录**（路径 A），不新建子工具表。

---

## 2. 配置契约：`feature-module`

### 2.1 标记格式

```text
<!-- feature-module:{"moduleKey":"video","role":"PRIMARY","primaryCapabilityCode":"text_to_video","sortOrder":10} -->
```

与 `<!-- tool-integration:... -->`、`<!-- ppt-workflow:... -->` **并存**于 `config_note`。

### 2.2 JSON 字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `moduleKey` | 是 | `video` \| `image` \| `text` \| `audio` \| `file` |
| `role` | 是 | `PRIMARY`（主创作）\| `SUB`（下部卡片） |
| `primaryCapabilityCode` | PRIMARY 时推荐 | Tab 分组码，如 `text_to_video` |
| `sortOrder` | 否 | 模块内排序，默认 `0` |
| `cardTitle` | SUB 可选 | 覆盖卡片标题 |
| `cardDescription` | SUB 可选 | 卡片描述 |
| `badges` | SUB 可选 | `["New"]` |

### 2.3 示例

**主创作（文生视频）**

```text
<!-- feature-module:{"moduleKey":"video","role":"PRIMARY","primaryCapabilityCode":"text_to_video","sortOrder":10} -->
```

**子功能（换脸）**

```text
<!-- feature-module:{"moduleKey":"video","role":"SUB","sortOrder":100,"cardTitle":"视频换脸","badges":["New"]} -->
```

### 2.4 未配置时的兜底

| moduleKey | 纳入条件 |
| --- | --- |
| `video` | `outputModality=VIDEO` 且 `integrationMode` 为 `STANDARD_TASK`（或缺省） |
| `image` | `outputModality=IMAGE` … |
| `text` | `outputModality=TEXT` … |

未写 `feature-module` 的上架标准任务工具：视为该模态栏目下的 **PRIMARY**，归入 `primaryCapabilityCode=general`。

**排除**：`PPT_WORKSPACE` 等非标准任务工具不进入 Manifest。

---

## 3. 解析与合并

### 3.1 `FeatureModuleResolver`

- 路径：`tool/feature/FeatureModuleResolver.java`
- 正则：`<!--\s*feature-module:(\{.*?\})\s*-->`
- 方法：`Optional<FeatureModuleConfig> parse(String configNote)`

### 3.2 `ConfigNoteMergeSupport`

更新 `hasIntegrationMarker` / `extractIntegrationMarkers` / `stripIntegrationMarkers`，**同时保留** `feature-module` 块，避免管理端整表更新 `config_note` 时丢失栏目配置。

---

## 4. API

### 4.1 用户端

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/feature-modules` | 栏目列表 |
| GET | `/api/v1/feature-modules/{moduleKey}` | 模块 Manifest |

#### `GET /api/v1/feature-modules` 响应示例

```json
{
  "code": 0,
  "data": [
    { "moduleKey": "video", "title": "视频", "description": "文生视频、图生视频与视频工具", "sortOrder": 1 },
    { "moduleKey": "image", "title": "图片", "sortOrder": 2 },
    { "moduleKey": "text", "title": "文本", "sortOrder": 3 }
  ]
}
```

#### `GET /api/v1/feature-modules/video` 响应示例

```json
{
  "code": 0,
  "data": {
    "moduleKey": "video",
    "title": "视频",
    "description": "创建 AI 视频",
    "primaryGroup": {
      "defaultCapabilityCode": "text_to_video",
      "capabilities": [
        {
          "code": "text_to_video",
          "label": "文生视频",
          "tools": [
            {
              "id": 1,
              "toolCode": "kling_text_to_video",
              "toolName": "可灵文生视频",
              "modelConfigName": "Kling 2.0",
              "outputModality": "VIDEO"
            }
          ]
        }
      ]
    },
    "subFeatures": [
      {
        "toolCode": "video_face_swap",
        "title": "视频换脸",
        "sortOrder": 100,
        "badges": ["New"]
      }
    ]
  }
}
```

### 4.2 管理端（P1）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/v1/feature-modules/catalog` | moduleKey、capability 建议枚举 |
| PUT | `/api/admin/v1/tools/{toolId}/feature-module` | 写入/更新 feature-module 块 |

**P0** 可先通过 `config_note` 手写 JSON，不强制 Admin API。

---

## 5. 服务实现要点

### 5.1 `FeatureModuleService`

1. `listModules()`：返回内置栏目元数据（video/image/text）。
2. `getManifest(moduleKey)`：
   - `toolMapper.findAllOnline()` 拉全量上架工具；
   - `ToolIntegrationResolver` 判断是否标准任务；
   - `FeatureModuleResolver` 读显式配置，否则按 `outputModality` 兜底；
   - PRIMARY 按 `primaryCapabilityCode` 分组排序；
   - SUB 组装 `FeatureSubToolResponse`（`cardTitle` 优先）。

### 5.2 capability 展示名

| code | label |
| --- | --- |
| `text_to_video` | 文生视频 |
| `image_to_video` | 图生视频 |
| `text_to_image` | 文生图 |
| `image_to_image` | 图生图 |
| `general` | 创作 |

### 5.3 任务创建

**无改动**。前端主创作区仍 `POST /api/v1/tasks`，`TaskServiceImpl.create` 逻辑不变。

---

## 6. 代码结构（P0 已落地）

```text
tool/feature/
  FeatureModuleConstants.java
  FeatureModuleConfig.java
  FeatureModuleResolver.java
  FeatureModuleService.java
  FeatureModuleServiceImpl.java
tool/controller/
  FeatureModuleController.java
tool/dto/
  FeatureModuleListItemResponse.java
  FeatureModuleManifestResponse.java
  FeaturePrimaryGroupResponse.java
  FeatureCapabilityResponse.java
  FeatureSubToolResponse.java
```

---

## 7. 运营配置示例（视频栏目）

| toolCode | role | primaryCapabilityCode |
| --- | --- | --- |
| `kling_text_to_video` | PRIMARY | `text_to_video` |
| `kling_text_to_video_pro` | PRIMARY | `text_to_video` |
| `kling_image_to_video` | PRIMARY | `image_to_video` |
| `video_face_swap` | SUB | — |
| `video_lip_sync` | SUB | — |

各工具独立 `modelConfigId` 与字段 schema；Kling 见 [kling_model_integration_guide.md](./kling_model_integration_guide.md)。

---

## 8. Agent 过滤（P2 建议）

`AgentToolDescriptorServiceImpl.listAvailableToolsForUser` 排除 `integrationMode != STANDARD_TASK`，避免 Agent 调用 PPT BFF。

---

## 9. OpenAPI

在 `docs/api/openapi.yml` 登记：

- `/api/v1/feature-modules`
- `/api/v1/feature-modules/{moduleKey}`

---

## 10. 测试清单

- [ ] 无 `feature-module` 的 VIDEO 工具出现在 `GET .../video` 的 PRIMARY `general` 组
- [ ] `role=SUB` 仅出现在 `subFeatures`
- [ ] PPT 工具不出现在 Manifest
- [ ] 更新工具 `config_note`（无 feature-module 块）不丢失已有 feature-module 标记
- [ ] 非法 `moduleKey` 返回 404

---

## 11. 关键依赖

| 组件 | 路径 |
| --- | --- |
| 集成解析 | `tool/integration/ToolIntegrationResolver.java` |
| config 合并 | `tool/support/ConfigNoteMergeSupport.java` |
| 工具查询 | `tool/mapper/ToolMapper.java` |
