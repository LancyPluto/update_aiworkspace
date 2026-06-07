<script setup lang="ts">
import { computed } from "vue"
import { Check, Copy, FileText, Image, Loader2, Pencil, RefreshCw, X } from "lucide-vue-next"
import ChatMessage from "./ChatMessage.vue"
import AgentAvatar from "./AgentAvatar.vue"
import RunTimeline from "./RunTimeline.vue"
import type { AgentAvatarState } from "./AgentAvatar.vue"
import type { AgentMessage, AgentRunEvent } from "@/api/types"
import type { AssetPreviewItem } from "@/types/assetPreview"
import type { ChatAssetRef } from "@/utils/agentChatAssetRefs"
import { bindLongPressReference, writeAssetDragData } from "@/utils/agentChatAssetRefs"
import { isImageAttachment, resolveAgentFileUrl } from "@/utils/agentAttachment"

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
  assetRefMap?: Map<string, ChatAssetRef>
}>()

const emit = defineEmits<{
  copy: [message: AgentMessage]
  "start-edit": [message: AgentMessage]
  "cancel-edit": []
  "submit-edit": [message: AgentMessage]
  regenerate: [message: AgentMessage]
  preview: [asset: AssetPreviewItem, message?: AgentMessage]
  reference: [payload: import("@/utils/agentChatAssetRefs").ChatAssetDragPayload]
  "update:editingMessageDraft": [value: string]
}>()

interface MessageAttachment {
  id: number | string
  name: string
  contentType?: string | null
  size?: number | null
  url?: string | null
  downloadUrl?: string | null
  status?: string | null
  source?: string | null
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
  const hasId = typeof item.id === "number" || typeof item.id === "string"
  const hasUrl = typeof item.url === "string" || typeof item.downloadUrl === "string"
  return hasId && typeof item.name === "string" && (hasUrl || item.source === "agent_file")
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

function isImageMessageAttachment(file: MessageAttachment) {
  return isImageAttachment(file.contentType, file.name)
}

function attachmentPreviewUrl(file: MessageAttachment) {
  return resolveAgentFileUrl(file.url || file.downloadUrl)
}

function resolveChatAsset(url?: string | null) {
  if (!url || !props.assetRefMap) return undefined
  return props.assetRefMap.get(url) || props.assetRefMap.get(resolveAgentFileUrl(url) || url)
}

function onAttachmentDragStart(event: DragEvent, file: MessageAttachment) {
  const url = attachmentPreviewUrl(file)
  if (!url) return
  const asset = resolveChatAsset(url)
  if (!asset) return
  writeAssetDragData(event, asset)
}

function bindAttachmentLongPress(element: HTMLElement | null, file: MessageAttachment) {
  const url = attachmentPreviewUrl(file)
  if (!element || !url) return
  const asset = resolveChatAsset(url)
  if (!asset) return
  bindLongPressReference(element, asset, (payload) => emit("reference", payload))
}

function openAttachmentPreview(file: MessageAttachment) {
  const url = attachmentPreviewUrl(file)
  if (!url) return
  emit("preview", {
    id: `attachment-${file.id}`,
    kind: "image",
    title: "图片附件",
    url,
  }, props.message)
}
</script>

<template>
  <article
    :class="['agent-message-row', message.role === 'USER' ? 'user' : 'assistant']"
    :data-message-id="message.id"
  >
    <div class="message-main">
      <div class="bubble" :class="{ 'bubble--streaming': isStreaming }">
        <div v-if="message.role !== 'USER'" class="assistant-name-row">
          <AgentAvatar :state="avatarState ?? 'idle'" size="sm" />
          <strong>科创点AI</strong>
        </div>
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
            <article
              v-for="file in attachments"
              :key="file.id"
              :ref="(el) => bindAttachmentLongPress(el as HTMLElement | null, file)"
              class="message-attachment-card"
              :class="{
                'message-attachment-card--image': isImageMessageAttachment(file),
                'chat-asset-draggable': !!resolveChatAsset(attachmentPreviewUrl(file)),
              }"
              :draggable="!!resolveChatAsset(attachmentPreviewUrl(file))"
              @dragstart="onAttachmentDragStart($event, file)"
            >
              <button
                v-if="isImageMessageAttachment(file) && attachmentPreviewUrl(file)"
                type="button"
                class="attachment-thumb-btn"
                aria-label="预览图片"
                @click="openAttachmentPreview(file)"
              >
                <img
                  :src="attachmentPreviewUrl(file)"
                  :alt="file.name"
                  class="attachment-thumb"
                  loading="lazy"
                />
              </button>
              <template v-else>
                <span class="attachment-icon">
                  <Image v-if="isImageMessageAttachment(file)" class="h-5 w-5" />
                  <FileText v-else class="h-5 w-5" />
                </span>
                <span class="attachment-copy">
                  <strong>{{ file.name }}</strong>
                  <small>{{ file.contentType || "FILE" }} {{ formatFileSize(file.size) }}</small>
                </span>
              </template>
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
            :resolve-chat-asset="resolveChatAsset"
            :enable-asset-drag="message.role === 'ASSISTANT'"
            @preview="(asset) => emit('preview', asset, message)"
            @reference="(payload) => emit('reference', payload)"
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
  gap: 8px;
  width: fit-content;
  min-height: 0;
  margin-bottom: 6px;
}

