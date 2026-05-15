-- 删除通过错误客户端编码导入的「工具目录」数据（tool_name 等变成问号），保留 id=1 的占位工具。
-- 执行后请在 Linux/容器内用 utf8mb4 会话重新跑各 *_seed_*.sql（勿用 Windows PowerShell 管道喂 SQL）。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

DELETE ti
FROM tool_field_schema_items ti
JOIN tool_field_schemas s ON ti.schema_id = s.id
JOIN ai_tools t ON s.tool_id = t.id
WHERE t.id > 1;

DELETE s
FROM tool_field_schemas s
JOIN ai_tools t ON s.tool_id = t.id
WHERE t.id > 1;

DELETE pv
FROM tool_prompt_versions pv
JOIN tool_prompts p ON pv.prompt_id = p.id
JOIN ai_tools t ON p.tool_id = t.id
WHERE t.id > 1;

DELETE p
FROM tool_prompts p
JOIN ai_tools t ON p.tool_id = t.id
WHERE t.id > 1;

DELETE FROM ai_tools WHERE id > 1;
