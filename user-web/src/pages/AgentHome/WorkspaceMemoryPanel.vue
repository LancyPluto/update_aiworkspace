<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { Edit3, Loader2, RefreshCcw, Save, Trash2, X } from "lucide-vue-next"
import {
  deleteAgentWorkspaceMemory,
  fetchAgentWorkspaceMemory,
  updateAgentWorkspaceMemory,
} from "@/api"
import type { AgentWorkspaceMemoryItem, UpdateAgentWorkspaceMemoryRequest } from "@/api/types"

const props = defineProps<{
  workspaceId: number | null
}>()

const items = ref<AgentWorkspaceMemoryItem[]>([])
const loading = ref(false)
const savingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)
const editingId = ref<number | null>(null)
const errorMessage = ref<string | null>(null)
const draft = ref<UpdateAgentWorkspaceMemoryRequest>({
  memoryType: "",
  title: "",
  content: "",
})

const canLoad = computed(() => Boolean(props.workspaceId))

watch(
  () => props.workspaceId,
  () => {
    void loadMemory()
  },
  { immediate: true },
)

async function loadMemory() {
  if (!canLoad.value || !props.workspaceId) {
    items.value = []
    return
  }

  loading.value = true
  errorMessage.value = null
  try {
    const res = await fetchAgentWorkspaceMemory(props.workspaceId)
    items.value = res.list
  } catch (error) {
    errorMessage.value = formatError(error)
  } finally {
    loading.value = false
  }
}

function startEdit(item: AgentWorkspaceMemoryItem) {
  editingId.value = item.id
  draft.value = {
    memoryType: item.memoryType,
    title: item.title,
    content: item.content,
  }
}

function cancelEdit() {
  editingId.value = null
  draft.value = { memoryType: "", title: "", content: "" }
}

async function saveMemory(item: AgentWorkspaceMemoryItem) {
  if (!props.workspaceId || savingId.value) return

  savingId.value = item.id
  errorMessage.value = null
  try {
    const updated = await updateAgentWorkspaceMemory(props.workspaceId, item.id, draft.value)
    items.value = items.value.map((current) => (current.id === updated.id ? updated : current))
    cancelEdit()
  } catch (error) {
    errorMessage.value = formatError(error)
  } finally {
    savingId.value = null
  }
}

async function deleteMemory(item: AgentWorkspaceMemoryItem) {
  if (!props.workspaceId || deletingId.value) return
  if (!window.confirm(`Delete "${item.title}"?`)) return

  deletingId.value = item.id
  errorMessage.value = null
  try {
    await deleteAgentWorkspaceMemory(props.workspaceId, item.id)
    items.value = items.value.filter((current) => current.id !== item.id)
    if (editingId.value === item.id) cancelEdit()
  } catch (error) {
    errorMessage.value = formatError(error)
  } finally {
    deletingId.value = null
  }
}

function formatError(error: unknown) {
  return error instanceof Error ? error.message : "Workspace memory request failed"
}
</script>

<template>
  <aside class="workspace-memory-panel">
    <header class="panel-header">
      <div>
        <p class="eyebrow">Workspace</p>
        <h2>Memory</h2>
      </div>
      <button type="button" class="icon-btn" :disabled="loading || !canLoad" title="Refresh" @click="loadMemory">
        <Loader2 v-if="loading" class="h-4 w-4 animate-spin" />
        <RefreshCcw v-else class="h-4 w-4" />
      </button>
    </header>

    <p v-if="errorMessage" class="panel-error">{{ errorMessage }}</p>

    <div v-if="loading && items.length === 0" class="panel-state">
      <Loader2 class="h-4 w-4 animate-spin" />
    </div>

    <div v-else-if="items.length === 0" class="panel-state">
      No workspace memory yet
    </div>

    <div v-else class="memory-list">
      <article v-for="item in items" :key="item.id" class="memory-item">
        <template v-if="editingId === item.id">
          <input v-model.trim="draft.memoryType" class="field compact" maxlength="32" aria-label="Memory type" />
          <input v-model.trim="draft.title" class="field" maxlength="160" aria-label="Memory title" />
          <textarea v-model.trim="draft.content" class="field content-field" rows="4" aria-label="Memory content" />
          <div class="item-actions">
            <button type="button" class="icon-btn" :disabled="savingId === item.id" title="Save" @click="saveMemory(item)">
              <Loader2 v-if="savingId === item.id" class="h-4 w-4 animate-spin" />
              <Save v-else class="h-4 w-4" />
            </button>
            <button type="button" class="icon-btn" title="Cancel" @click="cancelEdit">
              <X class="h-4 w-4" />
            </button>
          </div>
        </template>

        <template v-else>
          <div class="item-head">
            <span class="type-chip">{{ item.memoryType }}</span>
            <span class="status-text">{{ item.status }}</span>
          </div>
          <h3>{{ item.title }}</h3>
          <p>{{ item.content }}</p>
          <div class="item-actions">
            <button type="button" class="icon-btn" title="Edit" @click="startEdit(item)">
              <Edit3 class="h-4 w-4" />
            </button>
            <button
              type="button"
              class="icon-btn danger"
              :disabled="deletingId === item.id"
              title="Delete"
              @click="deleteMemory(item)"
            >
              <Loader2 v-if="deletingId === item.id" class="h-4 w-4 animate-spin" />
              <Trash2 v-else class="h-4 w-4" />
            </button>
          </div>
        </template>
      </article>
    </div>
  </aside>
</template>

<style scoped>
.workspace-memory-panel {
  min-width: 0;
  border-left: 1px solid var(--border);
  background: var(--card);
  padding: 16px;
  overflow-y: auto;
}

.panel-header,
.item-head,
.item-actions {
  display: flex;
  align-items: center;
}

.panel-header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.eyebrow {
  margin: 0 0 2px;
  color: var(--muted-foreground);
  font-size: 11px;
  text-transform: uppercase;
}

.panel-header h2 {
  margin: 0;
  font-size: 16px;
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: inline-grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--background);
  color: var(--foreground);
}

.icon-btn:disabled {
  opacity: 0.5;
}

.icon-btn.danger {
  color: #b42318;
}

.panel-error {
  border: 1px solid #fecdca;
  border-radius: 8px;
  background: #fffbfa;
  color: #b42318;
  padding: 10px;
  font-size: 12px;
}

.panel-state {
  min-height: 160px;
  display: grid;
  place-items: center;
  color: var(--muted-foreground);
  font-size: 13px;
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.memory-item {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--background);
  padding: 12px;
}

.item-head {
  justify-content: space-between;
  gap: 8px;
}

.type-chip {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 8px;
  color: var(--muted-foreground);
  font-size: 11px;
}

.status-text {
  color: var(--muted-foreground);
  font-size: 11px;
}

.memory-item h3 {
  margin: 10px 0 6px;
  font-size: 14px;
  line-height: 1.35;
}

.memory-item p {
  margin: 0;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.item-actions {
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.field {
  width: 100%;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--foreground);
  font: inherit;
  font-size: 13px;
  padding: 8px 10px;
  outline: 0;
}

.field + .field {
  margin-top: 8px;
}

.field.compact {
  font-size: 12px;
}

.content-field {
  resize: vertical;
  line-height: 1.5;
}
</style>
