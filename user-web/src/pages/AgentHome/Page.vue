<script setup lang="ts">
  import { computed, onMounted, ref, watch } from "vue"
  import { Bot, ChevronLeft, ChevronRight, Loader2, Plus, Sparkles, Trash2 } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  import WorkspaceMemoryPanel from "./WorkspaceMemoryPanel.vue"
  import AgentChatPane from "./AgentChatPane.vue"
  import { useAuthStore } from "@/store/authStore"
  import {
    createAgentSession,
    deleteAgentSession,
    fetchAgentModelConfigs,
    fetchAgentSessions,
    fetchAgentWorkspaces,
  } from "@/api"
  import type { AgentModelConfig, AgentSession, AgentWorkspace } from "@/api/types"

  const auth = useAuthStore()
  const workspaces = ref<AgentWorkspace[]>([])
  const activeWorkspaceId = ref<number | null>(null)
  const sessions = ref<AgentSession[]>([])
  const agentModels = ref<AgentModelConfig[]>([])
  const activeSessionId = ref<number | null>(null)
  const selectedModelConfigId = ref<number | null>(null)
  const sessionDrafts = ref<Record<number, string>>({})
  const sessionsLoading = ref(false)
  const modelsLoading = ref(false)
  const chatPaneRef = ref<InstanceType<typeof AgentChatPane> | null>(null)

  const AGENT_SESSION_SIDEBAR_KEY = "ai_tool_market_agent_session_sidebar_open"
  const AGENT_LAST_SESSION_KEY = "ai_tool_market_agent_last_session_id"
  const AGENT_SELECTED_MODEL_KEY = "ai_tool_market_agent_selected_model_config_id"
  const sessionSidebarOpen = ref(true)
  const deletingSessionId = ref<number | null>(null)
  const deleteSessionError = ref<string | null>(null)
  const selectedAgentModel = computed(() =>
    agentModels.value.find((model) => model.id === selectedModelConfigId.value) ?? agentModels.value[0] ?? null,
  )

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

  function modelLabel(model: AgentModelConfig) {
    return model.displayName || model.modelName || model.configCode || `Model ${model.id}`
  }

  function selectAgentModel(rawId: string) {
    if (!rawId) {
      selectedModelConfigId.value = null
      localStorage.removeItem(AGENT_SELECTED_MODEL_KEY)
      return
    }
    const id = Number(rawId)
    selectedModelConfigId.value = Number.isFinite(id) && id > 0 ? id : null
    if (selectedModelConfigId.value != null) {
      localStorage.setItem(AGENT_SELECTED_MODEL_KEY, String(selectedModelConfigId.value))
    }
  }

  function modelHasRuntimeAuth(model: AgentModelConfig) {
    if (model.provider.toLowerCase() === "mock") return true
    return Boolean(model.apiKeyMasked || model.extraAuthJsonMasked)
  }

  async function loadWorkspaces() {
    if (!auth.token) return
    const res = await fetchAgentWorkspaces({ token: auth.token })
    workspaces.value = res.list
    if (!activeWorkspaceId.value && workspaces.value[0]) {
      activeWorkspaceId.value = workspaces.value[0].id
    }
  }

  async function loadAgentModels() {
    if (!auth.token) return
    modelsLoading.value = true
    try {
      const list = (await fetchAgentModelConfigs({ token: auth.token }))
        .filter((model) => model.enabled !== false && model.agentEnabled !== false && modelHasRuntimeAuth(model))
      agentModels.value = list
      if (!list.length) {
        selectedModelConfigId.value = null
        localStorage.removeItem(AGENT_SELECTED_MODEL_KEY)
        return
      }
      const savedRaw = localStorage.getItem(AGENT_SELECTED_MODEL_KEY)
      const savedId = savedRaw ? Number(savedRaw) : NaN
      const target =
        list.find((model) => model.id === savedId) ??
        list.find((model) => model.isDefault) ??
        list[0]
      selectedModelConfigId.value = target.id
      localStorage.setItem(AGENT_SELECTED_MODEL_KEY, String(target.id))
    } finally {
      modelsLoading.value = false
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

  async function startSession(title = "新的Agent对话") {
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
    void loadAgentModels()
    void loadSessions()
  })
</script>

<template>
  <AppShell title="Agent" description="用自然语言让系统推荐、确认并调用工具">
    <div class="agent-page" :class="{ 'agent-page--session-collapsed': !sessionSidebarOpen }">
      <!-- 侧边栏切换按钮 -->
      <button class="sidebar-toggle-btn" @click="toggleSessionSidebar">
        <ChevronRight v-if="!sessionSidebarOpen" class="h-4 w-4" />
        <ChevronLeft v-else class="h-4 w-4" />
      </button>

      <aside class="agent-sidebar" :class="{ 'agent-sidebar--collapsed': !sessionSidebarOpen }">
        <button class="new-chat" type="button" @click="startSession()">
          <Plus class="h-4 w-4" />
          新会话
        </button>
        <div class="agent-model-picker">
          <label class="agent-model-label" for="agent-model-select">Agent 模型</label>
          <select
            id="agent-model-select"
            class="agent-model-select"
            :value="selectedModelConfigId ?? ''"
            :disabled="modelsLoading || agentModels.length === 0"
            @change="selectAgentModel(($event.target as HTMLSelectElement).value)"
          >
            <option v-if="modelsLoading" value="">加载中...</option>
            <option v-else-if="agentModels.length === 0" value="">暂无可选模型</option>
            <option v-for="model in agentModels" :key="model.id" :value="model.id">
              {{ modelLabel(model) }}
            </option>
          </select>
          <p v-if="selectedAgentModel" class="agent-model-meta">
            {{ selectedAgentModel.provider }} · {{ selectedAgentModel.modelName }}
          </p>
        </div>
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
          :model-config-id="selectedModelConfigId"
          :agent-models="agentModels"
          :models-loading="modelsLoading"
          @update:draft="onDraftUpdate"
          @change-model="selectAgentModel(String($event ?? ''))"
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
    height: calc(100vh - 64px);
    overflow: hidden;
    position: relative;
    background: #000;
  }

  .agent-page--session-collapsed {
    grid-template-columns: 0 minmax(0, 1fr) 320px;
  }

  /* 侧边栏显隐按钮样式 */
  .sidebar-toggle-btn {
    position: absolute;
    left: 10px;
    top: 12px;
    z-index: 10;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 1px solid rgb(255 255 255 / 0.08);
    background: rgb(255 255 255 / 0.055);
    color: rgb(255 255 255 / 0.72);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: 0 16px 36px rgb(0 0 0 / 0.38);
    backdrop-filter: blur(16px);
    transition: transform 0.18s ease, background 0.18s ease, color 0.18s ease;
  }

  .sidebar-toggle-btn:hover {
    transform: translateY(-1px);
    background: rgb(255 255 255 / 0.09);
    color: #fff;
  }

  .agent-sidebar {
    border-right: 0;
    background:
      radial-gradient(circle at 20% 8%, rgb(176 92 255 / 0.10), transparent 28%),
      #121214;
    padding: 16px 12px;
    min-width: 0;
    transition: opacity 0.15s ease, padding 0.15s ease;
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
    display: flex;
    flex-direction: column;
    box-shadow: inset -1px 0 0 rgb(255 255 255 / 0.025);
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
    border-radius: 14px;
  }

  .new-chat {
    width: 100%;
    height: 42px;
    border: 1px solid rgb(176 92 255 / 0.28);
    background: linear-gradient(135deg, rgb(176 92 255 / 0.30), rgb(255 255 255 / 0.055) 54%, rgb(34 211 238 / 0.08));
    color: rgb(255 255 255 / 0.88);
    font-size: 14px;
    font-weight: 700;
    cursor: pointer;
    margin-top: 32px;
    box-shadow: 0 14px 44px rgb(176 92 255 / 0.16), 0 10px 24px rgb(0 0 0 / 0.28);
    transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
  }

  .new-chat:hover {
    border-color: rgb(176 92 255 / 0.54);
    transform: translateY(-1px);
    box-shadow: 0 18px 56px rgb(176 92 255 / 0.24), 0 10px 24px rgb(0 0 0 / 0.32);
  }

  .agent-model-picker {
    margin-top: 12px;
    display: flex;
    flex-direction: column;
    gap: 7px;
    border: 0;
    border-radius: 18px;
    background: rgb(255 255 255 / 0.035);
    padding: 10px;
    box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.035);
  }

  .agent-model-label {
    font-size: 11px;
    font-weight: 700;
    color: rgb(255 255 255 / 0.34);
  }

  .agent-model-select {
    width: 100%;
    min-height: 32px;
    border: 1px solid rgb(255 255 255 / 0.08);
    border-radius: 999px;
    background: rgb(0 0 0 / 0.24);
    color: rgb(255 255 255 / 0.72);
    padding: 0 10px;
    outline: none;
    font-size: 12px;
  }

  .agent-model-select:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }

  .agent-model-meta {
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 11px;
    color: rgb(255 255 255 / 0.32);
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
    border-radius: 16px;
    min-width: 0;
    transition: background 0.18s ease, color 0.18s ease;
  }

  .session-row:hover {
    background: rgb(255 255 255 / 0.045);
  }

  .session-row.active {
    background: rgb(176 92 255 / 0.14);
    box-shadow: inset 0 0 0 1px rgb(176 92 255 / 0.12);
  }

  .session-item {
    flex: 1;
    min-width: 0;
    border: 0;
    background: transparent;
    padding: 10px 6px 10px 10px;
    color: rgb(255 255 255 / 0.42);
    font-size: 13px;
    text-align: left;
    cursor: pointer;
    transition: color 0.18s ease;
  }

  .session-row:hover .session-item,
  .session-row.active .session-item {
    color: rgb(255 255 255 / 0.86);
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
    color: rgb(255 255 255 / 0.32);
    cursor: pointer;
    opacity: 0;
    transform: translateX(4px);
    transition: opacity 0.18s ease, transform 0.18s ease, color 0.18s ease;
  }

  .session-row:hover .session-delete,
  .session-delete:focus-visible,
  .session-delete:disabled {
    opacity: 1;
    transform: translateX(0);
  }

  .session-delete:hover:not(:disabled) {
    color: rgb(252 165 165);
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
    overflow: hidden;
    background: #000;
  }

  .chat-pane-empty {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    color: rgb(255 255 255 / 0.50);
    padding: 28px;
  }

  .empty-mark {
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    border-radius: 22px;
    border: 1px solid rgb(176 92 255 / 0.32);
    background:
      radial-gradient(circle at 65% 25%, rgb(176 92 255 / 0.34), transparent 45%),
      rgb(255 255 255 / 0.045);
    color: rgb(210 170 255);
  }

  .chat-pane-empty h2 {
    margin: 18px 0 8px;
    font-size: 24px;
    color: #fff;
  }

  .chat-pane-empty-btn {
    margin-top: 22px;
    height: 40px;
    padding: 0 16px;
    border: 1px solid rgb(176 92 255 / 0.36);
    background: rgb(176 92 255 / 0.24);
    color: #fff;
    font-size: 14px;
    cursor: pointer;
  }

  .agent-memory-panel {
    min-width: 0;
    min-height: 0;
    height: 100%;
    overflow: hidden;
    background: #101012;
    box-shadow: inset 1px 0 0 rgb(255 255 255 / 0.025);
  }

  :deep(.workspace-memory-panel) {
    border-left: 0;
    background: transparent;
  }

  @media (max-width: 900px) {
    .agent-page {
      grid-template-columns: minmax(140px, 36vw) minmax(0, 1fr);
      grid-template-rows: minmax(0, 1fr) auto;
      height: calc(100vh - 64px);
      overflow: hidden;
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
