<script setup lang="ts">
import { RouterLink } from "vue-router"
import {
  Search,
  Filter,
  Star,
  Zap,
  TrendingUp,
  Pencil,
  Megaphone,
  Image as ImageIcon,
  Video,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"

const categories = [
  { id: "all", label: "全部工具", count: 86 },
  { id: "ec", label: "电商运营", count: 24 },
  { id: "content", label: "内容营销", count: 18 },
]

const hotTags = ["618 大促", "小红书爆款", "短视频脚本", "商品主图"]

const tools = [
  {
    name: "电商商品文案生成",
    tag: "电商运营",
    desc: "根据卖点生成淘宝/京东标题、详情页与短描述",
    cost: 12,
    hot: true,
    used: "1.2 万",
    rating: 4.9,
    icon: Pencil,
    slug: "ec-copy",
  },
  {
    name: "小红书种草笔记",
    tag: "内容营销",
    desc: "结合人群标签生成爆款开头与正文",
    cost: 18,
    hot: true,
    used: "8,432",
    rating: 4.8,
    icon: Megaphone,
    slug: "xhs-note",
  },
  {
    name: "商品主图生成",
    tag: "AI 绘图",
    desc: "上传商品白底图，一键生成多场景营销主图",
    cost: 60,
    hot: true,
    used: "5,210",
    rating: 4.7,
    icon: ImageIcon,
    slug: "main-image",
  },
  {
    name: "短视频口播脚本",
    tag: "视频生成",
    desc: "针对抖音/视频号生成口播脚本与分镜",
    cost: 25,
    used: "3,890",
    rating: 4.7,
    icon: Video,
    slug: "video-script",
  },
]
</script>

<template>
  <AppShell title="AI 工具超市" description="企业级 AI 工具，覆盖电商、内容、客服、运营全链路">
    <div class="flex flex-col xl:flex-row">
      <div class="hidden xl:block w-56 shrink-0 border-r border-border bg-card/40 p-5 space-y-6">
        <div>
          <p class="mb-3 flex items-center gap-2 text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <Filter class="h-3 w-3" /> 分类
          </p>
          <ul class="space-y-1">
            <li v-for="(c, i) in categories" :key="c.id">
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors"
                :class="
                  i === 0 ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground/80 hover:bg-secondary'
                "
              >
                <span>{{ c.label }}</span>
                <span class="text-[11px] text-muted-foreground">{{ c.count }}</span>
              </button>
            </li>
          </ul>
        </div>
      </div>

      <div class="flex-1 px-6 py-6 space-y-6 min-w-0">
        <div class="space-y-4">
          <div class="relative max-w-2xl">
            <Search
              class="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground pointer-events-none"
            />
            <input
              class="flex h-12 w-full rounded-lg border border-border bg-card pl-11 pr-32 text-sm shadow-sm"
              placeholder="搜索工具名称、场景、关键词…"
            />
            <button
              type="button"
              class="absolute right-1.5 top-1.5 h-9 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground"
            >
              搜索
            </button>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <span class="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <TrendingUp class="h-3 w-3 text-destructive" /> 热门搜索：
            </span>
            <button
              v-for="(t, i) in hotTags"
              :key="t"
              type="button"
              class="rounded-full border px-2.5 py-1 text-xs font-medium transition"
              :class="
                i < 2
                  ? 'border-destructive/30 bg-destructive/5 text-destructive'
                  : 'border-border bg-card text-foreground/80 hover:border-primary/40'
              "
            >
              {{ t }}
            </button>
          </div>
        </div>

        <div class="flex items-center justify-between border-b border-border pb-3">
          <div class="flex items-center gap-2">
            <h3 class="text-sm font-semibold">全部工具</h3>
            <span class="rounded-md bg-secondary px-2 py-0.5 text-[11px] text-secondary-foreground">
              共 {{ tools.length }} 个
            </span>
          </div>
        </div>

        <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="tool in tools"
            :key="tool.name"
            class="group rounded-xl border border-border bg-card p-5 transition hover:border-primary/40 hover:shadow-md"
          >
            <div class="flex items-start gap-3">
              <div
                class="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-accent to-primary/10 text-primary"
              >
                <component :is="tool.icon" class="h-5 w-5" />
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <h4 class="text-sm font-semibold truncate">{{ tool.name }}</h4>
                  <span
                    v-if="tool.hot"
                    class="shrink-0 rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive"
                  >
                    HOT
                  </span>
                </div>
                <p class="mt-0.5 inline-block rounded bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">
                  {{ tool.tag }}
                </p>
              </div>
            </div>
            <p class="mt-3 text-xs text-muted-foreground line-clamp-2 min-h-[32px]">{{ tool.desc }}</p>
            <div class="mt-4 flex items-center justify-between text-[11px] text-muted-foreground">
              <span class="inline-flex items-center gap-1">
                <Star class="h-3 w-3 fill-warning text-warning" /> {{ tool.rating }}
              </span>
              <span>已使用 {{ tool.used }} 次</span>
            </div>
            <div class="mt-3 flex items-center justify-between border-t border-border pt-3">
              <span class="inline-flex items-center gap-1 text-xs font-medium">
                <Zap class="h-3.5 w-3.5 text-warning" /> {{ tool.cost }}
                <span class="text-muted-foreground font-normal">算力 / 次</span>
              </span>
              <RouterLink
                :to="'/tools/' + tool.slug"
                class="inline-flex h-7 items-center rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:opacity-90"
              >
                立即使用
              </RouterLink>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
