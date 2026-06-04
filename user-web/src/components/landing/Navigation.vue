<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Button from '@/components/ui/Button.vue'
import { Menu, X } from 'lucide-vue-next'

const emit = defineEmits<{
  'open-login': []
}>()

const isScrolled = ref(false)
const isMobileMenuOpen = ref(false)

const navLinks = [
  { name: 'AI 工具集', href: '#features' },
  { name: '智能推荐', href: '#why-us' },
  { name: '定价', href: '#pricing' },
  { name: '关于我们', href: '#about' },
]

const handleScroll = () => {
  isScrolled.value = window.scrollY > 20
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})

const toggleMobileMenu = () => {
  isMobileMenuOpen.value = !isMobileMenuOpen.value
}
</script>

<template>
  <nav 
    :class="[
      'fixed top-0 left-0 right-0 z-50 transition-all duration-300',
      isScrolled ? 'bg-background/80 backdrop-blur-md border-b border-border' : 'bg-transparent'
    ]"
  >
   <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
  <div class="flex items-center justify-between h-16 lg:h-20">
    <!-- Logo -->
    <a href="#" class="flex items-center gap-2">
      <img src="/logo.svg" class="w-8 h-8 rounded-lg object-contain" alt="logo">
      <span class="font-semibold text-lg">科创点AI</span>
    </a>
        
        <!-- Desktop Navigation -->
        <div class="hidden lg:flex items-center gap-8">
          <a 
            v-for="link in navLinks" 
            :key="link.name"
            :href="link.href"
            class="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
          >
            {{ link.name }}
          </a>
        </div>
        
        <!-- Auth Buttons -->
        <div class="hidden lg:flex items-center gap-4">
          <Button variant="ghost" class="text-sm" @click="emit('open-login')">登录</Button>
          <Button class="text-sm" @click="emit('open-login')">免费体验</Button>
        </div>
        
        <!-- Mobile Menu Button -->
        <button 
          class="lg:hidden p-2"
          @click="toggleMobileMenu"
        >
          <Menu v-if="!isMobileMenuOpen" class="w-6 h-6" />
          <X v-else class="w-6 h-6" />
        </button>
      </div>
    </div>
    
    <!-- Mobile Menu -->
    <div 
      v-if="isMobileMenuOpen"
      class="lg:hidden bg-background border-t border-border"
    >
      <div class="px-6 py-4 space-y-3">
        <a 
          v-for="link in navLinks" 
          :key="link.name"
          :href="link.href"
          class="block py-2 text-sm font-medium text-muted-foreground hover:text-foreground"
          @click="isMobileMenuOpen = false"
        >
          {{ link.name }}
        </a>
        <div class="pt-4 space-y-3">
          <Button variant="ghost" class="w-full" @click="emit('open-login'); isMobileMenuOpen = false">登录</Button>
          <Button class="w-full" @click="emit('open-login'); isMobileMenuOpen = false">免费体验</Button>
        </div>
      </div>
    </div>
  </nav>
</template>
