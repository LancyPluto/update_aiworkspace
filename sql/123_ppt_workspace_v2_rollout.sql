-- PPT workspace V2: legacy binding backfill and user-facing route rollout.

INSERT INTO ppt_projects (
  user_id,
  tool_id,
  title,
  creation_type,
  status,
  engine_strategy,
  legacy_binding_id,
  created_at,
  updated_at
)
SELECT
  legacy.user_id,
  legacy.tool_id,
  COALESCE(NULLIF(legacy.title, ''), '未命名 PPT'),
  legacy.creation_type,
  legacy.status,
  'VISUAL',
  legacy.id,
  legacy.created_at,
  legacy.updated_at
FROM ppt_project_bindings legacy
LEFT JOIN ppt_projects project
  ON project.legacy_binding_id = legacy.id
WHERE project.id IS NULL;

INSERT INTO ppt_engine_bindings (
  project_id,
  engine_code,
  external_project_id,
  created_at,
  updated_at
)
SELECT
  project.id,
  'BANANA_VISUAL',
  legacy.banana_project_id,
  legacy.created_at,
  legacy.updated_at
FROM ppt_project_bindings legacy
JOIN ppt_projects project
  ON project.legacy_binding_id = legacy.id
LEFT JOIN ppt_engine_bindings binding
  ON binding.project_id = project.id
 AND binding.engine_code = 'BANANA_VISUAL'
WHERE binding.id IS NULL;

UPDATE ai_tools
SET config_note = REPLACE(
      config_note,
      '/tools/banana_ppt_generator/workspace',
      '/ppt'
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'banana_ppt_generator'
  AND config_note LIKE '%/tools/banana_ppt_generator/workspace%';

UPDATE ai_tools
SET config_note = REPLACE(
      config_note,
      '"customUiRoute":"/ppt",',
      '"customUiRoute":"/ppt","apiPrefix":"/api/v2/ppt",'
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE tool_code = 'banana_ppt_generator'
  AND config_note LIKE '%"customUiRoute":"/ppt",%'
  AND config_note NOT LIKE '%"apiPrefix":"/api/v2/ppt"%';
