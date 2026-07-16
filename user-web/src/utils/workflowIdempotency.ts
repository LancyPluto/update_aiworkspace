interface WorkflowIdempotencyStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

interface StoredWorkflowIdempotency {
  tokenMarker: string
  idempotencyKey: string
}

const memoryStore = new Map<string, StoredWorkflowIdempotency>()

export function workflowFeedbackIdempotencyKey(
  taskId: string | number,
  stepId: string | number,
  confirmationToken: string,
  action: string,
  createId: () => string,
  storage?: WorkflowIdempotencyStorage,
): string {
  const scope = `workflow-feedback-idempotency:${taskId}:${stepId}:${action}`
  const tokenMarker = marker(confirmationToken)
  const existing = readStored(scope, storage) ?? memoryStore.get(scope)
  if (existing?.tokenMarker === tokenMarker && existing.idempotencyKey) {
    return existing.idempotencyKey
  }

  const created = { tokenMarker, idempotencyKey: createId() }
  memoryStore.set(scope, created)
  try {
    storage?.setItem(scope, JSON.stringify(created))
  } catch {
    // Browser storage can be unavailable; the in-memory fallback still covers same-page retries.
  }
  return created.idempotencyKey
}

function readStored(
  scope: string,
  storage?: WorkflowIdempotencyStorage,
): StoredWorkflowIdempotency | null {
  if (!storage) return null
  try {
    const value = storage.getItem(scope)
    if (!value) return null
    const parsed = JSON.parse(value) as Partial<StoredWorkflowIdempotency>
    if (typeof parsed.tokenMarker !== "string" || typeof parsed.idempotencyKey !== "string") return null
    return { tokenMarker: parsed.tokenMarker, idempotencyKey: parsed.idempotencyKey }
  } catch {
    return null
  }
}

function marker(value: string): string {
  let hash = 2166136261
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return `${value.length}:${(hash >>> 0).toString(16)}`
}
