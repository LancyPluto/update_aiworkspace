<script setup lang="ts">
import { RouterLink, useRoute, useRouter } from "vue-router"
import type { Component } from "vue"
import {
  Bot,
  Compass,
  Gift,
  Home,
  Lightbulb,
  Package,
  UserRound,
  Wallet,
  WandSparkles,
  Wrench,
  FolderHeart,
  Images,
  ChevronDown,
  PanelLeft,
  PanelLeftClose,
  Sparkles,
  Search,
  Plus,
  Bell,
  Headphones,
  X,
  Menu,
  Loader2,
  Palette,
  Check,
} from "lucide-vue-next"
import { ref, onMounted, onUnmounted, computed, watch } from "vue"
import { useGlobalSearch, type GlobalSearchResultItem, type GlobalSearchScope } from "@/composables/useGlobalSearch"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchCustomerServiceSettings } from "@/api/settingsApi"
import type { CustomerServiceSettings } from "@/api/settingsApi"
import type { CreditAccount } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import MemberBadge from "@/components/MemberBadge/MemberBadge.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import {
  applyAppTheme,
  applyBrandAccent,
  BRAND_ACCENT_OPTIONS,
  getStoredBrandAccent,
  storeBrandAccent,
  type BrandAccent,
} from "@/utils/theme"
import AgentThemePicker from "@/pages/AgentHome/AgentThemePicker.vue"

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
  active?: (path: string, fullPath: string) => boolean
}

