<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import {
  Bell,
  Headphones,
  Gift,
  LogOut,
  Menu,
  Moon,
  PanelLeft,
  PanelLeftClose,
  Search,
  Sun,
  UserRound,
  X,
} from "lucide-vue-next"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchCustomerServiceSettings, type CustomerServiceSettings } from "@/api/settingsApi"
import type { CreditAccount } from "@/api/types"
import MemberBadge from "@/components/MemberBadge/MemberBadge.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { workspaceBottomNav, workspaceNavGroups } from "@/data/creativeHub"
import { useAuthStore } from "@/store/authStore"
import type { WorkspaceNavItem } from "@/types/workspace"
import { applyAppTheme, getStoredTheme, storeAppTheme, type AppTheme } from "@/utils/theme"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const SIDEBAR_OPEN_KEY = "ai_tool_market_sidebar_open"
const DEFAULT_CUSTOMER_SERVICE_QR = "https://cdn.wlcloudai.com/static/kf.jpg"

const credit = ref<CreditAccount | null>(null)
const customerServiceOpen = ref(false)
const customerServiceQrBroken = ref(false)
const mobileNavOpen = ref(false)
const sidebarOpen = ref(true)
const searchKeyword = ref("")
const notificationsOpen = ref(false)
const theme = ref<AppTheme>("light")
const customerService = ref<CustomerServiceSettings>({
  enabled: true,
  title: "联系客服",
  description: "扫码添加客服，获取使用支持",
  qrCodeUrl: DEFAULT_CUSTOMER_SERVICE_QR,
})

const navExactPaths = new Set(
  [...workspaceNavGroups.flatMap((group) => group.items), ...workspaceBottomNav].map((item) => item.to.split("?")[0]),
)

const isActive = (item: WorkspaceNavItem) => {
  const path = item.to.split("?")[0]
  if (route.path === path) return true
  // 当前路由被其他导航项精确占用时（如 PPT 工作台 /tools/.../workspace），前缀匹配项（工具）不再同时高亮
  if (navExactPaths.has(route.path)) return false
  if (item.match) {
    return item.match.some((entry) => route.path === entry || route.path.startsWith(`${entry}/`))
  }
  return false
}

const userName = computed(() => {
  const nickname = auth.user?.nickname?.trim() || ""
  const username = auth.user?.username?.trim() || ""
  const badEncoding = /\uFFFD|锟|阖€|鍍/.test(nickname)
  if (nickname && !badEncoding) return nickname
  if (username) return username
  const readablePrefix = nickname.match(/^[\w\s.-]{2,}/)?.[0]?.trim()
  return readablePrefix || "User"
})
const creditLabel = computed(() => (credit.value ? credit.value.available.toLocaleString() : "---"))
const creditTotalLabel = computed(() => (credit.value ? credit.value.totalGranted.toLocaleString() : "---"))
const creditPercent = computed(() => {
  if (!credit.value) return 0
  return Math.max(0, Math.min(100, Math.round((credit.value.available / (credit.value.totalGranted || 1)) * 100)))
})
const availableCredits = computed(() => credit.value?.available ?? null)
const customerServiceQrSrc = computed(() => customerService.value.qrCodeUrl?.trim() || DEFAULT_CUSTOMER_SERVICE_QR)

async function loadCreditAccount() {
  if (!auth.isLoggedIn || !auth.token) {
    credit.value = null
    return
  }
  try {
    credit.value = await fetchCreditAccount({ token: auth.token })
  } catch {
    credit.value = null
  }
}

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

function handleCreditsUpdated(event: Event) {
  const detail = (event as CustomEvent<CreditAccount | undefined>).detail
  if (detail) {
    credit.value = detail
    return
  }
  void loadCreditAccount()
}

function onCustomerServiceQrError() {
  customerServiceQrBroken.value = true
}

