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
  Images,
  ChevronDown,
  Moon,
  PanelLeft,
  PanelLeftClose,
  Sparkles,
  Sun,
  Search,
  Plus,
  Bell,
  Headphones,
  X,
  Menu,
} from "lucide-vue-next"
import { ref, onMounted, onUnmounted, computed, watch } from "vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchCustomerServiceSettings } from "@/api/settingsApi"
import type { CustomerServiceSettings } from "@/api/settingsApi"
import type { CreditAccount } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import MemberBadge from "@/components/MemberBadge/MemberBadge.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { applyAppTheme, getStoredTheme, storeAppTheme, type AppTheme } from "@/utils/theme"

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
const theme = ref<AppTheme>("light")
const sidebarOpen = ref(true)
const expandedGroups = ref<Set<string>>(new Set())
const customerServiceOpen = ref(false)
/** Vite publicDir=asset，kf.jpg 对外路径为 /kf.jpg */
const DEFAULT_CUSTOMER_SERVICE_QR = "/kf.jpg"

const customerService = ref<CustomerServiceSettings>({
  enabled: true,
  title: "联系客服",
  description: "扫码添加客服，获取使用支持",
  qrCodeUrl: DEFAULT_CUSTOMER_SERVICE_QR,
})

const customerServiceQrSrc = computed(() => {
  const url = customerService.value.qrCodeUrl?.trim()
  return url || DEFAULT_CUSTOMER_SERVICE_QR
})

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
  { type: "link", href: "/community", label: "社区发现", icon: Images },
  { type: "link", href: "/community/inspirations", label: "灵感收藏", icon: FolderHeart },
  { type: "link", href: "/billing", label: "会员与算力", icon: Wallet },
]

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function toggleTheme() {
  theme.value = theme.value === "dark" ? "light" : "dark"
}

watch(sidebarOpen, (open) => {
  localStorage.setItem(SIDEBAR_OPEN_KEY, open ? "1" : "0")
})

