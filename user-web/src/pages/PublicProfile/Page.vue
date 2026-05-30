<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ArrowLeft, Eye, Heart, Loader2, Star, UserRound } from "lucide-vue-next"
import UserAvatar from "@/components/UserAvatar.vue"
import { fetchCommunityCreator, fetchPublicUserPosts } from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityCreator, CommunityPost, PublicUserProfile } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const profile = ref<PublicUserProfile | null>(null)
const creator = ref<CommunityCreator | null>(null)
const posts = ref<CommunityPost[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref("")
const pageNo = ref(1)
const hasNext = ref(false)

const userId = computed(() => String(route.params.userId || ""))
const displayName = computed(() => profile.value?.nickname || profile.value?.username || `用户 ${userId.value}`)

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function postKind(post: CommunityPost) {
  const modality = (post.modality || "").toLowerCase()
  if (modality.includes("video")) return "video"
  if (modality.includes("audio")) return "audio"
  if (modality.includes("image")) return "image"
  return "text"
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

watch(userId, () => void load(true))
onMounted(() => void load(true))
</script>

<template>
  <main class="public-profile-page">
    <button class="back-button" type="button" @click="router.push('/marketplace')">
      <ArrowLeft class="h-4 w-4" />
      返回
    </button>

    <section class="profile-hero">
      <div class="hero-glow" />
      <UserAvatar :src="profile?.avatarUrl" :name="displayName" size="xl" />
      <div class="hero-copy">
        <p class="eyebrow">Public creator</p>
        <h1>{{ displayName }}</h1>
        <p>{{ profile?.bio || "这个用户还没有写简介。" }}</p>
      </div>
      <div class="hero-stats">
        <div>
          <strong>{{ profile?.postCount ?? "--" }}</strong>
          <span>作品</span>
        </div>
        <div>
          <strong>{{ profile?.likeCount ?? "--" }}</strong>
          <span>获赞</span>
        </div>
        <div>
          <strong>{{ profile?.favoriteCount ?? "--" }}</strong>
          <span>收藏</span>
        </div>
        <div>
          <strong>{{ creator?.sameStyleCount ?? "--" }}</strong>
          <span>同款</span>
        </div>
      </div>
    </section>

    <section class="post-section">
      <div class="section-title">
        <UserRound class="h-4 w-4" />
        <span>公开作品</span>
      </div>

      <div v-if="loading" class="state-panel">
        <Loader2 class="h-5 w-5 animate-spin" />
        加载主页中
      </div>
      <div v-else-if="error" class="state-panel error">{{ error }}</div>
      <div v-else-if="!posts.length" class="state-panel">暂时没有公开作品</div>

      <div v-else class="post-grid">
        <article v-for="post in posts" :key="post.id" class="post-card" @click="router.push(`/community/posts/${post.id}`)">
          <div class="media-frame">
            <img
              v-if="postKind(post) === 'image' && mediaUrl(post.coverUrl)"
              :src="mediaUrl(post.coverUrl)"
              :alt="post.title"
            />
            <video
              v-else-if="postKind(post) === 'video' && mediaUrl(post.coverUrl)"
              :src="mediaUrl(post.coverUrl)"
              muted
              loop
              playsinline
              preload="metadata"
            />
            <div v-else class="text-cover">
              {{ post.prompt || post.description || post.title }}
            </div>
            <span class="kind-pill">{{ post.modality }}</span>
          </div>
          <div class="post-body">
            <h2>{{ post.title }}</h2>
            <p>{{ post.description || post.toolName || "AI 创作" }}</p>
            <div class="post-meta">
              <span><Eye class="h-3.5 w-3.5" />{{ post.viewCount }}</span>
              <span><Heart class="h-3.5 w-3.5" />{{ post.likeCount }}</span>
              <span><Star class="h-3.5 w-3.5" />{{ post.favoriteCount }}</span>
            </div>
          </div>
        </article>
      </div>

      <button v-if="hasNext" class="load-more" type="button" :disabled="loadingMore" @click="load(false)">
        <Loader2 v-if="loadingMore" class="h-4 w-4 animate-spin" />
        加载更多
      </button>
    </section>
  </main>
</template>

<style scoped>
.public-profile-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 18% 8%, rgb(176 92 255 / 0.16), transparent 34%),
    radial-gradient(circle at 92% 12%, rgb(34 211 238 / 0.1), transparent 32%),
    #050505;
  color: #fff;
  padding: clamp(22px, 4vw, 64px);
}

.back-button,
.load-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.72);
  padding: 10px 16px;
  font-weight: 800;
}

.profile-hero {
  position: relative;
  margin-top: 24px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 24px;
  align-items: end;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  padding: 72px 0 36px;
}

.hero-glow {
  position: absolute;
  inset: 20% 10% auto;
  height: 180px;
  background: linear-gradient(90deg, transparent, rgb(176 92 255 / 0.18), rgb(34 211 238 / 0.08), transparent);
  filter: blur(60px);
  pointer-events: none;
}

.hero-copy {
  position: relative;
}

.eyebrow {
  margin: 0 0 10px;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.hero-copy h1 {
  margin: 0;
  font-size: clamp(44px, 8vw, 110px);
  line-height: 0.9;
}

.hero-copy p {
  max-width: 620px;
  margin: 18px 0 0;
  color: rgb(255 255 255 / 0.55);
  line-height: 1.8;
}

.hero-stats {
  display: flex;
  gap: 14px;
}

.hero-stats div {
  min-width: 94px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 20px;
  background: rgb(255 255 255 / 0.045);
  padding: 14px;
}

.hero-stats strong {
  display: block;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 24px;
}

.hero-stats span {
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
}

.post-section {
  padding-top: 34px;
}

.section-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.58);
  font-weight: 900;
}

.post-grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 22px;
}

.post-card {
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 28px;
  background: rgb(255 255 255 / 0.055);
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.post-card:hover {
  transform: translateY(-3px);
  border-color: rgb(176 92 255 / 0.42);
}

.media-frame {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: rgb(255 255 255 / 0.04);
}

.media-frame img,
.media-frame video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.text-cover {
  display: grid;
  height: 100%;
  place-items: center;
  padding: 22px;
  color: rgb(255 255 255 / 0.68);
  line-height: 1.7;
}

.kind-pill {
  position: absolute;
  left: 14px;
  top: 14px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 800;
}

.post-body {
  padding: 18px;
}

.post-body h2 {
  margin: 0;
  font-size: 20px;
}

.post-body p {
  margin: 9px 0 16px;
  min-height: 22px;
  color: rgb(255 255 255 / 0.45);
}

.post-meta {
  display: flex;
  gap: 14px;
  color: rgb(255 255 255 / 0.46);
}

.post-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.state-panel {
  margin-top: 24px;
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.12);
  border-radius: 28px;
  color: rgb(255 255 255 / 0.5);
}

.state-panel.error {
  color: rgb(254 202 202);
}

.load-more {
  margin: 26px auto 0;
}

@media (max-width: 900px) {
  .profile-hero {
    grid-template-columns: 1fr;
  }

  .hero-stats {
    flex-wrap: wrap;
  }
}
</style>
