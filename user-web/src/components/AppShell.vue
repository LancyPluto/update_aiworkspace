<script setup lang="ts">
import { RouterLink, useRoute, useRouter } from "vue-router"
import type { Component } from "vue"
import {
  ArrowUpRight,
  Bot,
  Compass,
  Crown,
  Home,
  Lightbulb,
  Package,
  UserRound,
  WandSparkles,
  Wrench,
  FolderHeart,
  Images,
  Ticket,
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
  Gift,
  Zap,
} from "lucide-vue-next"
import { ref, onMounted, onUnmounted, computed, watch } from "vue"
import { useGlobalSearch, type GlobalSearchResultItem, type GlobalSearchScope } from "@/composables/useGlobalSearch"
import { BRAND_LOGO_URL } from "@/config/brand"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchCustomerServiceSettings } from "@/api/settingsApi"
import type { CustomerServiceSettings } from "@/api/settingsApi"
import type { CreditAccount } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import UserAvatar from "@/components/UserAvatar.vue"
import CreditPowerIcon from "@/components/CreditPowerIcon/CreditPowerIcon.vue"
import { safeDisplayName } from "@/utils/displayName"
import {
  applyAppTheme,
  applyBrandAccent,
  BRAND_ACCENT_OPTIONS,
  getStoredBrandAccent,
  storeBrandAccent,
  type BrandAccent,
} from "@/utils/theme"

type NavLink = {
  type: "link"
  href: string
  label: string
  icon?: Component
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
const DEFAULT_CUSTOMER_SERVICE_QR = "https://cdn.wlcloudai.com/static/kf.jpg"

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
  { type: "link", href: "/billing", label: "会员与算力", icon: Ticket },
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
  const quota = creditQuota.value
  const remaining = remainingCredits.value
  if (remaining == null) return 0
  if (quota == null || quota <= 0) return remaining > 0 ? 100 : 0
  return Math.max(0, Math.min(100, Math.round((remaining / quota) * 100)))
})
const creditProgressWidth = computed(() => `${Math.max(3, creditPercent.value)}%`)
const creditWarning = computed(() => remainingCredits.value != null && creditPercent.value <= 20)
const remainingCredits = computed(() => credit.value?.available ?? credit.value?.balance ?? null)
const creditQuota = computed(() => {
  if (!credit.value) return null
  if (credit.value.totalGranted > 0) return credit.value.totalGranted
  return (credit.value.available ?? credit.value.balance) + Math.max(credit.value.totalConsumed ?? 0, 0)
})

// 套餐代码到会员版本的映射
const getMembershipLabel = (packageCode: string | null | undefined): string => {
  if (!packageCode) return "体验版"
  
  // 提取套餐类型（starter/growth/pro/flagship）
  const code = packageCode.toLowerCase()
  if (code.includes("starter")) return "标准版"
  if (code.includes("growth")) return "进阶版"
  if (code.includes("pro")) return "高级版"
  if (code.includes("flagship")) return "豪华版"
  
  return "体验版"
}

const membershipLabel = computed(() => getMembershipLabel(auth.user?.membershipPlan))
const paidMembership = computed(() => membershipLabel.value !== "体验版")
const creditCtaText = computed(() => (creditWarning.value ? "立即升级" : "提升额度"))
const isAgentRoute = computed(() => route.path === "/agent" || route.path.startsWith("/agent/"))
const safeUserName = computed(() => {
  const nickname = safeDisplayName(auth.user?.nickname)
  const username = safeDisplayName(auth.user?.username)
  const badEncoding = /\uFFFD|锟|阖€|鍍/.test(nickname)
  if (nickname && !badEncoding) return nickname
  if (username) return username
  const readablePrefix = nickname.match(/^[\w\s.-]{2,}/)?.[0]?.trim()
  return readablePrefix || "User"
})

