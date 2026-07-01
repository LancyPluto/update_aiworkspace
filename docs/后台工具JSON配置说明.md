# 后台工具 JSON 配置说明

> 适用范围：管理后台「AI 工具管理 → 工具配置 → 聊天输入控件 / 参数填写」。  
> 工具字段可通过配置包 `tools[].fields` 批量同步，见 [配置包导入导出 — 维护指南](./配置包导入导出-维护指南.md)。  
> 目标：让工具字段由后台数据驱动，用户端只负责按字段类型和 `optionsJson` 渲染，不在前端为具体模型硬编码参数。

## 一、字段总览

后台每个工具字段至少包含：

| 字段 | 说明 |
| --- | --- |
| `fieldKey` | 提交给 Worker/API 的参数名，必须与执行器期望一致 |
| `fieldName` | 用户端展示名 |
| `fieldType` | 控件类型，例如 `textarea`、`multi_image`、`aspect_ratio` |
| `required` | 是否接口必填 |
| `sortOrder` | 展示顺序 |
| `placeholder` | 简短占位提示，长说明建议写入 `optionsJson` 的说明类字段 |
| `optionsJson` | 控件元信息、选项、默认值、可见条件等 JSON |

用户端会读取 `optionsJson` 进行 UI 分层、默认值、选项、素材入口和校验，不要把这些规则写死在前端。

## 二、推荐 fieldType

| 类型 | 用途 | 提交值 |
| --- | --- | --- |
| `textarea` | 主提示词或长文本 | `string` |
| `text` | 短文本参数 | `string` |
| `number` | 数字输入 | `number` |
| `slider` | 权重、强度、创意发散等连续值 | `number` |
| `select` | 选项较多或不适合横向展示的枚举 | `string` |
| `radio` | 选项较少的枚举，用户端会优先渲染为分段按钮 | `string` |
| `checkbox` | 开关 | `boolean` |
| `aspect_ratio` | 画面比例 | `string`，如 `auto`、`16:9` |
| `image` / `image_upload` | 单图 URL | `string` |
| `multi_image` | 多参考图 URL 数组 | `string[]` |
| `multi_video` | 多参考视频 URL 数组 | `string[]` |
| `subject_element_list` | 可灵主体参考列表（element_id / 图片主体 / 视频主体） | `object[]` |
| `file` | 文件 URL，按字段名和配置推断图片/视频/音频/文件 | `string` |

## 三、optionsJson 通用字段

`optionsJson` 可以是对象，推荐使用以下键：

```json
{
  "defaultValue": "auto",
  "uiTier": "all",
  "uiGroup": "basic",
  "uiGroupLabel": "基础参数",
  "uiOrder": 10,
  "maxLength": 1200,
  "visibleWhen": {
    "customMode": ["true"]
  }
}
```

| 键 | 说明 |
| --- | --- |
| `defaultValue` | 默认值；优先级高于前端兜底 |
| `uiTier` | `all` / `simple` / `advanced`，高级参数放 `advanced` |
| `uiGroup` / `uiGroupLabel` | 预留分组信息 |
| `uiOrder` | 预留排序信息 |
| `maxLength` | 文本最大长度 |
| `maxLengthByModel` | 按模型名设置最大长度 |
| `visibleWhen` | 依赖其他字段值才显示 |
| `minCount` / `maxCount` | 多图字段最小/最大数量 |
| `accept` | 上传 MIME 约束，如 `image/*` |
| `libraryEnabled` | 是否允许从素材库选择 |
| `libraryKind` | 素材类型：`image` / `video` / `audio` / `file` |
| `uiRole` | UI 角色，例如 `referenceMaterial` |
| `placement` | 特殊摆放位置，例如 `composer` / `prompt_left` |

## 四、常规 / 高级模式

工作台默认是常规模式，只展示：

- 主 Prompt 字段；
- 参考素材入口；
- 画面比例；
- 必填且必须由用户确认的基础参数。

非必填专业参数应配置为高级参数：

```json
{
  "uiTier": "advanced",
  "defaultValue": 0.65,
  "slider": {
    "min": 0,
    "max": 1,
    "step": 0.01
  }
}
```

用户端会把高级参数收纳进「高级配置」折叠区。Suno 等确实需要向上游传递高级模式的工具，可以配置 `customMode` / `custom_mode` 字段；前端会展示显式的「创作模式」选项，并在用户选择高级模式或展开高级配置时同步该字段为启用状态。

