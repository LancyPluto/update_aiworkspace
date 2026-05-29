<script setup lang="ts">
import { ref, onMounted } from 'vue'

const metrics = [
  { value: 50, suffix: '+', label: '集成 AI 模型', description: '覆盖主流厂商' },
  { value: 10, suffix: '+', label: '精品创作工具', description: '每个都经实测优选' },
  { value: 10, suffix: '万+', label: '服务用户', description: '持续增长中' },
  { value: 0, suffix: '元', label: '新用户免费试用', description: '无需信用卡，无风险体验' },
]

const animatedValues = ref<number[]>(metrics.map(() => 0))
const isVisible = ref(false)

const animateValue = (index: number, target: number) => {
  const duration = 2000
  const startTime = performance.now()
  
  const updateValue = (currentTime: number) => {
    const elapsed = currentTime - startTime
    const progress = Math.min(elapsed / duration, 1)
    const easeOutQuart = 1 - Math.pow(1 - progress, 4)
    
    animatedValues.value[index] = parseFloat((target * easeOutQuart).toFixed(2))
    
    if (progress < 1) {
      requestAnimationFrame(updateValue)
    }
  }
  
  requestAnimationFrame(updateValue)
}

onMounted(() => {
  setTimeout(() => {
    isVisible.value = true
    metrics.forEach((metric, index) => {
      setTimeout(() => {
        animateValue(index, metric.value)
      }, index * 200)
    })
  }, 300)
})
</script>

<template>
  <section class="py-24 lg:py-32">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-8 lg:gap-12">
        <div 
          v-for="(metric, index) in metrics" 
          :key="index"
          :class="[
            'text-center transition-all duration-700',
            isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8',
          ]"
          :style="{ transitionDelay: `${index * 100}ms` }"
        >
          <div class="text-4xl lg:text-6xl font-display mb-2">
            {{ animatedValues[index] }}{{ metric.suffix }}
          </div>
          <div class="text-lg font-semibold mb-1">{{ metric.label }}</div>
          <div class="text-sm text-muted-foreground">{{ metric.description }}</div>
        </div>
      </div>
    </div>
  </section>
</template>