.assistant-name-row :deep(.agent-avatar--sm) {
  width: 24px;
  height: 24px;
}

.assistant-name-row :deep(.agent-avatar__logo) {
  width: 16px;
  height: 16px;
}

.assistant-name-row strong {
  display: block;
  font-size: 16px;
  background: linear-gradient(120deg, rgb(255 255 255 / 0.96), rgb(154 232 255), rgb(180 255 209));
  background-clip: text;
  color: transparent;
  font-weight: 800;
  line-height: 1.2;
}

.bubble {
  width: fit-content;
  max-width: 100%;
  border: 1px solid rgb(255 255 255 / 0.075);
  border-radius: 24px 24px 24px 10px;
  background:
    radial-gradient(circle at 8% 0%, var(--agent-bubble-assistant-tint), transparent 34%),
    linear-gradient(180deg, rgb(255 255 255 / 0.045), rgb(255 255 255 / 0.022));
  padding: 14px 16px;
  font-size: 18px;
  line-height: 1.75;
  color: var(--agent-text-primary);
  box-shadow: 0 18px 50px rgb(0 0 0 / 0.22), inset 0 1px 0 rgb(255 255 255 / 0.04);
  backdrop-filter: blur(10px);
  position: relative;
  overflow: hidden;
}

.message-attachments {
  display: flex;
  flex-wrap: wrap;
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

.message-attachment-card--image {
  min-width: 0;
  padding: 6px;
  border: 0;
  background: transparent;
}

.attachment-thumb-btn {
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: zoom-in;
  border-radius: 12px;
  overflow: hidden;
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
  width: 56px;
  height: 56px;
  border-radius: 12px;
  object-fit: cover;
  display: block;
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
  border: 1px solid rgb(255 255 255 / 0.11);
  background:
    radial-gradient(circle at 18% 10%, rgb(255 255 255 / 0.08), transparent 44%),
    rgb(255 255 255 / 0.105);
  border-radius: 24px 10px 24px 24px;
  padding: 11px 15px;
  color: rgb(255 255 255 / 0.94);
  box-shadow: 0 16px 42px rgb(0 0 0 / 0.20), inset 0 1px 0 rgb(255 255 255 / 0.055);
  backdrop-filter: blur(12px);
}

.agent-message-row.assistant .bubble:has(.agent-result-renderer) {
  width: min(820px, 100%);
  border-radius: 24px 24px 24px 10px;
  padding: 10px;
  background:
    radial-gradient(circle at 8% 0%, var(--agent-bubble-assistant-tint), transparent 34%),
    linear-gradient(180deg, rgb(255 255 255 / 0.045), rgb(255 255 255 / 0.022));
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
.chat-asset-draggable {
  cursor: grab;
}

.chat-asset-draggable:active {
  cursor: grabbing;
  opacity: 0.88;
}
</style>
