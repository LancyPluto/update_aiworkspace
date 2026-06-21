# Agent Engineering Guardrails

This file is the first-stop guide for future coding agents working in this repository. It records the multimodal Agent rules that must not be broken again.

## Multimodal Context Rules

- Do not route, patch arguments, or infer follow-up intent by matching raw user text such as `刚刚`, `上一张`, `原图`, `这张`, `电影级`, or `构图`. No business-layer keyword sniffing, regex interceptors, or whitelist piles for pronoun resolution.
- Conversation continuity must use structured state injection. Extract facts from `recentToolCalls`, `mediaUrls`, `argumentsJson`, `resultJson`, and tool metadata, then inject them through `<SessionState>` for the LLM to resolve semantically.
- `@图片` and inline chips are pointers, not files and not URLs. Before creating a backend task, all pointer-like media arguments must pass through `resolve_media_argument_pointers` so the worker receives concrete URLs, data URLs, base64, or readable files.
- Image-edit tools must distinguish `base_image_url` from `reference_images`. The base image is the image being edited, normally from `<SessionState.latest_generated_image.url>` or a user-selected base. `reference_images` are additional face, style, pose, or composition references.
- For follow-up image edits, the prompt must inherit `latest_generated_image.prompt` and append only the user's requested delta. Do not reset the scene, outfit, lighting, camera, background, composition, or style unless the user explicitly asks for that change.
- Output modality detection must be conservative. Words describing cinematic style, composition, or previous images are not video intent. Return video only for explicit video-output requests such as `视频`, `图生视频`, `做成视频`, `作为首帧`, `image-to-video`, or equivalent direct output wording.

## Required Touch Points

When changing multimodal Agent behavior, inspect and update these files together:

- `agent-service/app/runtime/session_state.py`: structured session hydration and `<SessionState>` prompt rules.
- `agent-service/app/core/attachment_catalog.py`: inline chip / `@` pointer catalog, ordered content parts, and media argument resolution.
- `agent-service/app/core/user_attachment_priority.py`: attachment priority and reference image arrays.
- `agent-service/app/tools/backend_tool.py`: final tool arguments before backend task creation.
- `agent-service/app/tools/registry.py`: conservative output modality inference.
- `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentToolDescriptorServiceImpl.java`: tool schema exposed to the LLM.
- `worker/handlers/image_generation_handler.py`: final worker-side image parameter order.

## Required Tests

Any change in this area must include focused tests for:

- Session state hydration from structured recent tool calls without reading or matching the raw user message.
- Tool schema containing `base_image_url` and `reference_images` for GPT/OpenAI image tools.
- Pointer resolution from `@图片...`, `assetKey`, `fileId`, and `latest_generated_image.url` into concrete media URLs.
- Worker image ordering: `base_image_url` first, then `reference_images`, then legacy compatibility fields.
- Modality routing: style/composition/follow-up wording must not be treated as video unless the user explicitly asks for video output.
