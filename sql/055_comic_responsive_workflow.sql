SET NAMES utf8mb4;

-- 漫剧工作流升级：增加用户确认、补充输入、条件判断节点（响应式编排画布）

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET
  w.nodes_json = JSON_ARRAY(
    JSON_OBJECT('id', 'start', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 40, 'y', 220),
      'data', JSON_OBJECT('title', 'Start', 'nodeDefType', 'start', 'kind', 'start', 'color', '#10b981')),
    JSON_OBJECT('id', 'field-input', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 300, 'y', 220),
      'data', JSON_OBJECT('title', '初始表单', 'nodeDefType', 'field_input', 'kind', 'input', 'color', '#64748b')),
    JSON_OBJECT('id', 'script-planner', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 560, 'y', 220),
      'data', JSON_OBJECT('title', '剧本与分镜', 'nodeDefType', 'llm_text', 'kind', 'model', 'color', '#3b82f6',
        'parameters', JSON_OBJECT('modelConfigId', (SELECT id FROM agent_model_configs WHERE enabled = 1 ORDER BY is_default DESC, id ASC LIMIT 1), 'role', 'comic_script_planner'))),
    JSON_OBJECT('id', 'confirm-script', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 820, 'y', 120),
      'data', JSON_OBJECT('title', '确认剧本', 'nodeDefType', 'user_confirm', 'kind', 'confirm', 'color', '#14b8a6',
        'parameters', JSON_OBJECT('sourceNodeId', 'script-planner'))),
    JSON_OBJECT('id', 'user-input-script', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 820, 'y', 340),
      'data', JSON_OBJECT('title', '剧本修订输入', 'nodeDefType', 'user_input', 'kind', 'input', 'color', '#0ea5e9',
        'parameters', JSON_OBJECT('fieldKey', 'scriptRevision'))),
    JSON_OBJECT('id', 'condition-script', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1080, 'y', 220),
      'data', JSON_OBJECT('title', '是否改剧本', 'nodeDefType', 'condition', 'kind', 'condition', 'color', '#a855f7',
        'parameters', JSON_OBJECT('revisionField', 'scriptRevision'))),
    JSON_OBJECT('id', 'keyframe', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1340, 'y', 220),
      'data', JSON_OBJECT('title', '电影感关键帧', 'nodeDefType', 'image_model', 'kind', 'model', 'color', '#ec4899',
        'parameters', JSON_OBJECT('modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_image_turbo' LIMIT 1)))),
    JSON_OBJECT('id', 'confirm-keyframe', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1600, 'y', 120),
      'data', JSON_OBJECT('title', '确认关键帧', 'nodeDefType', 'user_confirm', 'kind', 'confirm', 'color', '#14b8a6',
        'parameters', JSON_OBJECT('sourceNodeId', 'keyframe'))),
    JSON_OBJECT('id', 'user-input-visual', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1600, 'y', 360),
      'data', JSON_OBJECT('title', '画面修订输入', 'nodeDefType', 'user_input', 'kind', 'input', 'color', '#0ea5e9',
        'parameters', JSON_OBJECT('fieldKey', 'visualRevision'))),
    JSON_OBJECT('id', 'tts', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1860, 'y', 80),
      'data', JSON_OBJECT('title', '角色配音', 'nodeDefType', 'tts_model', 'kind', 'model', 'color', '#8b5cf6',
        'parameters', JSON_OBJECT('modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'siliconflow_voice_tts' LIMIT 1)))),
    JSON_OBJECT('id', 'clip-video', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 1860, 'y', 360),
      'data', JSON_OBJECT('title', '图生视频', 'nodeDefType', 'video_model', 'kind', 'model', 'color', '#f97316',
        'parameters', JSON_OBJECT('modelConfigId', (SELECT id FROM agent_model_configs WHERE config_code = 'volcengine-gateway-video' LIMIT 1), 'resolution', '480p', 'durationSeconds', 5))),
    JSON_OBJECT('id', 'compose', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 2120, 'y', 220),
      'data', JSON_OBJECT('title', '字幕合成', 'nodeDefType', 'subtitle', 'kind', 'tool', 'color', '#f97316')),
    JSON_OBJECT('id', 'output', 'type', 'workflowNode', 'position', JSON_OBJECT('x', 2380, 'y', 220),
      'data', JSON_OBJECT('title', '成片输出', 'nodeDefType', 'video_output', 'kind', 'output', 'color', '#ef4444'))
  ),
  w.edges_json = JSON_ARRAY(
    JSON_OBJECT('id', 'e1', 'source', 'start', 'target', 'field-input', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e2', 'source', 'field-input', 'target', 'script-planner', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e3', 'source', 'script-planner', 'target', 'confirm-script', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e4', 'source', 'script-planner', 'target', 'user-input-script', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e5', 'source', 'confirm-script', 'target', 'condition-script', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e6', 'source', 'user-input-script', 'target', 'condition-script', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e7', 'source', 'condition-script', 'target', 'keyframe', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e8', 'source', 'script-planner', 'target', 'keyframe', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e9', 'source', 'keyframe', 'target', 'confirm-keyframe', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e10', 'source', 'keyframe', 'target', 'user-input-visual', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e11', 'source', 'script-planner', 'target', 'tts', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e12', 'source', 'keyframe', 'target', 'clip-video', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e13', 'source', 'tts', 'target', 'compose', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e14', 'source', 'clip-video', 'target', 'compose', 'type', 'smoothstep'),
    JSON_OBJECT('id', 'e15', 'source', 'compose', 'target', 'output', 'type', 'smoothstep')
  ),
  w.status = 'PUBLISHED',
  w.version = w.version + 1,
  w.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'ai_comic_drama_agent';
