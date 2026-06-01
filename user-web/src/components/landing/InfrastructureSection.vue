<script setup lang="ts">
import { Code2, Image, Music, FileText } from 'lucide-vue-next'

const infrastructureItems = [
  { icon: Code2, title: '智能内容生成', description: '全场景理解与创作模型，驱动智能路由与多模态生成。' },
  { icon: Image, title: 'AI 图像生成', description: '文生图与图生图模型，支持高分辨率输出与风格定制。' },
  { icon: Music, title: '图生视频生成', description: '动态视频生成模型，支持静态图转视频与运动控制。' },
  { icon: FileText, title: '语音与音乐生成', description: '一站式音频创作模型，支持语音合成、AI 作曲与声音克隆。' },
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
                <span class="text-sm font-medium">PPT 制作</span>
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
