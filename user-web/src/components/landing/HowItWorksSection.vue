<script setup lang="ts">
import { Target, TrendingUp, Gift } from 'lucide-vue-next'

const steps = [
  {
    number: '01',
    icon: Target,
    title: '智能匹配，用对模型',
    description: '不是所有 AI 都适合干同一件事。我们为每个工具精心挑选了最擅长该任务的模型——生图用 可灵生图，视频用 可灵生视频，音频用 MiniMax。你只管提需求，剩下的交给我们。',
  },
  {
    number: '02',
    icon: TrendingUp,
    title: '越用越聪明',
    description: '每一次使用，我们都在学习你的偏好。你喜欢的风格、常用的参数、偏好的输出格式……系统会自动记忆并优化推荐，用得越久越顺手。',
  },
  {
    number: '03',
    icon: Gift,
    title: '免费试用，实惠定价',
    description: '新用户无需绑卡即可免费体验全部工具。觉得好用再升级，后期套餐价格远低于单独订阅各个 AI 服务，性价比拉满。',
  },
]
</script>

<template>
  <section id="why-us" class="py-24 lg:py-32 bg-muted/50">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <!-- Section Header -->
      <div class="max-w-3xl mx-auto text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          为什么选择我们
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          三个理由，就够了
        </h2>
        <p class="text-lg text-muted-foreground">
          我们不做大而全，只做精而准——帮你省时间、省精力、省钱。
        </p>
      </div>
      
      <!-- Steps -->
      <div class="relative">
        <div class="grid lg:grid-cols-3 gap-12 lg:gap-8">
          <div 
            v-for="(step, index) in steps" 
            :key="index"
            class="relative"
          >
            <div class="bg-card rounded-2xl p-8 border border-border hover:border-foreground/20 transition-all duration-300">
              <div class="flex items-center justify-between mb-6">
                <span class="text-4xl font-display text-muted-foreground/50">{{ step.number }}</span>
                <div class="w-14 h-14 rounded-2xl bg-primary/10 flex items-center justify-center">
                  <component :is="step.icon" class="w-7 h-7 text-primary" />
                </div>
              </div>
              <h3 class="text-xl font-semibold mb-3">{{ step.title }}</h3>
              <p class="text-muted-foreground leading-relaxed">{{ step.description }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
