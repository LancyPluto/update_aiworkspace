<script setup lang="ts">
import { ArrowLeft } from "lucide-vue-next"
import UserAvatar from "@/components/UserAvatar.vue"

defineProps<{
  visible: boolean
  displayName: string
  avatarUrl?: string | null
  postCount?: number | null
}>()

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <header class="sticky-header" :class="{ 'sticky-header--visible': visible }">
    <button type="button" class="sticky-header__back" aria-label="返回" @click="emit('back')">
      <ArrowLeft class="h-4 w-4" />
    </button>
    <UserAvatar :src="avatarUrl" :name="displayName" size="sm" />
    <span class="sticky-header__name">{{ displayName }}</span>
    <span v-if="postCount != null" class="sticky-header__meta">{{ postCount }} 作品</span>
  </header>
</template>

<style scoped>
.sticky-header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 clamp(16px, 4vw, 48px);
  background: rgb(5 5 5 / 0.72);
  border-bottom: 1px solid rgb(255 255 255 / 0.06);
  backdrop-filter: blur(20px) saturate(140%);
  transform: translateY(-100%);
  opacity: 0;
  pointer-events: none;
  transition: transform 0.28s cubic-bezier(0.34, 1.2, 0.64, 1), opacity 0.22s ease;
}

.sticky-header--visible {
  transform: translateY(0);
  opacity: 1;
  pointer-events: auto;
}

.sticky-header__back {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgb(255 255 255 / 0.1);
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.78);
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.sticky-header__back:hover {
  border-color: var(--profile-accent-soft);
  color: #fff;
}

.sticky-header__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
  color: rgb(255 255 255 / 0.9);
}

.sticky-header__meta {
  flex-shrink: 0;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
}
</style>
