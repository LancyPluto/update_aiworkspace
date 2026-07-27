export type ComposerUploadMediaKind = "image" | "video" | "audio"

export interface ComposerMediaRouteSlot {
  fieldKey: string
  kind: ComposerUploadMediaKind | "file"
  canAdd: boolean
  uploading: boolean
  uploadPriority: number
  count: number
  maxCount: number
  previewUrls: string[]
}

export function availableComposerMediaSlots<T extends ComposerMediaRouteSlot>(
  slots: T[],
  kind?: ComposerUploadMediaKind,
): T[] {
  return slots
    .filter((slot) => slot.kind !== "file" && (!kind || slot.kind === kind))
    .filter((slot) => slot.canAdd && !slot.uploading)
    .map((slot, index) => ({ slot, index }))
    .sort((left, right) => left.slot.uploadPriority - right.slot.uploadPriority || left.index - right.index)
    .map(({ slot }) => slot)
}

export function remainingComposerMediaCapacity(slots: ComposerMediaRouteSlot[]): number {
  return slots
    .filter((slot) => slot.kind !== "file")
    .reduce((total, slot) => total + Math.max(0, slot.maxCount - slot.count), 0)
}

export function lastComposerMediaPreviewKey(slots: ComposerMediaRouteSlot[]): string | null {
  for (let slotIndex = slots.length - 1; slotIndex >= 0; slotIndex -= 1) {
    const slot = slots[slotIndex]!
    if (slot.previewUrls.length > 0) return `${slot.fieldKey}:${slot.previewUrls.length - 1}`
  }
  return null
}
