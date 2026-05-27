# Agent Tool Health And Long-Term Memory Plan

Updated: 2026-05-27

## Tool Health

- Keep manual availability and runtime health separate.
- `agent_enabled` remains the admin switch.
- `health_status` records runtime health: `UNKNOWN`, `HEALTHY`, or `FAILED`.
- `health_message` stores the latest actionable failure reason.
- `health_checked_at` stores the latest health update time.

Current behavior:

- Worker maps authentication failures to `MODEL_AUTH_FAILED`.
- Worker maps provider/capability mismatch to `MODEL_PROVIDER_UNAVAILABLE`.
- Backend marks the related Agent tool as `FAILED` when those errors are reported.
- Agent runtime filters `FAILED` tools out of the available tool list.
- Successful task completion marks the tool `HEALTHY`.
- Re-enabling a tool in admin resets failed health state to `UNKNOWN`.

Next steps:

- Add batch validation for tools grouped by model config.
- Add admin filters for failed tools and model-bound tools.
- Show remediation copy for common health failures, especially invalid token, missing key, provider mismatch, and worker not ready.

## Long-Term Memory

The memory system should start as auditable workspace memory, not as an invisible global user profile.

Memory types:

- `user_profile`: stable preferences, communication style, common output requirements, brand/style taste.
- `project_knowledge`: business facts, project rules, tool usage conventions, implementation decisions.
- `custom`: temporary or special-purpose notes that do not fit the first two types.

Product rules:

- Memory must be visible, editable, and deletable by the user/admin.
- Do not save sensitive identity, credential, payment, or private personal data unless the user explicitly asks and the product has a clear compliance story.
- Prefer concise facts over transcript summaries.
- Prefer updating an existing memory over appending duplicates.
- Inject only relevant memories into a run context, with a small limit and traceable snapshot.

Suggested rollout:

1. Keep workspace memory as the primary unit.
2. Add a memory management panel in the Agent configuration/workspace area.
3. Add confidence/source fields later if memory quality becomes a problem.
4. Add global user profile only after tenant/account boundaries and privacy controls are clear.

Recommended storage model:

- `agent_memories`: one durable fact per row.
- Required fields: `id`, `tenant_id`, `workspace_id`, `user_id`, `memory_type`, `title`, `content`, `status`, `visibility`, `created_at`, `updated_at`.
- Quality fields: `source_type`, `source_id`, `confidence`, `last_used_at`, `use_count`, `expires_at`.
- Retrieval fields: `tags_json`, `embedding_id` or `embedding_vector` depending on the vector stack.
- `status` should support `ACTIVE`, `ARCHIVED`, and `DELETED`.
- `visibility` should start with `PRIVATE_USER` and `WORKSPACE`, then add team-level sharing when permissions are mature.

Runtime flow:

1. Build a context query from the latest user message, selected tool, workspace, and session topic.
2. Retrieve only active memories inside the caller's tenant and workspace boundary.
3. Rank by semantic relevance, recency, explicit pinning, and prior successful use.
4. Inject a small, traceable memory snapshot into the Agent run context.
5. Let the Agent propose memory updates, but commit them only through a backend policy layer.

Memory write policy:

- Save stable preferences only after repeated evidence or explicit user confirmation.
- Save project facts when they affect future output quality or tool routing.
- Do not save one-off prompts, celebrity/image subjects, passwords, API keys, payment data, private identity data, or medical/legal/financial sensitive facts by default.
- Prefer merging into an existing memory if the meaning overlaps.
- Record the source message/run so users and admins can audit why a memory exists.

Product UI:

- Reuse the current Prompt/Agent configuration page as the first admin surface.
- Add tabs for `System Prompt`, `Tools`, `Memory`, and `Runtime Logs`.
- The Memory tab should show searchable memory cards, source, last used time, and delete/archive actions.
- Add a per-workspace switch: `Allow Agent to remember useful preferences`.
- Add a per-run trace panel so users can see which memories were used in an answer.

Near-term implementation order:

1. Add read-only memory schema and admin UI skeleton.
2. Capture explicit memories only, via a user action such as "remember this".
3. Add retrieval into Agent context snapshots with a strict item/token limit.
4. Add Agent-suggested memory proposals after tool calling is stable.
5. Add vector retrieval and decay/merge jobs after real usage data exists.
