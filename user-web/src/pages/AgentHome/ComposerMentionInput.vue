<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from "vue"
import {
  clearEditor,
  editorIsEmpty,
  getAtQueryAtCaret,
  insertMentionChip,
  insertPlainTextAtCaret,
  mentionFromChipElement,
  removeMentionsByAssetKeys,
  removeMentionBeforeCaret,
  removeSelectedMentionChips,
  serializeEditor,
} from "@/utils/agentComposerMentionEditor"
import type { ComposerEditorSnapshot } from "@/utils/agentComposerMentionEditor"
import type { AgentReferenceMention } from "@/utils/agentReferenceMentions"

const props = defineProps<{
  modelValue: string
  referenceMentions: AgentReferenceMention[]
  disabled?: boolean
  expanded?: boolean
  placeholder?: string
  reducedMotion?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: string]
  "update:referenceMentions": [value: AgentReferenceMention[]]
  input: []
  atQuery: [query: string]
  closeAtQuery: []
  keydown: [event: KeyboardEvent]
  enter: [event: KeyboardEvent]
  paste: [event: ClipboardEvent]
  mentionHover: [payload: { mention: AgentReferenceMention; rect: DOMRect }]
  mentionLeave: []
}>()

const editorRef = ref<HTMLDivElement | null>(null)
const isEmpty = ref(true)
let syncing = false
let savedRange: Range | null = null
let hoveredMentionId: string | null = null

function saveSelection() {
  const root = editorRef.value
  const selection = window.getSelection()
  if (!root || !selection || selection.rangeCount === 0) return
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer)) return
  savedRange = range.cloneRange()
}

function restoreSelection() {
  const root = editorRef.value
  const selection = window.getSelection()
  if (!root || !selection || !savedRange || !root.contains(savedRange.commonAncestorContainer)) return
  selection.removeAllRanges()
  selection.addRange(savedRange)
}

function editorSnapshot(): ComposerEditorSnapshot {
  const root = editorRef.value
  return root ? serializeEditor(root) : { text: "", mentions: [], contentParts: [], positionalPrompt: "" }
}

function syncFromEditor() {
  const root = editorRef.value
  if (!root || syncing) return
  const snapshot = editorSnapshot()
  isEmpty.value = editorIsEmpty(root)
  emit("update:modelValue", snapshot.text.trim())
  emit("update:referenceMentions", snapshot.mentions)
  emit("input")
  saveSelection()
}

function syncAtQuery() {
  const root = editorRef.value
  if (!root || props.disabled) {
    emit("closeAtQuery")
    return
  }
  const active = getAtQueryAtCaret(root)
  if (!active) {
    emit("closeAtQuery")
    return
  }
  emit("atQuery", active.query)
}

function onInput() {
  syncFromEditor()
  syncAtQuery()
}

function onKeydown(event: KeyboardEvent) {
  saveSelection()
  if (["ArrowDown", "ArrowUp", "Enter", "Tab", "Escape"].includes(event.key)) {
    emit("keydown", event)
    if (event.defaultPrevented) return
  }

  if (event.key === "Backspace") {
    const root = editorRef.value
    if (!root) return
    if (removeSelectedMentionChips(root) || removeMentionBeforeCaret(root)) {
      event.preventDefault()
      syncFromEditor()
      syncAtQuery()
      return
    }
  }

  if (event.key === "Enter") {
    event.preventDefault()
    emit("enter", event)
    return
  }

  if (event.key === "Escape") {
    emit("closeAtQuery")
  }
}

function onKeyup() {
  saveSelection()
}

function onPaste(event: ClipboardEvent) {
  emit("paste", event)
  const text = event.clipboardData?.getData("text/plain")
  if (!text) return
  event.preventDefault()
  const root = editorRef.value
  if (!root) return
  insertPlainTextAtCaret(root, text)
  void nextTick(() => {
    syncFromEditor()
    syncAtQuery()
  })
}

function focus() {
  editorRef.value?.focus()
}

function insertMention(mention: AgentReferenceMention, displayLabel: string) {
  const root = editorRef.value
  if (!root || props.disabled) return
  root.focus()
  const active = getAtQueryAtCaret(root)
  if (!active) restoreSelection()
  insertMentionChip(root, mention, displayLabel, active?.range)
  closeAtQuery()
  void nextTick(() => {
    syncFromEditor()
    focus()
  })
}

