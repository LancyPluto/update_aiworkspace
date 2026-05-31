/** Lightweight haptic feedback wrapper; silently no-ops when unsupported. */

function vibrate(pattern: number | number[]) {
  if (typeof navigator === "undefined" || typeof navigator.vibrate !== "function") return
  try {
    navigator.vibrate(pattern)
  } catch {
    /* ignore */
  }
}

/** Short tap on send / confirm actions */
export function lightTap() {
  vibrate(8)
}

/** Subtle tick when passing scroll nodes or selecting timeline phase */
export function selectionTick() {
  vibrate(4)
}
