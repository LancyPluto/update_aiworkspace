import { onMounted, onUnmounted, ref } from "vue"

export interface TypingPlaceholderOptions {
  placeholders: string[]
  typeDelayMs?: number
  deleteDelayMs?: number
  pauseAfterCompleteMs?: number
  pauseBeforeNextMs?: number
}

export function useTypingPlaceholder(options: TypingPlaceholderOptions) {
  const {
    placeholders,
    typeDelayMs = 100,
    deleteDelayMs = 50,
    pauseAfterCompleteMs = 3000,
    pauseBeforeNextMs = 500,
  } = options

  const currentText = ref("")
  const isRunning = ref(false)

  let wordIndex = 0
  let charIndex = 0
  let isDeleting = false
  let timer: ReturnType<typeof setTimeout> | null = null

  function clearTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  function tick() {
    if (!isRunning.value || !placeholders.length) return

    const currentWord = placeholders[wordIndex] || ""

    if (isDeleting) {
      currentText.value = currentWord.substring(0, charIndex - 1)
      charIndex -= 1
    } else {
      currentText.value = currentWord.substring(0, charIndex + 1)
      charIndex += 1
    }

    let delay = isDeleting ? deleteDelayMs : typeDelayMs

    if (!isDeleting && currentText.value === currentWord) {
      delay = pauseAfterCompleteMs
      isDeleting = true
    } else if (isDeleting && currentText.value === "") {
      isDeleting = false
      wordIndex = (wordIndex + 1) % placeholders.length
      delay = pauseBeforeNextMs
    }

    timer = setTimeout(tick, delay)
  }

  function start() {
    if (!placeholders.length || isRunning.value) return
    isRunning.value = true
    clearTimer()
    tick()
  }

  function stop() {
    isRunning.value = false
    clearTimer()
    currentText.value = ""
    charIndex = 0
    isDeleting = false
  }

  function pause() {
    isRunning.value = false
    clearTimer()
    currentText.value = ""
  }

  function resume() {
    if (isRunning.value || !placeholders.length) return
    charIndex = 0
    isDeleting = false
    currentText.value = ""
    start()
  }

  function getCurrentPlaceholder() {
    return placeholders[wordIndex] || ""
  }

  onMounted(start)
  onUnmounted(stop)

  return {
    currentText,
    isRunning,
    start,
    stop,
    pause,
    resume,
    getCurrentPlaceholder,
  }
}