function insertMentions(items: Array<{ mention: AgentReferenceMention; displayLabel: string }>) {
  const root = editorRef.value
  if (!root || props.disabled || items.length === 0) return
  root.focus()
  restoreSelection()
  for (const item of items) {
    insertMentionChip(root, item.mention, item.displayLabel)
  }
  closeAtQuery()
  void nextTick(() => {
    syncFromEditor()
    focus()
  })
}

function removeMentions(assetKeys: string[]) {
  const root = editorRef.value
  if (!root || assetKeys.length === 0) return
  if (removeMentionsByAssetKeys(root, new Set(assetKeys))) {
    syncFromEditor()
    syncAtQuery()
  }
}

function onMouseOver(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const chip = target?.closest?.(".composer-mention-chip") as HTMLElement | null
  if (!chip || hoveredMentionId === chip.dataset.mentionId) return
  const mention = mentionFromChipElement(chip)
  if (!mention) return
  hoveredMentionId = chip.dataset.mentionId || null
  emit("mentionHover", { mention, rect: chip.getBoundingClientRect() })
}

function onMouseLeave() {
  hoveredMentionId = null
  emit("mentionLeave")
}

function closeAtQuery() {
  emit("closeAtQuery")
}

function adjustHeight() {
  const el = editorRef.value
  if (!el) return
  const target = props.expanded ? Math.min(window.innerHeight * 0.44, 360) : 52
  el.style.height = `${target}px`
  el.style.overflowY = el.scrollHeight > target ? "auto" : "hidden"
}

watch(
  () => props.modelValue,
  (value) => {
    const root = editorRef.value
    if (!root) return
    const snapshot = serializeEditor(root)
    if (value === snapshot.text.trim() && (value || snapshot.mentions.length === 0)) {
      if (!value && snapshot.mentions.length > 0) {
        syncing = true
        clearEditor(root)
        isEmpty.value = true
        emit("update:referenceMentions", [])
        syncing = false
      }
      return
    }
    if (!value) {
      syncing = true
      clearEditor(root)
      isEmpty.value = true
      emit("update:referenceMentions", [])
      syncing = false
    }
  },
)

watch(
  () => props.expanded,
  () => {
    void nextTick(adjustHeight)
  },
)

onMounted(() => {
  adjustHeight()
})

defineExpose({ focus, insertMention, insertMentions, removeMentions, adjustHeight, getSnapshot: editorSnapshot })
</script>

<template>
  <div
    ref="editorRef"
    class="composer-mention-editor"
    :class="{
      'composer-mention-editor--expand': expanded,
      'composer-mention-editor--spring': !reducedMotion,
      'composer-mention-editor--disabled': disabled,
      'is-empty': isEmpty,
    }"
    :contenteditable="disabled ? 'false' : 'true'"
    role="textbox"
    aria-multiline="true"
    :aria-placeholder="placeholder"
    :data-placeholder="placeholder"
    @input="onInput"
    @keydown="onKeydown"
    @keyup="onKeyup"
    @mouseup="saveSelection"
    @focus="saveSelection"
    @paste="onPaste"
    @mouseover="onMouseOver"
    @mouseleave="onMouseLeave"
  />
</template>

<style scoped>
.composer-mention-editor {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 18px;
  line-height: 1.6;
  height: 52px;
  min-height: 52px;
  max-height: 52px;
  overflow-y: hidden;
  padding: 8px 40px 6px 4px;
  color: var(--agent-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.composer-mention-editor--spring {
  transition: height 280ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.composer-mention-editor--expand {
  height: min(360px, 44vh);
  min-height: 118px;
  max-height: min(360px, 44vh);
}

.composer-mention-editor--disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.composer-mention-editor.is-empty::before {
  content: attr(data-placeholder);
  color: rgb(255 255 255 / 0.34);
  pointer-events: none;
}

.composer-mention-editor :deep(.composer-mention-chip) {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  margin: 0 2px;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgb(59 130 246 / 0.22);
  border: 1px solid rgb(96 165 250 / 0.45);
  color: #93c5fd;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
  vertical-align: baseline;
  user-select: all;
  cursor: default;
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  .composer-mention-editor--spring {
    transition: height 120ms ease;
  }
}
</style>
