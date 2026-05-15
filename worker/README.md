# AI Task Worker

Python Worker for consuming Redis tasks, rendering prompts, calling AI models, and reporting results back to backend internal APIs.

## Contract Source Of Truth

Use `worker/WORKER_BACKEND_FINAL_CONTRACT.md` as the authoritative contract for:

- Redis queue message fields
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