type NavSection = {
  label?: string
  items: NavLink[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const searchRootRef = ref<HTMLElement | null>(null)
const brandAccentRootRef = ref<HTMLElement | null>(null)
const scopeMenuOpen = ref(false)
const brandAccentMenuOpen = ref(false)
const brandAccent = ref<BrandAccent>("cyan")
const {
  keyword: searchKeyword,
  scope: searchScope,
  scopeLabels,
  panelOpen: searchPanelOpen,
  loading: searchLoading,
  error: searchError,
  results: searchResults,
  openPanel: openSearchPanel,
  closePanel: closeSearchPanel,
  submitSearch,
  setScope: setSearchScope,
  clearDebounce: clearSearchDebounce,
} = useGlobalSearch()

const searchScopeOptions: GlobalSearchScope[] = ["all", "models", "agents", "materials"]

const searchKindMeta: Record<
  GlobalSearchResultItem["kind"],
  { label: string; icon: Component }
> = {
  model: { label: "模型", icon: Sparkles },
  agent: { label: "智能体", icon: Bot },
  material: { label: "我的素材", icon: FolderHeart },
  community: { label: "社区素材", icon: Images },
}

const SIDEBAR_OPEN_KEY = "ai_tool_market_sidebar_open"

const credit = ref<CreditAccount | null>(null)
const sidebarOpen = ref(true)
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

const mainNav: NavLink[] = [
  {
    type: "link",
    href: "/home",
    label: "首页",
    icon: Home,
    active: (path) => path === "/home",
  },
  {
    type: "link",
    href: "/dashboard",
    label: "生成",
    icon: WandSparkles,
  },
  {
    type: "link",
    href: "/agent",
    label: "Agent",
    icon: Bot,
  },
]

const navSections: NavSection[] = [
  {
    label: "创意",
    items: [
      {
        type: "link",
        href: "/marketplace",
        label: "工具",
        icon: Wrench,
        active: (path) => path === "/marketplace" || path.startsWith("/chat/"),
      },
      { type: "link", href: "/library", label: "资产", icon: Package },
      { type: "link", href: "/community", label: "社区", icon: Compass },
      { type: "link", href: "/community/inspirations", label: "灵感收藏", icon: Lightbulb },
    ],
  },
]

const accountNav: NavLink[] = [
  { type: "link", href: "/billing", label: "会员与算力", icon: Wallet },
  { type: "link", href: "/profile", label: "个人资料", icon: UserRound },
]

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function selectBrandAccent(next: BrandAccent) {
  brandAccent.value = next
  applyBrandAccent(next)
  storeBrandAccent(next)
  brandAccentMenuOpen.value = false
}

watch(sidebarOpen, (open) => {
  localStorage.setItem(SIDEBAR_OPEN_KEY, open ? "1" : "0")
})

function isActive(item: NavLink) {
  if (item.active) return item.active(route.path, route.fullPath)
  if (item.href === "/home") {
    return route.path === item.href
  }
  if (item.href === "/marketplace") {
    return route.path === item.href || route.path.startsWith("/chat/")
  }
  if (item.href === "/agents") {
    return route.path === item.href || route.path.startsWith(item.href + "/")
  }
  if (item.href === "/agent") {
    return route.path === item.href || route.path.startsWith(item.href + "/")
  }
  if (item.href === "/library") {
    return route.path === "/library" || route.path.startsWith("/library/")
  }
  if (item.href === "/community") {
    return route.path === item.href || route.path.startsWith("/community/posts/")
  }
  const [path] = item.href.split("?")
  return route.path === path || route.path.startsWith(path + "/")
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

function toggleSearchScopeMenu() {
  scopeMenuOpen.value = !scopeMenuOpen.value
}

function selectSearchScope(next: GlobalSearchScope) {
  setSearchScope(next)
  scopeMenuOpen.value = false
  if (searchKeyword.value.trim()) openSearchPanel()
}

function onSearchFocus() {
  openSearchPanel()
  if (searchKeyword.value.trim()) submitSearch()
}

function onSearchKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    closeSearchPanel()
    scopeMenuOpen.value = false
    return
  }
  if (event.key === "Enter") {
    event.preventDefault()
    if (searchResults.value.length > 0) {
      void navigateSearchResult(searchResults.value[0])
      return
    }
    submitSearch()
  }
}

async function navigateSearchResult(item: GlobalSearchResultItem) {
  closeSearchPanel()
  scopeMenuOpen.value = false
  clearSearchDebounce()
  await router.push(item.href)
}

function onDocumentPointerDown(event: MouseEvent) {
  const root = searchRootRef.value
  const target = event.target as Node
  if (!root || !root.contains(target)) {
    closeSearchPanel()
    scopeMenuOpen.value = false
  }
  const accentRoot = brandAccentRootRef.value
  if (!accentRoot || !accentRoot.contains(target)) {
    brandAccentMenuOpen.value = false
  }
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
  applyAppTheme("dark")
  brandAccent.value = getStoredBrandAccent()
  applyBrandAccent(brandAccent.value)

  const saved = localStorage.getItem(SIDEBAR_OPEN_KEY)
  if (saved === "0") sidebarOpen.value = false
  if (saved === "1") sidebarOpen.value = true

  window.addEventListener("credits:updated", handleCreditsUpdated)
  document.addEventListener("mousedown", onDocumentPointerDown)
  await Promise.all([loadCreditAccount(), loadCustomerServiceSettings()])
})

onUnmounted(() => {
  window.removeEventListener("credits:updated", handleCreditsUpdated)
  document.removeEventListener("mousedown", onDocumentPointerDown)
  clearSearchDebounce()
})

watch(
  () => route.fullPath,
  () => {
    closeSearchPanel()
    scopeMenuOpen.value = false
  },
)
</script>

