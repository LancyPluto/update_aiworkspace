<script setup lang="ts">
import Separator from '@/components/ui/Separator.vue'
import { BRAND_LOGO_URL } from '@/config/brand'

type FooterLink = { name: string; href?: string; to?: string }

const footerLinks: Record<string, { title: string; links: FooterLink[] }> = {
  product: {
    title: '产品',
    links: [
      { name: 'AI 工具集', href: '#features' },
      { name: '智能匹配', href: '#why-us' },
      { name: '定价', href: '#pricing' },
      { name: '工具市场', to: '/marketplace' },
    ],
  },
  company: {
    title: '公司',
    links: [
      { name: '关于我们', href: '#about' },
      { name: '联系我们', to: '/contact' },
    ],
  },
  resources: {
    title: '资源',
    links: [
      { name: '社区', to: '/community' },
      { name: 'AI生成内容标识', to: '/legal/aigc-labeling' },
    ],
  },
  legal: {
    title: '法律',
    links: [
      { name: '隐私政策', to: '/legal/privacy' },
      { name: '服务条款', to: '/legal/terms' },
      { name: '退款说明', to: '/legal/refund' },
    ],
  },
}
</script>

<template>
  <footer id="about" class="bg-muted/50 border-t border-border">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12 py-16">
      <div class="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-8 lg:gap-12">
        <!-- Logo -->
        <div class="col-span-2 md:col-span-4 lg:col-span-1">
          <RouterLink to="/" class="flex items-center mb-4">
            <img :src="BRAND_LOGO_URL" class="h-11 w-auto max-w-[170px] object-contain" alt="科创点AI">
          </RouterLink>
          <p class="text-sm text-muted-foreground mb-6">
            让每个人都能轻松使用最强的 AI 能力。
          </p>
        </div>
        
        <!-- Links -->
        <div v-for="(section, key) in footerLinks" :key="key">
          <h4 class="font-semibold mb-4">{{ section.title }}</h4>
          <ul class="space-y-3">
            <li v-for="link in section.links" :key="link.name">
              <RouterLink
                v-if="link.to"
                :to="link.to"
                class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                {{ link.name }}
              </RouterLink>
              <a v-else :href="link.href" class="text-sm text-muted-foreground hover:text-foreground transition-colors">{{ link.name }}</a>
            </li>
          </ul>
        </div>
      </div>
      
      <Separator class="my-8" />
      
      <div class="flex flex-col md:flex-row items-center justify-between gap-4">
        <p class="text-sm text-muted-foreground">
          2026 科创点AI. All rights reserved.
        </p>
        <RouterLink to="/contact" class="text-sm text-muted-foreground hover:text-foreground transition-colors">客服与内容投诉</RouterLink>
      </div>
    </div>
  </footer>
</template>
