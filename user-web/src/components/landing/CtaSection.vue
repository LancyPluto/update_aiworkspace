<script setup lang="ts">
import Button from '@/components/ui/Button.vue'
import { ArrowRight } from 'lucide-vue-next'

const emit = defineEmits<{
  'open-login': []
}>()
</script>

<template>
  <section class="py-24 lg:py-32">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="relative overflow-hidden rounded-3xl p-12 lg:p-20">
        <!-- Black-purple gradient background -->
        <div class="absolute inset-0 bg-gradient-to-br from-black via-purple-950/50 to-black" />
        
        <!-- Animated purple glow effects -->
        <div class="absolute top-0 right-0 w-96 h-96 bg-purple-500/20 rounded-full blur-3xl animate-pulse-slow" />
        <div class="absolute bottom-0 left-0 w-64 h-64 bg-violet-500/15 rounded-full blur-3xl animate-pulse-slow-delay" />
        
        <div class="relative z-10 text-center">
          <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6 text-white">
            新用户免费试用，无需信用卡
          </h2>
          <p class="text-lg text-purple-200/80 mb-8 max-w-2xl mx-auto">
            0 成本体验所有 AI 工具，觉得好用再升级。省时、省力、省钱，为什么不试试？
          </p>
          <div class="flex flex-col sm:flex-row items-center justify-center gap-4">
            <Button 
              size="lg" 
              class="breathing-button bg-gradient-to-r from-purple-600 to-violet-600 text-white hover:from-purple-500 hover:to-violet-500 px-8 h-14 text-base rounded-full group"
              @click="emit('open-login')"
            >
              免费开始使用
              <ArrowRight class="w-4 h-4 ml-2 transition-transform group-hover:translate-x-1" />
            </Button>
            <Button 
              size="lg" 
              variant="outline" 
              class="h-14 px-8 text-base rounded-full border-purple-500/50 text-purple-300 hover:bg-purple-500/20 hover:text-white transition-all duration-300"
              @click="emit('open-login')"
            >
              查看全部工具
            </Button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
@keyframes pulse-slow {
  0%, 100% { opacity: 0.2; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(1.1); }
}

@keyframes breathing {
  0%, 100% { 
    transform: scale(1); 
    box-shadow: 0 0 20px rgba(147, 51, 234, 0.4), 0 0 40px rgba(147, 51, 234, 0.2);
  }
  50% { 
    transform: scale(1.03); 
    box-shadow: 0 0 30px rgba(147, 51, 234, 0.6), 0 0 60px rgba(147, 51, 234, 0.3);
  }
}

.animate-pulse-slow {
  animation: pulse-slow 4s ease-in-out infinite;
}

.animate-pulse-slow-delay {
  animation: pulse-slow 4s ease-in-out infinite;
  animation-delay: 1s;
}

.breathing-button {
  animation: breathing 3s ease-in-out infinite;
}

.breathing-button:hover {
  animation-play-state: paused;
  transform: scale(1.05);
}
</style>