watch(theme, (next) => {
  applyAppTheme(next)
  storeAppTheme(next)
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
  if (path === "/community") {
    return route.path === path || route.path.startsWith("/community/posts/")
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
const isAgentRoute = computed(() => route.path === "/agent" || route.path.startsWith("/agent/"))
const safeUserName = computed(() => {
  const nickname = auth.user?.nickname?.trim() || ""
  const username = auth.user?.username?.trim() || ""
  const badEncoding = /�|锟|阖€|鍍|\uFFFD/.test(nickname)
  if (nickname && !badEncoding) return nickname
  if (username) return username
  const readablePrefix = nickname.match(/^[\w\s.-]{2,}/)?.[0]?.trim()
  return readablePrefix || "User"
})

function handleCreditsUpdated(event: Event) {
  const detail = (event as CustomEvent<CreditAccount | undefined>).detail
  if (detail) {
    credit.value = detail
    return
  }
  void loadCreditAccount()
}

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

const customerServiceQrBroken = ref(false)

function onCustomerServiceQrError() {
  customerServiceQrBroken.value = true
}

watch(customerServiceQrSrc, () => {
  customerServiceQrBroken.value = false
})

async function loadCustomerServiceSettings() {
  try {
    customerService.value = await fetchCustomerServiceSettings({ token: auth.token })
    customerServiceQrBroken.value = false
  } catch {
    customerService.value = {
      enabled: true,
      title: "联系客服",
      description: "扫码添加客服，获取使用支持",
      qrCodeUrl: DEFAULT_CUSTOMER_SERVICE_QR,
    }
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
  theme.value = getStoredTheme()
  applyAppTheme(theme.value)

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
  window.addEventListener("credits:updated", handleCreditsUpdated)
  await Promise.all([loadCreditAccount(), loadCustomerServiceSettings()])
})

onUnmounted(() => {
  window.removeEventListener("credits:updated", handleCreditsUpdated)
})
</script>

<template>
  <div
    class="app-shell-root flex h-screen overflow-hidden bg-background text-foreground"
    :style="{ '--app-sidebar-width': sidebarOpen ? '268px' : '0px' }"
  >
    <aside
      class="hidden h-full w-[268px] shrink-0 flex-col border-r border-white/8 bg-[#141414]"
      :class="sidebarOpen ? 'lg:flex' : 'lg:hidden'"
    >
      <div class="flex h-20 shrink-0 items-center gap-3 px-6">
        <img src="/logo.svg" alt="AI Tool Market" class="h-10 w-10 rounded-xl object-contain" />
        <div class="flex flex-col leading-tight">
          <span class="text-lg font-semibold">科创点AI</span>
          <span class="text-[11px] text-white/45">经营助手平台</span>
        </div>
      </div>

      <nav class="min-h-0 flex-1 overflow-y-auto px-4 py-2">
        <ul class="flex flex-col gap-2">
          <template v-for="item in userNav" :key="item.type === 'link' ? item.href : item.id">
            <li v-if="item.type === 'link'">
              <RouterLink
                :to="item.href"
                class="relative flex items-center gap-3 rounded-2xl px-4 py-3 text-[15px] font-semibold transition-colors before:absolute before:left-0 before:top-1/2 before:h-6 before:w-px before:-translate-y-1/2 before:rounded-full before:bg-transparent before:transition-colors"
                :class="
                  isActive(item.href)
                    ? 'bg-white/[0.045] text-white before:bg-primary'
                    : 'text-white/70 hover:bg-white/6 hover:text-white'
                "
              >
                <component
                  :is="item.icon"
                  class="h-5 w-5 transition-colors"
                  :class="isActive(item.href) ? 'text-primary' : 'text-white/68'"
                />
                <span>{{ item.label }}</span>
              </RouterLink>
            </li>

            <li v-else>
              <button
                type="button"
                class="flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-[15px] font-semibold transition-colors"
                :class="
                  isGroupActive(item)
                    ? 'text-white'
                    : 'text-white/70 hover:bg-white/6 hover:text-white'
                "
                :aria-expanded="isGroupExpanded(item.id)"
                @click="toggleGroup(item.id)"
              >
                <component
                  :is="item.icon"
                  class="h-5 w-5 shrink-0 transition-colors"
                  :class="isGroupActive(item) ? 'text-white/85' : 'text-white/60'"
                />
                <span class="flex-1 text-left">{{ item.label }}</span>
                <ChevronDown
                  class="h-4 w-4 shrink-0 text-white/40 transition-transform duration-200"
                  :class="{ 'rotate-180': isGroupExpanded(item.id) }"
                />
              </button>

              <div
                class="grid transition-[grid-template-rows] duration-200 ease-in-out"
                :class="isGroupExpanded(item.id) ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'"
              >
                <div class="overflow-hidden">
                  <ul class="ml-10 mt-1 flex flex-col gap-1 border-l border-white/10 pl-4">
                    <li v-for="child in item.children" :key="child.href">
                      <RouterLink
                        :to="child.href"
                        class="relative flex items-center gap-2.5 rounded-xl py-2 pl-3 pr-2 text-sm font-medium transition-colors before:absolute before:-left-[17px] before:top-1/2 before:h-px before:w-3 before:bg-white/10"
                        :class="
                          isActive(child.href)
                            ? 'bg-white/[0.035] text-white'
                            : 'text-white/55 hover:bg-white/6 hover:text-white'
                        "
                      >
                        <component
                          :is="child.icon"
                          class="h-3.5 w-3.5 shrink-0 transition-colors"
                          :class="isActive(child.href) ? 'text-primary' : 'text-white/42'"
                        />
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

      <div class="shrink-0 p-5">
        <div class="rounded-2xl border border-white/8 bg-white/[0.025] p-4 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.035)]">
          <p class="text-[11px] font-medium tracking-wide text-white/42">可用算力</p>
          <p class="mt-1 font-mono text-[12px] tabular-nums text-white/76">
            {{ credit ? credit.available.toLocaleString() : '---' }}
            <span class="font-normal text-white/32">
              / {{ credit ? credit.totalGranted.toLocaleString() : '---' }}
            </span>
          </p>
          <div class="mt-3 h-[3px] overflow-hidden rounded-full bg-white/8">
            <div
              class="h-full rounded-full bg-gradient-to-r from-primary/75 to-sky-300/65"
              :style="{ width: Math.min(creditPercent, 100) + '%' }"
            />
          </div>
          <RouterLink
            :to="'/billing'"
            class="mt-3 block text-center text-[11px] font-medium text-primary/80 transition hover:text-primary hover:drop-shadow-[0_0_10px_rgb(176_92_255_/_0.35)]"
          >
            充值 / 升级套餐
          </RouterLink>
        </div>
      </div>
    </aside>

    <div class="flex h-full min-h-0 min-w-0 flex-1 flex-col">
      <header class="z-30 flex h-20 shrink-0 items-center gap-4 border-b border-white/8 bg-[#151515]/95 px-5 backdrop-blur-xl">
        <button
          type="button"
          class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/[0.04] text-white/70 hover:bg-white/10 hover:text-white lg:hidden"
          aria-label="菜单"
        >
          <Menu class="h-5 w-5" aria-hidden="true" />
        </button>
        <button
          type="button"
          class="hidden h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/[0.04] text-white/60 hover:bg-white/10 hover:text-white lg:inline-flex"
          :aria-label="sidebarOpen ? '隐藏侧栏' : '显示侧栏'"
          :aria-expanded="sidebarOpen"
          @click="toggleSidebar"
        >
          <PanelLeftClose v-if="sidebarOpen" class="h-4 w-4" aria-hidden="true" />
          <PanelLeft v-else class="h-4 w-4" aria-hidden="true" />
        </button>
        <div class="min-w-0 flex-1 lg:max-w-[360px]">
          <h1 v-if="title" class="text-base font-semibold truncate">{{ title }}</h1>
          <p v-if="description" class="text-xs text-white/45 truncate">{{ description }}</p>
        </div>
        <div class="hidden h-12 min-w-0 flex-1 items-center rounded-full bg-white/[0.07] px-4 ring-1 ring-white/8 xl:flex">
          <span class="pr-4 text-sm text-white/70">全部</span>
          <span class="h-5 w-px bg-white/10" />
          <Search class="ml-4 h-5 w-5 text-white/35" />
          <input
            class="h-full min-w-0 flex-1 bg-transparent px-3 text-sm text-white outline-none placeholder:text-white/35"
            placeholder="搜索模型、智能体和素材"
          />
        </div>
        <RouterLink
          v-if="!isAgentRoute"
          :to="userRoutes.toolList"
          class="hidden h-11 items-center gap-2 rounded-full bg-gradient-to-br from-primary/90 via-fuchsia-400/85 to-primary/80 px-5 text-sm font-semibold text-white shadow-[0_14px_34px_rgb(176_92_255_/_0.24),inset_0_1px_0_rgb(255_255_255_/_0.22)] transition hover:brightness-110 md:inline-flex"
        >
          <Plus class="h-4 w-4" />
          创建
        </RouterLink>
        <button
          type="button"
          class="hidden h-10 w-10 shrink-0 items-center justify-center rounded-full text-white/55 hover:bg-white/8 hover:text-white md:inline-flex"
          aria-label="通知"
        >
          <Bell class="h-5 w-5" />
        </button>
        <button
          v-if="customerService.enabled"
          type="button"
          class="hidden h-10 shrink-0 items-center gap-2 rounded-full border border-white/10 bg-white/[0.04] px-4 text-sm font-medium text-white/70 transition-colors hover:bg-white/10 hover:text-white md:inline-flex"
          @click="customerServiceOpen = true"
        >
          <Headphones class="h-4 w-4" aria-hidden="true" />
          联系客服
        </button>
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-white/10 bg-white/[0.04] text-white/60 transition hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            :aria-label="theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'"
            :title="theme === 'dark' ? 'Light mode' : 'Dark mode'"
            :aria-pressed="theme === 'dark'"
            @click="toggleTheme"
          >
            <Sun v-if="theme === 'dark'" class="h-4 w-4" aria-hidden="true" />
            <Moon v-else class="h-4 w-4" aria-hidden="true" />
          </button>
          <template v-if="auth.isLoggedIn">
            <RouterLink
              :to="userRoutes.profile"
              class="flex items-center gap-2 rounded-full px-2 py-1 transition hover:bg-white/8"
              title="我的资料"
            >
              <MemberBadge :available="availableCredits" />
              <UserAvatar :src="auth.user?.avatarUrl" :name="safeUserName" size="sm" />
              <span class="hidden max-w-[140px] truncate text-xs text-white/60 sm:inline">{{ safeUserName }}</span>
            </RouterLink>
            <button
              type="button"
              class="text-xs text-white/45 hover:text-white"
              @click="auth.logout()"
            >
              退出
            </button>
          </template>
          <template v-else>
            <RouterLink
              :to="'/'"
              class="text-xs text-primary hover:text-white"
            >
              登录
            </RouterLink>
          </template>
        </div>
      </header>
      <main class="min-h-0 flex-1 overflow-x-hidden overflow-y-auto bg-[#111111]">
        <slot />
      </main>
    </div>

    <div
      v-if="customerServiceOpen"
      class="fixed inset-0 z-[100] flex items-center justify-center bg-black/65 px-4 backdrop-blur-sm"
      @click.self="customerServiceOpen = false"
    >
      <section class="relative w-full max-w-sm rounded-[28px] border border-white/10 bg-[#1d1d22] p-6 text-center shadow-[0_24px_80px_rgb(0_0_0_/_0.55)]">
        <button
          type="button"
          class="absolute right-4 top-4 inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 hover:bg-white/10 hover:text-white"
          aria-label="关闭联系客服"
          @click="customerServiceOpen = false"
        >
          <X class="h-4 w-4" aria-hidden="true" />
        </button>

        <div class="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-primary/15 text-primary ring-1 ring-primary/25">
          <Headphones class="h-6 w-6" aria-hidden="true" />
        </div>
        <h2 class="mt-4 text-xl font-semibold text-white">{{ customerService.title || "联系客服" }}</h2>
        <p class="mt-2 text-sm leading-6 text-white/55">
          {{ customerService.description || "扫码添加客服，获取使用支持" }}
        </p>

        <div class="mx-auto mt-5 flex aspect-square w-56 max-w-full items-center justify-center rounded-3xl bg-white p-3">
          <img
            v-if="!customerServiceQrBroken"
            :src="customerServiceQrSrc"
            :alt="customerService.title || '客服二维码'"
            class="h-full w-full rounded-2xl object-contain"
            @error="onCustomerServiceQrError"
          />
          <img
            v-else
            :src="DEFAULT_CUSTOMER_SERVICE_QR"
            :alt="customerService.title || '客服二维码'"
            class="h-full w-full rounded-2xl object-contain"
          />
        </div>
      </section>
    </div>
  </div>
</template>

