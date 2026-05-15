<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'

const props = defineProps({
  message: { type: String, default: '' },
  isUser: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const displayed = ref('')
const fullText = computed(() => props.message ?? '')

// 流式打字动画
async function type() {
  displayed.value = ''
  const text = fullText.value
  for (let i = 0; i < text.length; i++) {
    if (!props.streaming) break
    displayed.value += text[i]
    await new Promise(r => setTimeout(r, 4))
  }
  displayed.value = text
}

// XSS 安全过滤
function cleanXss(str: string) {
  return str
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

// 渲染：先展示 → 后高亮（懒处理）
function renderContent() {
  let html = cleanXss(displayed.value)
  html = html.replace(/```([\s\S]*?)```/g, (_, code) => {
    return `<pre class="code-block"><code>${cleanXss(code)}</code></pre>`
  })
  return html
}

onMounted(() => {
  if (props.streaming) type()
  else displayed.value = fullText.value
})

watch(() => props.message, async () => {
  if (props.streaming) {
    await nextTick()
    type()
  } else {
    displayed.value = props.message
  }
})
</script>

<template>
  <div class="message-content">
    <div class="text" v-html="renderContent()"></div>
    <span v-if="streaming" class="stream-cursor"></span>
  </div>
</template>

<style scoped>
.message-content {
  line-height: 1.6;
  position: relative;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
}
.code-block {
  background: #f1f5f9;
  padding: 10px;
  border-radius: 8px;
  margin: 8px 0;
  overflow-x: auto;
  font-size: 13px;
}
.stream-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  background: #666;
  margin-left: 2px;
  animation: blink 1s infinite;
}
@keyframes blink {
  0%,100% { opacity:1; }
  50% { opacity:0; }
}
</style>