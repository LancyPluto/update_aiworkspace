<script setup lang="ts">
import { computed } from "vue"
import { Check, Copy, FileText, Image, Loader2, Pencil, RefreshCw, X } from "lucide-vue-next"
import ChatMessage from "./ChatMessage.vue"
import AgentAvatar from "./AgentAvatar.vue"
import RunTimeline from "./RunTimeline.vue"
import type { AgentAvatarState } from "./AgentAvatar.vue"
import type { AgentMessage, AgentRunEvent } from "@/api/types"
import type { AssetPreviewItem } from "@/types/assetPreview"

const props = defineProps<{
  message: AgentMessage
  index: number
  runEvents: AgentRunEvent[]
  userAvatarUrl?: string | null
  userDisplayName: string
  editingMessageId: number | null
  editingMessageDraft: string
  editingRegenerating: boolean
  copiedMessageId: number | null
  regeneratingMessageId: number | null
  hasActiveRun: boolean
  sending: boolean
  modelsLoading: boolean
  modelConfigId?: number | null
  avatarState?: AgentAvatarState
  isStreaming?: boolean
}>()

const emit = defineEmits<{
  copy: [message: AgentMessage]
  "start-edit": [message: AgentMessage]
  "cancel-edit": []
  "submit-edit": [message: AgentMessage]
  regenerate: [message: AgentMessage]
  preview: [asset: AssetPreviewItem]
  "update:editingMessageDraft": [value: string]
}>()

interface MessageAttachment {
  id: number
  name: string
  contentType?: string | null
  size?: number | null
  url?: string | null
  status?: string | null
}

function parseMessageJson(value?: string | null) {
  if (!value) return {} as Record<string, unknown>
  try {
    const parsed = JSON.parse(value) as unknown
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

function isAttachment(value: unknown): value is MessageAttachment {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false
  const item = value as Record<string, unknown>
  return typeof item.id === "number" && typeof item.name === "string"
}

const attachments = computed(() => {
  const payload = parseMessageJson(props.message.contentJson)
  const raw = payload.attachments
  return Array.isArray(raw) ? raw.filter(isAttachment) : []
})

function formatFileSize(size?: number | null) {
  if (size == null || !Number.isFinite(size)) return ""
  if (size < 1024) return `${size}B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`
  return `${(size / 1024 / 1024).toFixed(1)}MB`
}

function isImageAttachment(file: MessageAttachment) {
  return (file.contentType || "").toLowerCase().startsWith("image/")
}
</script>

<template>
  <article
    :class="['agent-message-row', message.role === 'USER' ? 'user' : 'assistant']"
    :data-message-id="message.id"
  >
    <div class="message-main">
      <div v-if="message.role !== 'USER'" class="assistant-name-row">
        <AgentAvatar :state="avatarState ?? 'idle'" />
        <div>
          <strong>科创点AI</strong>
        </div>
      </div>
      <div class="bubble" :class="{ 'bubble--streaming': isStreaming }">
        <div v-if="editingMessageId === message.id" class="message-edit-box">
          <textarea
            :value="editingMessageDraft"
            class="message-edit-input"
            rows="3"
            :disabled="editingRegenerating"
            @input="emit('update:editingMessageDraft', ($event.target as HTMLTextAreaElement).value)"
            @keydown.enter.exact.prevent="emit('submit-edit', message)"
            @keydown.esc.prevent="emit('cancel-edit')"
          />
          <div class="message-edit-actions">
            <button
              type="button"
              class="message-action-btn"
              title="取消"
              aria-label="取消修改"
              :disabled="editingRegenerating"
              @click="emit('cancel-edit')"
            >
              <X class="h-4 w-4" />
            </button>
            <button
              type="button"
              class="message-action-btn primary"
              title="保存并重新发送"
              aria-label="保存并重新发送"
              :disabled="editingRegenerating || !editingMessageDraft.trim()"
              @click="emit('submit-edit', message)"
            >
              <Loader2 v-if="editingRegenerating" class="h-4 w-4 animate-spin" />
              <Check v-else class="h-4 w-4" />
            </button>
          </div>
        </div>
        <template v-else>
          <div v-if="message.role === 'USER' && attachments.length" class="message-attachments">
            <article v-for="file in attachments" :key="file.id" class="message-attachment-card">
              <span class="attachment-icon">
                <img
                  v-if="isImageAttachment(file) && file.url"
                  :src="file.url"
                  :alt="file.name"
                  class="attachment-thumb"
                  loading="lazy"
                />
                <Image v-else-if="isImageAttachment(file)" class="h-5 w-5" />
                <FileText v-else class="h-5 w-5" />
              </span>
              <span class="attachment-copy">
                <strong>{{ file.name }}</strong>
                <small>{{ file.contentType || "FILE" }} {{ formatFileSize(file.size) }}</small>
              </span>
            </article>
          </div>
          <RunTimeline
            v-if="message.role === 'ASSISTANT' && runEvents.length > 0"
            :events="runEvents"
            :inline-mode="true"
            :process-mode="true"
          />
          <ChatMessage
            v-if="message.contentText.trim() || message.role !== 'USER'"
            :message="message.contentText"
            :is-user="message.role === 'USER'"
            :streaming="isStreaming"
            @preview="emit('preview', $event)"
          />
        </template>
      </div>
      <div class="message-actions" :class="{ 'message-actions--user': message.role === 'USER' }">
        <button
          type="button"
          class="message-action-btn"
          :title="copiedMessageId === message.id ? '已复制' : '复制'"
          :aria-label="copiedMessageId === message.id ? '已复制' : '复制消息'"
          @click="emit('copy', message)"
        >
          <Check v-if="copiedMessageId === message.id" class="h-4 w-4" />
          <Copy v-else class="h-4 w-4" />
        </button>
        <button
          v-if="message.role === 'USER'"
          type="button"
          class="message-action-btn"
          title="修改"
          aria-label="修改消息"
          :disabled="hasActiveRun || sending || editingRegenerating || regeneratingMessageId != null"
          @click="emit('start-edit', message)"
        >
          <Pencil class="h-4 w-4" />
        </button>
        <button
          v-if="message.role === 'ASSISTANT' && message.runId"
          type="button"
          class="message-action-btn"
          title="重新生成"
          aria-label="重新生成回复"
          :disabled="
            hasActiveRun ||
            sending ||
            editingRegenerating ||
            regeneratingMessageId != null ||
            modelsLoading ||
            !modelConfigId
          "
          @click="emit('regenerate', message)"
        >
          <Loader2 v-if="regeneratingMessageId === message.id" class="h-4 w-4 animate-spin" />
          <RefreshCw v-else class="h-4 w-4" />
        </button>
      </div>
      <div v-if="message.role === 'USER' && message.editedAt" class="message-meta message-meta--user">
        已编辑
      </div>
    </div>
  </article>
</template>

<style scoped>
.agent-message-row {
  display: flex;
  justify-content: flex-start;
  margin: 30px auto;
  width: min(100%, 980px);
  max-width: 980px;
  animation: message-rise 0.24s ease-out;
}

.agent-message-row.user {
  justify-content: flex-end;
}

.message-main {
  position: relative;
  min-width: 0;
  width: fit-content;
  max-width: 100%;
}

.agent-message-row.user .message-main {
  margin-left: auto;
}

.agent-message-row.assistant .message-main {
  margin-left: -4px;
}

.assistant-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 50px;
  margin-bottom: 10px;
}

.assistant-name-row :deep(.agent-avatar--md) {
  width: 50px;
  height: 50px;
}

.assistant-name-row :deep(.agent-avatar__logo) {
  width: 28px;
  height: 28px;
}

.assistant-name-row strong {
  display: block;
  color: var(--agent-text-primary);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.2;
}

.bubble {
  width: fit-content;
  max-width: 100%;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding: 0;
  font-size: 18px;
  line-height: 1.75;
  color: var(--agent-text-primary);
  box-shadow: none;
  backdrop-filter: none;
  position: relative;
  overflow: hidden;
}

.message-attachments {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.message-attachment-card {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: min(240px, 72vw);
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.06);
  padding: 9px 11px;
}

.attachment-icon {
  width: 32px;
  height: 32px;
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  background: rgb(96 165 250 / 0.18);
  color: rgb(147 197 253);
}

.attachment-thumb {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  object-fit: cover;
}

.attachment-copy {
  min-width: 0;
  display: grid;
  line-height: 1.25;
}

.attachment-copy strong,
.attachment-copy small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-copy strong {
  color: var(--agent-text-primary);
  font-size: 13px;
}

.attachment-copy small {
  margin-top: 2px;
  color: var(--agent-text-muted);
  font-size: 11px;
}

.bubble--streaming::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--agent-accent), transparent);
  opacity: 0.7;
}

