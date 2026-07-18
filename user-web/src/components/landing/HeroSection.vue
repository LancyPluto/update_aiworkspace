<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, Search, Sparkles } from 'lucide-vue-next'
import Button from '@/components/ui/Button.vue'
import AnimatedSphere from './AnimatedSphere.vue'
import HeroInteractiveHeadline from './HeroInteractiveHeadline.vue'

const emit = defineEmits<{
  'open-login': []
}>()

const isVisible = ref(false)

onMounted(() => {
  isVisible.value = true
})
</script>

<template>
  <section class="landing-hero" aria-label="科创点AI 创作入口">
    <div class="landing-hero__grid" aria-hidden="true" />
    <div class="landing-hero__sphere" aria-hidden="true">
      <AnimatedSphere />
    </div>

    <div class="landing-hero__inner">
      <div :class="['landing-hero__copy', isVisible && 'landing-hero__copy--visible']">
        <p class="landing-hero__eyebrow">
          <Sparkles class="h-4 w-4" aria-hidden="true" />
          科创点AI · 一站式 AIGC 创作平台
        </p>

        <HeroInteractiveHeadline />

        <p class="landing-hero__lead">
          不用反复比较模型。描述你的创作需求，我们帮你找到合适的图像、视频与音频工具。
        </p>

        <form class="landing-hero__search" @submit.prevent="emit('open-login')">
          <label class="sr-only" for="landing-tool-search">搜索工具或描述创作需求</label>
          <Search class="h-5 w-5" aria-hidden="true" />
          <input
            id="landing-tool-search"
            type="search"
            placeholder="搜索工具，或输入：做一张电商主图"
            autocomplete="off"
          >
          <button class="landing-hero__search-button" type="submit">
            开始
            <ArrowRight class="h-4 w-4" aria-hidden="true" />
          </button>
        </form>

        <div class="landing-hero__actions">
          <Button class="landing-hero__primary group" size="lg" @click="emit('open-login')">
            免费进入工作台
            <ArrowRight class="h-4 w-4 transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
          </Button>
          <RouterLink to="/marketplace" class="landing-hero__secondary">
            浏览工具市场
          </RouterLink>
        </div>

        <dl class="landing-hero__metrics" aria-label="平台概览">
          <div>
            <dt>10+</dt>
            <dd>精品创作工具</dd>
          </div>
          <div>
            <dt>3</dt>
            <dd>图像、视频、音频</dd>
          </div>
          <div>
            <dt>0 元</dt>
            <dd>新用户免费试用</dd>
          </div>
        </dl>
      </div>
    </div>
  </section>
</template>

<style scoped>
.landing-hero {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  padding: 128px 24px 64px;
  background: rgb(8 9 12);
}

.landing-hero__grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgb(255 255 255 / 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgb(255 255 255 / 0.04) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: linear-gradient(to bottom, rgb(0 0 0 / 0.82), transparent 78%);
  pointer-events: none;
}

.landing-hero__sphere {
  position: absolute;
  top: 50%;
  right: max(-180px, calc((100vw - 1440px) / 2 - 120px));
  width: min(62vw, 820px);
  aspect-ratio: 1;
  opacity: 0.58;
  transform: translateY(-50%);
  pointer-events: none;
}

.landing-hero__inner {
  position: relative;
  z-index: 1;
  display: flex;
  min-height: calc(100vh - 192px);
  max-width: 1180px;
  margin: 0 auto;
  align-items: center;
}

.landing-hero__copy {
  width: min(900px, 78%);
  opacity: 0;
  transform: translateY(12px);
  transition: opacity 240ms ease, transform 240ms ease;
}

.landing-hero__copy--visible {
  opacity: 1;
  transform: translateY(0);
}

.landing-hero__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 24px;
  color: var(--brand-active-text);
  font-size: 13px;
  font-weight: 700;
}

.landing-hero__lead {
  max-width: 640px;
  margin: 28px 0 0;
  color: rgb(255 255 255 / 0.68);
  font-size: 17px;
  line-height: 1.8;
}

.landing-hero__search {
  display: flex;
  width: min(680px, 100%);
  min-height: 58px;
  align-items: center;
  gap: 12px;
  margin-top: 28px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 14px;
  background: rgb(13 16 22 / 0.82);
  padding: 7px 7px 7px 18px;
  color: rgb(255 255 255 / 0.72);
  box-shadow: 0 16px 50px rgb(0 0 0 / 0.24);
  backdrop-filter: blur(12px);
}

.landing-hero__search:focus-within {
  border-color: var(--brand-border);
  box-shadow: 0 0 0 3px rgb(var(--brand-primary-rgb) / 0.16), 0 16px 50px rgb(0 0 0 / 0.24);
}

.landing-hero__search input {
  min-width: 0;
  flex: 1;
  border: 0;
  background: transparent;
  color: #fff;
  font-size: 15px;
  outline: none;
}

.landing-hero__search input::placeholder {
  color: rgb(255 255 255 / 0.44);
}

.landing-hero__search-button,
.landing-hero__primary {
  border: 0;
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: var(--brand-button-shadow);
}

.landing-hero__search-button {
  display: inline-flex;
  height: 44px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  padding-inline: 18px;
  font-weight: 700;
}

.landing-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 20px;
}

.landing-hero__primary {
  height: 48px;
  border-radius: 10px;
  padding-inline: 18px;
}

.landing-hero__secondary {
  display: inline-flex;
  min-height: 48px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.04);
  padding: 0 18px;
  color: rgb(255 255 255 / 0.8);
  font-size: 14px;
  font-weight: 650;
  transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease;
}

.landing-hero__secondary:hover {
  border-color: var(--brand-border);
  background: var(--brand-softer);
  color: #fff;
}

.landing-hero__metrics {
  display: grid;
  max-width: 620px;
  margin: 32px 0 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.landing-hero__metrics div {
  border-left: 1px solid rgb(255 255 255 / 0.12);
  padding-left: 14px;
}

.landing-hero__metrics dt {
  color: #fff;
  font-size: 24px;
  font-weight: 760;
  line-height: 1;
}

.landing-hero__metrics dd {
  margin: 8px 0 0;
  color: rgb(255 255 255 / 0.56);
  font-size: 13px;
}

@media (max-width: 900px) {
  .landing-hero__sphere {
    right: -240px;
    width: 760px;
    opacity: 0.36;
  }

  .landing-hero__copy {
    width: 100%;
  }
}

@media (max-width: 640px) {
  .landing-hero {
    min-height: auto;
    padding: 104px 16px 48px;
  }

  .landing-hero__inner {
    min-height: 0;
  }

  .landing-hero__sphere {
    top: 33%;
    right: -260px;
    width: 640px;
    opacity: 0.3;
  }

  .landing-hero__eyebrow {
    margin-bottom: 20px;
  }

  .landing-hero__lead {
    margin-top: 24px;
    font-size: 15px;
  }

  .landing-hero__search,
  .landing-hero__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .landing-hero__search {
    padding: 14px;
  }

  .landing-hero__search-button,
  .landing-hero__primary,
  .landing-hero__secondary {
    width: 100%;
  }

  .landing-hero__metrics {
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-hero__copy,
  .landing-hero__secondary {
    transition: none;
  }
}
</style>
