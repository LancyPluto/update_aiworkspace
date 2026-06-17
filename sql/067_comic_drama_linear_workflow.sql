SET NAMES utf8mb4;

-- 漫剧工作流 v4：无配音版，严格线性顺序
-- 流程：表单 → 剧本生成 → 用户确认剧本 → 关键帧生成 → 用户确认场景图 → 图生视频 → 字幕合成 → 成片输出
-- 共 9 个节点（去掉 TTS 节点）
-- 修复：
--   1. script-planner 绑定 agnes-2.0-flash (id=83) 作为 LLM
--   2. 移除 TTS 节点和相关边，暂不配音/配乐
--   3. keyframe 绑定 agnes-image-2.1-flash (id=85)
--   4. clip-video 绑定 agnes-video-v2.0 (id=86)

UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET
  w.nodes_json = '[
    {"id":"start","type":"workflowNode","position":{"x":40,"y":240},"data":{"title":"Start","nodeDefType":"start","kind":"start","color":"#10b981","outputSlots":[{"name":"context","type":"json","label":"会话上下文"}]}},
    {"id":"field-input","type":"workflowNode","position":{"x":260,"y":240},"data":{"title":"初始表单","nodeDefType":"field_input","kind":"input","color":"#64748b","inputSlots":[{"name":"context","type":"json","label":"会话上下文"}],"outputSlots":[{"name":"params","type":"json","label":"用户填写参数"}]}},
    {"id":"script-planner","type":"workflowNode","position":{"x":500,"y":240},"data":{"title":"剧本与分镜","nodeDefType":"llm_text","kind":"model","color":"#3b82f6","inputSlots":[{"name":"form","type":"json","label":"初始表单"}],"outputSlots":[{"name":"script","type":"json","label":"剧本分镜"}],"parameters":{"role":"comic_script_planner","progressStep":"生成剧本与分镜脚本","modelConfigId":83}}},
    {"id":"user-input-script","type":"workflowNode","position":{"x":760,"y":240},"data":{"title":"剧本/分镜确认","nodeDefType":"user_input","kind":"input","color":"#0ea5e9","inputSlots":[{"name":"upstream","type":"any","label":"上一步结果"}],"outputSlots":[{"name":"scriptFeedback","type":"text","label":"脚本意见"},{"name":"storyboardFeedback","type":"text","label":"分镜意见"}],"parameters":{"fieldKey":"scriptFeedback","stageLabel":"剧本/分镜确认"}}},
    {"id":"keyframe","type":"workflowNode","position":{"x":1020,"y":240},"data":{"title":"生成人物图与场景图","nodeDefType":"image_model","kind":"model","color":"#ec4899","inputSlots":[{"name":"script","type":"json","label":"剧本分镜"},{"name":"form","type":"json","label":"表单参数"}],"outputSlots":[{"name":"keyframe","type":"image","label":"关键帧"}],"parameters":{"progressStep":"生成人物图与场景图","modelConfigId":85}}},
    {"id":"user-input-scene","type":"workflowNode","position":{"x":1280,"y":240},"data":{"title":"场景图确认","nodeDefType":"user_input","kind":"input","color":"#0ea5e9","inputSlots":[{"name":"upstream","type":"any","label":"上一步结果"}],"outputSlots":[{"name":"sceneFeedback","type":"text","label":"场景图意见"}],"parameters":{"fieldKey":"sceneFeedback","stageLabel":"场景图确认"}}},
    {"id":"clip-video","type":"workflowNode","position":{"x":1540,"y":240},"data":{"title":"逐镜生成视频","nodeDefType":"video_model","kind":"model","color":"#f97316","inputSlots":[{"name":"keyframe","type":"image","label":"关键帧"},{"name":"script","type":"json","label":"剧本分镜"}],"outputSlots":[{"name":"clip","type":"video","label":"视频片段"}],"parameters":{"progressStep":"逐个生成分镜视频","modelConfigId":86}}},
    {"id":"compose","type":"workflowNode","position":{"x":1800,"y":240},"data":{"title":"字幕合成与拼接","nodeDefType":"subtitle","kind":"tool","color":"#f97316","inputSlots":[{"name":"clip","type":"video","label":"视频片段"},{"name":"script","type":"json","label":"剧本分镜"}],"outputSlots":[{"name":"finalVideo","type":"video","label":"成片"}],"parameters":{"progressStep":"字幕合成与拼接成片"}}},
    {"id":"output","type":"workflowNode","position":{"x":2060,"y":240},"data":{"title":"成片输出","nodeDefType":"video_output","kind":"output","color":"#ef4444","inputSlots":[{"name":"finalVideo","type":"video","label":"成片"}],"parameters":{"displayMode":"video"}}}
  ]',
  w.edges_json = '[
    {"id":"e-start-field","source":"start","target":"field-input","sourceHandle":"out-context","targetHandle":"in-context","type":"smoothstep"},
    {"id":"e-field-script","source":"field-input","target":"script-planner","sourceHandle":"out-params","targetHandle":"in-form","type":"smoothstep"},
    {"id":"e-script-confirm","source":"script-planner","target":"user-input-script","sourceHandle":"out-script","targetHandle":"in-upstream","type":"smoothstep"},
    {"id":"e-confirm-keyframe","source":"user-input-script","target":"keyframe","sourceHandle":"out-scriptFeedback","targetHandle":"in-script","type":"smoothstep"},
    {"id":"e-field-keyframe","source":"field-input","target":"keyframe","sourceHandle":"out-params","targetHandle":"in-form","type":"smoothstep"},
    {"id":"e-keyframe-scene","source":"keyframe","target":"user-input-scene","sourceHandle":"out-keyframe","targetHandle":"in-upstream","type":"smoothstep"},
    {"id":"e-script-keyframe","source":"script-planner","target":"keyframe","sourceHandle":"out-script","targetHandle":"in-script","type":"smoothstep","label":"剧本数据"},
    {"id":"e-scene-clip","source":"user-input-scene","target":"clip-video","sourceHandle":"out-sceneFeedback","targetHandle":"in-keyframe","type":"smoothstep"},
    {"id":"e-keyframe-clip","source":"keyframe","target":"clip-video","sourceHandle":"out-keyframe","targetHandle":"in-keyframe","type":"smoothstep","label":"关键帧"},
    {"id":"e-script-clip","source":"script-planner","target":"clip-video","sourceHandle":"out-script","targetHandle":"in-script","type":"smoothstep","label":"剧本数据"},
    {"id":"e-clip-compose","source":"clip-video","target":"compose","sourceHandle":"out-clip","targetHandle":"in-clip","type":"smoothstep"},
    {"id":"e-script-compose","source":"script-planner","target":"compose","sourceHandle":"out-script","targetHandle":"in-script","type":"smoothstep"},
    {"id":"e-compose-output","source":"compose","target":"output","sourceHandle":"out-finalVideo","targetHandle":"in-finalVideo","type":"smoothstep"}
  ]',
  w.config_json = JSON_OBJECT(
    'workflowType', 'AI_COMIC_DRAMA',
    'integrationMode', 'STANDARD_TASK',
    'requiredModelConfigCodes', JSON_ARRAY('agnes-2.0-flash', 'agnes-image-2.1-flash', 'agnes-video-v2.0')
  ),
  w.status = 'PUBLISHED',
  w.version = w.version + 1,
  w.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'ai_comic_drama_agent';