.agent-message-row.user .bubble {
  border: 1px solid rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.06);
  border-radius: 16px 6px 16px 16px;
  padding: 10px 13px;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.04);
}

.agent-message-row.assistant .bubble:has(.agent-result-renderer) {
  width: min(820px, 100%);
  border-radius: 12px;
  padding: 8px;
  background: rgb(255 255 255 / 0.025);
}

.message-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  min-height: 28px;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.agent-message-row:hover .message-actions,
.agent-message-row:focus-within .message-actions {
  opacity: 1;
}

.message-actions--user {
  justify-content: flex-end;
}

.message-meta {
  margin-top: 2px;
  color: rgb(255 255 255 / 0.34);
  font-size: 12px;
}

.message-meta--user {
  text-align: right;
}

.message-action-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.52);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.message-action-btn:hover:not(:disabled) {
  border-color: var(--agent-accent-soft);
  background: var(--agent-accent-soft);
  color: #fff;
  transform: translateY(-1px);
}

.message-action-btn.primary {
  border-color: var(--agent-accent-soft);
  background: var(--agent-accent-soft);
  color: #fff;
}

.message-action-btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.message-edit-box {
  display: grid;
  gap: 10px;
  width: min(640px, 68vw);
}

.message-edit-input {
  width: 100%;
  min-height: 92px;
  max-height: 260px;
  resize: none;
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 16px;
  background: rgb(0 0 0 / 0.24);
  color: rgb(255 255 255 / 0.9);
  outline: none;
  padding: 10px 12px;
  font-size: 15px;
  line-height: 1.6;
}

.message-edit-input:focus {
  border-color: var(--agent-accent-soft);
  box-shadow: 0 0 0 3px var(--agent-accent-soft);
}

.message-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@keyframes message-rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 720px) {
  .agent-message-row {
    margin: 20px auto;
  }
  .message-actions {
    opacity: 1;
  }
}
</style>
