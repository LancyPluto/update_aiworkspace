SET NAMES utf8mb4;

-- 漫剧工作流 v3：精简线性流程，严格顺序执行
-- 流程：表单 → 剧本生成 → 用户确认脚本/分镜 → 关键帧生成 → 用户确认场景图 → 配音+视频(并行) → 合成 → 成片输出
-- 共 10 个节点（start/output + 2 个 user_input + 2 个 model 主节点 + tts + video + compose + field_input）
-- 修复：
--   1. TTS 不再与脚本并行（旧版 e-script-tts 直连），需等关键帧确认后才执行
--   2. 视频生成同样在关键帧确认后执行
--   3. 只保留 2 个用户确认点：脚本确认 + 场景图确认

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET
  w.nodes_json = '[
    {"id":"start","type":"workflowNode","position":{"x":40,"y":240},"data":{"title":"Start","nodeDefType":"start","kind":"start","color":"#10b981","outputSlots":[{"name":"context","type":"json","label":"会话上下文"}]}},
    {"id":"field-input","type":"workflowNode","position":{"x":260,"y":240},"data":{"title":"初始表单","nodeDefType":"field_input","kind":"input","color":"#64748b","inputSlots":[{"name":"context","type":"json","label":"会话上下文"}],"outputSlots":[{"name":"params","type":"json","label":"用户填写参数"}]}},
    {"id":"script-planner","type":"workflowNode","position":{"x":500,"y":240},"data":{"title":"剧本与分镜","nodeDefType":"llm_text","kind":"model","color":"#3b82f6","inputSlots":[{"name":"form","type":"json","label":"初始表单"}],"outputSlots":[{"name":"script","type":"json","label":"剧本分镜"}],"parameters":{"role":"comic_script_planner","progressStep":"生成剧本与分镜"}}},
    {"id":"user-input-script","type":"workflowNode","position":{"x":760,"y":240},"data":{"title":"剧本/分镜确认","nodeDefType":"user_input","kind":"input","color":"#0ea5e9","inputSlots":[{"name":"upstream","type":"any","label":"上一步结果"}],"outputSlots":[{"name":"scriptFeedback","type":"text","label":"脚本意见"},{"name":"storyboardFeedback","type":"text","label":"分镜意见"}],"parameters":{"fieldKey":"scriptFeedback","stageLabel":"剧本/分镜确认"}}},
    {"id":"keyframe","type":"workflowNode","position":{"x":1020,"y":240},"data":{"title":"关键帧生成","nodeDefType":"image_model","kind":"model","color":"#ec4899","inputSlots":[{"name":"script","type":"json","label":"剧本分镜"},{"name":"form","type":"json","label":"表单参数"}],"outputSlots":[{"name":"keyframe","type":"image","label":"关键帧"}],"parameters":{"progressStep":"生成电影感关键帧","modelConfigId":85}}},
    {"id":"user-input-scene","type":"workflowNode","position":{"x":1280,"y":240},"data":{"title":"场景图确认","nodeDefType":"user_input","kind":"input","color":"#0ea5e9","inputSlots":[{"name":"upstream","type":"any","label":"上一步结果"}],"outputSlots":[{"name":"sceneFeedback","type":"text","label":"场景图意见"}],"parameters":{"fieldKey":"sceneFeedback","stageLabel":"场景图确认"}}},
    {"id":"tts","type":"workflowNode","position":{"x":1540,"y":160},"data":{"title":"角色配音","nodeDefType":"tts_model","kind":"model","color":"#8b5cf6","inputSlots":[{"name":"script","type":"json","label":"剧本分镜"}],"outputSlots":[{"name":"audio","type":"audio","label":"配音音频"}],"parameters":{"progressStep":"生成角色配音"}}},
    {"id":"clip-video","type":"workflowNode","position":{"x":1540,"y":320},"data":{"title":"图生视频","nodeDefType":"video_model","kind":"model","color":"#f97316","inputSlots":[{"name":"keyframe","type":"image","label":"关键帧"},{"name":"script","type":"json","label":"剧本分镜"}],"outputSlots":[{"name":"clip","type":"video","label":"视频片段"}],"parameters":{"progressStep":"图生视频","modelConfigId":86}}},
    {"id":"compose","type":"workflowNode","position":{"x":1800,"y":240},"data":{"title":"字幕合成","nodeDefType":"subtitle","kind":"tool","color":"#f97316","inputSlots":[{"name":"clip","type":"video","label":"视频片段"},{"name":"audio","type":"audio","label":"配音音频"},{"name":"script","type":"json","label":"剧本分镜"}],"outputSlots":[{"name":"finalVideo","type":"video","label":"成片"}],"parameters":{"progressStep":"字幕与音视频合成"}}},
    {"id":"output","type":"workflowNode","position":{"x":2060,"y":240},"data":{"title":"成片输出","nodeDefType":"video_output","kind":"output","color":"#ef4444","inputSlots":[{"name":"finalVideo","type":"video","label":"成片"}],"parameters":{"displayMode":"video"}}}
  ]',
  w.edges_json = '[
    {"id":"e-start-field","source":"start","target":"field-input","sourceHandle":"out-context","targetHandle":"in-context","type":"smoothstep"},
    {"id":"e-field-script","source":"field-input","target":"script-planner","sourceHandle":"out-params","targetHandle":"in-form","type":"smoothstep"},
    {"id":"e-script-confirm","source":"script-planner","target":"user-input-script","sourceHandle":"out-script","targetHandle":"in-upstream","type":"smoothstep"},
    {"id":"e-confirm-keyframe","source":"user-input-script","target":"keyframe","sourceHandle":"out-scriptFeedback","targetHandle":"in-script","type":"smoothstep"},
    {"id":"e-field-keyframe","source":"field-input","target":"keyframe","sourceHandle":"out-params","targetHandle":"in-form","type":"smoothstep"},
    {"id":"e-keyframe-scene","source":"keyframe","target":"user-input-scene","sourceHandle":"out-keyframe","targetHandle":"in-upstream","type":"smoothstep"},
    {"id":"e-scene-tts","source":"user-input-scene","target":"tts","sourceHandle":"out-sceneFeedback","targetHandle":"in-script","type":"smoothstep"},
    {"id":"e-scene-clip","source":"user-input-scene","target":"clip-video","sourceHandle":"out-sceneFeedback","targetHandle":"in-keyframe","type":"smoothstep"},
    {"id":"e-keyframe-clip","source":"keyframe","target":"clip-video","sourceHandle":"out-keyframe","targetHandle":"in-keyframe","type":"smoothstep","label":"关键帧"},
    {"id":"e-clip-compose","source":"clip-video","target":"compose","sourceHandle":"out-clip","targetHandle":"in-clip","type":"smoothstep"},
    {"id":"e-tts-compose","source":"tts","target":"compose","sourceHandle":"out-audio","targetHandle":"in-audio","type":"smoothstep"},
    {"id":"e-script-compose","source":"script-planner","target":"compose","sourceHandle":"out-script","targetHandle":"in-script","type":"smoothstep"},
    {"id":"e-compose-output","source":"compose","target":"output","sourceHandle":"out-finalVideo","targetHandle":"in-finalVideo","type":"smoothstep"}
  ]',
  w.config_json = JSON_OBJECT(
    'workflowType', 'AI_COMIC_DRAMA',
    'integrationMode', 'STANDARD_TASK',
    'requiredModelConfigCodes', JSON_ARRAY('siliconflow_voice_tts', 'agnes-image-2.1-flash', 'agnes-video-v2.0')
  ),
  w.status = 'PUBLISHED',
  w.version = w.version + 1,
  w.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'ai_comic_drama_agent';
