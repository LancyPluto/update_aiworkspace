<script setup lang="ts">
const topRow = [
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/AI生成的梦幻风景.png', title: 'AI 生成的梦幻风景' },
  { type: 'video', src: 'https://www.w3schools.com/html/mov_bbb.mp4', thumbnail: 'https://images.unsplash.com/photo-1620712943543-bcc4688e7485?w=600&h=400&fit=crop', title: 'AI 视频生成示例' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/产品图生成.png', title: '产品图生成' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/抽象.png', title: 'AI 抽象画作' },
  { type: 'video', src: 'https://cdn.wlcloudai.com/static/猴子视频.mp4', thumbnail: 'https://images.unsplash.com/photo-1611162617474-5b21e879e113?w=600&h=400&fit=crop', title: 'AI 视频创作' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/赛博朋克.png', title: '赛博朋克风格' },
  { type: 'video', src: 'https://cdn.wlcloudai.com/static/3D动画生成.mp4', thumbnail: 'https://images.unsplash.com/photo-1633356122544-f134324a6cee?w=600&h=400&fit=crop', title: '3D 动画生成' },
]

const bottomRow = [
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/人像艺术.png', title: '人像艺术' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/油画风.png', title: '油画风格转换' },
  { type: 'video', src: 'https://www.w3schools.com/html/movie.mp4', thumbnail: 'https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=600&h=400&fit=crop', title: '风景视频生成' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/动物肖像图.png', title: '动物肖像' },
  { type: 'image', src: 'https://cdn.wlcloudai.com/static/漫剧.png', title: '动漫插画' },
  { type: 'video', src: 'https://www.w3schools.com/html/mov_bbb.mp4', thumbnail: 'https://images.unsplash.com/photo-1614854262318-831574f15f1f?w=600&h=400&fit=crop', title: '产品视频' },
  { type: 'image', src: 'https://images.unsplash.com/photo-1614851099511-773084f6911d?w=600&h=400&fit=crop', title: '渐变艺术' },
]

const marqueeTop = [...topRow, ...topRow]
const marqueeBottom = [...bottomRow, ...bottomRow]
</script>

<template>
  <section class="py-24 lg:py-32 bg-muted/50 overflow-hidden">
    <div class="max-w-[1400px] mx-auto px-6 lg:px-12">
      <div class="text-center mb-16">
        <span class="inline-flex items-center gap-2 text-sm font-mono text-muted-foreground mb-4">
          <span class="w-6 h-px bg-foreground/30" />
          用户作品
        </span>
        <h2 class="text-3xl lg:text-5xl font-display tracking-tight mb-6">
          AI 创作成果展示
        </h2>
        <p class="text-lg text-muted-foreground">
          每一个工具背后，都是该领域最优质的 AI 模型在做支撑。
        </p>
      </div>

      <div class="space-y-8">
        <div class="relative">
          <div class="flex animate-marquee-right whitespace-nowrap">
            <div
              v-for="(item, index) in marqueeTop"
              :key="`top-${index}`"
              class="flex-shrink-0 mx-4"
            >
              <div class="relative w-[320px] h-[220px] rounded-2xl overflow-hidden group cursor-pointer">
                <template v-if="item.type === 'video'">
                  <video
                    :src="item.src"
                    :poster="item.thumbnail"
                    muted
                    loop
                    playsinline
                    preload="metadata"
                    class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                  />
                </template>
                <template v-else>
                  <img
                    :src="item.src"
                    :alt="item.title"
                    class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                    width="320"
                    height="220"
                    loading="lazy"
                    decoding="async"
                  />
                </template>
                <div class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/70 to-transparent p-4 opacity-0 group-hover:opacity-100 transition-opacity">
                  <p class="text-white text-sm font-medium">{{ item.title }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="relative">
          <div class="flex animate-marquee-left whitespace-nowrap">
            <div
              v-for="(item, index) in marqueeBottom"
              :key="`bottom-${index}`"
              class="flex-shrink-0 mx-4"
            >
              <div class="relative w-[320px] h-[220px] rounded-2xl overflow-hidden group cursor-pointer">
                <template v-if="item.type === 'video'">
                  <video
                    :src="item.src"
                    :poster="item.thumbnail"
                    muted
                    loop
                    playsinline
                    preload="metadata"
                    class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                  />
                </template>
                <template v-else>
                  <img
                    :src="item.src"
                    :alt="item.title"
                    class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                    width="320"
                    height="220"
                    loading="lazy"
                    decoding="async"
                  />
                </template>
                <div class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/70 to-transparent p-4 opacity-0 group-hover:opacity-100 transition-opacity">
                  <p class="text-white text-sm font-medium">{{ item.title }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
@keyframes marquee-right {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}

@keyframes marquee-left {
  from { transform: translateX(-50%); }
  to { transform: translateX(0); }
}

.animate-marquee-right {
  animation: marquee-right 30s linear infinite;
}

.animate-marquee-left {
  animation: marquee-left 30s linear infinite;
}
</style>
