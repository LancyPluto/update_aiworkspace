<script setup lang="ts">
import { ref } from 'vue'
import Button from '@/components/ui/Button.vue'
import { Check } from 'lucide-vue-next'

const emit = defineEmits<{
  'open-login': []
}>()

const isYearly = ref(false)

const plans = [
  {
    name: '免费版',
    description: '适合个人尝鲜体验',
    monthlyPrice: 0,
    yearlyPrice: 0,
    features: [
      '每日 30 次 AI 调用',
      '支持全部工具类型',
      '基础模型匹配',
      '社区支持',
    ],
    cta: '免费体验',
    popular: false,
  },
  {
    name: '专业版',
    description: '适合日常高频使用',
    monthlyPrice: 29,
    yearlyPrice: 290,
    features: [
      '每月 3000 次 AI 调用',
      '优先使用最新模型',
      '智能路由加速',
      '偏好记忆功能',
      '优先技术支持',
    ],
    cta: '立即订阅',
    popular: true,
  },
  {
    name: '企业版',
    description: '适合团队协作部署',
    monthlyPrice: 99,
    yearlyPrice: 990,
    features: [
      '无限 AI 调用',
      '专属模型配置',
      '团队协作空间',
      '使用数据分析',
      'SLA 保障',
      '7x24h 专属支持',
    ],
    cta: '联系商务',
    popular: false,
  },
]
</script>

<template>
  <section id="pricing" class="py-24 lg:py-32 bg-muted/50">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          定价方案
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          选择适合您的方案
        </h2>
        <p class="text-lg text-muted-foreground mb-8">
          从个人创作者到企业团队，灵活满足不同需求。新用户免费试用，无需信用卡。
        </p>
        
        <!-- Billing Toggle -->
        <div class="inline-flex items-center gap-4 p-1 bg-card rounded-full border border-border">
          <button 
            :class="[
              'px-6 py-2 rounded-full text-sm font-medium transition-all',
              !isYearly ? 'bg-foreground text-background' : 'text-muted-foreground hover:text-foreground'
            ]"
            @click="isYearly = false"
          >
            月付
          </button>
          <button 
            :class="[
              'px-6 py-2 rounded-full text-sm font-medium transition-all flex items-center gap-2',
              isYearly ? 'bg-foreground text-background' : 'text-muted-foreground hover:text-foreground'
            ]"
            @click="isYearly = true"
          >
            年付
            <span class="text-xs bg-destructive/20 text-destructive px-2 py-0.5 rounded-full">省17%</span>
          </button>
        </div>
      </div>
      
      <!-- Pricing Cards -->
      <div class="grid md:grid-cols-3 gap-8">
        <div 
          v-for="(plan, index) in plans" 
          :key="index"
          :class="[
            'relative rounded-2xl p-8 border transition-all duration-300 cursor-pointer',
            plan.popular 
              ? 'bg-foreground text-background border-foreground hover:scale-105 hover:shadow-2xl hover:shadow-purple-500/20' 
              : 'bg-card border-border hover:border-purple-500/50 hover:-translate-y-2 hover:shadow-xl hover:shadow-purple-500/10'
          ]"
        >
          <!-- Popular Badge -->
          <div 
            v-if="plan.popular"
            class="absolute -top-4 left-1/2 -translate-x-1/2 px-4 py-1 rounded-full bg-accent text-accent-foreground text-xs font-medium"
          >
            最受欢迎
          </div>
          
          <div class="mb-6">
            <h3 :class="['text-xl font-semibold mb-2', plan.popular ? 'text-background' : 'text-foreground']">{{ plan.name }}</h3>
            <p :class="['text-sm', plan.popular ? 'text-background/70' : 'text-muted-foreground']">{{ plan.description }}</p>
          </div>
          
          <div class="mb-6">
            <span class="text-4xl font-display">
              ¥{{ isYearly ? plan.yearlyPrice : plan.monthlyPrice }}
            </span>
            <span :class="['ml-1', plan.popular ? 'text-background/70' : 'text-muted-foreground']">
              / {{ isYearly ? '年' : '月' }}
            </span>
          </div>
          
          <ul class="space-y-3 mb-8">
            <li 
              v-for="(feature, featureIndex) in plan.features" 
              :key="featureIndex"
              class="flex items-center gap-3 transition-all duration-200 hover:gap-4"
            >
              <Check :class="['w-5 h-5 transition-transform duration-200', plan.popular ? 'text-background/70' : 'text-primary', 'hover:scale-110']" />
              <span :class="plan.popular ? 'text-background/90' : 'text-foreground'">{{ feature }}</span>
            </li>
          </ul>
          
          <Button 
            :class="[
              'w-full transition-all duration-300',
              plan.popular 
                ? 'bg-background text-foreground hover:bg-background/90 hover:scale-105' 
                : 'bg-foreground text-background hover:bg-purple-600 hover:scale-105'
            ]"
            @click="emit('open-login')"
          >
            {{ plan.cta }}
          </Button>
        </div>
      </div>
    </div>
  </section>
</template>