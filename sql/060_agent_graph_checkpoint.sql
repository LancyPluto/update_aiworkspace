-- Persisted multi-step agent graph checkpoint, used to resume the LangGraph
-- agentic loop across the human-in-the-loop tool confirmation boundary.
-- The checkpoint is a serialized snapshot of the in-flight agent state
-- (message stack, plan, artifacts, pending confirmation) for one run.
ALTER TABLE agent_runs
  ADD COLUMN graph_checkpoint_json MEDIUMTEXT NULL AFTER preferred_tool_code;
