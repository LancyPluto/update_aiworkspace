<script setup lang="ts">
import { computed } from "vue"
import type { AgentReferenceMention } from "@/utils/agentReferenceMentions"
import { segmentMessageWithMentions } from "@/utils/agentMessageMentions"

const props = defineProps<{
  text: string
  mentions: AgentReferenceMention[]
}>()

const segments = computed(() => segmentMessageWithMentions(props.text, props.mentions))
</script>

<template>
  <p class="mention-message-body">
    <template v-for="(segment, index) in segments" :key="index">
      <span v-if="segment.type === 'text'" class="mention-message-text">{{ segment.value }}</span>
      <span
        v-else
        class="mention-message-chip"
        :title="segment.mention.refLabel"
      >
        {{ segment.displayLabel }}
      </span>
    </template>
  </p>
</template>

<style scoped>
.mention-message-body {
  margin: 0;
  font-size: 18px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.mention-message-text {
  color: inherit;
}

.mention-message-chip {
  display: inline-flex;
  align-items: center;
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
  white-space: nowrap;
}
</style>