<template>
  <div
    class="app-shell-root flex h-screen overflow-hidden bg-background text-foreground"
    :style="{ '--app-sidebar-width': sidebarOpen ? '248px' : '0px' }"
  >
    <aside
      class="hidden h-full w-[248px] shrink-0 flex-col border-r border-white/[0.06] bg-[#08090d]"
      :class="sidebarOpen ? 'lg:flex' : 'lg:hidden'"
    >
      <div class="app-shell-brand-row flex h-[92px] shrink-0 items-center justify-between px-6 pb-4 pt-6">
        <div class="app-shell-brand-ambient" aria-hidden="true" />
        <RouterLink to="/agent" class="app-shell-agent-logo-link min-w-0 flex-1">
          <img src="/logo.png" class="app-shell-agent-logo" alt="科创点AI" />
        </RouterLink>
        <button
          type="button"
          class="z-20 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-white/[0.06] bg-white/[0.035] text-white/58 transition hover:border-[var(--brand-border)] hover:bg-[var(--brand-softer)] hover:text-[var(--brand-active-text)]"
          :aria-label="sidebarOpen ? '隐藏侧栏' : '显示侧栏'"
          :aria-expanded="sidebarOpen"
          @click="toggleSidebar"
        >
          <PanelLeftClose class="h-[18px] w-[18px]" aria-hidden="true" />
        </button>
      </div>

      <nav class="sidebar-nav min-h-0 flex-1 overflow-y-auto px-3 pb-4 pt-1">
        <ul class="flex flex-col gap-1.5">
          <li v-for="item in mainNav" :key="item.href">
            <RouterLink
              :to="item.href"
              class="sidebar-nav-link"
              :class="isActive(item) ? 'sidebar-nav-link--active' : 'sidebar-nav-link--idle'"
            >
              <component
                :is="item.icon"
                class="h-[18px] w-[18px] shrink-0 transition-colors"
                :class="isActive(item) ? '' : 'text-white/72'"
              />
              <span>{{ item.label }}</span>
            </RouterLink>
          </li>
        </ul>

        <section
          v-for="section in navSections"
          :key="section.label || section.items.map((item) => item.href).join('|')"
          class="mt-4 border-t border-white/[0.065] pt-3 first:mt-5"
        >
          <p v-if="section.label" class="sidebar-section-label">{{ section.label }}</p>
          <ul class="flex flex-col gap-1.5">
            <li v-for="item in section.items" :key="item.href">
              <RouterLink
                :to="item.href"
                class="sidebar-nav-link"
                :class="isActive(item) ? 'sidebar-nav-link--active' : 'sidebar-nav-link--idle'"
              >
                <component
                  :is="item.icon"
                  class="h-[18px] w-[18px] shrink-0 transition-colors"
                  :class="isActive(item) ? '' : 'text-white/72'"
                />
                <span>{{ item.label }}</span>
              </RouterLink>
            </li>
          </ul>
        </section>
      </nav>

      <div class="shrink-0 border-t border-white/[0.065] px-3 pb-4 pt-4">
        <RouterLink
          v-for="item in accountNav.slice(0, 1)"
          :key="item.href"
          :to="item.href"
          class="sidebar-nav-link mb-2"
          :class="isActive(item) ? 'sidebar-nav-link--active' : 'sidebar-nav-link--idle'"
        >
          <component
            :is="item.icon"
            class="h-[18px] w-[18px] shrink-0 transition-colors"
            :class="isActive(item) ? '' : 'text-white/72'"
          />
          <span>{{ item.label }}</span>
        </RouterLink>

        <button
          type="button"
          class="group relative mb-3 flex h-11 w-full items-center gap-2.5 overflow-hidden rounded-lg border border-white/[0.055] bg-white/[0.025] px-3 text-left text-sm font-medium text-white/76 transition hover:border-[var(--brand-border)] hover:bg-white/[0.045] hover:text-white"
        >
          <Gift class="h-[17px] w-[17px] shrink-0 text-white/58 transition group-hover:text-[var(--brand-active-text)]" aria-hidden="true" />
          <span class="min-w-0 flex-1">
            <span class="block truncate">推荐有礼</span>
          </span>
          <span class="rounded-full bg-white/[0.07] px-1.5 py-0.5 text-[10px] font-medium text-[var(--brand-active-text)] ring-1 ring-white/[0.05]">最新</span>
        </button>

        <RouterLink
          to="/billing"
          class="mb-2 block rounded-lg border border-white/[0.055] bg-white/[0.025] p-3 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.02)] transition hover:border-[var(--brand-border)] hover:bg-white/[0.04]"
        >
          <p class="text-xs font-medium text-white/40">可用算力</p>
          <p class="mt-2 font-mono text-[12px] font-semibold tabular-nums text-white/88">
            {{ credit ? credit.available.toLocaleString() : '---' }}
            <span class="ml-1 font-normal text-white/28">
              / {{ credit ? credit.totalGranted.toLocaleString() : '---' }}
            </span>
          </p>
          <div class="mt-3 h-1 overflow-hidden rounded-full bg-white/[0.08]">
            <div
              class="app-shell-credit-progress h-full rounded-full"
              :style="{ width: Math.min(creditPercent, 100) + '%' }"
            />
          </div>
        </RouterLink>

        <RouterLink
          v-for="item in accountNav.slice(1)"
          :key="item.href"
          :to="item.href"
          class="sidebar-nav-link"
          :class="isActive(item) ? 'sidebar-nav-link--active' : 'sidebar-nav-link--idle'"
        >
          <component
            :is="item.icon"
            class="h-[18px] w-[18px] shrink-0 transition-colors"
            :class="isActive(item) ? '' : 'text-white/72'"
          />
          <span>{{ item.label }}</span>
        </RouterLink>
        <div class="mt-3 px-2">
          <RouterLink
            :to="'/billing'"
            class="text-xs font-semibold text-[var(--brand-active-text)] transition hover:text-white"
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
          class="h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-white/10 bg-white/[0.04] text-white/60 hover:bg-white/10 hover:text-white"
          :class="sidebarOpen ? 'hidden' : 'hidden lg:inline-flex'"
          :aria-label="sidebarOpen ? '隐藏侧栏' : '显示侧栏'"
          :aria-expanded="sidebarOpen"
          @click="toggleSidebar"
        >
          <PanelLeftClose v-if="sidebarOpen" class="h-4 w-4" aria-hidden="true" />
          <PanelLeft v-else class="h-4 w-4" aria-hidden="true" />
        </button>
        <div class="min-w-0 flex-1 lg:max-w-[360px]">
          <h1
            v-if="title"
            class="truncate text-base font-bold text-white"
          >
            {{ title }}
          </h1>
          <p v-if="description" class="text-xs text-white/45 truncate">{{ description }}</p>
        </div>
        <div ref="searchRootRef" class="relative hidden min-w-0 flex-1 lg:block lg:max-w-[520px] xl:max-w-[620px]">
          <form
            class="flex h-12 items-center rounded-full bg-white/[0.07] px-4 ring-1 ring-white/8 transition focus-within:ring-primary/35"
            @submit.prevent="submitSearch"
          >
            <div class="relative shrink-0">
              <button
                type="button"
                class="inline-flex items-center gap-1 pr-3 text-sm text-white/70 transition hover:text-white"
                aria-haspopup="listbox"
                :aria-expanded="scopeMenuOpen"
                @click="toggleSearchScopeMenu"
              >
                {{ scopeLabels[searchScope] }}
                <ChevronDown class="h-3.5 w-3.5 text-white/40" />
              </button>
              <div
                v-if="scopeMenuOpen"
                class="absolute left-0 top-[calc(100%+10px)] z-50 min-w-[112px] overflow-hidden rounded-2xl border border-white/10 bg-[#1b1b20] p-1 shadow-[0_18px_48px_rgb(0_0_0_/_0.45)]"
              >
                <button
                  v-for="option in searchScopeOptions"
                  :key="option"
                  type="button"
                  class="flex w-full rounded-xl px-3 py-2 text-left text-sm transition"
                  :class="searchScope === option ? 'bg-primary/15 text-white' : 'text-white/65 hover:bg-white/8 hover:text-white'"
                  @click="selectSearchScope(option)"
                >
                  {{ scopeLabels[option] }}
                </button>
              </div>
            </div>
            <span class="h-5 w-px bg-white/10" />
            <Search class="ml-3 h-5 w-5 shrink-0 text-white/35" />
            <input
              v-model="searchKeyword"
              class="h-full min-w-0 flex-1 bg-transparent px-3 text-sm text-white outline-none placeholder:text-white/35"
              placeholder="搜索模型、智能体和素材"
              autocomplete="off"
              @focus="onSearchFocus"
              @keydown="onSearchKeydown"
            />
            <Loader2 v-if="searchLoading" class="h-4 w-4 shrink-0 animate-spin text-white/45" />
          </form>

          <div
            v-if="searchPanelOpen && (searchKeyword.trim() || searchLoading || searchError || searchResults.length)"
            class="absolute left-0 right-0 top-[calc(100%+10px)] z-50 overflow-hidden rounded-[24px] border border-white/10 bg-[#17171c]/98 shadow-[0_24px_80px_rgb(0_0_0_/_0.55)] backdrop-blur-xl"
          >
            <div v-if="searchLoading && searchResults.length === 0" class="flex items-center gap-2 px-4 py-5 text-sm text-white/50">
              <Loader2 class="h-4 w-4 animate-spin" />
              正在搜索...
            </div>
            <p v-else-if="searchError" class="px-4 py-5 text-sm text-red-300">{{ searchError }}</p>
            <p v-else-if="!searchLoading && searchKeyword.trim() && searchResults.length === 0" class="px-4 py-5 text-sm text-white/45">
              没有找到「{{ searchKeyword.trim() }}」相关内容
            </p>
            <ul v-else class="max-h-[420px] overflow-y-auto py-2">
              <li v-for="item in searchResults" :key="item.id">
                <button
                  type="button"
                  class="flex w-full items-center gap-3 px-4 py-3 text-left transition hover:bg-white/[0.05]"
                  @click="navigateSearchResult(item)"
                >
                  <span class="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white/[0.06] text-primary">
                    <img
                      v-if="item.coverUrl"
                      :src="item.coverUrl"
                      :alt="item.title"
                      class="h-full w-full object-cover"
                    />
                    <component :is="searchKindMeta[item.kind].icon" v-else class="h-4 w-4" />
                  </span>
                  <span class="min-w-0 flex-1">
                    <span class="block truncate text-sm font-medium text-white">{{ item.title }}</span>
                    <span v-if="item.subtitle" class="mt-0.5 block truncate text-xs text-white/42">{{ item.subtitle }}</span>
                  </span>
                  <span class="shrink-0 rounded-full bg-white/[0.06] px-2 py-1 text-[11px] text-white/45">
                    {{ searchKindMeta[item.kind].label }}
                  </span>
                </button>
              </li>
            </ul>
          </div>
        </div>
        <RouterLink
          v-if="!isAgentRoute"
          :to="userRoutes.toolList"
          class="app-shell-create-button hidden h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold text-white transition hover:brightness-110 md:inline-flex"
        >
          <Plus class="h-4 w-4" />
          创建
        </RouterLink>
        <AgentThemePicker v-if="isAgentRoute" target-selector=".agent-page" />
        <div ref="brandAccentRootRef" class="relative hidden md:block">
          <button
            type="button"
            class="app-shell-accent-trigger"
            aria-label="切换品牌配色"
            :aria-expanded="brandAccentMenuOpen"
            @click="brandAccentMenuOpen = !brandAccentMenuOpen"
          >
            <Palette class="h-[18px] w-[18px]" aria-hidden="true" />
          </button>
          <Transition name="app-shell-accent-pop">
            <div v-if="brandAccentMenuOpen" class="app-shell-accent-menu">
              <p class="app-shell-accent-title">全局品牌色</p>
              <button
                v-for="option in BRAND_ACCENT_OPTIONS"
                :key="option.id"
                type="button"
                class="app-shell-accent-option"
                :class="{ 'app-shell-accent-option--active': brandAccent === option.id }"
                @click="selectBrandAccent(option.id)"
              >
                <span class="app-shell-accent-swatch" :style="{ background: option.swatch }" />
                <span class="min-w-0 flex-1">
                  <span class="block text-sm font-semibold text-white">{{ option.label }}</span>
                  <span class="mt-0.5 block truncate text-[11px] text-white/42">{{ option.description }}</span>
                </span>
                <Check v-if="brandAccent === option.id" class="h-4 w-4 text-[var(--brand-active-text)]" aria-hidden="true" />
              </button>
            </div>
          </Transition>
        </div>
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