function formatCreditNumber(value: number | null | undefined) {
  if (value == null) return "---"
  return Math.max(0, Math.round(value)).toLocaleString()
}

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
          <img :src="BRAND_LOGO_URL" class="app-shell-agent-logo" alt="科创点AI" />
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
                v-if="item.icon"
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
                  v-if="item.icon"
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
            v-if="item.icon"
            :is="item.icon"
            class="h-[18px] w-[18px] shrink-0 transition-colors"
            :class="isActive(item) ? '' : 'text-white/72'"
          />
          <span>{{ item.label }}</span>
        </RouterLink>

        <RouterLink
          to="/referral"
          class="group relative mb-3 flex h-11 w-full items-center gap-2.5 overflow-hidden rounded-lg border border-white/[0.055] bg-white/[0.025] px-3 text-left text-sm font-medium text-white/76 transition hover:border-[var(--brand-border)] hover:bg-white/[0.045] hover:text-white"
        >
          <Gift class="h-[18px] w-[18px] shrink-0 text-white/60 transition group-hover:text-white/90" aria-hidden="true" />
          <span class="min-w-0 flex-1">
            <span class="block truncate">推荐有礼</span>
          </span>
          <span class="rounded-full bg-white/[0.07] px-1.5 py-0.5 text-[10px] font-medium text-[var(--brand-active-text)] ring-1 ring-white/[0.05]">最新</span>
        </RouterLink>

        <RouterLink
          to="/billing"
          class="app-shell-credit-card"
          :class="{ 'app-shell-credit-card--warning': creditWarning }"
          :aria-label="`可用算力 ${formatCreditNumber(remainingCredits)}，本月额度 ${formatCreditNumber(creditQuota)}，${creditCtaText}`"
        >
          <span class="app-shell-credit-card__glow" aria-hidden="true" />
          <span class="app-shell-credit-card__header">
            <span class="app-shell-credit-card__eyebrow">剩余额度</span>
            <span class="app-shell-credit-card__plan" :class="{ 'app-shell-credit-card__plan--paid': paidMembership }">
              <Crown v-if="paidMembership" :size="12" />
              <Zap v-else :size="12" />
              {{ membershipLabel }}
            </span>
          </span>
          <span class="app-shell-credit-card__value">
            <CreditPowerIcon :size="18" />
            <strong>{{ formatCreditNumber(remainingCredits) }}</strong>
            <span>可用算力</span>
          </span>
          <small class="app-shell-credit-card__quota">本月额度 {{ formatCreditNumber(creditQuota) }}</small>
          <span class="app-shell-credit-card__track" aria-hidden="true">
            <span class="app-shell-credit-progress" :style="{ width: creditProgressWidth }" />
          </span>
          <span class="app-shell-credit-card__cta">
            {{ creditCtaText }}
            <ArrowUpRight :size="13" />
          </span>
        </RouterLink>

        <RouterLink
          v-for="item in accountNav.slice(1)"
          :key="item.href"
          :to="item.href"
          class="sidebar-nav-link"
          :class="isActive(item) ? 'sidebar-nav-link--active' : 'sidebar-nav-link--idle'"
        >
          <component
            v-if="item.icon"
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
      <header
        class="app-shell-header z-30 grid h-20 shrink-0 grid-cols-[1fr_auto_1fr] items-center gap-4 border-b border-white/8 bg-[#151515]/95 px-5 backdrop-blur-xl"
        :class="{ 'app-shell-header--agent': isAgentRoute }"
      >
        <div class="flex min-w-0 items-center gap-3">
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
        </div>
        <div ref="searchRootRef" class="relative hidden w-full min-w-0 lg:block lg:w-[min(100%,520px)] xl:w-[min(100%,620px)]">
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
            <span v-if="isAgentRoute" class="app-shell-search-shortcut">⌘ K</span>
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
        <div class="flex min-w-0 items-center justify-end gap-3 md:gap-4">
          <RouterLink
            v-if="!isAgentRoute"
            :to="userRoutes.toolList"
            class="app-shell-create-button hidden h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold text-white transition hover:brightness-110 md:inline-flex"
          >
            <Plus class="h-4 w-4" />
            创建
          </RouterLink>
          <div v-if="!isAgentRoute" ref="brandAccentRootRef" class="relative hidden md:block">
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
        </div>
      </header>
      <main
        class="app-shell-main min-h-0 flex-1 overflow-x-hidden overflow-y-auto bg-[#111111]"
        :class="{ 'app-shell-main--agent': isAgentRoute }"
      >
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
.app-shell-header--agent {
  height: 88px;
  border-bottom-color: rgb(255 255 255 / 0.075);
  background:
    radial-gradient(circle at 18% 0%, var(--agent-accent-soft, rgb(176 92 255 / 0.12)), transparent 32%),
    linear-gradient(180deg, rgb(35 38 47 / 0.94), rgb(26 28 35 / 0.92));
  box-shadow: 0 1px 0 rgb(255 255 255 / 0.035), 0 18px 60px rgb(0 0 0 / 0.26);
}

.app-shell-header--agent :deep(form) {
  height: 50px;
  border: 1px solid rgb(255 255 255 / 0.105);
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.065), rgb(255 255 255 / 0.035)),
    rgb(22 24 29 / 0.72);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.05), 0 16px 42px rgb(0 0 0 / 0.20);
}

.app-shell-search-shortcut {
  display: inline-flex;
  height: 24px;
  align-items: center;
  border-radius: 8px;
  padding: 0 8px;
  color: rgb(255 255 255 / 0.54);
  font-size: 12px;
  line-height: 1;
  background: rgb(255 255 255 / 0.055);
}

