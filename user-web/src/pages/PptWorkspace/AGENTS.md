# PPT Workspace Frontend Rules

This file supplements `user-web/AGENTS.md` for the PPT workspace.

## Product boundary

- The browser calls only the 科创点AI backend. It must never call Banana Slides or another renderer directly.
- `PPT_WORKSPACE` is an independent integration mode and route. Do not rename or reuse the `TEXT` modality.
- Treat engine identifiers and capabilities as display metadata; keep engine-specific request fields out of components.
- Restore state from platform project and job APIs after refresh. An in-memory polling timer is not a source of truth.

## Structure and state

- API transport and normalization belong in `src/api/pptApi.ts`.
- Cross-page project and job state belongs in a dedicated Pinia store.
- Polling, cancellation, retry, and recovery belong in composables; pages compose behavior and components render it.
- Keep components focused on one workspace responsibility: project list, creation form, slide rail, preview, action inspector, job status, or export list.
- Use stable project, slide, and job IDs as keys. Do not use array positions for editable or asynchronous items.

## UX requirements

- Cover loading, empty, success, error, disabled, engine-maintenance, insufficient-credit, retry, and refresh-recovery states.
- Always show the current action, progress, estimated or reserved credit impact, and the next available action.
- Stage cards are navigation and inspection controls only. Clicking a stage must never submit, retry, regenerate, export, or incur credits.
- Every credit-bearing action requires a separately labelled action control and an explicit confirmation step that names the stage, model, credit impact, and downstream scope.
- Preserve the last successful artifact when a regeneration attempt fails. Present the artifact as available with a warning instead of replacing it with a blocking failure state.
- Keep stage inspection available while another stage is running; disable mutations, not reading.
- Model pickers may show only backend-provided, currently executable platform models. Persist selections through the project model-binding API and disable changes while a job is active.
- Describe model choice as platform-managed: users select a model, but never configure or see provider credentials in the PPT workspace.
- Use the existing cyan brand variables and UI primitives. Do not copy the Banana frontend or introduce a separate visual system.
- Provide keyboard navigation, visible focus, semantic labels, accessible icon buttons, and reduced-motion behavior.
- Keep historical projects readable while the engine is unavailable; disable only actions that require the engine.

## Required checks

- Route tests for `PPT_WORKSPACE` and regression tests proving `TEXT` keeps its original behavior.
- Store/composable tests for polling, terminal states, cancellation, retry, refresh recovery, and duplicate events.
- Component coverage for engine maintenance and insufficient-credit handling.
- Run `npm test` and `npm run build` for functional changes.

