<script setup lang="ts">
import { AlertTriangle } from "lucide-vue-next"
import {
  acceptConfirmDelete,
  cancelConfirmDelete,
  useConfirmDeleteState,
} from "@/composables/useConfirmDelete"

const state = useConfirmDeleteState()
</script>

<template>
  <Teleport to="body">
    <div
      v-if="state.open"
      class="fixed inset-0 z-[100] flex items-center justify-center bg-black/55 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-delete-title"
      @click.self="cancelConfirmDelete"
    >
      <div class="confirm-delete-panel w-full max-w-sm overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        <div class="flex items-center gap-3 border-b border-border bg-destructive/5 px-4 py-4">
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-destructive/15 text-destructive">
            <AlertTriangle class="h-4 w-4" aria-hidden="true" />
          </div>
          <h3 id="confirm-delete-title" class="text-base font-semibold text-foreground">
            {{ state.title }}
          </h3>
        </div>

        <div class="px-4 py-4">
          <p v-if="state.description" class="text-sm leading-relaxed text-muted-foreground">
            {{ state.description }}
          </p>
          <p v-else-if="state.itemName" class="text-sm text-muted-foreground">
            确定要删除
            <span class="font-medium text-foreground">「{{ state.itemName }}」</span>
            吗？
          </p>
          <p v-else class="text-sm text-muted-foreground">确定要执行此删除操作吗？</p>
          <p v-if="state.warning" class="mt-2 text-xs font-medium text-destructive">
            {{ state.warning }}
          </p>
        </div>

        <div class="flex justify-end gap-2 border-t border-border bg-muted/30 px-4 py-3">
          <button
            type="button"
            class="inline-flex h-9 items-center justify-center rounded-md border border-border bg-card px-4 text-sm font-medium text-foreground transition-colors hover:bg-secondary"
            @click="cancelConfirmDelete"
          >
            取消
          </button>
          <button
            type="button"
            class="inline-flex h-9 items-center justify-center rounded-md bg-destructive px-4 text-sm font-medium text-destructive-foreground shadow-sm transition-colors hover:bg-destructive/90"
            @click="acceptConfirmDelete"
          >
            {{ state.confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-delete-panel {
  animation: confirmDeleteIn 0.15s ease-out;
}

@keyframes confirmDeleteIn {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(4px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
</style>
