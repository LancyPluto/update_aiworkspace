# xiaohongshu_copywriting Seed 数据说明

## 结论

- `xiaohongshu_copywriting` 不需要新建表。
- 这个工具使用项目现有的通用工具表和 Prompt 表。
- 需要做的是执行一份初始化数据脚本，把工具、字段、Prompt 注册进去。

## 涉及的现有表

- `tool_categories`
- `ai_tools`
- `tool_field_schemas`
- `tool_field_schema_items`
- `tool_prompts`
- `tool_prompt_versions`

## 初始化脚本位置

- SQL 文件：[002_seed_xiaohongshu_copywriting.sql](file:///Users/a1-6/Project/ai-tool-market/sql/002_seed_xiaohongshu_copywriting.sql)

## 这份 seed 数据做了什么

- 在 `ai_tools` 中注册工具：
  - `tool_code = xiaohongshu_copywriting`
  - `tool_name = AI 小红书文案生成器`
- 在 `tool_field_schemas` 中注册字段版本：
  - `schema_version = v1`
- 在 `tool_field_schema_items` 中注册 5 个字段：
  - `productName`
  - `targetCustomer`
  - `style`
  - `sellingPoints`
  - `extraInfo`
- 在 `tool_prompts` 中注册默认 Prompt：
  - `prompt_code = default`
- 在 `tool_prompt_versions` 中注册 Prompt 版本：
  - `version_no = v1`
- 更新 `tool_prompts.active_version_id` 指向当前生效版本

## 执行前提

- 基础建表脚本 [001_init_v1.sql](file:///Users/a1-6/Project/ai-tool-market/sql/001_init_v1.sql) 已执行
- `tool_categories` 中已存在：
  - `category_code = copywriting`

## 执行顺序

- 先执行 [001_init_v1.sql](file:///Users/a1-6/Project/ai-tool-market/sql/001_init_v1.sql)
- 再执行 [002_seed_xiaohongshu_copywriting.sql](file:///Users/a1-6/Project/ai-tool-market/sql/002_seed_xiaohongshu_copywriting.sql)

## 幂等说明

- 当前 seed SQL 使用了 `NOT EXISTS`
- 重复执行时，正常情况下不会重复插入同一工具、同一 schema、同一字段、同一 Prompt 版本

## 执行后应看到的数据

- `ai_tools` 中有一条：
  - `tool_code = xiaohongshu_copywriting`
- `tool_field_schemas` 中有一条：
  - 对应工具的 `v1` schema
- `tool_field_schema_items` 中有 5 条字段项
- `tool_prompts` 中有一条：
  - `prompt_code = default`
- `tool_prompt_versions` 中有一条：
  - `version_no = v1`
- `tool_prompts.active_version_id` 已被更新

## 为什么不需要新表

- 这个工具的输入结构可以用现有的字段 schema 体系表达
- 这个工具的 Prompt 可以用现有的 Prompt 版本体系表达
- 这个工具的结果可以用现有的 `ai_result_resources` 表存储
- 所以它是“新增一套配置数据”，不是“新增一种存储模型”

## 什么时候才考虑新表

- 工具需要专属的复杂业务实体
- 工具需要通用表无法承载的额外关系结构
- 工具需要长期记忆、版本草稿、复杂素材库等专属数据

当前小红书文案生成器不属于以上情况。
