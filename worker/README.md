# AI Task Worker

Python Worker for consuming RabbitMQ tasks, rendering prompts, calling AI models, and reporting results back to backend internal APIs. Redis consumption is kept only for local development or legacy compatibility.

## Contract Source Of Truth

Use `worker/WORKER_BACKEND_FINAL_CONTRACT.md` as the authoritative contract for:

- RabbitMQ queue message fields and legacy Redis compatibility
- `execution-context` response fields
- `processing / success / failed` callback payloads
- error codes and field sources

## Directory Layout

- `client/`: backend internal API calls and model API calls
- `handlers/`: task orchestration logic
- `prompt/`: shared prompt rendering utilities
- `task_queue/`: queue consumption
- `tools/`: tool-specific assets and post-processing rules

## Tool Asset Example

Each tool can keep its own prompt, schema, output contract, and examples under:

```text
tools/<tool_code>/
```

## Image Reference Contract

The worker is the execution boundary, not the semantic resolver. It must receive concrete media sources, never UI-only labels such as `@图片1`.

- Agent-service resolves inline chip / `@` pointers before task creation.
- OpenAI Images tasks read `base_image_url` first, then append `reference_images`, then fall back to legacy fields such as `image`, `images`, `referenceImageUrl`, and `baseImageUrl`.
- The first resolved image sent to `/images/edits` is always the base image. Later images are references.
- If `resolve_reference_image_data_url` raises `reference image must be base64, data url, URL, or readable local file`, the bug is upstream unless the worker received a malformed concrete URL.

Keep `tests/test_image_generation_handler_references.py` updated when changing image parameter order or compatibility fields.
