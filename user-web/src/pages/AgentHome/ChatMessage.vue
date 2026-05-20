<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from "vue"
import { renderMarkdown } from "@/utils/markdownRender"

const props = defineProps({
  message: { type: String, default: "" },
  isUser: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const displayed = ref("")
const fullText = computed(() => props.message ?? "")

const renderedHtml = computed(() => renderMarkdown(displayed.value))

// 流式打字动画
async function type() {
  displayed.value = ""
  const text = fullText.value
  for (let i = 0; i < text.length; i++) {
    if (!props.streaming) break
    displayed.value += text[i]
    await new Promise((r) => setTimeout(r, 4))
  }
  displayed.value = text
}

onMounted(() => {
  if (props.streaming) void type()
  else displayed.value = fullText.value
})

watch(
  () => props.message,
  async () => {
    if (props.streaming) {
      await nextTick()
      void type()
    } else {
      displayed.value = props.message ?? ""
    }
  },
)
</script>

<template>
  <div class="message-content" :class="{ 'message-content--user': isUser }">
    <div
      v-if="renderedHtml"
      class="markdown-body"
      :class="{ 'markdown-body--user': isUser }"
      v-html="renderedHtml"
    />
    <span v-else-if="streaming" class="stream-placeholder" />
    <span v-if="streaming" class="stream-cursor" />
  </div>
</template>

<style scoped>
.message-content {
  line-height: 1.65;
  position: relative;
}

.markdown-body {
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 0 0 0.75em;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 1em 0 0.5em;
  font-weight: 600;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 1.35em;
}

.markdown-body :deep(h2) {
  font-size: 1.2em;
}

.markdown-body :deep(h3) {
  font-size: 1.08em;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.5em 0 0.75em;
  padding-left: 1.4em;
}

.markdown-body :deep(li + li) {
  margin-top: 0.25em;
}

.markdown-body :deep(blockquote) {
  margin: 0.75em 0;
  padding: 0.35em 0.9em;
  border-left: 3px solid color-mix(in srgb, var(--primary) 55%, var(--border));
  background: color-mix(in srgb, var(--muted) 65%, transparent);
  color: var(--muted-foreground);
}

.markdown-body :deep(a) {
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown-body :deep(hr) {
  margin: 1em 0;
  border: none;
  border-top: 1px solid var(--border);
}

.markdown-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.15em 0.35em;
  border-radius: 4px;
  background: color-mix(in srgb, var(--muted) 80%, transparent);
}

.markdown-body :deep(pre.code-block) {
  margin: 0.75em 0;
  padding: 12px 14px;
  border-radius: 8px;
  overflow-x: auto;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 13px;
  line-height: 1.55;
}

.markdown-body :deep(pre.code-block code) {
  padding: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 0.75em 0;
  border-collapse: collapse;
  font-size: 0.92em;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--border);
  padding: 0.45em 0.65em;
  text-align: left;
}

.markdown-body :deep(th) {
  background: color-mix(in srgb, var(--muted) 70%, transparent);
}

.markdown-body--user :deep(a) {
  color: color-mix(in srgb, #fff 92%, var(--primary));
}

.markdown-body--user :deep(blockquote) {
  border-left-color: color-mix(in srgb, #fff 45%, transparent);
  background: color-mix(in srgb, #fff 12%, transparent);
  color: color-mix(in srgb, #fff 85%, transparent);
}

.markdown-body--user :deep(code) {
  background: color-mix(in srgb, #fff 18%, transparent);
  color: inherit;
}

.stream-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: currentColor;
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s infinite;
}

.stream-placeholder {
  display: inline-block;
  width: 0.5em;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
