<script setup lang="ts">
  import { onMounted, ref, watch } from "vue"
  import { Bot, Loader2, Plus, Sparkles, Trash2 } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  import WorkspaceMemoryPanel from "./WorkspaceMemoryPanel.vue"
  import AgentChatPane from "./AgentChatPane.vue"
  import { useAuthStore } from "@/store/authStore"
  import {
    createAgentSession,
    deleteAgentSession,
    fetchAgentSessions,
    fetchAgentWorkspaces,
  } from "@/api"
  import type { AgentSession, AgentWorkspace } from "@/api/types"

  const auth = useAuthStore()
  const workspaces = ref<AgentWorkspace[]>([])
  const activeWorkspaceId = ref<number | null>(null)
  const sessions = ref<AgentSession[]>([])
  const activeSessionId = ref<number | null>(null)
  const sessionDrafts = ref<Record<number, string>>({})
  const sessionsLoading = ref(false)
  const chatPaneRef = ref<InstanceType<typeof AgentChatPane> | null>(null)

  const AGENT_SESSION_SIDEBAR_KEY = "ai_tool_market_agent_session_sidebar_open"
  const AGENT_LAST_SESSION_KEY = "ai_tool_market_agent_last_session_id"
  const sessionSidebarOpen = ref(true)
  const deletingSessionId = ref<number | null>(null)
  const deleteSessionError = ref<string | null>(null)

  function toggleSessionSidebar() {
    sessionSidebarOpen.value = !sessionSidebarOpen.value
  }

  watch(sessionSidebarOpen, (open) => {
    localStorage.setItem(AGENT_SESSION_SIDEBAR_KEY, open ? "1" : "0")
  })

  function onDraftUpdate(value: string) {
    if (!activeSessionId.value) return
    sessionDrafts.value[activeSessionId.value] = value
  }

  function persistActiveSession(sessionId: number) {
    localStorage.setItem(AGENT_LAST_SESSION_KEY, String(sessionId))
  }

  async function loadWorkspaces() {
    if (!auth.token) return
    const res = await fetchAgentWorkspaces({ token: auth.token })
    workspaces.value = res.list
    if (!activeWorkspaceId.value && workspaces.value[0]) {
      activeWorkspaceId.value = workspaces.value[0].id
    }
  }

  async function loadSessions() {
    if (!auth.token) return
    sessionsLoading.value = true
    try {
      const res = await fetchAgentSessions({ token: auth.token })
      sessions.value = res.list
      if (!activeSessionId.value && sessions.value.length > 0) {
        const savedRaw = localStorage.getItem(AGENT_LAST_SESSION_KEY)
        const savedId = savedRaw ? Number(savedRaw) : NaN
        const target = sessions.value.find((s) => s.id === savedId) ?? sessions.value[0]
        selectSession(target.id)
      }
    } finally {
      sessionsLoading.value = false
    }
  }

  function selectSession(sessionId: number) {
    activeSessionId.value = sessionId
    persistActiveSession(sessionId)
    deleteSessionError.value = null
  }

  async function startSession(title = "?? Agent ??") {
    if (!auth.token) return
    const session = await createAgentSession({ title }, { token: auth.token })
    sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
    if (sessionDrafts.value[session.id] === undefined) {
      sessionDrafts.value[session.id] = ""
    }
    selectSession(session.id)
  }

  async function removeSession(session: AgentSession, event: MouseEvent) {
    event.stopPropagation()
    if (!auth.token || deletingSessionId.value != null) return
    const pane = chatPaneRef.value
    if (session.id === activeSessionId.value && pane?.hasActiveRun) {
      pane.showError("当前会话 Agent 仍在运行，请稍后再删除。")
      return
    }
    if (!confirm(`确定删除「${session.title}」？`)) return
    deletingSessionId.value = session.id
    deleteSessionError.value = null
    try {
      await deleteAgentSession(session.id, { token: auth.token })
      delete sessionDrafts.value[session.id]
      const wasActive = activeSessionId.value === session.id
      sessions.value = sessions.value.filter((item) => item.id !== session.id)
      if (wasActive) {
        activeSessionId.value = null
        const next = sessions.value[0]
        if (next) selectSession(next.id)
      }
    } catch (error) {
      deleteSessionError.value = error instanceof Error ? error.message : "删除会话失败"
    } finally {
      deletingSessionId.value = null
    }
  }

  onMounted(() => {
    const saved = localStorage.getItem(AGENT_SESSION_SIDEBAR_KEY)
    if (saved === "0") sessionSidebarOpen.value = false
    if (saved === "1") sessionSidebarOpen.value = true
    void loadWorkspaces()
    void loadSessions()
  })
</script>

