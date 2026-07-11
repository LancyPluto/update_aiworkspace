<script setup lang="ts">
import { ArrowLeft } from "lucide-vue-next"

defineProps<{
  title: string
  summary: string
  sections: Array<{ title: string; paragraphs: string[] }>
}>()
</script>

<template>
  <main class="legal-page">
    <div class="legal-shell">
      <RouterLink to="/" class="back-link"><ArrowLeft :size="18" />返回首页</RouterLink>
      <header>
        <p>科创点AI · 用户服务文件</p>
        <h1>{{ title }}</h1>
        <div class="version-row"><span>版本 1.0</span><span>生效日期：2026-07-12</span></div>
        <p class="summary">{{ summary }}</p>
      </header>
      <section v-for="section in sections" :key="section.title">
        <h2>{{ section.title }}</h2>
        <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.legal-page { min-height: 100vh; background: #090b0f; color: #f4f7fb; padding: 48px 20px 80px; }
.legal-shell { width: min(820px, 100%); margin: 0 auto; }
.back-link { display: inline-flex; align-items: center; gap: 8px; color: #77d9ef; text-decoration: none; margin-bottom: 48px; }
header { padding-bottom: 32px; border-bottom: 1px solid #252a33; }
header > p:first-child { color: #65d4ec; font-size: 13px; }
h1 { margin: 12px 0 16px; font-size: clamp(32px, 6vw, 48px); line-height: 1.15; }
.version-row { display: flex; flex-wrap: wrap; gap: 16px; color: #8f98a8; font-size: 13px; }
.summary { margin-top: 24px; color: #c2c8d2; line-height: 1.8; }
section { padding-top: 32px; }
h2 { font-size: 20px; margin-bottom: 14px; }
section p { color: #aeb6c4; line-height: 1.9; margin: 10px 0; }
</style>
