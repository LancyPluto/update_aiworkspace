SET NAMES utf8mb4;

-- 简化 AI 漫剧表单：去掉集数规划等多余字段，剧本/分镜由工作流大模型节点生成。

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET i.status = 'INACTIVE'
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND s.schema_version = 'v1'
  AND i.field_key IN (
    'episodeCount',
    'targetAudience',
    'episodeDuration',
    'mainCharacters',
    'outputDetail',
    'negativePrompt'
  );

UPDATE ai_tools
SET estimated_credit_cost = 0
WHERE tool_code = 'ai_comic_drama_agent';

UPDATE tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
SET
  i.field_name = '剧情梗概（可选）',
  i.placeholder = '留空则由大模型自动生成剧本与分镜',
  i.required = 0
WHERE t.tool_code = 'ai_comic_drama_agent'
  AND s.schema_version = 'v1'
  AND i.field_key = 'plotOutline'
  AND i.status = 'ACTIVE';
