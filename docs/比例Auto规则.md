# 比例 Auto 规则

## 目标

工作台表单里的“比例”默认值使用 `Auto`（界面显示“智能”），含义是“不要由平台强制指定画面比例，让模型或供应商服务自行决定”。它不是 `16:9` 的别名，也不应该在执行层被兜底成固定比例。

## 标准值

- 前端展示：`智能`
- 前端/后端/Worker 参数值：`auto`
- 兼容输入：`智能`、`Auto`、`AUTO`、`adaptive`、`default`、空值
- 比例字符串必须使用半角冒号：`16:9`、`9:16`
- 如果后台配置里误写成全角冒号，如 `16：9`，前端提交前必须归一化成 `16:9`

## 前端规则

- “比例”必须作为正式控件类型配置：`fieldType=aspect_ratio`。
- 用户端按后台字段 `optionsJson/options` 渲染比例选项，不在用户端写死比例列表。
- `aspect_ratio` 控件使用分段按钮，不使用下拉框。
- 默认选中 `auto`。只有工具字段配置显式声明 `defaultValue`，或能力配置声明 `defaultRatio` 时，才用配置值。
- 推荐图片比例预设顺序：
  - `auto`、`9:16`、`2:3`、`3:4`、`1:1`、`4:3`、`3:2`、`16:9`、`21:9`
- 推荐视频比例预设顺序：
  - `auto`、`16:9`、`9:16`、`1:1`
- 提交任务时同时传：
  - `aspectRatio`
  - `imageRatio`
- 当用户选择“智能”时，这两个字段值都传 `auto`。
- “生成数量”等数字范围输入使用 `fieldType=slider`，不要在用户端按字段名特殊判断。

## Worker 规则

Worker 收到 `auto` 时，应按以下优先级处理：

1. 供应商协议明确支持 `auto`：传官方 `auto`。
2. 供应商协议通过省略字段表示自动：不传 `aspect_ratio`、`ratio`、`image_size`、`size` 等强约束字段。
3. 供应商协议必须填写尺寸且不支持自动：使用该供应商自己的默认值，但必须在代码或配置里注明这是供应商限制，不要把 `auto` 当作用户选择的 `16:9`。

当前实现约定：

- OpenAI/Ofox Images：`auto` 转为 `size=auto`。
- Kling：`auto` 时不发送 `aspect_ratio`。
- Seedance：`auto` 时不发送 `ratio/aspect_ratio/size/image_size`。
- Skywork：`auto` 时不发送 `style.aspect_ratio`，也不发送 `image_size`。
- SiliconFlow：`auto` 时不发送 `image_size`。
- InfiniteTalk：`auto` 时不发送 `aspectRatio`。

## 新模型接入检查清单

- 不要把未知比例兜底成 `16:9`，除非供应商 API 强制要求且文档已注明。
- 新增 client 的 `_aspect_ratio()` 或同类函数时，必须先判断 `auto/智能/adaptive/default/空值` 并返回空或官方 `auto`。
- 如果模型配置提供比例 options，应包含 `auto`，并使用半角冒号。
- 管理后台新增/修改比例字段时，使用 `aspect_ratio` 类型；新增/修改数量字段时，优先使用 `slider` 类型并配置 `slider.min/max/step/defaultValue`。
- 如果供应商支持更多比例，只扩展预设列表和映射，不改变 `auto` 语义。
- 测试至少覆盖：
  - `aspectRatio=auto` 不发送固定比例。
  - `aspectRatio=16：9` 被归一化成 `16:9`。
  - 明确选择 `9:16` 时仍发送 `9:16`。

## 反例

以下行为不允许：

- `auto` -> `16:9`
- `智能` -> `16:9`
- 未识别比例 -> 默默固定 `16:9`
- UI 显示“智能”，但请求实际传 `16:9`
