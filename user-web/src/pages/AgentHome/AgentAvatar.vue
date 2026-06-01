<script setup lang="ts">
export type AgentAvatarState = "idle" | "thinking" | "streaming"

defineProps<{
  state?: AgentAvatarState
  size?: "sm" | "md"
}>()
</script>

<template>
  <div
    class="agent-avatar"
    :class="[
      size === 'sm' ? 'agent-avatar--sm' : 'agent-avatar--md',
      state === 'thinking' && 'agent-avatar--thinking',
      state === 'streaming' && 'agent-avatar--streaming',
    ]"
    aria-hidden="true"
  >
    <img src="/logo.svg" alt="" class="agent-avatar__logo" />
  </div>
</template>

<style scoped>
.agent-avatar {
  display: grid;
  place-items: center;
  border-radius: 0;
  border: 0;
  background: transparent;
  flex-shrink: 0;
}

.agent-avatar--md {
  width: 42px;
  height: 42px;
}

.agent-avatar--sm {
  width: 34px;
  height: 34px;
}

.agent-avatar__logo {
  width: 22px;
  height: 22px;
  object-fit: contain;
  filter: drop-shadow(0 0 10px var(--agent-accent-glow, rgb(176 92 255 / 0.28)));
  transition: transform 0.3s ease;
}

.agent-avatar--sm .agent-avatar__logo {
  width: 18px;
  height: 18px;
}

.agent-avatar--thinking {
  animation: avatar-pulse 2s ease-in-out infinite;
}

.agent-avatar--streaming {
  animation: avatar-pulse 1.6s ease-in-out infinite;
}

.agent-avatar--streaming .agent-avatar__logo {
  animation: avatar-logo-breathe 1.6s ease-in-out infinite;
}

@media (prefers-reduced-motion: reduce) {
  .agent-avatar--thinking,
  .agent-avatar--streaming,
  .agent-avatar--streaming .agent-avatar__logo {
    animation: none;
  }
}

@keyframes avatar-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 var(--agent-accent-soft, rgb(176 92 255 / 0.2));
  }
  50% {
    box-shadow: 0 0 0 6px transparent;
  }
}

@keyframes avatar-logo-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.06);
  }
}
</style>
