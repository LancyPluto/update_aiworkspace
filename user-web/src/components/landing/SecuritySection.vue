<script setup lang="ts">
import { Target, TrendingUp, PiggyBank, Rocket } from 'lucide-vue-next'

const securityFeatures = [
  { icon: Target, title: '智能匹配', description: '自动识别任务类型，推荐最合适的 AI 模型，无需选择困难' },
  { icon: TrendingUp, title: '越用越懂你', description: '记住你的风格偏好，每次使用都更贴合习惯' },
  { icon: PiggyBank, title: '高性价比', description: '免费试用，后期套餐比单独订阅各个 AI 省钱得多' },
  { icon: Rocket, title: '持续更新', description: '紧跟最新模型动态，第一时间接入更强的 AI 能力' },
]
</script>

<template>
  <section class="py-24 lg:py-32">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="grid lg:grid-cols-2 gap-12 lg:gap-20 items-center">
        <!-- Text Content -->
        <div>
          <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
            <span class="w-6 h-px bg-foreground/30" />
            平台优势
          </span>
          <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
            省心、省钱、越用越好用
          </h2>
          <p class="text-lg text-muted-foreground mb-8">
            不用在十几个 AI 工具之间来回切换，不用纠结选哪个模型，一个平台搞定所有创作需求。
          </p>
          
          <div class="grid grid-cols-2 gap-6">
            <div 
              v-for="(feature, index) in securityFeatures" 
              :key="index"
              class="flex items-start gap-3"
            >
              <div class="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
                <component :is="feature.icon" class="w-5 h-5 text-primary" />
              </div>
              <div>
                <h4 class="font-semibold mb-1">{{ feature.title }}</h4>
                <p class="text-sm text-muted-foreground">{{ feature.description }}</p>
              </div>
            </div>
          </div>
        </div>
        
        <!-- Visual -->
        <div class="relative">
          <div class="aspect-square rounded-3xl bg-gradient-to-br from-primary/10 to-accent/10 p-8 lg:p-12">
            <div class="w-full h-full rounded-2xl bg-card border border-border flex items-center justify-center">
              <div class="text-center">
                <div class="w-20 h-20 mx-auto rounded-2xl bg-primary/10 flex items-center justify-center mb-6">
                  <Target class="w-10 h-10 text-primary" />
                </div>
                <h3 class="text-xl font-semibold mb-2">一个平台就够了</h3>
                <p class="text-muted-foreground">告别多工具切换，省时省力</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
