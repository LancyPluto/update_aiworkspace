<script setup lang="ts">
import { Loader2, MessageSquare, Plus, Trash2 } from "lucide-vue-next"
import type { ChatSession } from "@/api/aiToolTypes"

defineProps<{
  sessions: ChatSession[]
  activeSessionId: string | null
  loading?: boolean
  deletingSessionId?: string | null
  deleteError?: string | null
  open?: boolean
}>()

const emit = defineEmits<{
  create: []
  select: [sessionId: string]
  delete: [session: ChatSession, event: MouseEvent]
}>()
</script>

<template>
  <aside
    class="shrink-0 border-r border-border bg-card transition-[width,opacity] duration-200"
    :class="open ? 'w-64 opacity-100' : 'w-0 overflow-hidden opacity-0 pointer-events-none border-r-0'"
  >
    <div class="flex h-full w-64 flex-col p-3">
      <button
        type="button"
        class="inline-flex h-9 w-full items-center justify-center gap-2 rounded-lg bg-primary text-sm font-medium text-primary-foreground hover:opacity-90"
        @click="emit('create')"
      >
        <Plus class="h-4 w-4" />
        新建会话
      </button>

      <p v-if="deleteError" class="mt-2 text-xs text-destructive">{{ deleteError }}</p>

      <div v-if="loading" class="mt-4 flex justify-center py-6 text-muted-foreground">
        <Loader2 class="h-4 w-4 animate-spin" />
      </div>

      <div v-else class="mt-3 min-h-0 flex-1 overflow-y-auto">
        <p class="mb-2 px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">历史记录</p>
        <div v-if="sessions.length === 0" class="px-1 py-6 text-center text-xs text-muted-foreground">
          暂无会话，点击上方新建
        </div>
        <ul v-else class="space-y-1">
          <li
            v-for="session in sessions"
            :key="session.id"
            class="group flex items-stretch rounded-lg"
            :class="session.id === activeSessionId ? 'bg-primary/10' : 'hover:bg-secondary/80'"
          >
            <button
              type="button"
              class="flex min-w-0 flex-1 items-center gap-2 px-2.5 py-2 text-left text-sm"
              @click="emit('select', session.id)"
            >
              <MessageSquare class="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              <span class="truncate">{{ session.title || "新对话" }}</span>
            </button>
            <button
              type="button"
              class="flex w-8 shrink-0 items-center justify-center text-muted-foreground opacity-0 transition-opacity hover:text-destructive group-hover:opacity-100"
              :class="session.id === activeSessionId ? 'opacity-100' : ''"
              :disabled="deletingSessionId === session.id"
              :aria-label="`删除会话：${session.title}`"
              @click="emit('delete', session, $event)"
            >
              <Loader2 v-if="deletingSessionId === session.id" class="h-3.5 w-3.5 animate-spin" />
              <Trash2 v-else class="h-3.5 w-3.5" />
            </button>
          </li>
        </ul>
      </div>
    </div>
  </aside>
</template>
