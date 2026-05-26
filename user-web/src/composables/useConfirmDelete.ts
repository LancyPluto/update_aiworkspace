import { reactive, readonly } from "vue"

export type ConfirmDeleteOptions = {
  title?: string
  itemName?: string
  description?: string
  warning?: string
  confirmLabel?: string
}

type ConfirmDeleteState = {
  open: boolean
  title: string
  itemName: string
  description: string
  warning: string
  confirmLabel: string
}

const state = reactive<ConfirmDeleteState>({
  open: false,
  title: "确认删除",
  itemName: "",
  description: "",
  warning: "此操作不可撤销",
  confirmLabel: "确认删除",
})

let resolvePromise: ((value: boolean) => void) | null = null
let escapeHandler: ((event: KeyboardEvent) => void) | null = null

function cleanupEscape() {
  if (escapeHandler) {
    document.removeEventListener("keydown", escapeHandler)
    escapeHandler = null
  }
}

function finish(confirmed: boolean) {
  state.open = false
  cleanupEscape()
  const resolve = resolvePromise
  resolvePromise = null
  resolve?.(confirmed)
}

export function confirmDelete(options: ConfirmDeleteOptions = {}): Promise<boolean> {
  if (state.open && resolvePromise) {
    finish(false)
  }

  return new Promise((resolve) => {
    resolvePromise = resolve
    state.title = options.title ?? "确认删除"
    state.itemName = options.itemName ?? ""
    state.description = options.description ?? ""
    state.warning = options.warning ?? "此操作不可撤销"
    state.confirmLabel = options.confirmLabel ?? "确认删除"
    state.open = true

    escapeHandler = (event: KeyboardEvent) => {
      if (event.key === "Escape" && state.open) {
        finish(false)
      }
    }
    document.addEventListener("keydown", escapeHandler)
  })
}

export function acceptConfirmDelete() {
  finish(true)
}

export function cancelConfirmDelete() {
  finish(false)
}

export function useConfirmDeleteState() {
  return readonly(state)
}
