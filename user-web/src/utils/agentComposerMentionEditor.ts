import type { AgentReferenceMention } from "@/utils/agentReferenceMentions"

export const MENTION_CHIP_CLASS = "composer-mention-chip"

export interface ComposerMentionChip {
  id: string
  displayLabel: string
  mention: AgentReferenceMention
}

export type ComposerContentPart =
  | { type: "text"; text: string }
  | {
      type: "image" | "file"
      file_id?: string | number
      url?: string
      asset_key: string
      name?: string
      content_type?: string
    }

export interface ComposerEditorSnapshot {
  text: string
  mentions: AgentReferenceMention[]
  contentParts: ComposerContentPart[]
  positionalPrompt: string
}

function mentionChipId(): string {
  return `m-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

export function createMentionChipElement(chip: ComposerMentionChip): HTMLSpanElement {
  const el = document.createElement("span")
  el.className = MENTION_CHIP_CLASS
  el.contentEditable = "false"
  el.dataset.mentionId = chip.id
  el.dataset.displayLabel = chip.displayLabel
  el.dataset.assetKey = chip.mention.assetKey ?? ""
  el.dataset.fileId = chip.mention.fileId == null ? "" : String(chip.mention.fileId)
  el.dataset.refLabel = chip.mention.refLabel
  el.dataset.url = chip.mention.url
  el.dataset.source = chip.mention.source ?? ""
  el.dataset.kind = chip.mention.kind ?? "image"
  el.dataset.name = chip.mention.name ?? ""
  el.dataset.contentType = chip.mention.contentType ?? ""
  el.dataset.previewUrl = chip.mention.previewUrl ?? ""
  el.title = chip.mention.refLabel || chip.displayLabel
  el.textContent = chip.displayLabel
  return el
}

export function mentionFromChipElement(el: HTMLElement): AgentReferenceMention | null {
  const url = el.dataset.url?.trim()
  if (!url) return null
  return {
    token: el.dataset.displayLabel || el.dataset.refLabel || "",
    refLabel: el.dataset.refLabel || el.dataset.displayLabel || "",
    assetKey: el.dataset.assetKey || undefined,
    fileId: el.dataset.fileId || undefined,
    url,
    kind: el.dataset.kind || "image",
    name: el.dataset.name || undefined,
    contentType: el.dataset.contentType || undefined,
    previewUrl: el.dataset.previewUrl || undefined,
    source: el.dataset.source || undefined,
  }
}

export function serializeEditor(root: HTMLElement): ComposerEditorSnapshot {
  const mentions: AgentReferenceMention[] = []
  let text = ""
  const contentParts: ComposerContentPart[] = []
  let positionalPrompt = ""

  const appendText = (value: string) => {
    text += value
    const normalized = value.replace(/\u00a0/g, " ")
    if (!normalized) return
    const previous = contentParts[contentParts.length - 1]
    if (previous?.type === "text") {
      previous.text += normalized
    } else {
      contentParts.push({ type: "text", text: normalized })
    }
    positionalPrompt += normalized
  }

  const appendMentionText = (label: string) => {
    const needsLeadingSpace = text.length > 0 && !/\s$/.test(text)
    text += `${needsLeadingSpace ? " " : ""}${label} `
  }

  const appendMentionPart = (mention: AgentReferenceMention) => {
    if (text.length > 0 && !/\s$/.test(text)) {
      const previous = contentParts[contentParts.length - 1]
      if (previous?.type === "text") {
        previous.text += " "
      } else {
        contentParts.push({ type: "text", text: " " })
      }
      positionalPrompt += " "
    }
    const assetKey = mention.assetKey || (mention.fileId == null ? mention.url : `file_${mention.fileId}`)
    const contentType = mention.contentType || ""
    const kind = (mention.kind || "").toLowerCase()
    const partType = kind === "image" || contentType.toLowerCase().startsWith("image/") ? "image" : "file"
    const part: ComposerContentPart = {
      type: partType,
      asset_key: assetKey,
    }
    if (mention.fileId != null) part.file_id = mention.fileId
    if (mention.url) part.url = mention.url
    if (mention.name) part.name = mention.name
    if (contentType) part.content_type = contentType
    contentParts.push(part)
    positionalPrompt += `{${assetKey}}`
  }

  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      appendText(node.textContent ?? "")
      return
    }
    if (!(node instanceof HTMLElement)) return
    if (node.classList.contains(MENTION_CHIP_CLASS)) {
      const mention = mentionFromChipElement(node)
      const label = node.dataset.displayLabel || mention?.token || ""
      if (mention) {
        const orderedMention = { ...mention, token: label }
        mentions.push(orderedMention)
        appendMentionPart(orderedMention)
      }
      appendMentionText(label)
      return
    }
    if (node.tagName === "BR") {
      appendText("\n")
      return
    }
    node.childNodes.forEach(walk)
  }

  root.childNodes.forEach(walk)
  const normalizedText = text.replace(/\u00a0/g, " ").trimEnd()
  const normalizedParts = contentParts
    .map((part) => part.type === "text" ? { ...part, text: part.text.replace(/\u00a0/g, " ") } : part)
    .filter((part) => part.type !== "text" || part.text.length > 0)
  return {
    text: normalizedText,
    mentions,
    contentParts: normalizedParts,
    positionalPrompt: positionalPrompt.replace(/\u00a0/g, " ").trimEnd(),
  }
}

export function editorIsEmpty(root: HTMLElement): boolean {
  const snapshot = serializeEditor(root)
  return !snapshot.text.trim() && snapshot.mentions.length === 0
}

export function clearEditor(root: HTMLElement) {
  root.innerHTML = ""
}

export function removeMentionsByAssetKeys(root: HTMLElement, assetKeys: Set<string>): boolean {
  let removed = false
  const chips = root.querySelectorAll<HTMLElement>(`.${MENTION_CHIP_CLASS}`)
  chips.forEach((chip) => {
    const mention = mentionFromChipElement(chip)
    const keys = [
      chip.dataset.assetKey,
      mention?.assetKey,
      mention?.fileId == null ? undefined : `file_${mention.fileId}`,
      mention?.fileId == null ? undefined : `agent_file:${mention.fileId}`,
      mention?.url ? `url:${mention.url}` : undefined,
      mention?.url,
    ].filter(Boolean) as string[]
    if (!keys.some((key) => assetKeys.has(key))) return
    const trailing = chip.nextSibling
    chip.remove()
    if (trailing?.nodeType === Node.TEXT_NODE && /^\u00a0?$/.test(trailing.textContent ?? "")) {
      trailing.remove()
    }
    removed = true
  })
  return removed
}

export function insertMentionChip(
  root: HTMLElement,
  mention: AgentReferenceMention,
  displayLabel: string,
  replaceRange?: { startContainer: Node; startOffset: number; endContainer: Node; endOffset: number },
) {
  const chip = createMentionChipElement({
    id: mentionChipId(),
    displayLabel,
    mention: { ...mention, token: displayLabel },
  })
  const spaceAfter = document.createTextNode("\u00a0")

  if (replaceRange) {
    const range = document.createRange()
    range.setStart(replaceRange.startContainer, replaceRange.startOffset)
    range.setEnd(replaceRange.endContainer, replaceRange.endOffset)
    range.deleteContents()
    range.insertNode(spaceAfter)
    range.insertNode(chip)
    range.setStartAfter(spaceAfter)
    range.collapse(true)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    return
  }

  root.appendChild(chip)
  root.appendChild(spaceAfter)
  const range = document.createRange()
  range.setStartAfter(spaceAfter)
  range.collapse(true)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

export function getAtQueryAtCaret(root: HTMLElement): {
  query: string
  range: { startContainer: Node; startOffset: number; endContainer: Node; endOffset: number }
} | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || !root.contains(selection.anchorNode)) return null
  const anchor = selection.anchorNode
  const offset = selection.anchorOffset
  if (!anchor) return null

  let textNode: Text | null = null
  let atOffset = 0

  if (anchor.nodeType === Node.TEXT_NODE) {
    textNode = anchor as Text
    atOffset = offset
  } else if (anchor.nodeType === Node.ELEMENT_NODE) {
    const element = anchor as HTMLElement
    const child = element.childNodes[offset - 1] ?? element.childNodes[offset]
    if (child?.nodeType === Node.TEXT_NODE) {
      textNode = child as Text
      atOffset = textNode.length
    }
  }
  if (!textNode) return null

  const before = textNode.data.slice(0, atOffset)
  const atIndex = before.lastIndexOf("@")
  if (atIndex < 0) return null
  if (atIndex > 0 && !/\s/.test(before[atIndex - 1] || "")) return null

  const query = before.slice(atIndex + 1)
  if (query.includes(" ") || query.includes("\n")) return null

  return {
    query,
    range: {
      startContainer: textNode,
      startOffset: atIndex,
      endContainer: textNode,
      endOffset: atOffset,
    },
  }
}

function nodeBeforeCaret(root: HTMLElement): Node | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) return null
  const range = selection.getRangeAt(0)
  if (!range.collapsed || !root.contains(range.startContainer)) return null

  const probe = document.createRange()
  probe.selectNodeContents(root)
  probe.setEnd(range.startContainer, range.startOffset)

  const fragment = probe.cloneContents()
  if (!fragment.childNodes.length) return null
  return fragment.childNodes[fragment.childNodes.length - 1]
}

export function removeMentionBeforeCaret(root: HTMLElement): boolean {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || !selection.isCollapsed) return false
  const range = selection.getRangeAt(0)
  if (!root.contains(range.startContainer)) return false

  const previous = nodeBeforeCaret(root)
  if (previous instanceof HTMLElement && previous.classList.contains(MENTION_CHIP_CLASS)) {
    const trailing = previous.nextSibling
    previous.remove()
    if (trailing?.nodeType === Node.TEXT_NODE && /^\u00a0?$/.test(trailing.textContent ?? "")) {
      trailing.remove()
    }
    return true
  }

  if (range.startContainer.nodeType === Node.TEXT_NODE) {
    const textNode = range.startContainer as Text
    const offset = range.startOffset
    if (offset === 0) {
      const prev = textNode.previousSibling
      if (prev instanceof HTMLElement && prev.classList.contains(MENTION_CHIP_CLASS)) {
        const trailing = prev.nextSibling
        prev.remove()
        if (trailing === textNode && /^\u00a0?$/.test(textNode.data)) {
          textNode.remove()
        }
        return true
      }
    }
  }
  return false
}

export function removeSelectedMentionChips(root: HTMLElement): boolean {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return false
  const range = selection.getRangeAt(0)
  if (!root.contains(range.commonAncestorContainer)) return false

  const chips = root.querySelectorAll(`.${MENTION_CHIP_CLASS}`)
  let removed = false
  chips.forEach((chip) => {
    if (selection.containsNode(chip, true)) {
      chip.remove()
      removed = true
    }
  })
  return removed
}

export function insertPlainTextAtCaret(root: HTMLElement, text: string) {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) {
    root.appendChild(document.createTextNode(text))
    return
  }
  const range = selection.getRangeAt(0)
  range.deleteContents()
  range.insertNode(document.createTextNode(text))
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}