<template>
  <AppShell title="Agent" description="用自然语言让系统推荐、确认并调用工具">
    <div class="agent-page" :class="{ 'agent-page--session-collapsed': !sessionSidebarOpen }">
      <aside class="agent-sidebar" :class="{ 'agent-sidebar--collapsed': !sessionSidebarOpen }">
        <button class="new-chat" type="button" @click="startSession()">
          <Plus class="h-4 w-4" />
          新会话
        </button>
        <p v-if="deleteSessionError" class="sidebar-error">{{ deleteSessionError }}</p>
        <div v-if="sessionsLoading" class="session-list-loading">
          <Loader2 class="h-4 w-4 animate-spin" />
        </div>
        <div class="session-list">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session-row"
            :class="{ active: session.id === activeSessionId }"
          >
            <button type="button" class="session-item" @click="selectSession(session.id)">
              <Bot class="h-4 w-4 shrink-0" />
              <span>{{ session.title }}</span>
            </button>
            <button
              type="button"
              class="session-delete"
              :disabled="deletingSessionId === session.id"
              :aria-label="`删除会话：${session.title}`"
              @click="removeSession(session, $event)"
            >
              <Loader2 v-if="deletingSessionId === session.id" class="h-4 w-4 animate-spin" aria-hidden="true" />
              <Trash2 v-else class="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
        </div>
      </aside>

      <section class="chat-pane">
        <AgentChatPane
          v-if="activeSessionId"
          :key="activeSessionId"
          ref="chatPaneRef"
          :session-id="activeSessionId"
          :token="auth.token"
          :draft="sessionDrafts[activeSessionId] ?? ''"
          :session-sidebar-open="sessionSidebarOpen"
          :sessions="sessions"
          @update:draft="onDraftUpdate"
          @toggle-session-sidebar="toggleSessionSidebar"
        />
        <div v-else class="chat-pane-empty">
          <div class="empty-mark"><Sparkles class="h-6 w-6" /></div>
          <h2>开始新的 Agent 会话</h2>
          <p>点击左侧「新会话」，或下方按钮创建会话后开始对话。</p>
          <button type="button" class="new-chat chat-pane-empty-btn" @click="startSession()">
            <Plus class="h-4 w-4" />
            新会话
          </button>
        </div>
      </section>

      <div class="agent-memory-panel">
        <WorkspaceMemoryPanel :workspace-id="activeWorkspaceId" :token="auth.token" />
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
  .agent-page {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr) 320px;
    min-height: calc(100vh - 64px);
  }

  .agent-page--session-collapsed {
    grid-template-columns: 0 minmax(0, 1fr) 320px;
  }

  .agent-sidebar {
    border-right: 1px solid var(--border);
    background: var(--card);
    padding: 14px;
    min-width: 0;
    transition: opacity 0.15s ease, padding 0.15s ease;
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
    display: flex;
    flex-direction: column;
  }

  .agent-sidebar--collapsed {
    width: 0;
    max-width: 0;
    padding: 0;
    border-right-width: 0;
    overflow: hidden;
    opacity: 0;
    pointer-events: none;
  }

  .new-chat,
  .session-item,
  .session-delete,
  .chat-pane-empty-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border-radius: 8px;
  }

  .new-chat {
    width: 100%;
    height: 40px;
    border: 1px solid var(--border);
    background: var(--foreground);
    color: var(--primary-foreground);
    font-size: 14px;
    cursor: pointer;
  }

  .sidebar-error {
    margin: 10px 0 0;
    font-size: 12px;
    color: #b42318;
  }

  .session-list-loading {
    margin-top: 14px;
    display: flex;
    justify-content: center;
    color: var(--muted-foreground);
  }

  .session-list {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-height: 0;
  }

  .session-row {
    display: flex;
    align-items: stretch;
    gap: 2px;
    border-radius: 8px;
    min-width: 0;
  }

  .session-row.active,
  .session-row:hover {
    background: var(--secondary);
  }

  .session-item {
    flex: 1;
    min-width: 0;
    border: 0;
    background: transparent;
    padding: 10px 6px 10px 10px;
    color: var(--foreground);
    font-size: 13px;
    text-align: left;
    cursor: pointer;
  }

  .session-item span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .session-delete {
    flex-shrink: 0;
    width: 36px;
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--muted-foreground);
    cursor: pointer;
  }

  .session-delete:hover:not(:disabled) {
    color: var(--destructive);
  }

  .session-delete:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .chat-pane {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    height: 100%;
  }

  .chat-pane-empty {
    flex: 1;
    min-height: 58vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    color: var(--muted-foreground);
    padding: 28px;
  }

  .empty-mark {
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    border-radius: 12px;
    background: var(--foreground);
    color: var(--primary-foreground);
  }

  .chat-pane-empty h2 {
    margin: 18px 0 8px;
    font-size: 24px;
    color: var(--foreground);
  }

  .chat-pane-empty-btn {
    margin-top: 22px;
    height: 40px;
    padding: 0 16px;
    border: 1px solid var(--border);
    background: var(--foreground);
    color: var(--primary-foreground);
    font-size: 14px;
    cursor: pointer;
  }

  .agent-memory-panel {
    min-width: 0;
    min-height: 0;
  }

  @media (max-width: 900px) {
    .agent-page {
      grid-template-columns: minmax(140px, 36vw) minmax(0, 1fr);
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .agent-page--session-collapsed {
      grid-template-columns: 0 minmax(0, 1fr);
    }

    .agent-memory-panel {
      grid-column: 1 / -1;
      border-top: 1px solid var(--border);
    }

    :deep(.workspace-memory-panel) {
      border-left: 0;
    }

    .agent-sidebar::-webkit-scrollbar {
      width: 4px;
    }
    .agent-sidebar::-webkit-scrollbar-thumb {
      background: var(--muted-foreground);
      border-radius: 4px;
      opacity: 0;
      transition: opacity 0.2s;
    }
    .agent-sidebar:hover::-webkit-scrollbar-thumb {
      opacity: 1;
    }
  }
</style>
