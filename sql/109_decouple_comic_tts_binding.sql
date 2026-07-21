SET NAMES utf8mb4;

-- Model bindings belong to the editable workflow draft. Historical migrations remain immutable;
-- this forward migration only removes provider-specific defaults from the current v2 draft.
UPDATE tool_workflows workflow
JOIN ai_tools tool ON tool.id = workflow.tool_id
SET workflow.nodes_json = JSON_SET(
      CAST(workflow.nodes_json AS JSON),
      REPLACE(
        JSON_UNQUOTE(JSON_SEARCH(
          CAST(workflow.nodes_json AS JSON),
          'one',
          'shot-audio',
          NULL,
          '$[*].id'
        )),
        '.id',
        '.data.parameters.modelConfigId'
      ),
      NULL
    ),
    workflow.config_json = JSON_REMOVE(
      CAST(workflow.config_json AS JSON),
      '$.requiredModelConfigCodes'
    ),
    workflow.draft_revision = COALESCE(workflow.draft_revision, 0) + 1,
    workflow.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'ai_comic_drama_agent'
  AND workflow.updated_by IS NULL
  AND COALESCE(workflow.draft_revision, 0) = 1
  AND JSON_VALID(workflow.nodes_json) = 1
  AND JSON_VALID(workflow.config_json) = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(
        CAST(workflow.config_json AS JSON),
        '$.workflowType'
      )) = 'AI_COMIC_DRAMA_V2'
  AND JSON_UNQUOTE(JSON_EXTRACT(
        CAST(workflow.config_json AS JSON),
        '$.templateVersion'
      )) = 'comic-project-v2'
  AND JSON_SEARCH(
    CAST(workflow.nodes_json AS JSON),
    'one',
    'shot-audio',
    NULL,
    '$[*].id'
  ) IS NOT NULL
  AND JSON_CONTAINS_PATH(
    CAST(workflow.nodes_json AS JSON),
    'one',
    REPLACE(
      JSON_UNQUOTE(JSON_SEARCH(
        CAST(workflow.nodes_json AS JSON),
        'one',
        'shot-audio',
        NULL,
        '$[*].id'
      )),
      '.id',
      '.data.parameters.modelConfigId'
    )
  ) = 1;