<style scoped>
.sidebar-nav {
  scrollbar-width: thin;
  scrollbar-color: rgb(255 255 255 / 0.12) transparent;
}

.sidebar-nav::-webkit-scrollbar {
  width: 6px;
}

.sidebar-nav::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.12);
  background-clip: content-box;
}

.sidebar-nav-link {
  position: relative;
  display: flex;
  min-height: 40px;
  width: 100%;
  align-items: center;
  gap: 10px;
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 14px;
  font-weight: 400;
  letter-spacing: 0;
  transition:
    background-color 160ms ease,
    color 160ms ease,
    box-shadow 160ms ease;
}

.sidebar-nav-link--idle {
  color: rgb(255 255 255 / 0.78);
}

.sidebar-nav-link--idle:hover {
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.92);
}

.sidebar-nav-link--idle:hover :deep(svg) {
  color: #fff;
}

.sidebar-nav-link--active {
  background: var(--brand-active-bg);
  color: var(--brand-active-text);
  font-weight: 520;
}

.sidebar-nav-link--active :deep(svg) {
  color: var(--brand-active-text);
}

.sidebar-section-label {
  margin-bottom: 10px;
  padding-inline: 4px;
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 1px;
}

.app-shell-brand-row {
  position: relative;
  z-index: 0;
  isolation: isolate;
}

