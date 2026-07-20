SET NAMES utf8mb4;

-- AI 漫剧项目 v2 只更新当前草稿。已发布版本和运行中任务继续使用各自锁定的快照，
-- 管理员通过工具上线动作完成校验与发布，本迁移不自动开启执行。
SET @comic_text_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-2.0-flash' AND enabled = 1
  ORDER BY id DESC LIMIT 1
);
SET @comic_image_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-image-2.1-flash' AND enabled = 1
  ORDER BY id DESC LIMIT 1
);
SET @comic_video_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'agnes-video-v2.0' AND enabled = 1
  ORDER BY id DESC LIMIT 1
);
SET @comic_tts_model_id = (
  SELECT id FROM agent_model_configs
  WHERE config_code = 'moma_cosyvoice' AND enabled = 1
  ORDER BY id DESC LIMIT 1
);

UPDATE tool_workflows workflow
JOIN ai_tools tool ON tool.id = workflow.tool_id
SET workflow.nodes_json = JSON_ARRAY(
      JSON_OBJECT(
        'id', 'start',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 40, 'y', 300),
        'data', JSON_OBJECT(
          'title', '开始项目',
          'detail', '创建可长期保存并多次生成的 AI 漫剧项目',
          'kind', 'start',
          'nodeDefType', 'start',
          'iconName', 'play',
          'color', '#10b981',
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'context', 'type', 'json', 'label', '会话上下文')),
          'parameters', JSON_OBJECT('templateVersion', 'comic-project-v2', 'handlerKey', 'comic.project')
        )
      ),
      JSON_OBJECT(
        'id', 'project-input',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 340, 'y', 300),
        'data', JSON_OBJECT(
          'title', '创建或导入剧本',
          'detail', '支持 AI 创作、直接粘贴、TXT、Markdown 和 DOCX 导入',
          'kind', 'input',
          'nodeDefType', 'field_input',
          'iconName', 'file-input',
          'color', '#475569',
          'inputSlots', JSON_ARRAY(JSON_OBJECT('name', 'context', 'type', 'json', 'label', '会话上下文')),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'params', 'type', 'json', 'label', '用户填写参数')),
          'parameters', JSON_OBJECT()
        )
      ),
      JSON_OBJECT(
        'id', 'script-normalize',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 680, 'y', 300),
        'data', JSON_OBJECT(
          'title', '整理完整剧本',
          'detail', '保留导入原文，将人物、场景、对白和段落整理成统一剧本版本',
          'kind', 'model',
          'nodeDefType', 'script_planner',
          'iconName', 'file-text',
          'color', '#3b82f6',
          'inputSlots', JSON_ARRAY(JSON_OBJECT('name', 'form', 'type', 'json', 'label', '项目与剧本输入')),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'script', 'type', 'json', 'label', '结构化剧本')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_text_model_id,
            'handlerKey', 'comic.script',
            'role', 'comic_script',
            'progressStep', '整理完整剧本'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'storyboard',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 1020, 'y', 300),
        'data', JSON_OBJECT(
          'title', 'AI 分镜拆解',
          'detail', '生成镜号、时码、景别、机位、运镜、情绪、画面、台词和声音字段',
          'kind', 'model',
          'nodeDefType', 'storyboard_generator',
          'iconName', 'file-text',
          'color', '#6366f1',
          'inputSlots', JSON_ARRAY(JSON_OBJECT('name', 'script', 'type', 'json', 'label', '结构化剧本')),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '可编辑分镜表')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_text_model_id,
            'handlerKey', 'comic.storyboard',
            'role', 'comic_storyboard',
            'progressStep', '拆分结构化分镜'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'confirm-storyboard',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 1360, 'y', 300),
        'data', JSON_OBJECT(
          'title', '锁定分镜',
          'detail', '用户完成增删、重排和编辑后锁定当前分镜版本',
          'kind', 'confirm',
          'nodeDefType', 'user_confirm',
          'iconName', 'circle-dot',
          'color', '#14b8a6',
          'inputSlots', JSON_ARRAY(JSON_OBJECT('name', 'artifact', 'type', 'any', 'label', '待确认分镜')),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'confirmed', 'type', 'json', 'label', '确认结果')),
          'parameters', JSON_OBJECT('sourceNodeId', 'storyboard', 'stageKey', 'STORYBOARD_LOCK')
        )
      ),
      JSON_OBJECT(
        'id', 'character-assets',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 1700, 'y', 80),
        'data', JSON_OBJECT(
          'title', '角色三视图',
          'detail', '生成并版本化角色正面、侧面、背面参考图',
          'kind', 'model',
          'nodeDefType', 'character_design',
          'iconName', 'image',
          'color', '#d946ef',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'approval', 'type', 'json', 'label', '分镜锁定结果')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'characters', 'type', 'json', 'label', '角色参考资产')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_image_model_id,
            'handlerKey', 'comic.character_reference',
            'progressStep', '生成角色三视图'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'scene-assets',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 1700, 'y', 300),
        'data', JSON_OBJECT(
          'title', '场景锚点图',
          'detail', '生成并版本化主要场景的空间、光线和风格参考图',
          'kind', 'model',
          'nodeDefType', 'scene_design',
          'iconName', 'image',
          'color', '#ec4899',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'approval', 'type', 'json', 'label', '分镜锁定结果')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'scenes', 'type', 'json', 'label', '场景参考资产')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_image_model_id,
            'handlerKey', 'comic.scene_reference',
            'progressStep', '生成场景锚点图'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'shot-audio',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 1700, 'y', 520),
        'data', JSON_OBJECT(
          'title', '台词与旁白',
          'detail', '按角色和台词行生成独立配音，保留字幕时间信息',
          'kind', 'model',
          'nodeDefType', 'tts_model',
          'iconName', 'mic',
          'color', '#8b5cf6',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'approval', 'type', 'json', 'label', '分镜锁定结果')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'audio', 'type', 'json', 'label', '配音与字幕素材')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_tts_model_id,
            'handlerKey', 'comic.shot_tts',
            'progressStep', '生成台词与旁白'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'confirm-assets',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 2040, 'y', 300),
        'data', JSON_OBJECT(
          'title', '确认参考素材与费用',
          'detail', '选定角色和场景版本，展示本批镜头预计费用后继续',
          'kind', 'confirm',
          'nodeDefType', 'user_confirm',
          'iconName', 'circle-dot',
          'color', '#14b8a6',
          'inputSlots', JSON_ARRAY(JSON_OBJECT('name', 'artifact', 'type', 'any', 'label', '待确认素材')),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'confirmed', 'type', 'json', 'label', '确认结果')),
          'parameters', JSON_OBJECT('sourceNodeId', 'scene-assets', 'stageKey', 'ASSET_AND_COST_APPROVAL')
        )
      ),
      JSON_OBJECT(
        'id', 'shot-batch',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 2380, 'y', 300),
        'data', JSON_OBJECT(
          'title', '镜头批次计划',
          'detail', '将已锁定分镜展开为独立任务并按并发上限执行',
          'kind', 'loop',
          'nodeDefType', 'scene_loop',
          'iconName', 'repeat',
          'color', '#eab308',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'assets', 'type', 'json', 'label', '素材与费用确认'),
            JSON_OBJECT('name', 'form', 'type', 'json', 'label', '项目参数')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'batch', 'type', 'json', 'label', '镜头任务批次')),
          'parameters', JSON_OBJECT(
            'handlerKey', 'comic.shot_batch',
            'durationField', 'episodeDuration',
            'secondsPerScene', 5,
            'maxScenes', 18,
            'imageConcurrency', 4,
            'videoConcurrency', 2
          )
        )
      ),
      JSON_OBJECT(
        'id', 'shot-keyframes',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 2720, 'y', 180),
        'data', JSON_OBJECT(
          'title', '并行生成关键帧',
          'detail', '每个镜头独立生成并携带已选角色和场景参考图',
          'kind', 'model',
          'nodeDefType', 'keyframe_generator',
          'iconName', 'image',
          'color', '#f472b6',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'batch', 'type', 'json', 'label', '镜头任务批次'),
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'characters', 'type', 'json', 'label', '角色参考资产'),
            JSON_OBJECT('name', 'scenes', 'type', 'json', 'label', '场景参考资产')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'keyframes', 'type', 'json', 'label', '镜头关键帧版本')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_image_model_id,
            'handlerKey', 'comic.shot_keyframe',
            'progressStep', '并行生成镜头关键帧'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'shot-videos',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 3060, 'y', 180),
        'data', JSON_OBJECT(
          'title', '批量生成镜头视频',
          'detail', '按镜头独立调用视频模型，失败只重试对应镜头',
          'kind', 'model',
          'nodeDefType', 'image_to_video',
          'iconName', 'video',
          'color', '#f97316',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'keyframes', 'type', 'json', 'label', '镜头关键帧版本'),
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜'),
            JSON_OBJECT('name', 'characters', 'type', 'json', 'label', '角色参考资产'),
            JSON_OBJECT('name', 'scenes', 'type', 'json', 'label', '场景参考资产')
          ),
          'outputSlots', JSON_ARRAY(JSON_OBJECT('name', 'clips', 'type', 'json', 'label', '镜头视频版本')),
          'parameters', JSON_OBJECT(
            'modelConfigId', @comic_video_model_id,
            'handlerKey', 'comic.shot_video',
            'progressStep', '批量生成镜头视频'
          )
        )
      ),
      JSON_OBJECT(
        'id', 'compose',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 3400, 'y', 300),
        'data', JSON_OBJECT(
          'title', '组装成片',
          'detail', '拼接选定镜头，混合配音、旁白、BGM、音效并生成字幕',
          'kind', 'tool',
          'nodeDefType', 'subtitle',
          'iconName', 'wrench',
          'color', '#f97316',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'clips', 'type', 'json', 'label', '已选镜头视频'),
            JSON_OBJECT('name', 'audio', 'type', 'json', 'label', '配音与声音素材'),
            JSON_OBJECT('name', 'storyboard', 'type', 'json', 'label', '已锁定分镜')
          ),
          'outputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'finalVideo', 'type', 'video', 'label', '最终成片'),
            JSON_OBJECT('name', 'subtitle', 'type', 'file', 'label', 'SRT 字幕')
          ),
          'parameters', JSON_OBJECT('handlerKey', 'comic.compose', 'progressStep', '组装音视频与字幕')
        )
      ),
      JSON_OBJECT(
        'id', 'output',
        'type', 'workflowNode',
        'position', JSON_OBJECT('x', 3740, 'y', 300),
        'data', JSON_OBJECT(
          'title', '交付成片',
          'detail', '输出可播放 MP4、SRT 字幕和项目素材版本',
          'kind', 'output',
          'nodeDefType', 'video_output',
          'iconName', 'file-output',
          'color', '#ef4444',
          'inputSlots', JSON_ARRAY(
            JSON_OBJECT('name', 'finalVideo', 'type', 'video', 'label', '最终成片'),
            JSON_OBJECT('name', 'subtitle', 'type', 'file', 'label', 'SRT 字幕')
          ),
          'parameters', JSON_OBJECT('displayMode', 'video', 'templateVersion', 'comic-project-v2')
        )
      )
    ),
    workflow.edges_json = JSON_ARRAY(
      JSON_OBJECT('id', 'e-start-input', 'source', 'start', 'target', 'project-input', 'sourceHandle', 'out-context', 'targetHandle', 'in-context', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-input-script', 'source', 'project-input', 'target', 'script-normalize', 'sourceHandle', 'out-params', 'targetHandle', 'in-form', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-script-storyboard', 'source', 'script-normalize', 'target', 'storyboard', 'sourceHandle', 'out-script', 'targetHandle', 'in-script', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-confirm', 'source', 'storyboard', 'target', 'confirm-storyboard', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-artifact', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-characters', 'source', 'storyboard', 'target', 'character-assets', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-confirm-characters', 'source', 'confirm-storyboard', 'target', 'character-assets', 'sourceHandle', 'out-confirmed', 'targetHandle', 'in-approval', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-scenes', 'source', 'storyboard', 'target', 'scene-assets', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-confirm-scenes', 'source', 'confirm-storyboard', 'target', 'scene-assets', 'sourceHandle', 'out-confirmed', 'targetHandle', 'in-approval', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-audio', 'source', 'storyboard', 'target', 'shot-audio', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-confirm-audio', 'source', 'confirm-storyboard', 'target', 'shot-audio', 'sourceHandle', 'out-confirmed', 'targetHandle', 'in-approval', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-characters-assets', 'source', 'character-assets', 'target', 'confirm-assets', 'sourceHandle', 'out-characters', 'targetHandle', 'in-artifact', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-scenes-assets', 'source', 'scene-assets', 'target', 'confirm-assets', 'sourceHandle', 'out-scenes', 'targetHandle', 'in-artifact', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-batch', 'source', 'storyboard', 'target', 'shot-batch', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-assets-batch', 'source', 'confirm-assets', 'target', 'shot-batch', 'sourceHandle', 'out-confirmed', 'targetHandle', 'in-assets', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-input-batch', 'source', 'project-input', 'target', 'shot-batch', 'sourceHandle', 'out-params', 'targetHandle', 'in-form', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-batch-keyframe', 'source', 'shot-batch', 'target', 'shot-keyframes', 'sourceHandle', 'out-batch', 'targetHandle', 'in-batch', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-keyframe', 'source', 'storyboard', 'target', 'shot-keyframes', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-characters-keyframe', 'source', 'character-assets', 'target', 'shot-keyframes', 'sourceHandle', 'out-characters', 'targetHandle', 'in-characters', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-scenes-keyframe', 'source', 'scene-assets', 'target', 'shot-keyframes', 'sourceHandle', 'out-scenes', 'targetHandle', 'in-scenes', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-keyframe-video', 'source', 'shot-keyframes', 'target', 'shot-videos', 'sourceHandle', 'out-keyframes', 'targetHandle', 'in-keyframes', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-video', 'source', 'storyboard', 'target', 'shot-videos', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-characters-video', 'source', 'character-assets', 'target', 'shot-videos', 'sourceHandle', 'out-characters', 'targetHandle', 'in-characters', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-scenes-video', 'source', 'scene-assets', 'target', 'shot-videos', 'sourceHandle', 'out-scenes', 'targetHandle', 'in-scenes', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-video-compose', 'source', 'shot-videos', 'target', 'compose', 'sourceHandle', 'out-clips', 'targetHandle', 'in-clips', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-audio-compose', 'source', 'shot-audio', 'target', 'compose', 'sourceHandle', 'out-audio', 'targetHandle', 'in-audio', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-storyboard-compose', 'source', 'storyboard', 'target', 'compose', 'sourceHandle', 'out-storyboard', 'targetHandle', 'in-storyboard', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-compose-output', 'source', 'compose', 'target', 'output', 'sourceHandle', 'out-finalVideo', 'targetHandle', 'in-finalVideo', 'type', 'smoothstep'),
      JSON_OBJECT('id', 'e-subtitle-output', 'source', 'compose', 'target', 'output', 'sourceHandle', 'out-subtitle', 'targetHandle', 'in-subtitle', 'type', 'smoothstep')
    ),
    workflow.groups_json = NULL,
    workflow.config_json = JSON_OBJECT(
      'workflowType', 'AI_COMIC_DRAMA_V2',
      'templateVersion', 'comic-project-v2',
      'projectMode', TRUE,
      'singleEpisodeProduction', TRUE,
      'episodeDurationRangeSeconds', JSON_ARRAY(30, 90),
      'shotCountRange', JSON_ARRAY(6, 18),
      'imageConcurrency', 4,
      'videoConcurrency', 2,
      'billingMode', 'SHOT_ITEMIZED',
      'requiredModelConfigCodes', JSON_ARRAY(
        'agnes-2.0-flash',
        'agnes-image-2.1-flash',
        'agnes-video-v2.0',
        'moma_cosyvoice'
      )
    ),
    workflow.draft_revision = workflow.draft_revision + 1,
    workflow.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'ai_comic_drama_agent';

-- 补齐 /agents 与 /agent 共用的输入 Schema，不强制同时填写主题和导入文件；
-- ComicProjectApplicationService 根据 sourceMode 做互斥校验。
INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT schema_row.id, seed.field_key, seed.field_name, seed.field_type, seed.placeholder,
       seed.options_json, seed.validation_json, seed.required, seed.execution_required,
       seed.user_required, seed.default_value, seed.agent_fill_strategy, 'LOW', seed.sort_order, 'ACTIVE'
FROM tool_field_schemas schema_row
JOIN ai_tools tool ON tool.id = schema_row.tool_id
JOIN (
  SELECT 'sourceMode' field_key, '剧本来源' field_name, 'select' field_type,
         '选择 AI 创作或导入完整剧本' placeholder,
         JSON_ARRAY(
           JSON_OBJECT('label', 'AI 创作', 'value', 'AI_CREATE'),
           JSON_OBJECT('label', '粘贴或导入剧本', 'value', 'IMPORT')
         ) options_json,
         NULL validation_json, 1 required, 1 execution_required, 0 user_required,
         'AI_CREATE' default_value, 'default' agent_fill_strategy, 1 sort_order
  UNION ALL
  SELECT 'scriptText', '完整剧本（可选）', 'textarea',
         '可直接粘贴完整剧本；选择 AI 创作时可以留空', NULL,
         JSON_OBJECT('maxLength', 200000), 0, 0, 0, NULL, 'infer_from_user', 3
  UNION ALL
  SELECT 'scriptFile', '剧本文件（可选）', 'file',
         '上传 TXT、Markdown 或 DOCX 文件', NULL,
         JSON_OBJECT('extensions', JSON_ARRAY('txt', 'md', 'markdown', 'docx')), 0, 0, 0, NULL, 'ask_user', 4
  UNION ALL
  SELECT 'episodeDuration', '本集目标时长', 'select', '选择 30–90 秒',
         JSON_ARRAY(
           JSON_OBJECT('label', '30 秒', 'value', '30'),
           JSON_OBJECT('label', '60 秒', 'value', '60'),
           JSON_OBJECT('label', '90 秒', 'value', '90')
         ),
         JSON_OBJECT('minimum', 30, 'maximum', 90), 0, 0, 0, '60', 'default', 7
) seed
WHERE tool.tool_code = 'ai_comic_drama_agent'
  AND schema_row.status IN ('ACTIVE', 'PUBLISHED')
ON DUPLICATE KEY UPDATE
  field_name = VALUES(field_name),
  field_type = VALUES(field_type),
  placeholder = VALUES(placeholder),
  options_json = VALUES(options_json),
  validation_json = VALUES(validation_json),
  required = VALUES(required),
  execution_required = VALUES(execution_required),
  user_required = VALUES(user_required),
  default_value = VALUES(default_value),
  agent_fill_strategy = VALUES(agent_fill_strategy),
  sort_order = VALUES(sort_order),
  status = 'ACTIVE';

UPDATE tool_field_schema_items item
JOIN tool_field_schemas schema_row ON schema_row.id = item.schema_id
JOIN ai_tools tool ON tool.id = schema_row.tool_id
SET item.required = 0,
    item.execution_required = 0,
    item.user_required = 0,
    item.agent_fill_strategy = 'infer_from_user',
    item.sort_order = 2,
    item.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'ai_comic_drama_agent'
  AND item.field_key = 'storyTheme';

UPDATE tool_field_schema_items item
JOIN tool_field_schemas schema_row ON schema_row.id = item.schema_id
JOIN ai_tools tool ON tool.id = schema_row.tool_id
SET item.sort_order = CASE item.field_key
      WHEN 'genre' THEN 5
      WHEN 'plotOutline' THEN 6
      WHEN 'visualStyle' THEN 8
      WHEN 'aspectRatio' THEN 9
      WHEN 'resolution' THEN 10
      WHEN 'referenceMaterial' THEN 11
      ELSE item.sort_order
    END,
    item.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'ai_comic_drama_agent'
  AND item.field_key IN ('genre', 'plotOutline', 'visualStyle', 'aspectRatio', 'resolution', 'referenceMaterial');

UPDATE tool_field_schema_items item
JOIN tool_field_schemas schema_row ON schema_row.id = item.schema_id
JOIN ai_tools tool ON tool.id = schema_row.tool_id
SET item.status = 'INACTIVE',
    item.updated_at = CURRENT_TIMESTAMP
WHERE tool.tool_code = 'ai_comic_drama_agent'
  AND item.field_key = 'episodeLength';
