<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useTemplateRef, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ArrowLeft } from "lucide-vue-next"
import { fetchCommunityCreator, fetchPublicUserPosts } from "@/api/communityApi"
import type { CommunityCreator, CommunityPost, PublicUserProfile } from "@/api/types"
import { useProfileParallax } from "@/composables/useProfileParallax"
import ProfileAmbientBackground from "@/pages/PublicProfile/ProfileAmbientBackground.vue"
import ProfileHeroSection from "@/pages/PublicProfile/ProfileHeroSection.vue"
import ProfilePortfolioGrid from "@/pages/PublicProfile/ProfilePortfolioGrid.vue"
import ProfileStatsRow from "@/pages/PublicProfile/ProfileStatsRow.vue"
import ProfileStickyHeader from "@/pages/PublicProfile/ProfileStickyHeader.vue"
import { useAuthStore } from "@/store/authStore"
import { applyProfileThemeToElement, resolveProfileThemeId } from "@/utils/profileTheme"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const pageRoot = useTemplateRef<HTMLElement>("pageRoot")
const heroRef = useTemplateRef<HTMLElement>("heroRef")

const profile = ref<PublicUserProfile | null>(null)
const creator = ref<CommunityCreator | null>(null)
const posts = ref<CommunityPost[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref("")
const pageNo = ref(1)
const hasNext = ref(false)
const headerCompact = ref(false)
const scrollOffset = ref(0)

const userId = computed(() => String(route.params.userId || ""))
const displayName = computed(() => profile.value?.nickname || profile.value?.username || `用户 ${userId.value}`)
const featuredCount = computed(() => creator.value?.featuredCount ?? creator.value?.featuredPosts?.length ?? 0)

const { offsetX, offsetY } = useProfileParallax(pageRoot)

let heroObserver: IntersectionObserver | null = null

function applyTheme() {
  if (!pageRoot.value || !userId.value) return
  const themeId = resolveProfileThemeId(userId.value, auth.user?.id)
  applyProfileThemeToElement(pageRoot.value, themeId)
}

function onScroll() {
  scrollOffset.value = window.scrollY
}

function setupHeroObserver() {
  heroObserver?.disconnect()
  if (!heroRef.value) return
  heroObserver = new IntersectionObserver(
    ([entry]) => {
      headerCompact.value = !entry?.isIntersecting
    },
    { root: null, threshold: 0, rootMargin: "-40% 0px 0px 0px" },
  )
  heroObserver.observe(heroRef.value)
}

async function load(reset = true) {
  if (!userId.value) return
  if (reset) {
    loading.value = true
    pageNo.value = 1
    posts.value = []
  } else {
    loadingMore.value = true
  }
  error.value = ""
  try {
    const currentPage = reset ? 1 : pageNo.value
    const [user, page] = await Promise.all([
      reset ? fetchCommunityCreator(userId.value, { token: auth.token }) : Promise.resolve(creator.value),
      fetchPublicUserPosts(userId.value, {
        token: auth.token,
        query: { pageNo: currentPage, pageSize: 12 },
      }),
    ])
    if (user) {
      creator.value = user
      profile.value = user.profile
    }
    posts.value = reset ? page.list : [...posts.value, ...page.list]
    hasNext.value = page.hasNext
    pageNo.value = currentPage + 1
  } catch (err) {
    error.value = err instanceof Error ? err.message : "公开主页加载失败"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push("/marketplace")
  }
}

watch(userId, () => {
  applyTheme()
  void load(true)
})

onMounted(() => {
  applyTheme()
  void load(true)
  window.addEventListener("scroll", onScroll, { passive: true })
  void nextTick(setupHeroObserver)
})

onUnmounted(() => {
  window.removeEventListener("scroll", onScroll)
  heroObserver?.disconnect()
})
</script>

<template>
  <main ref="pageRoot" class="public-profile-page">
    <ProfileAmbientBackground
      :offset-x="offsetX"
      :offset-y="offsetY"
      :scroll-offset="scrollOffset"
    />

    <ProfileStickyHeader
      :visible="headerCompact"
      :display-name="displayName"
      :avatar-url="profile?.avatarUrl"
      :post-count="profile?.postCount"
      @back="goBack"
    />

    <button
      v-show="!headerCompact"
      class="back-fab"
      type="button"
      aria-label="返回"
      @click="goBack"
    >
      <ArrowLeft class="h-4 w-4" />
    </button>

    <div class="public-profile-page__content">
      <div ref="heroRef">
        <ProfileHeroSection
          :avatar-url="profile?.avatarUrl"
          :display-name="displayName"
          :bio="profile?.bio"
          :featured-count="featuredCount"
        />
        <ProfileStatsRow
          :post-count="profile?.postCount"
          :like-count="profile?.likeCount"
          :favorite-count="profile?.favoriteCount"
          :same-style-count="creator?.sameStyleCount"
        />
      </div>

      <ProfilePortfolioGrid
        :posts="posts"
        :featured-posts="creator?.featuredPosts"
        :loading="loading"
        :loading-more="loadingMore"
        :error="error"
        :has-next="hasNext"
        @load-more="load(false)"
      />
    </div>
  </main>
</template>

<style scoped>
.public-profile-page {
  position: relative;
  min-height: 100vh;
  background: #050505;
  color: #fff;
  overflow-x: hidden;
}

.public-profile-page__content {
  position: relative;
  z-index: 1;
  max-width: 1200px;
  margin: 0 auto;
  padding: clamp(22px, 4vw, 64px);
}

.back-fab {
  position: fixed;
  left: clamp(16px, 4vw, 48px);
  top: clamp(16px, 3vw, 28px);
  z-index: 25;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgb(255 255 255 / 0.1);
  background: rgb(5 5 5 / 0.55);
  backdrop-filter: blur(12px) saturate(140%);
  color: rgb(255 255 255 / 0.78);
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.back-fab:hover {
  border-color: var(--profile-accent-soft);
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}
</style>
