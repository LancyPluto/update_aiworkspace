SET NAMES utf8mb4;

-- Schema deployment must not switch an existing public tool to a disabled runtime.
-- Operations may enable the workflow only after reconciliation and canary gates pass.
UPDATE ai_tools
SET execution_mode = 'DIRECT',
    billing_mode = 'FIXED',
    agent_surface_enabled = 0
WHERE tool_code = 'ai_comic_drama_agent';

UPDATE tool_workflows workflow
JOIN ai_tools tool ON tool.id = workflow.tool_id
SET workflow.execution_enabled = 0
WHERE tool.tool_code = 'ai_comic_drama_agent';
