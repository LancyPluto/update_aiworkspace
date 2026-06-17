<script setup lang="ts">
  import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
  import { useRoute, useRouter } from "vue-router"
  import { ChevronLeft, ChevronRight, Loader2, Plus, Sparkles, Trash2 } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  import AgentChatPane from "./AgentChatPane.vue"
  import AgentThemePicker from "./AgentThemePicker.vue"
  import { confirmDelete } from "@/composables/useConfirmDelete"
  import { useAuthStore } from "@/store/authStore"
  import {
    createAgentSession,
    deleteAgentSession,
    fetchAgentModelConfigs,
    fetchAgentSessions,
  } from "@/api"
  import type { AgentModelConfig, AgentSession } from "@/api/types"
  import { applyStoredAgentTheme } from "@/utils/agentTheme"

  const auth = useAuthStore()
  const route = useRoute()
  const router = useRouter()
  const sessions = ref<AgentSession[]>([])
  const agentModels = ref<AgentModelConfig[]>([])
  const activeSessionId = ref<number | null>(null)
  const selectedModelConfigId = ref<number | null>(null)
  const sessionDrafts = ref<Record<number, string>>({})
  const sessionsLoading = ref(false)
  const modelsLoading = ref(false)
  const chatPaneRef = ref<InstanceType<typeof AgentChatPane> | null>(null)
  let modelRefreshTimer: number | null = null

  const AGENT_SESSION_SIDEBAR_KEY = "ai_tool_market_agent_session_sidebar_open"
  const AGENT_LAST_SESSION_KEY = "ai_tool_market_agent_last_session_id"
  const AGENT_SELECTED_MODEL_KEY = "ai_tool_market_agent_selected_model_config_id"
  const sessionSidebarOpen = ref(true)
  const deletingSessionId = ref<number | null>(null)
  const deleteSessionError = ref<string | null>(null)

  const groupedSessions = computed(() => {
    const map = new Map<string, AgentSession[]>()
    for (const session of sessions.value) {
      const label = sessionTimeGroup(session.updatedAt || session.createdAt)
      const list = map.get(label) ?? []
      list.push(session)
      map.set(label, list)
    }
    return ["今天", "昨天", "前 7 天", "更早"]
      .map((label) => ({ label, sessions: map.get(label) ?? [] }))
      .filter((group) => group.sessions.length > 0)
  })

  function sessionTimeGroup(value?: string | null) {
    if (!value) return "更早"
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return "更早"
    const now = new Date()
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const startOfTarget = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
    const diffDays = Math.floor((startOfToday - startOfTarget) / 86400000)
    if (diffDays <= 0) return "今天"
    if (diffDays === 1) return "昨天"
    if (diffDays <= 7) return "前 7 天"
    return "更早"
  }

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

  async function loadAgentModels() {
    if (!auth.token) return
    modelsLoading.value = true
    try {
      const list = (await fetchAgentModelConfigs({ token: auth.token }))
        .filter((model) => model.enabled !== false && model.agentEnabled !== false)
      agentModels.value = list
      if (!list.length) {
        selectedModelConfigId.value = null
        localStorage.removeItem(AGENT_SELECTED_MODEL_KEY)
        return
      }
      const savedRaw = localStorage.getItem(AGENT_SELECTED_MODEL_KEY)
      const savedId = savedRaw ? Number(savedRaw) : NaN
      const currentId = selectedModelConfigId.value
      const target =
        list.find((model) => model.id === currentId) ??
        list.find((model) => model.id === savedId) ??
        list.find((model) => model.isDefault) ??
        list[0]
      selectedModelConfigId.value = target.id
      localStorage.setItem(AGENT_SELECTED_MODEL_KEY, String(target.id))
    } finally {
      modelsLoading.value = false
    }
  }

  function refreshAgentModelsInBackground() {
    if (modelsLoading.value) return
    void loadAgentModels()
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

  async function startSession(title = "新对话") {
    if (!auth.token) return null
    const session = await createAgentSession({ title }, { token: auth.token })
    sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
    if (sessionDrafts.value[session.id] === undefined) {
      sessionDrafts.value[session.id] = ""
    }
    selectSession(session.id)
    return session
  }

  function routePrompt() {
    const raw = route.query.prompt
    return typeof raw === "string" ? raw.trim() : ""
  }

  async function ensureActiveSession() {
    if (activeSessionId.value) return activeSessionId.value
    if (sessions.value.length > 0) {
      selectSession(sessions.value[0].id)
      return activeSessionId.value
    }
    const session = await startSession("新对话")
    return session?.id ?? null
  }

  async function applyRoutePrompt() {
    const prompt = routePrompt()
    if (!prompt || !auth.token) return

    const sessionId = await ensureActiveSession()
    if (!sessionId) return

    sessionDrafts.value[sessionId] = prompt

    const nextQuery = { ...route.query }
    delete nextQuery.prompt
    void router.replace({ query: nextQuery })
  }

  async function removeSession(session: AgentSession, event: MouseEvent) {
    event.stopPropagation()
    if (!auth.token || deletingSessionId.value != null) return
    const pane = chatPaneRef.value
    if (session.id === activeSessionId.value && pane?.hasActiveRun) {
      pane.showError("当前会话 Agent 仍在运行，请稍后再删除。")
      return
    }
    const confirmed = await confirmDelete({
      title: "删除会话",
      itemName: session.title,
    })
    if (!confirmed) return
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
    void nextTick(() => applyStoredAgentTheme())
    void (async () => {
      await loadAgentModels()
      await loadSessions()
      await applyRoutePrompt()
    })()
    modelRefreshTimer = window.setInterval(refreshAgentModelsInBackground, 15000)
    window.addEventListener("focus", refreshAgentModelsInBackground)
    document.addEventListener("visibilitychange", refreshAgentModelsInBackground)
  })

  watch(
    () => route.query.prompt,
    () => {
      void applyRoutePrompt()
    },
  )

  onUnmounted(() => {
    if (modelRefreshTimer != null) {
      window.clearInterval(modelRefreshTimer)
      modelRefreshTimer = null
    }
    window.removeEventListener("focus", refreshAgentModelsInBackground)
    document.removeEventListener("visibilitychange", refreshAgentModelsInBackground)
  })
