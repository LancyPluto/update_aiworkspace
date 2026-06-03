<script setup lang="ts">
import { Quote } from 'lucide-vue-next'
import Avatar from '@/components/ui/Avatar.vue'

const testimonials = [
  {
    quote: '以前做海报、剪视频、配文案要在好几个工具之间来回切换，流程特别乱。现在在一个平台就能搞定所有创作环节，还会自动帮我匹配最合适的能力模块，省心太多了。',
    author: '周雨',
    role: '自媒体博主',
    company: '',
  },
  {
    quote: '最惊喜的是它越用越懂我，现在每次生图都会自动匹配我习惯的风格和参数，基本不用再手动调了。',
    author: '陈思远',
    role: '设计师',
    company: '',
  },
  {
    quote: '免费试用体验了全部工具，感觉很棒。算了一下成本，比单独订阅好几个平台便宜太多了，果断升级了专业版。',
    author: '李佳',
    role: '创业者',
    company: '',
  },
]
</script>

<template>
  <section class="py-24 lg:py-32">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          用户反馈
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          大家都在用
        </h2>
        <p class="text-lg text-muted-foreground">
          从个人创作者到团队用户，都在用我们的工具提升效率。
        </p>
      </div>
      
      <div class="grid md:grid-cols-3 gap-8">
        <div 
          v-for="(testimonial, index) in testimonials" 
          :key="index"
          class="bg-card rounded-2xl p-8 border border-border hover:border-foreground/20 transition-all duration-300"
        >
          <Quote class="w-10 h-10 text-primary/20 mb-4" />
          <p class="text-lg text-foreground/90 mb-6 leading-relaxed">
            "{{ testimonial.quote }}"
          </p>
          <div class="flex items-center gap-3">
            <Avatar />
            <div>
              <div class="font-semibold">{{ testimonial.author }}</div>
              <div class="text-sm text-muted-foreground">{{ testimonial.role }}<template v-if="testimonial.company"> @ {{ testimonial.company }}</template></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
