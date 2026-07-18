<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import Button from '@/components/ui/Button.vue'
import { Menu, X } from 'lucide-vue-next'
import { BRAND_LOGO_URL } from '@/config/brand'

const emit = defineEmits<{
  'open-login': []
}>()

const isScrolled = ref(false)
const isMobileMenuOpen = ref(false)

const navLinks = [
  { name: '工具市场', href: '/marketplace' },
  { name: '社区作品', href: '/community' },
  { name: '工作流', href: '#features' },
  { name: '定价', href: '#pricing' },
]

const logoSrc = BRAND_LOGO_URL

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
    <RouterLink to="/" class="flex items-center">
      <img
        :src="logoSrc"
        class="h-10 w-auto max-w-[150px] object-contain [filter:drop-shadow(0_0_8px_rgb(34_211_238_/_0.35))]"
        alt="科创点AI"
      >
    </RouterLink>
        
        <!-- Desktop Navigation -->
        <div class="hidden lg:flex items-center gap-8">
          <template v-for="link in navLinks" :key="link.name">
            <RouterLink
              v-if="link.href.startsWith('/')"
              :to="link.href"
              class="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              {{ link.name }}
            </RouterLink>
            <a
              v-else
              :href="link.href"
              class="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              {{ link.name }}
            </a>
          </template>
        </div>
        
        <!-- Auth Buttons -->
        <div class="hidden lg:flex items-center gap-4">
          <Button variant="ghost" class="text-sm" @click="emit('open-login')">登录</Button>
          <Button class="text-sm" @click="emit('open-login')">免费体验</Button>
        </div>
        
        <!-- Mobile Menu Button -->
        <button
          class="lg:hidden p-2"
          :aria-label="isMobileMenuOpen ? '关闭导航菜单' : '打开导航菜单'"
          :aria-expanded="isMobileMenuOpen"
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
        <RouterLink
          v-for="link in navLinks.filter((item) => item.href.startsWith('/'))"
          :key="link.name"
          :to="link.href"
          class="block py-2 text-sm font-medium text-muted-foreground hover:text-foreground"
          @click="isMobileMenuOpen = false"
        >
          {{ link.name }}
        </RouterLink>
        <a
          v-for="link in navLinks" 
          :key="link.name"
          v-show="!link.href.startsWith('/')"
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
