<script setup lang="ts">
import { ref, onMounted } from 'vue'
import Button from '@/components/ui/Button.vue'
import { ArrowRight } from 'lucide-vue-next'
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
  <section class="relative min-h-screen flex flex-col justify-center overflow-hidden">
    <!-- Animated sphere background -->
    <div class="absolute right-0 top-1/2 -translate-y-1/2 w-[600px] h-[600px] lg:w-[800px] lg:h-[800px] opacity-40 pointer-events-none">
      <AnimatedSphere />
    </div>
    
    <!-- Subtle grid lines -->
    <div class="absolute inset-0 overflow-hidden pointer-events-none opacity-30">
      <div v-for="i in 8" :key="`h-${i}`" 
           class="absolute h-px bg-foreground/10"
           :style="{ top: `${12.5 * (i + 1)}%`, left: 0, right: 0 }" />
      <div v-for="i in 12" :key="`v-${i}`" 
           class="absolute w-px bg-foreground/10"
           :style="{ left: `${8.33 * (i + 1)}%`, top: 0, bottom: 0 }" />
    </div>
    
    <div class="relative z-10 max-w-[1400px] mx-auto px-6 lg:px-12 py-32 lg:py-40">
      <!-- Eyebrow -->
      <div 
        :class="[
          'mb-8 transition-all duration-700',
          isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
        ]"
      >
        <span class="inline-flex items-center gap-3 text-sm font-mono text-muted-foreground">
          <span class="w-8 h-px bg-foreground/30" />
          你的全能 AI 工具箱
        </span>
      </div>
      
      <!-- Main headline -->
      <div class="mt-4 lg:mt-18 mb-20">
        <HeroInteractiveHeadline />
      </div>
      
      <!-- Description -->
      <div class="grid lg:grid-cols-2 gap-12 lg:gap-24 items-end mt-6 lg:mt-25">
        <p 
          :class="[
            'text-lg lg:text-2xl text-muted-foreground leading-relaxed max-w-xl transition-all duration-700 delay-200',
            isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
          ]"
        >
          不用纠结选哪个 AI，我们帮你挑最擅长的那个。
          文生图、图生视频、音频生成、工作流编排，一个平台全搞定。
        </p>
        
        <!-- CTAs -->
        <div 
          :class="[
            'flex flex-col sm:flex-row items-start gap-4 transition-all duration-700 delay-300',
            isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
          ]"
        >
          <Button 
            size="lg" 
            class="bg-foreground hover:bg-foreground/90 text-background px-8 h-14 text-base rounded-full group"
            @click="emit('open-login')"
          >
            免费开始试用
            <ArrowRight class="w-4 h-4 ml-2 transition-transform group-hover:translate-x-1" />
          </Button>
          <Button 
            size="lg" 
            class="h-14 px-8 rounded-full text-white"
            @click="emit('open-login')"
          >
            查看工具集
          </Button>
        </div>
      </div>
    </div>
    
    <!-- Stats marquee -->
    <div 
      :class="[
        'absolute bottom-24 left-0 right-0 transition-all duration-700 delay-500',
        isVisible ? 'opacity-100' : 'opacity-0'
      ]"
    >
      <div class="flex gap-16 animate-marquee whitespace-nowrap">
        <template v-for="i in 2" :key="i">
          <div class="flex gap-16">
            <div 
              v-for="stat in [
                { value: '50+', label: '集成 AI 模型', company: 'GPT-4o' },
                { value: '10+', label: '精品创作工具', company: 'Midjourney' },
                { value: '10x', label: '效率提升', company: 'Claude' },
                { value: '0元', label: '新用户免费试用', company: '无需信用卡' },
              ]"
              :key="`${stat.company}-${i}`"
              class="flex items-baseline gap-4"
            >
              <span class="text-4xl lg:text-5xl font-display">{{ stat.value }}</span>
              <span class="text-sm text-muted-foreground">
                {{ stat.label }}
                <span class="block font-mono text-xs mt-1">{{ stat.company }}</span>
              </span>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
@keyframes marquee {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

.animate-marquee {
  animation: marquee 30s linear infinite;
}
</style>
