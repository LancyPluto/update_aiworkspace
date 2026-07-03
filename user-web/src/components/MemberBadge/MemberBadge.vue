<script setup lang="ts">
import { computed } from "vue"
import type { UserProfile } from "@/api/types"

const props = defineProps<{
  available?: number | null
  membershipPlan?: string | null
}>()

// 套餐代码到会员版本的映射
const getMembershipLabel = (packageCode: string | null | undefined): string => {
  if (!packageCode) return "体验版"
  
  // 提取套餐类型（starter/growth/pro/flagship）
  const code = packageCode.toLowerCase()
  if (code.includes("starter")) return "标准版"
  if (code.includes("growth")) return "高级版"
  if (code.includes("pro")) return "进阶版"
  if (code.includes("flagship")) return "豪华版"
  
  return "体验版"
}

const isMember = computed(() => props.available != null && props.available > 0)
const membershipLabel = computed(() => getMembershipLabel(props.membershipPlan))

const balanceLabel = computed(() => {
  if (props.available == null) return "算力余额：---点"
  return `算力余额：${props.available.toLocaleString()}点`
})
</script>

<template>
  <span
    v-if="isMember"
    class="member-badge group relative inline-flex shrink-0 cursor-default items-center gap-1.5"
    role="img"
    :aria-label="`会员-${membershipLabel}`"
  >
    <!-- 书签图标 -->
    <svg
      class="member-badge__bookmark"
      viewBox="0 0 24 24"
      width="16"
      height="16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path
        d="M5 3V21L12 17L19 21V3H5Z"
        stroke="#F5D061"
        stroke-width="2"
        stroke-linejoin="round"
        fill="#FFF8DC"
      />
    </svg>

    <!-- 会员版本标签 -->
    <span class="member-badge__label text-xs font-semibold text-amber-600">
      {{ membershipLabel }}
    </span>

    <!-- 皇冠图标 -->
    <svg
      class="member-badge__icon"
      viewBox="0 0 24 24"
      width="30"
      height="30"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="member-v-gold" x1="6" y1="7" x2="18" y2="17" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#F5D061" />
          <stop offset="100%" stop-color="#B8860B" />
        </linearGradient>
      </defs>
      <path
        d="M12 2.5L20 6.5V11.5C20 16.2 16.6 20.1 12 21.5C7.4 20.1 4 16.2 4 11.5V6.5L12 2.5Z"
        fill="#fff"
        stroke="#141414"
        stroke-width="1.5"
        stroke-linejoin="round"
      />
      <path
        d="M8.5 8.5L12 15.5L15.5 8.5"
        stroke="url(#member-v-gold)"
        stroke-width="2.25"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>

    <span
      class="member-badge__tooltip pointer-events-none absolute right-0 top-full z-50 mt-2 whitespace-nowrap rounded-lg border border-amber-200/90 bg-white px-3.5 py-2 text-sm font-semibold text-amber-600 opacity-0 shadow-lg shadow-black/10 transition-all duration-200 group-hover:translate-y-0 group-hover:opacity-100 translate-y-0.5"
      role="tooltip"
    >
      {{ balanceLabel }}
      <span
        class="absolute -top-1.5 right-3 h-2.5 w-2.5 rotate-45 border-l border-t border-amber-200/90 bg-white"
        aria-hidden="true"
      />
    </span>
  </span>
</template>

<style scoped>
.member-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
}

.member-badge__bookmark {
  display: block;
  filter: drop-shadow(0 1px 1px rgb(0 0 0 / 0.08));
}

.member-badge__label {
  display: inline-block;
  padding: 0.125rem 0.375rem;
  background: linear-gradient(135deg, #fffbeb 0%, #fef3c7 100%);
  border-radius: 0.25rem;
  border: 1px solid rgba(245, 208, 97, 0.4);
}

.member-badge__icon {
  display: block;
  filter: drop-shadow(0 1px 2px rgb(0 0 0 / 0.12));
  transition: transform 0.15s ease, filter 0.15s ease;
}

.group:hover .member-badge__icon {
  transform: scale(1.06);
  filter: drop-shadow(0 2px 4px rgb(0 0 0 / 0.16));
}
</style>
