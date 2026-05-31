<script setup lang="ts">
import { Sparkles } from "lucide-vue-next"
import UserAvatar from "@/components/UserAvatar.vue"

defineProps<{
  avatarUrl?: string | null
  displayName: string
  bio?: string | null
  featuredCount?: number
}>()
</script>

<template>
  <section class="profile-hero">
    <div class="profile-hero__glow" aria-hidden="true" />

    <div class="profile-hero__identity">
      <div class="profile-avatar-ring">
        <UserAvatar :src="avatarUrl" :name="displayName" size="xl" class="profile-avatar" />
      </div>

      <div class="profile-hero__copy">
        <div class="profile-badges">
          <span class="profile-badge profile-badge--primary">
            <Sparkles class="h-3.5 w-3.5" />
            公开创作者
          </span>
          <span v-if="featuredCount && featuredCount > 0" class="profile-badge profile-badge--secondary">
            精选 {{ featuredCount }} 作
          </span>
        </div>

        <h1>{{ displayName }}</h1>

        <p v-if="bio" class="profile-bio">{{ bio }}</p>
        <template v-else>
          <p class="profile-bio profile-bio--empty">灵感正在酝酿中…</p>
          <p class="profile-bio-slogan">每一幅作品，都是一次与 AI 的对话</p>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
@import url("https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,200;0,9..40,300;0,9..40,400;1,9..40,300&display=swap");

.profile-hero {
  position: relative;
  margin-top: 56px;
  padding: 48px 0 40px;
  border-bottom: 1px solid rgb(255 255 255 / 0.07);
}

.profile-hero__glow {
  position: absolute;
  inset: 10% 5% auto;
  height: 160px;
  background: linear-gradient(
    90deg,
    transparent,
    var(--profile-accent-soft),
    var(--profile-mesh-2),
    transparent
  );
  filter: blur(56px);
  pointer-events: none;
}

.profile-hero__identity {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: clamp(20px, 4vw, 36px);
}

.profile-avatar-ring {
  padding: 4px;
  border-radius: 28px;
  background: linear-gradient(145deg, var(--profile-accent-soft), transparent 55%);
  box-shadow:
    0 0 0 1px var(--profile-accent-soft),
    0 0 32px var(--profile-accent-glow);
  transition: box-shadow 0.25s ease, transform 0.25s ease;
}

.profile-avatar-ring:hover {
  box-shadow:
    0 0 0 1px var(--profile-accent),
    0 0 48px var(--profile-accent-glow);
  transform: translateY(-2px);
}

.profile-avatar-ring :deep(.user-avatar) {
  width: 96px;
  height: 96px;
  font-size: 28px;
}

.profile-hero__copy {
  flex: 1;
  min-width: min(100%, 280px);
}

.profile-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.profile-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.profile-badge--primary {
  border: 1px solid var(--profile-accent-soft);
  background: linear-gradient(135deg, var(--profile-accent-soft), rgb(255 255 255 / 0.04));
  color: var(--profile-accent-light);
  padding: 6px 12px 6px 10px;
  box-shadow: 0 0 20px var(--profile-accent-glow);
}

.profile-badge--secondary {
  border: 1px solid rgb(255 255 255 / 0.1);
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.55);
  padding: 6px 12px;
  letter-spacing: 0.08em;
}

.profile-hero__copy h1 {
  margin: 0;
  font-family: "DM Sans", ui-sans-serif, system-ui, sans-serif;
  font-size: clamp(36px, 6vw, 72px);
  font-weight: 200;
  letter-spacing: 0.04em;
  line-height: 1.05;
  color: #fff;
}

.profile-bio {
  max-width: 620px;
  margin: 16px 0 0;
  color: rgb(255 255 255 / 0.58);
  font-size: 15px;
  line-height: 1.75;
}

.profile-bio--empty {
  color: rgb(255 255 255 / 0.48);
  font-style: italic;
}

.profile-bio-slogan {
  margin: 6px 0 0;
  color: rgb(255 255 255 / 0.28);
  font-size: 13px;
  letter-spacing: 0.02em;
}

@media (prefers-reduced-motion: reduce) {
  .profile-avatar-ring:hover {
    transform: none;
  }
}

@media (max-width: 640px) {
  .profile-hero {
    margin-top: 48px;
    padding-top: 32px;
  }

  .profile-hero__identity {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
