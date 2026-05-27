-- PPT 生成工具种子数据（联调用）
INSERT INTO ai_tools (
  tool_code, tool_name, category_id, description, cover_url,
  tool_type, input_modality, output_modality, config_note,
  status, estimated_credit_cost, execution_handler, is_deleted
)
SELECT
  'banana_ppt_generator',
  'AI PPT 生成器',
  tc.id,
  '基于 banana-slides 的多步骤 PPT 生成：想法/大纲/描述/出图/导出',
  NULL,
  'IMAGE_GENERATION',
  'MULTIMODAL',
  'FILE',
  CONCAT(
    '【运营说明】支持从想法/大纲/描述创建 PPT，支持导出 PPTX。',
    CHAR(10), CHAR(10),
    '<!-- ppt-workflow:',
    '{"integrationMode":"PPT_WORKSPACE","customUiRoute":"/tools/banana_ppt_generator/workspace",',
    '"creationTypes":["idea","outline","descriptions"],',
    '"steps":[',
    '{"code":"CREATE","name":"创建项目","credits":5,"enabled":true},',
    '{"code":"OUTLINE","name":"生成大纲","credits":10,"enabled":true},',
    '{"code":"DESCRIPTIONS","name":"生成描述","credits":20,"enabled":true},',
    '{"code":"IMAGES","name":"生成图片","credits":50,"enabled":true},',
    '{"code":"EXPORT_PPTX","name":"导出图片幻灯片","credits":5,"enabled":true},',
    '{"code":"EXPORT_EDITABLE_PPTX","name":"导出可编辑PPTX","credits":15,"enabled":true},',
    '{"code":"EXPORT_PDF","name":"导出PDF","credits":5,"enabled":true}',
    '],',
    '"features":{"renovation":false,"singlePageRegenerate":true,"refineOutline":true,"refineDescriptions":true,"exportEditablePptx":true}}',
    ' -->'
  ),
  'ONLINE',
  90,
  'TEXT_GENERATION',
  0
FROM tool_categories tc
WHERE tc.category_code = 'copywriting'
LIMIT 1
ON DUPLICATE KEY UPDATE
  tool_name = VALUES(tool_name),
  status = 'ONLINE',
  config_note = VALUES(config_note),
  estimated_credit_cost = VALUES(estimated_credit_cost),
  updated_at = CURRENT_TIMESTAMP;
