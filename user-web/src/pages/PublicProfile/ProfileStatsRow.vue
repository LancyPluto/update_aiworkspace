<script setup lang="ts">
import { onMounted, watch } from "vue"
import { Wand2 } from "lucide-vue-next"
import { useAnimatedStat } from "@/composables/useAnimatedStat"

const props = defineProps<{
  postCount?: number | null
  likeCount?: number | null
  favoriteCount?: number | null
  sameStyleCount?: number | null
}>()

const postsStat = useAnimatedStat(() => props.postCount ?? null)
const likesStat = useAnimatedStat(() => props.likeCount ?? null)
const favoritesStat = useAnimatedStat(() => props.favoriteCount ?? null)
const sameStyleStat = useAnimatedStat(() => props.sameStyleCount ?? null)

function refreshAll() {
  postsStat.setStatic()
  likesStat.setStatic()
  favoritesStat.setStatic()
  sameStyleStat.setStatic()
}

watch(
  () => [props.postCount, props.likeCount, props.favoriteCount, props.sameStyleCount],
  refreshAll,
)

onMounted(refreshAll)
</script>

<template>
  <div class="profile-stats">
    <div class="profile-stat">
      <strong>{{ postsStat.displayValue.value }}</strong>
      <span>作品</span>
    </div>
    <div
      class="profile-stat profile-stat--animated"
      @mouseenter="likesStat.animateFromHover()"
      @mouseleave="likesStat.setStatic()"
    >
      <strong>{{ likesStat.displayValue.value }}</strong>
      <span>获赞</span>
    </div>
    <div class="profile-stat">
      <strong>{{ favoritesStat.displayValue.value }}</strong>
      <span>收藏</span>
    </div>
    <div
      class="profile-stat profile-stat--highlight profile-stat--animated"
      title="被同款创作的次数，代表你的创作影响力"
      @mouseenter="sameStyleStat.animateFromHover()"
      @mouseleave="sameStyleStat.setStatic()"
    >
      <strong>{{ sameStyleStat.displayValue.value }}</strong>
      <span>
        <Wand2 class="h-3.5 w-3.5" />
        同款
      </span>
    </div>
  </div>
</template>

<style scoped>
.profile-stats {
  display: flex;
  flex-wrap: wrap;
  gap: clamp(24px, 5vw, 48px);
  margin: 28px 0 8px;
  padding-bottom: 8px;
}

.profile-stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 56px;
}

.profile-stat strong {
  font-size: clamp(28px, 4vw, 32px);
  font-weight: 300;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  line-height: 1;
  color: #fff;
}

.profile-stat span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.profile-stat--animated {
  cursor: default;
}

.profile-stat--highlight strong {
  color: var(--profile-accent-light);
  text-shadow: 0 0 24px var(--profile-accent-glow);
}

.profile-stat--highlight span {
  color: var(--profile-accent);
}
</style>
