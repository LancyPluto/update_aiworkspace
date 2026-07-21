<script setup lang="ts">
import Button from '@/components/ui/Button.vue'
import { Check } from 'lucide-vue-next'

const emit = defineEmits<{
  'open-login': []
}>()

const plans = [
  {
    name: '免费版',
    description: '适合个人尝鲜体验',
    price: 0,
    credits: '100 算力',
    validity: '注册即赠，按需消耗',
    features: [
      '注册赠送 100 算力',
      '支持全部工具类型',
      '按实际调用扣减算力',
      '社区支持与灵感浏览',
    ],
    cta: '免费体验',
    popular: false,
  },
  {
    name: '专业版',
    description: '适合日常高频使用',
    price: 45,
    credits: '5,000 算力',
    validity: '有效期 90 天',
    features: [
      '到账 5,000 算力',
      '优先任务队列',
      'API 调用加速',
      '支持全部工具与模型',
      '登录后在账单页充值',
    ],
    cta: '登录充值',
    popular: true,
  },
  {
    name: '企业版',
    description: '适合团队高频创作',
    price: 99,
    credits: '12,000 算力',
    validity: '有效期 180 天',
    features: [
      '到账 12,000 算力',
      '优先任务队列',
      'API 调用加速',
      '模型选型咨询支持',
      '登录后在账单页充值',
    ],
    cta: '登录充值',
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
          算力充值
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          选择适合您的方案
        </h2>
        <p class="text-lg text-muted-foreground mb-4">
          按需购买算力，用多少充多少。新用户注册即赠算力，无需绑卡即可体验全部工具。
        </p>
        <p class="text-sm text-muted-foreground">
          以下为算力充值参考方案，与账单页套餐一致；另有入门包，并支持自定义金额充值。
        </p>
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
              : 'bg-card border-border hover:border-purple-500/50 hover:-translate-y-2 hover:shadow-xl hover:shadow-purple-500/10',
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
            <h3 :class="['text-xl font-semibold mb-2', plan.popular ? 'text-background' : 'text-foreground']">
              {{ plan.name }}
            </h3>
            <p :class="['text-sm', plan.popular ? 'text-background/70' : 'text-muted-foreground']">
              {{ plan.description }}
            </p>
          </div>

          <div class="mb-6">
            <span class="text-4xl font-display tabular-nums">
              ¥{{ plan.price }}
            </span>
            <span :class="['ml-1 text-sm', plan.popular ? 'text-background/70' : 'text-muted-foreground']">
              {{ plan.price > 0 ? '/ 一次性充值' : '/ 注册即赠' }}
            </span>
            <p :class="['mt-3 text-lg font-semibold tabular-nums', plan.popular ? 'text-background' : 'text-foreground']">
              {{ plan.credits }}
            </p>
            <p :class="['mt-1 text-xs', plan.popular ? 'text-background/60' : 'text-muted-foreground']">
              {{ plan.validity }}
            </p>
          </div>

          <ul class="space-y-3 mb-8">
            <li
              v-for="(feature, featureIndex) in plan.features"
              :key="featureIndex"
              class="flex items-center gap-3 transition-all duration-200 hover:gap-4"
            >
              <Check
                :class="[
                  'w-5 h-5 shrink-0 transition-transform duration-200 hover:scale-110',
                  plan.popular ? 'text-background/70' : 'text-primary',
                ]"
              />
              <span :class="plan.popular ? 'text-background/90' : 'text-foreground'">{{ feature }}</span>
            </li>
          </ul>

          <Button
            :class="[
              'w-full transition-all duration-300',
              plan.popular
                ? 'bg-background text-foreground hover:bg-background/90 hover:scale-105'
                : 'bg-foreground text-background hover:bg-purple-600 hover:scale-105',
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
