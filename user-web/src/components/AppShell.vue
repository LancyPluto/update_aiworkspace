<script setup lang="ts">
import { RouterLink, useRoute } from "vue-router"
import type { Component } from "vue"
import {
  Bot,
  BrainCircuit,
  LayoutGrid,
  Store,
  ListChecks,
  Wallet,
  FolderHeart,
  ChevronDown,
  PanelLeft,
  PanelLeftClose,
  Sparkles,
} from "lucide-vue-next"
import { ref, onMounted, computed, watch } from "vue"
import { fetchCreditAccount } from "@/api/creditApi"
import type { CreditAccount } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import MemberBadge from "@/components/MemberBadge/MemberBadge.vue"

withDefaults(
  defineProps<{
    title?: string
    description?: string
  }>(),
  {},
)

type NavLink = {
  type: "link"
  href: string
  label: string
  icon: Component
}

type NavGroup = {
  type: "group"
  id: string
  label: string
  icon: Component
  children: { href: string; label: string; icon: Component }[]
}

const route = useRoute()
const auth = useAuthStore()

const SIDEBAR_OPEN_KEY = "ai_tool_market_sidebar_open"
const EXPANDED_GROUPS_KEY = "ai_tool_market_nav_expanded_groups"

const credit = ref<CreditAccount | null>(null)
const sidebarOpen = ref(true)
const expandedGroups = ref<Set<string>>(new Set())

const userNav: (NavLink | NavGroup)[] = [
  { type: "link", href: "/agent", label: "Agent", icon: Bot },
  { type: "link", href: "/dashboard", label: "工作台", icon: LayoutGrid },
  {
    type: "group",
    id: "ai-market",
    label: "AI 工具超市",
    icon: Store,
    children: [
      { href: "/marketplace", label: "大模型", icon: Sparkles },
      { href: "/agents", label: "智能体", icon: BrainCircuit },
    ],
  },
  { type: "link", href: "/tasks", label: "我的任务", icon: ListChecks },
  { type: "link", href: "/library", label: "素材库", icon: FolderHeart },
  { type: "link", href: "/billing", label: "会员与算力", icon: Wallet },
]

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

watch(sidebarOpen, (open) => {
  localStorage.setItem(SIDEBAR_OPEN_KEY, open ? "1" : "0")
})

function saveExpandedGroups() {
  localStorage.setItem(EXPANDED_GROUPS_KEY, JSON.stringify([...expandedGroups.value]))
}

function toggleGroup(id: string) {
  const next = new Set(expandedGroups.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedGroups.value = next
  saveExpandedGroups()
}

function isGroupExpanded(id: string) {
  return expandedGroups.value.has(id)
}

function isActive(path: string) {
  if (path === "/marketplace") {
    return route.path === path || route.path.startsWith("/chat/")
  }
  if (path === "/agents") {
    return route.path === path || route.path.startsWith(path + "/")
  }
  if (path === "/agent") {
    return route.path === path || route.path.startsWith(path + "/")
  }
  return route.path === path || route.path.startsWith(path + "/")
}

function isGroupActive(group: NavGroup) {
  return group.children.some((child) => isActive(child.href))
}

function ensureActiveGroupExpanded() {
  let changed = false
  const next = new Set(expandedGroups.value)
  for (const item of userNav) {
    if (item.type === "group" && isGroupActive(item) && !next.has(item.id)) {
      next.add(item.id)
      changed = true
    }
  }
  if (changed) {
    expandedGroups.value = next
    saveExpandedGroups()
  }
}

const creditPercent = computed(() => {
  if (!credit.value) return 0
  return Math.round((credit.value.available / (credit.value.totalGranted || 1)) * 100)
})

const availableCredits = computed(() => credit.value?.available ?? null)

async function loadCreditAccount() {
  if (!auth.isLoggedIn || !auth.token) {
    credit.value = null
    return
  }
  try {
    credit.value = await fetchCreditAccount({ token: auth.token })
  } catch {
    // 静默处理
  }
}

watch(() => route.path, ensureActiveGroupExpanded, { immediate: true })

watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void loadCreditAccount()
    else credit.value = null
  },
)

watch(
  () => route.path,
  (path) => {
    if (path === "/billing" && auth.isLoggedIn) void loadCreditAccount()
  },
)