.app-shell-brand-ambient {
  pointer-events: none;
  position: absolute;
  inset: 0 0 -34px;
  z-index: 0;
  background:
    radial-gradient(ellipse 178px 112px at 42px 34px, rgb(var(--brand-primary-rgb) / 0.46) 0%, rgb(var(--brand-tertiary-rgb) / 0.28) 38%, transparent 74%),
    radial-gradient(ellipse 164px 108px at 134px 52px, rgb(var(--brand-secondary-rgb) / 0.24) 0%, rgb(var(--brand-secondary-rgb) / 0.12) 44%, transparent 78%);
  filter: blur(18px);
  opacity: 0.95;
}

.app-shell-agent-logo-link {
  position: relative;
  z-index: 10;
  display: flex;
  height: 40px;
  align-items: center;
  overflow: visible;
  border-radius: 16px;
  padding-right: 10px;
}

.app-shell-agent-logo {
  display: block;
  width: auto;
  max-width: 156px;
  height: 40px;
  object-fit: contain;
  object-position: left center;
  filter:
    drop-shadow(0 0 8px rgb(var(--brand-primary-rgb) / 0.45))
    drop-shadow(0 0 16px rgb(var(--brand-tertiary-rgb) / 0.24));
}

.app-shell-create-button {
  background: var(--brand-gradient);
  box-shadow: var(--brand-button-shadow);
}

