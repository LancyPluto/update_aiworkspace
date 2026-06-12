<script setup lang="ts">
import { Code2, Image, Music, FileText } from 'lucide-vue-next'

const infrastructureItems = [
  { icon: Code2, title: 'GPT-4o', description: 'OpenAI 旗舰模型，驱动智能路由与内容生成' },
  { icon: Image, title: 'Midjourney V6', description: '业界领先的文生图模型，艺术感与细节兼备' },
  { icon: Music, title: 'Runway Gen-3', description: '专业级图生视频模型，效果出众' },
  { icon: FileText, title: 'Suno V4', description: 'AI 音乐生成标杆，支持多种风格' },
]
</script>

<template>
  <section class="py-24 lg:py-32 bg-muted/50">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="grid lg:grid-cols-2 gap-12 lg:gap-20 items-center">
        <!-- Visual -->
        <div class="relative">
          <div class="aspect-square rounded-3xl bg-gradient-to-br from-primary/10 to-accent/10 p-8 lg:p-12">
            <div class="grid grid-cols-2 gap-4 h-full">
              <div class="bg-card rounded-xl p-6 border border-border flex flex-col items-center justify-center text-center">
                <Image class="w-8 h-8 text-primary mb-3" />
                <span class="text-sm font-medium">AI 生图</span>
              </div>
              <div class="bg-card rounded-xl p-6 border border-border flex flex-col items-center justify-center text-center">
                <FileText class="w-8 h-8 text-primary mb-3" />
                <span class="text-sm font-medium">工作流编排</span>
              </div>
              <div class="bg-card rounded-xl p-6 border border-border flex flex-col items-center justify-center text-center">
                <Music class="w-8 h-8 text-primary mb-3" />
                <span class="text-sm font-medium">音频生成</span>
              </div>
              <div class="bg-card rounded-xl p-6 border border-border flex flex-col items-center justify-center text-center">
                <Code2 class="w-8 h-8 text-primary mb-3" />
                <span class="text-sm font-medium">智能路由</span>
              </div>
            </div>
          </div>
        </div>
        
        <!-- Text Content -->
        <div>
          <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
            <span class="w-6 h-px bg-foreground/30" />
            精选模型矩阵
          </span>
          <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
            每个品类，只选最强的那个
          </h2>
          <p class="text-lg text-muted-foreground mb-8">
            不追求数量，只追求效果。每一个工具背后都是该领域最顶尖的模型能力。
          </p>
          
          <div class="grid grid-cols-2 gap-4">
            <div 
              v-for="(item, index) in infrastructureItems" 
              :key="index"
              class="flex items-start gap-3"
            >
              <div class="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                <component :is="item.icon" class="w-5 h-5 text-primary" />
              </div>
              <div>
                <h4 class="font-semibold text-sm mb-1">{{ item.title }}</h4>
                <p class="text-xs text-muted-foreground">{{ item.description }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