## 五、参考素材 / 多参考图配置

当工具需要参考图、参考素材、源图、首帧图等输入时，优先使用 `multi_image` 或单图上传字段，并标记为主输入框素材入口。

推荐配置：

```json
{
  "fieldKey": "referenceImages",
  "fieldName": "参考图",
  "fieldType": "multi_image",
  "required": false,
  "sortOrder": 20,
  "placeholder": "选择多张参考图",
  "optionsJson": "{\"uiRole\":\"referenceMaterial\",\"placement\":\"composer\",\"maxCount\":8,\"accept\":\"image/*\",\"libraryEnabled\":true,\"libraryKind\":\"image\"}"
}
```

渲染规则：

- `multi_image` 会被用户端识别为主参考素材控件；
- `fieldKey/fieldName/placeholder` 包含 `reference`、`ref`、`source`、`input`、`material`、`asset`、`参考`、`素材` 等语义时，也会进入主输入框左侧素材入口；
- 不再在参数区渲染「参考图 + 素材库」大卡片；
- 主输入框左上角显示极简上传触发器；
- 已选素材显示在 Textarea 下方的横向缩略图预览条；
- 点击触发器打开统一素材弹窗，支持「上传 / 素材」双 Tab、拖拽上传、最近上传、已生成素材、多选确认。

提交格式：

```json
{
  "prompt": "用户提示词",
  "referenceImages": [
    "https://.../image-1.png",
    "https://.../image-2.png"
  ]
}
```

单图字段示例：

```json
{
  "fieldKey": "sourceImageUrl",
  "fieldName": "源图",
  "fieldType": "image",
  "required": true,
  "sortOrder": 20,
  "optionsJson": "{\"uiRole\":\"referenceMaterial\",\"placement\":\"composer\",\"accept\":\"image/*\",\"libraryEnabled\":true,\"libraryKind\":\"image\"}"
}
```

### 可灵参考列表示例

可灵 `imageList` / `videoList` / `elementList` 请使用 `multi_image`、`multi_video`、`subject_element_list`，**不要**用 `textarea` 手填 JSON。

完整 fieldType 说明、optionsJson、提交格式与迁移清单见 **[可灵参考列表表单配置说明](./可灵参考列表表单配置说明.md)**。

## 六、比例配置

比例必须使用正式控件类型 `aspect_ratio`，不要在用户端写死比例列表。

```json
{
  "fieldKey": "aspectRatio",
  "fieldName": "画面比例",
  "fieldType": "aspect_ratio",
  "required": true,
  "sortOrder": 30,
  "optionsJson": {
    "defaultValue": "auto",
    "options": [
      { "label": "智能", "value": "auto" },
      { "label": "9:16", "value": "9:16" },
      { "label": "1:1", "value": "1:1" },
      { "label": "4:3", "value": "4:3" },
      { "label": "16:9", "value": "16:9" }
    ]
  }
}
```

规则：

- `auto` 在用户端显示为「智能」；
- 只有配置显式声明默认值时才使用指定默认值；
- 前端会同时提交 `aspectRatio` 和 `imageRatio` 兼容字段。

## 七、生成数量 / 质量

少量枚举推荐使用 `radio`，用户端会渲染为横向分段按钮。

```json
{
  "fieldKey": "count",
  "fieldName": "生成数量",
  "fieldType": "radio",
  "required": true,
  "sortOrder": 40,
  "optionsJson": {
    "defaultValue": "1",
    "options": ["1", "2", "3", "4"]
  }
}
```

```json
{
  "fieldKey": "quality",
  "fieldName": "质量",
  "fieldType": "radio",
  "required": true,
  "sortOrder": 50,
  "optionsJson": {
    "defaultValue": "low",
    "options": [
      { "label": "低", "value": "low" },
      { "label": "中", "value": "medium" },
      { "label": "高", "value": "high" },
      { "label": "自动", "value": "auto" }
    ]
  }
}
```

## 八、滑块配置

权重、强度、发散度等使用 `slider`：

```json
{
  "fieldKey": "styleWeight",
  "fieldName": "风格权重",
  "fieldType": "slider",
  "required": false,
  "sortOrder": 80,
  "optionsJson": {
    "uiTier": "advanced",
    "defaultValue": 0.65,
    "slider": {
      "min": 0,
      "max": 1,
      "step": 0.01
    }
  }
}
```

