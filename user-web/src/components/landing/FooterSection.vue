<script setup lang="ts">
import Separator from '@/components/ui/Separator.vue'

const footerLinks = {
  product: {
    title: '产品',
    links: [
      { name: 'AI 工具集', href: '#features' },
      { name: '智能匹配', href: '#why-us' },
      { name: '定价', href: '#pricing' },
      { name: '更新日志', href: '#' },
    ],
  },
  company: {
    title: '公司',
    links: [
      { name: '关于我们', href: '#' },
      { name: '博客', href: '#' },
      { name: '招聘', href: '#' },
      { name: '联系我们', href: '#' },
    ],
  },
  resources: {
    title: '资源',
    links: [
      { name: '文档', href: '#' },
      { name: 'API 参考', href: '#' },
      { name: '状态', href: '#' },
      { name: '社区', href: '#' },
    ],
  },
  legal: {
    title: '法律',
    links: [
      { name: '隐私政策', href: '#' },
      { name: '服务条款', href: '#' },
      { name: 'Cookie 政策', href: '#' },
      { name: '安全', href: '#' },
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
          <a href="#" class="flex items-center gap-2 mb-4">
            <div class="w-8 h-8 rounded-lg bg-foreground flex items-center justify-center">
              <span class="text-background font-bold text-sm">科</span>
            </div>
            <span class="font-semibold text-lg">科创点AI</span>
          </a>
          <p class="text-sm text-muted-foreground mb-6">
            让每个人都能轻松使用最强的 AI 能力。
          </p>
        </div>
        
        <!-- Links -->
        <div v-for="(section, key) in footerLinks" :key="key">
          <h4 class="font-semibold mb-4">{{ section.title }}</h4>
          <ul class="space-y-3">
            <li v-for="link in section.links" :key="link.name">
              <a 
                :href="link.href" 
                class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                {{ link.name }}
              </a>
            </li>
          </ul>
        </div>
      </div>
      
      <Separator class="my-8" />
      
      <div class="flex flex-col md:flex-row items-center justify-between gap-4">
        <p class="text-sm text-muted-foreground">
          2026 科创点AI. All rights reserved.
        </p>
        <div class="flex items-center gap-6">
          <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors">
            微信公众号
          </a>
          <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors">
            B站
          </a>
          <a href="#" class="text-sm text-muted-foreground hover:text-foreground transition-colors">
            小红书
          </a>
        </div>
      </div>
    </div>
  </footer>
</template>