.app-shell-credit-progress {
  background: var(--brand-progress-gradient);
}

.app-shell-accent-trigger {
  display: inline-flex;
  height: 40px;
  width: 40px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.58);
  transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease, box-shadow 160ms ease;
}

.app-shell-accent-trigger:hover,
.app-shell-accent-trigger[aria-expanded="true"] {
  border-color: var(--brand-border);
  background: var(--brand-softer);
  color: var(--brand-active-text);
  box-shadow: 0 12px 28px var(--brand-glow);
}

.app-shell-accent-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  z-index: 60;
  width: 232px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 20px;
  background:
    radial-gradient(circle at 24% 0%, var(--brand-softer), transparent 46%),
    rgb(23 23 28 / 0.98);
  padding: 10px;
  box-shadow: 0 22px 60px rgb(0 0 0 / 0.48), 0 0 40px var(--brand-glow);
  backdrop-filter: blur(18px);
}

.app-shell-accent-title {
  margin: 2px 4px 8px;
  color: rgb(255 255 255 / 0.46);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.08em;
}

.app-shell-accent-option {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  border: 1px solid transparent;
  border-radius: 14px;
  padding: 9px;
  text-align: left;
  transition: border-color 160ms ease, background-color 160ms ease;
}

.app-shell-accent-option:hover {
  background: rgb(255 255 255 / 0.06);
}

.app-shell-accent-option--active {
  border-color: var(--brand-border);
  background: var(--brand-softer);
}

.app-shell-accent-swatch {
  height: 30px;
  width: 30px;
  flex-shrink: 0;
  border-radius: 999px;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.28), 0 8px 20px var(--brand-glow);
}

.app-shell-accent-pop-enter-active,
.app-shell-accent-pop-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.app-shell-accent-pop-enter-from,
.app-shell-accent-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
}
</style>