.app-shell-main--agent {
  background:
    radial-gradient(circle at 42% 0%, rgb(255 255 255 / 0.035), transparent 30%),
    #111318;
}

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
  font-weight: 500;
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

.app-shell-credit-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-bottom: 10px;
  overflow: hidden;
  border: 1px solid rgb(82 230 255 / 0.28);
  border-radius: 8px;
  padding: 13px 14px 12px;
  background:
    linear-gradient(160deg, rgb(13 41 51 / 0.88), rgb(8 10 16 / 0.94) 58%),
    rgb(255 255 255 / 0.025);
  color: rgb(255 255 255 / 0.78);
  box-shadow: 0 12px 28px rgb(0 0 0 / 0.24), inset 0 1px 0 rgb(255 255 255 / 0.07);
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.app-shell-credit-card:hover {
  border-color: rgb(82 230 255 / 0.52);
  box-shadow: 0 16px 34px rgb(0 0 0 / 0.3), 0 0 24px rgb(82 230 255 / 0.12);
  transform: translateY(-1px);
}

.app-shell-credit-card__glow {
  position: absolute;
  inset: -40% -20% auto auto;
  width: 120px;
  height: 120px;
  border-radius: 999px;
  background: radial-gradient(circle, rgb(82 230 255 / 0.18), transparent 64%);
  pointer-events: none;
}

.app-shell-credit-card__header,
.app-shell-credit-card__value,
.app-shell-credit-card__quota,
.app-shell-credit-card__track,
.app-shell-credit-card__cta {
  position: relative;
  z-index: 1;
}

.app-shell-credit-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.app-shell-credit-card__eyebrow {
  color: rgb(255 255 255 / 0.48);
  font-size: 11px;
  font-weight: 700;
}

.app-shell-credit-card__plan {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid rgb(59 130 246 / 0.22);
  border-radius: 999px;
  color: #60d7ff;
  background: rgb(59 130 246 / 0.1);
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
  white-space: nowrap;
}

.app-shell-credit-card__plan--paid {
  border-color: rgb(245 208 97 / 0.28);
  color: #f5d061;
  background: rgb(245 208 97 / 0.12);
}

.app-shell-credit-card__value {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.app-shell-credit-card__value strong {
  min-width: 0;
  color: #fff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 23px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0;
  text-shadow: 0 0 18px rgb(82 230 255 / 0.2);
}

.app-shell-credit-card__value span {
  color: rgb(255 255 255 / 0.56);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.app-shell-credit-card__quota {
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  font-weight: 600;
}

.app-shell-credit-card__track {
  display: block;
  height: 5px;
  overflow: hidden;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.09);
}

.app-shell-credit-progress {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #52e6ff, #78f3e0);
  box-shadow: 0 0 12px rgb(82 230 255 / 0.45);
  transition: width 220ms ease, background 160ms ease;
}

.app-shell-credit-card__cta {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  margin-top: 2px;
  border: 1px solid rgb(82 230 255 / 0.28);
  border-radius: 8px;
  background:
    linear-gradient(#111820, #111820) padding-box,
    linear-gradient(90deg, rgb(82 230 255 / 0.85), rgb(195 68 255 / 0.72)) border-box;
  color: #e8fbff;
  font-size: 12px;
  font-weight: 800;
}

.app-shell-credit-card--warning {
  border-color: rgb(251 146 60 / 0.36);
  box-shadow: 0 12px 28px rgb(0 0 0 / 0.24), 0 0 22px rgb(251 146 60 / 0.1);
}

.app-shell-credit-card--warning .app-shell-credit-card__value strong {
  color: #ffb86b;
  text-shadow: 0 0 18px rgb(251 146 60 / 0.26);
  animation: app-shell-credit-breathe 2.4s ease-in-out infinite;
}

.app-shell-credit-card--warning .app-shell-credit-progress {
  background: linear-gradient(90deg, #ffb86b, #fb7185);
  box-shadow: 0 0 14px rgb(251 146 60 / 0.5);
}

.app-shell-credit-card--warning .app-shell-credit-card__cta {
  border-color: rgb(251 146 60 / 0.34);
  background:
    linear-gradient(#17120f, #17120f) padding-box,
    linear-gradient(90deg, rgb(251 146 60 / 0.86), rgb(245 208 97 / 0.72)) border-box;
}

@keyframes app-shell-credit-breathe {
  0%,
  100% {
    filter: drop-shadow(0 0 0 rgb(251 146 60 / 0));
  }

  50% {
    filter: drop-shadow(0 0 8px rgb(251 146 60 / 0.42));
  }
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