</script>

<template>
  <AppShell title="智能对话" description="用自然语言让系统推荐、确认并调用工具">
    <div class="agent-page" :class="{ 'agent-page--session-collapsed': !sessionSidebarOpen }">
      <button class="sidebar-toggle-btn" type="button" @click="toggleSessionSidebar">
        <ChevronRight v-if="!sessionSidebarOpen" class="h-4 w-4" />
        <ChevronLeft v-else class="h-4 w-4" />
      </button>

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
          <section v-for="group in groupedSessions" :key="group.label" class="session-group">
            <p class="session-group-label">{{ group.label }}</p>
            <div
              v-for="session in group.sessions"
              :key="session.id"
              class="session-row"
              :class="{ active: session.id === activeSessionId }"
            >
              <button type="button" class="session-item" @click="selectSession(session.id)">
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
          </section>
        </div>

        <AgentThemePicker />
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
    </div>
  </AppShell>
</template>

<style scoped>
  .agent-page {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr);
    height: 100%;
    max-height: 100%;
    min-height: 0;
    overflow: hidden;
    position: relative;
    background: #0a0a0d;
  }

  .agent-page--session-collapsed {
    grid-template-columns: 0 minmax(0, 1fr);
  }

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
      radial-gradient(circle at 20% 8%, var(--agent-bg-mesh-1, rgb(176 92 255 / 0.10)), transparent 28%),
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
    scrollbar-width: thin;
    scrollbar-color: rgb(255 255 255 / 0.14) transparent;
  }

  .agent-sidebar::-webkit-scrollbar {
    width: 4px;
  }

  .agent-sidebar::-webkit-scrollbar-track {
    background: transparent;
  }

  .agent-sidebar::-webkit-scrollbar-thumb {
    border-radius: 999px;
    background: rgb(255 255 255 / 0.10);
  }

  .agent-sidebar:hover::-webkit-scrollbar-thumb {
    background: rgb(255 255 255 / 0.18);
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
    border: 1px solid var(--agent-accent-soft);
    background: linear-gradient(135deg, var(--agent-accent-soft), rgb(255 255 255 / 0.055) 54%, var(--agent-bg-mesh-2));
    color: rgb(255 255 255 / 0.88);
    font-size: 14px;
    font-weight: 700;
    cursor: pointer;
    margin-top: 32px;
    box-shadow: 0 14px 44px var(--agent-accent-glow), 0 10px 24px rgb(0 0 0 / 0.28);
    transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
  }

  .new-chat:hover {
    border-color: var(--agent-accent);
    transform: translateY(-1px);
    box-shadow: 0 18px 56px var(--agent-accent-glow), 0 10px 24px rgb(0 0 0 / 0.32);
  }

  .sidebar-error {
    margin: 10px 0 0;
    font-size: 12px;
    color: #fca5a5;
  }

  .session-list-loading {
    margin-top: 14px;
    display: flex;
    justify-content: center;
    color: var(--muted-foreground);
  }

  .session-list {
    margin-top: 18px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    flex: 1;
    min-height: 0;
  }

  .session-group-label {
    margin: 2px 8px 6px;
    color: rgb(255 255 255 / 0.28);
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0;
  }

  .session-row {
    display: flex;
    align-items: stretch;
    gap: 2px;
    border-radius: 14px;
    min-width: 0;
    transition: background 0.18s ease, color 0.18s ease;
  }

  .session-row:hover {
    background: rgb(255 255 255 / 0.035);
  }

  .session-row.active {
    background: rgb(255 255 255 / 0.052);
    box-shadow: inset 2px 0 0 var(--agent-accent);
  }

  .session-item {
    flex: 1;
    min-width: 0;
    border: 0;
    background: transparent;
    padding: 10px 8px 10px 14px;
    color: rgb(255 255 255 / 0.42);
    font-size: 13px;
    text-align: left;
    cursor: pointer;
    transition: color 0.18s ease;
  }

  .session-row:hover .session-item {
    color: rgb(255 255 255 / 0.72);
  }

  .session-row.active .session-item {
    color: rgb(255 255 255 / 0.92);
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
    background: #0a0a0d;
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
    border: 1px solid var(--agent-accent-soft);
    background:
      radial-gradient(circle at 65% 25%, var(--agent-accent-soft), transparent 45%),
      rgb(255 255 255 / 0.045);
    color: var(--agent-accent-light);
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
    border: 1px solid var(--agent-accent-soft);
    background: var(--agent-accent-soft);
    color: #fff;
    font-size: 14px;
    cursor: pointer;
  }

  @media (max-width: 900px) {
    .agent-page {
      grid-template-columns: minmax(140px, 36vw) minmax(0, 1fr);
      height: 100%;
      overflow: hidden;
    }

    .agent-page--session-collapsed {
      grid-template-columns: 0 minmax(0, 1fr);
    }
  }
</style>
