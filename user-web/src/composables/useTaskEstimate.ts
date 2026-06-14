import { ref, watch, onBeforeUnmount, type Ref } from "vue"
import { estimateTask } from "@/api/taskApi"
import type { EstimateTaskRequest, TaskEstimateResponse } from "@/api/types"

export interface UseTaskEstimateInput {
  toolCode: string | null | undefined
  params: Record<string, unknown>
  modelConfigId?: number | null
  /** Skip the network call (e.g. workflow tools with variable pricing). */
  skip?: boolean
}

/**
 * Debounced real-time pricing preview backed by the authoritative POST /tasks/estimate endpoint,
 * so the displayed cost always matches what will be frozen on submit. Aborts in-flight requests
 * when the parameter combination changes.
 */
export function useTaskEstimate(input: Ref<UseTaskEstimateInput | null>, debounceMs = 300) {
  const estimate = ref<TaskEstimateResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let timer: ReturnType<typeof setTimeout> | null = null
  let controller: AbortController | null = null

  function reset() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    controller?.abort()
    controller = null
  }

  async function run(current: UseTaskEstimateInput) {
    controller?.abort()
    const ctrl = new AbortController()
    controller = ctrl
    loading.value = true
    error.value = null
    try {
      const body: EstimateTaskRequest = {
        toolCode: current.toolCode as string,
        params: current.params ?? {},
        modelConfigId: current.modelConfigId ?? null,
      }
      const result = await estimateTask(body, { signal: ctrl.signal })
      if (ctrl.signal.aborted) return
      estimate.value = result
    } catch (err) {
      if (ctrl.signal.aborted || (err as Error)?.name === "AbortError") return
      error.value = err instanceof Error ? err.message : "估算失败"
      estimate.value = null
    } finally {
      if (!ctrl.signal.aborted) loading.value = false
    }
  }

  watch(
    input,
    (value) => {
      reset()
      if (!value || !value.toolCode || value.skip) {
        if (value?.skip) {
          // Variable-priced tools: clear numeric estimate, UI shows "算力不详".
          estimate.value = null
          error.value = null
          loading.value = false
        }
        return
      }
      const snapshot = value
      timer = setTimeout(() => run(snapshot), debounceMs)
    },
    { deep: true, immediate: true },
  )

  onBeforeUnmount(reset)

  return { estimate, loading, error }
}