onMounted(async () => {
  const saved = localStorage.getItem(SIDEBAR_OPEN_KEY)
  if (saved === "0") sidebarOpen.value = false
  if (saved === "1") sidebarOpen.value = true

  const savedGroups = localStorage.getItem(EXPANDED_GROUPS_KEY)
  if (savedGroups) {
    try {
      const parsed = JSON.parse(savedGroups) as string[]
      if (Array.isArray(parsed)) expandedGroups.value = new Set(parsed)
    } catch {
      expandedGroups.value = new Set(["ai-market"])
    }
  } else {
    expandedGroups.value = new Set(["ai-market"])
  }

  ensureActiveGroupExpanded()
  await loadCreditAccount()
})
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-background text-foreground">
    <aside
      class="fixed inset-y-0 left-0 z-40 hidden w-60 shrink-0 flex-col border-r border-border bg-card"
      :class="sidebarOpen ? 'lg:flex' : 'lg:hidden'"
    >
      <div class="flex h-16 shrink-0 items-center gap-2.5 border-b border-border px-5">
        <img src="/logo.svg" alt="AI Tool Market" class="h-9 w-9 rounded-lg object-contain" />
        <div class="flex flex-col leading-tight">
          <span class="text-sm font-semibold">智擎 AI</span>
          <span class="text-[11px] text-muted-foreground">经营助手平台</span>
        </div>
      </div>

      <nav class="flex-1 overflow-y-auto px-3 py-4 pb-36">
        <p class="px-3 pb-2 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">工作区</p>
        <ul class="flex flex-col gap-1">
          <template v-for="item in userNav" :key="item.type === 'link' ? item.href : item.id">
            <li v-if="item.type === 'link'">
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

            <li v-else>
              <button
                type="button"
                class="flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors"
                :class="
                  isGroupActive(item)
                    ? 'bg-primary/10 text-primary'
                    : 'text-foreground/80 hover:bg-secondary'
                "
                :aria-expanded="isGroupExpanded(item.id)"
                @click="toggleGroup(item.id)"
              >
                <component :is="item.icon" class="h-4 w-4 shrink-0" />
                <span class="flex-1 text-left">{{ item.label }}</span>
                <ChevronDown
                  class="h-4 w-4 shrink-0 text-muted-foreground transition-transform duration-200"
                  :class="{ 'rotate-180': isGroupExpanded(item.id) }"
                />
              </button>

              <div
                class="grid transition-[grid-template-rows] duration-200 ease-in-out"
                :class="isGroupExpanded(item.id) ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'"
              >
                <div class="overflow-hidden">
                  <ul class="mt-1 flex flex-col gap-0.5 border-l border-border/70 pl-3 ml-5">
                    <li v-for="child in item.children" :key="child.href">
                      <RouterLink
                        :to="child.href"
                        class="flex items-center gap-2.5 rounded-md py-1.5 pl-3 pr-2 text-[13px] font-medium transition-colors"
                        :class="
                          isActive(child.href)
                            ? 'bg-primary/10 text-primary'
                            : 'text-foreground/70 hover:bg-secondary hover:text-foreground'
                        "
                      >
                        <component :is="child.icon" class="h-3.5 w-3.5 shrink-0" />
                        <span>{{ child.label }}</span>
                      </RouterLink>
                    </li>
                  </ul>
                </div>
              </div>
            </li>
          </template>
        </ul>
      </nav>

      <div class="fixed bottom-0 left-0 z-50 w-60 border-t border-border bg-card p-4">
        <div class="rounded-lg border border-border bg-accent/40 p-3">
          <p class="text-xs font-medium">本月已用算力</p>
          <p class="mt-1 text-lg font-semibold text-primary">
            {{ credit ? credit.totalConsumed.toLocaleString() : '---' }}
            <span class="text-xs font-normal text-muted-foreground">
              / {{ credit ? credit.totalGranted.toLocaleString() : '---' }}
            </span>
          </p>
          <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-secondary">
            <div
              class="h-full rounded-full bg-primary"
              :style="{ width: Math.min(creditPercent, 100) + '%' }"
            />
          </div>
          <RouterLink
            :to="'/billing'"
            class="mt-3 block text-center text-xs font-medium text-primary hover:underline"
          >
            充值 / 升级套餐 →
          </RouterLink>
        </div>
      </div>
    </aside>

    <div class="flex flex-1 flex-col min-w-0" :class="sidebarOpen ? 'lg:pl-60' : ''">
      <header class="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-border bg-card/80 px-6 backdrop-blur">
        <button
          type="button"
          class="hidden lg:inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-border bg-background text-muted-foreground hover:bg-secondary hover:text-foreground"
          :aria-label="sidebarOpen ? '隐藏侧栏' : '显示侧栏'"
          :aria-expanded="sidebarOpen"
          @click="toggleSidebar"
        >
          <PanelLeftClose v-if="sidebarOpen" class="h-4 w-4" aria-hidden="true" />
          <PanelLeft v-else class="h-4 w-4" aria-hidden="true" />
        </button>
        <div class="min-w-0 flex-1">
          <h1 v-if="title" class="text-base font-semibold truncate">{{ title }}</h1>
          <p v-if="description" class="text-xs text-muted-foreground truncate">{{ description }}</p>
        </div>
        <div class="ml-auto flex items-center gap-3">
          <template v-if="auth.isLoggedIn">
            <div class="flex items-center gap-2">
              <MemberBadge :available="availableCredits" />
              <span class="text-xs text-muted-foreground">{{ auth.user?.nickname || auth.user?.username }}</span>
            </div>
            <button
              type="button"
              class="text-xs text-muted-foreground hover:text-foreground"
              @click="auth.logout()"
            >
              退出
            </button>
          </template>
          <template v-else>
            <RouterLink
              :to="'/'"
              class="text-xs text-primary hover:underline"
            >
              登录
            </RouterLink>
          </template>
        </div>
      </header>
      <main class="min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