function closeMobileNav() {
  mobileNavOpen.value = false
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function toggleTheme() {
  theme.value = theme.value === "dark" ? "light" : "dark"
}

function submitSearch() {
  const keyword = searchKeyword.value.trim()
  void router.push({
    name: "ToolList",
    query: keyword ? { keyword } : {},
  })
}

watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) void loadCreditAccount()
    else credit.value = null
  },
)

watch(customerServiceQrSrc, () => {
  customerServiceQrBroken.value = false
})

watch(sidebarOpen, (open) => {
  localStorage.setItem(SIDEBAR_OPEN_KEY, open ? "1" : "0")
})

watch(theme, (next) => {
  applyAppTheme(next)
  storeAppTheme(next)
})

watch(
  () => route.fullPath,
  () => {
    notificationsOpen.value = false
  },
)

onMounted(async () => {
  theme.value = getStoredTheme()
  applyAppTheme(theme.value)

  const savedSidebar = localStorage.getItem(SIDEBAR_OPEN_KEY)
  if (savedSidebar === "0") sidebarOpen.value = false
  if (savedSidebar === "1") sidebarOpen.value = true

  window.addEventListener("credits:updated", handleCreditsUpdated)
  await Promise.all([loadCreditAccount(), loadCustomerServiceSettings()])
})

onUnmounted(() => {
  window.removeEventListener("credits:updated", handleCreditsUpdated)
})
</script>

