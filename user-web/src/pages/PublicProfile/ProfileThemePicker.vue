<script setup lang="ts">
import { ref } from "vue"
import { Palette } from "lucide-vue-next"
import {
  getStoredProfileTheme,
  PROFILE_AMBIENT_THEMES,
  storeProfileTheme,
  type ProfileThemeId,
} from "@/utils/profileTheme"

const selectedId = ref<ProfileThemeId>(getStoredProfileTheme())

const open = ref(false)

const emit = defineEmits<{
  change: [id: ProfileThemeId]
}>()

function applyTheme(id: ProfileThemeId) {
  selectedId.value = id
  storeProfileTheme(id)
  emit("change", id)
}

function toggle() {
  open.value = !open.value
}
</script>

<template>
  <div class="profile-theme-picker">
    <button type="button" class="profile-theme-picker__toggle" @click="toggle">
      <Palette class="h-4 w-4" />
      公开主页氛围
    </button>

    <Transition name="theme-pop">
      <div v-if="open" class="profile-theme-picker__popover" @click.stop>
        <p class="profile-theme-picker__title">选择公开页氛围色</p>
        <div class="profile-theme-picker__grid">
          <button
            v-for="theme in PROFILE_AMBIENT_THEMES"
            :key="theme.id"
            type="button"
            class="profile-theme-picker__swatch"
            :class="{ 'profile-theme-picker__swatch--active': selectedId === theme.id }"
            :style="{ background: theme.swatch }"
            :title="theme.label"
            :aria-label="theme.label"
            @click="applyTheme(theme.id)"
          />
        </div>
      </div>
    </Transition>
    <div v-if="open" class="profile-theme-picker__backdrop" @click="open = false" />
  </div>
</template>

<style scoped>
.profile-theme-picker {
  position: relative;
  margin-top: 12px;
}

.profile-theme-picker__toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.65);
  padding: 8px 12px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.profile-theme-picker__toggle:hover {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.profile-theme-picker__backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.profile-theme-picker__popover {
  position: absolute;
  left: 0;
  top: calc(100% + 8px);
  z-index: 41;
  min-width: 220px;
  padding: 12px;
  border-radius: 16px;
  background: rgb(22 22 26 / 0.96);
  border: 1px solid rgb(255 255 255 / 0.08);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.5);
  backdrop-filter: blur(16px);
}

.profile-theme-picker__title {
  margin: 0 0 10px;
  font-size: 11px;
  color: rgb(255 255 255 / 0.45);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.profile-theme-picker__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.profile-theme-picker__swatch {
  aspect-ratio: 1;
  border-radius: 10px;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.profile-theme-picker__swatch:hover {
  transform: scale(1.06);
}

.profile-theme-picker__swatch--active {
  border-color: #fff;
  box-shadow: 0 0 0 2px var(--profile-accent, rgb(176 92 255));
}

.theme-pop-enter-active,
.theme-pop-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.theme-pop-enter-from,
.theme-pop-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
