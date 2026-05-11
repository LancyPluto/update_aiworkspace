<script setup lang="ts">
import { RouterLink, useRoute } from "vue-router"
import {
  LayoutGrid,
  Store,
  ListChecks,
  Wallet,
  FolderHeart,
  Sparkles,
  ShieldCheck,
  ChevronRight,
} from "lucide-vue-next"
import { userRoutes } from "@/router/userRoutes"

withDefaults(
  defineProps<{
    title?: string
    description?: string
  }>(),
  {},
)

const route = useRoute()

const userNav = [
  { href: userRoutes.dashboard, label: "工作台", icon: LayoutGrid },
  { href: userRoutes.toolList, label: "AI 工具超市", icon: Store },
  { href: userRoutes.myTasks, label: "我的任务", icon: ListChecks },
  { href: userRoutes.library, label: "素材库", icon: FolderHeart },
  { href: userRoutes.billing, label: "会员与算力", icon: Wallet },
]

function isActive(href: string) {
  if (href === userRoutes.dashboard) return route.path === href
  return route.path === href || route.path.startsWith(href + "/")
}
</script>

<template>
  <div class="flex min-h-screen bg-background text-foreground">
    <aside class="hidden lg:flex w-60 shrink-0 flex-col border-r border-border bg-card">
      <div class="flex h-16 items-center gap-2.5 px-5 border-b border-border">
        <div class="flex h-9 w-9 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <Sparkles class="h-5 w-5" />
        </div>
        <div class="flex flex-col leading-tight">
          <span class="text-sm font-semibold">智擎 AI</span>
          <span class="text-[11px] text-muted-foreground">经营助手平台</span>
        </div>
      </div>

      <nav class="flex-1 overflow-y-auto px-3 py-4">
        <p class="px-3 pb-2 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">工作区</p>
        <ul class="flex flex-col gap-1">
          <li v-for="item in userNav" :key="item.href">
            <RouterLink
              :to="item.href"
              class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors"
              :class="
                isActive(item.href)
                  ? 'bg-primary/10 text-primary'
                  : 'text-foreground/80 hover:bg-secondary'
              "
            >
              <component :is="item.icon" class="h-4 w-4" />
              <span>{{ item.label }}</span>
            </RouterLink>
          </li>
        </ul>

        <p class="px-3 pt-6 pb-2 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">管理</p>
        <div
          class="flex items-center justify-between rounded-md px-3 py-2 text-sm text-muted-foreground opacity-70 cursor-not-allowed"
        >
          <span class="flex items-center gap-3">
            <ShieldCheck class="h-4 w-4" />
            进入管理后台
          </span>
          <ChevronRight class="h-4 w-4" />
        </div>
      </nav>

      <div class="border-t border-border p-4">
        <div class="rounded-lg border border-border bg-accent/40 p-3">
          <p class="text-xs font-medium">本月剩余算力</p>
          <p class="mt-1 text-lg font-semibold text-primary">
            12,480 <span class="text-xs font-normal text-muted-foreground">/ 30,000</span>
          </p>
          <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-secondary">
            <div class="h-full w-[42%] rounded-full bg-primary" />
          </div>
          <RouterLink
            :to="userRoutes.billing"
            class="mt-3 block text-center text-xs font-medium text-primary hover:underline"
          >
            充值 / 升级套餐 →
          </RouterLink>
        </div>
      </div>
    </aside>

    <div class="flex flex-1 flex-col min-w-0">
      <header class="sticky top-0 z-30 flex h-16 items-center border-b border-border bg-card/80 px-6 backdrop-blur">
        <div class="min-w-0">
          <h1 v-if="title" class="text-base font-semibold truncate">{{ title }}</h1>
          <p v-if="description" class="text-xs text-muted-foreground truncate">{{ description }}</p>
        </div>
      </header>
      <main class="flex-1 overflow-x-hidden">
        <slot />
      </main>
    </div>
  </div>
</template>