用户端视觉规范：

- 轨道高度 3px；
- 手柄直径 12px；
- 非必填滑块默认进入高级配置。

## 九、Suno / 音频高级模式示例

> **同系列多版本选型**：若多个版本共用同一 API / Handler，仅在表单里加 `model` 下拉即可（如 Suno V4.5 / V5.5），不必拆成多个工具。完整决策树、字段规范与验收清单见 [同系列模型版本表单配置规范](./同系列模型版本表单配置规范.md)。

Suno 这类上游确实需要 `customMode` 的工具，保留该字段：

```json
{
  "fieldKey": "customMode",
  "fieldName": "高级模式",
  "fieldType": "checkbox",
  "required": false,
  "sortOrder": 10,
  "optionsJson": {
    "uiTier": "advanced",
    "defaultValue": false
  }
}
```

依赖高级模式才显示的字段：

```json
{
  "fieldKey": "style",
  "fieldName": "音乐风格",
  "fieldType": "text",
  "required": false,
  "sortOrder": 20,
  "optionsJson": {
    "uiTier": "advanced",
    "visibleWhen": {
      "customMode": ["true"]
    }
  }
}
```

### 9.1 Suno 模型版本字段（表单内选型）

同一音乐工具内切换版本时，增加 `fieldKey: model` 的 `select` 字段；`value` 必须与上游 API 枚举一致，并可用 `maxLengthByModel` 按版本限制其它字段长度。管理端可使用选项预设「Suno 模型」。示例：

```json
{
  "fieldKey": "model",
  "fieldName": "Suno 模型",
  "fieldType": "select",
  "optionsJson": {
    "uiTier": "all",
    "uiGroup": "meta",
    "defaultValue": "V5_5",
    "options": [
      { "label": "V5.5（推荐）", "value": "V5_5" },
      { "label": "V5", "value": "V5" },
      { "label": "V4.5", "value": "V4_5" }
    ]
  }
}
```

### 9.2 可灵模型版本字段（按任务类型拆工具）

可灵按 API 任务类型拆 6 个工具（文生/图生/动作/多图/Omni/生图），**同一工具内**用 `model` 选 V1–V3 等版本。选项预设见 `kling_model_image2video` 等。示例：

```json
{
  "fieldKey": "model",
  "fieldName": "可灵模型版本",
  "fieldType": "select",
  "optionsJson": {
    "uiTier": "all",
    "uiGroup": "meta",
    "defaultValue": "kling-v3",
    "options": [
      { "label": "V3（推荐）", "value": "kling-v3" },
      { "label": "V2.6", "value": "kling-v2-6" }
    ]
  }
}
```

详见 [可灵模型配置.md](../可灵模型配置.md)、[同系列模型版本表单配置规范](./同系列模型版本表单配置规范.md)。

### 9.3 火山 / 豆包模型版本字段（按模态拆工具）

火山视频/图像各一张工具卡，**同一工具内**用 `model` 选 Seedance / Seedream 版本。选项预设见 `volcengine_model_video`、`volcengine_model_image`。

```json
{
  "fieldKey": "model",
  "fieldName": "Seedance 模型",
  "fieldType": "select",
  "optionsJson": {
    "uiTier": "all",
    "uiGroup": "meta",
    "defaultValue": "doubao-seedance-1-5-pro-251215",
    "options": [
      { "label": "Seedance 1.5 Pro（推荐）", "value": "doubao-seedance-1-5-pro-251215" }
    ]
  }
}
```

详见 [火山模型配置.md](../火山模型配置.md)。

## 十、配置检查清单

- 主 Prompt 字段标记 `core: true` 或作为工具的核心字段传入。
- 参考图使用 `multi_image`，并配置 `uiRole=referenceMaterial`、`placement=composer`。
- 比例使用 `aspect_ratio`，默认值优先 `auto`。
- 生成数量、质量等短枚举使用 `radio`，不要使用原生下拉。
- 专业参数配置 `uiTier=advanced`。
- 长说明不要塞进 placeholder；placeholder 保持短句，详细说明写文档或后续 tooltip 元信息。
- 字段名、字段类型、提交值必须与 Worker 执行器保持一致。

