<script setup lang="ts">
import { Image, Film, Music, FileText, Cpu, Heart } from 'lucide-vue-next'

const features = [
  {
    icon: Image,
    title: 'AI 生图',
    description: '基于 Midjourney + DALL·E 能力，输入描述即可生成高品质图片，无需提示词工程。',
  },
  {
    icon: Film,
    title: '图生视频',
    description: '接入 Runway + Pika，让静态图片动起来，支持风格迁移和运动控制。',
  },
  {
    icon: Music,
    title: '音频生成',
    description: '集成 Suno + 火山引擎 TTS，文字转语音、AI 作曲、声音克隆一站式完成。',
  },
  {
    icon: FileText,
    title: 'PPT 生成',
    description: '基于 GPT-4o + Claude，输入主题即可生成结构清晰、设计精美的演示文稿。',
  },
  {
    icon: Cpu,
    title: '智能路由',
    description: '自动识别你的任务类型，匹配最擅长该任务的 AI 模型，省去挑选和调试的时间。',
  },
  {
    icon: Heart,
    title: '越用越懂你',
    description: '记住你的风格偏好和常用配置，每次使用都更贴合你的习惯，无需重复设置。',
  },
]
</script>

<template>
  <section id="features" class="py-24 lg:py-32">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <!-- Section Header -->
      <div class="max-w-3xl mx-auto text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          AI 工具集
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          每个 AI 都有它的用武之地
        </h2>
        <p class="text-lg text-muted-foreground">
          不堆砌模型，只精选每个领域最擅长的那一个，让你不做选择。
        </p>
      </div>
      
      <!-- Features Grid -->
      <div class="grid md:grid-cols-2 lg:grid-cols-3 gap-8">
        <div 
          v-for="(feature, index) in features" 
          :key="index"
          class="group p-8 rounded-2xl bg-card border border-border hover:border-foreground/20 hover:shadow-lg transition-all duration-300"
        >
          <div class="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-6 group-hover:bg-primary/20 transition-colors">
            <component :is="feature.icon" class="w-6 h-6 text-primary" />
          </div>
          <h3 class="text-xl font-semibold mb-3">{{ feature.title }}</h3>
          <p class="text-muted-foreground leading-relaxed">{{ feature.description }}</p>
        </div>
      </div>
    </div>
  </section>
</template>