<template>
  <main class="workspace-workspace" :class="{ 'workspace-workspace--sidebar-collapsed': !sidebarOpen }">
    <header class="workspace-topbar">
      <div>
        <div class="workspace-topbar-left">
          <button
            class="workspace-sidebar-toggle"
            type="button"
            :aria-label="sidebarOpen ? '隐藏侧栏' : '显示侧栏'"
            :aria-expanded="sidebarOpen"
            @click="toggleSidebar"
          >
            <PanelLeftClose v-if="sidebarOpen" :size="17" />
            <PanelLeft v-else :size="17" />
          </button>
          <RouterLink to="/home" class="workspace-brand">科创点AI</RouterLink>
        </div>

        <form class="workspace-top-search" @submit.prevent="submitSearch">
          <Search :size="16" />
          <input v-model="searchKeyword" type="search" placeholder="搜索工具、模型和素材" />
        </form>

        <div class="workspace-top-actions">
          <template v-if="auth.isLoggedIn">
            <RouterLink to="/billing" class="workspace-credit">{{ creditLabel }}</RouterLink>
            <button v-if="customerService.enabled" class="workspace-customer-button" type="button" @click="customerServiceOpen = true">
              <Headphones :size="15" />客服
            </button>
            <div class="workspace-notification-wrap">
              <button class="workspace-icon-btn" type="button" aria-label="通知" @click="notificationsOpen = !notificationsOpen">
                <Bell :size="17" />
              </button>
              <div v-if="notificationsOpen" class="workspace-notification-popover">
                <strong>通知</strong>
                <p>暂无新通知</p>
                <RouterLink to="/create">查看生成</RouterLink>
              </div>
            </div>
            <button
              class="workspace-icon-btn"
              type="button"
              :aria-label="theme === 'dark' ? '切换浅色主题' : '切换深色主题'"
              :aria-pressed="theme === 'dark'"
              @click="toggleTheme"
            >
              <Sun v-if="theme === 'dark'" :size="17" />
              <Moon v-else :size="17" />
            </button>
            <RouterLink to="/profile" class="workspace-project" title="我的资料">
              <MemberBadge :available="availableCredits" />
              <UserAvatar :src="auth.user?.avatarUrl" :name="userName" size="sm" />
              <span class="workspace-project-name">{{ userName }}</span>
            </RouterLink>
            <button class="workspace-icon-btn" type="button" aria-label="退出登录" @click="auth.logout()">
              <LogOut :size="18" />
            </button>
          </template>
          <template v-else>
            <RouterLink to="/" class="workspace-login-link">登录</RouterLink>
            <RouterLink to="/create" class="workspace-start-link">免费开始</RouterLink>
          </template>
          <button class="workspace-menu" type="button" aria-label="菜单" @click="mobileNavOpen = true"><Menu :size="20" /></button>
        </div>
      </div>
    </header>

    <aside
      class="workspace-sidebar"
      :class="{ 'mobile-open': mobileNavOpen, 'desktop-collapsed': !sidebarOpen }"
      :aria-hidden="!sidebarOpen && !mobileNavOpen"
      :inert="!sidebarOpen && !mobileNavOpen"
    >
      <div class="workspace-sidebar-inner">
        <nav class="workspace-sidebar-nav">
          <div class="workspace-nav-main">
            <section v-for="(group, index) in workspaceNavGroups" :key="index" class="workspace-nav-group" :class="{ divided: index > 0 }">
              <p v-if="group.label" class="workspace-nav-label">{{ group.label }}</p>
              <RouterLink
                v-for="item in group.items"
                :key="item.label"
                :to="item.to"
                class="workspace-nav-link"
                :class="{ active: isActive(item) }"
                @click="closeMobileNav"
              >
                <component v-if="item.icon" :is="item.icon" :size="16" />
                <span>{{ item.label }}</span>
              </RouterLink>
            </section>
          </div>

          <section class="workspace-nav-bottom">
            <RouterLink
              v-for="item in workspaceBottomNav"
              :key="item.label"
              :to="item.to"
              class="workspace-nav-link"
              :class="{ active: isActive(item) }"
              @click="closeMobileNav"
            >
                <component v-if="item.icon" :is="item.icon" :size="16" />
              <span>{{ item.label }}</span>
            </RouterLink>
            <RouterLink to="/billing" class="workspace-referral" @click="closeMobileNav">
              <span class="workspace-new-badge">最新</span>
              <strong><Gift :size="16" class="inline-block align-[-2px]" />推荐有礼</strong>
              <small>获取更多算力</small>
            </RouterLink>
            <RouterLink v-if="auth.isLoggedIn" to="/billing" class="workspace-credit-panel" @click="closeMobileNav">
              <span>可用算力</span>
              <strong>{{ creditLabel }} <small>/ {{ creditTotalLabel }}</small></strong>
              <em><i :style="{ width: creditPercent + '%' }" /></em>
            </RouterLink>
            <RouterLink v-if="auth.isLoggedIn" to="/profile" class="workspace-nav-link" @click="closeMobileNav">
              <UserRound :size="16" />
              <span>个人资料</span>
            </RouterLink>
          </section>
        </nav>
      </div>
    </aside>
    <button
      v-if="mobileNavOpen"
      class="workspace-mobile-backdrop"
      type="button"
      aria-label="关闭菜单"
      @click="mobileNavOpen = false"
    />

    <section class="workspace-workspace-content">
      <div class="workspace-workspace-inner">
        <slot />
      </div>
    </section>

    <div v-if="customerServiceOpen" class="workspace-service-modal" @click.self="customerServiceOpen = false">
      <section class="workspace-service-card">
        <button class="workspace-service-close" type="button" aria-label="关闭联系客服" @click="customerServiceOpen = false">
          <X :size="16" />
        </button>
        <div class="workspace-service-icon"><Headphones :size="24" /></div>
        <h2>{{ customerService.title || "联系客服" }}</h2>
        <p>{{ customerService.description || "扫码添加客服，获取使用支持" }}</p>
        <div class="workspace-service-qr">
          <img
            :src="customerServiceQrBroken ? DEFAULT_CUSTOMER_SERVICE_QR : customerServiceQrSrc"
            :alt="customerService.title || '客服二维码'"
            @error="onCustomerServiceQrError"
          />
        </div>
      </section>
    </div>
  </main>
</template>
