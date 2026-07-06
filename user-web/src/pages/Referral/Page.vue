<script setup lang="ts">
import { computed, ref } from "vue"
import { Copy, Gift, Link2, Share2, Sparkles, Ticket, Users } from "lucide-vue-next"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const copied = ref(false)

const inviteCode = computed(() => {
  const id = auth.user?.id
  return id ? `WLCLOUD${String(id).padStart(5, "0")}` : "WLCLOUDAI"
})

const inviteUrl = computed(() => {
  if (typeof window === "undefined") return `https://wlcloudai.com/?invite=${inviteCode.value}`
  return `${window.location.origin}/?invite=${inviteCode.value}`
})

async function copyInviteLink() {
  copied.value = false
  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1800)
  } catch {
    copied.value = false
  }
}

const rewardCards = [
  { title: "好友注册", value: "+100", unit: "算力", icon: Users },
  { title: "首次充值", value: "10%", unit: "返利", icon: Ticket },
  { title: "连续邀请", value: "阶梯", unit: "加成", icon: Sparkles },
]

const steps = [
  "分享邀请链接给好友",
  "好友注册并完成首次创作",
  "奖励自动进入你的算力账户",
]
</script>

<template>
  <main class="referral-page">
    <section class="referral-hero">
      <div class="referral-hero__copy">
        <p class="referral-eyebrow">
          <Gift class="h-4 w-4" aria-hidden="true" />
          推荐有礼
        </p>
        <h1>邀请好友一起创作，双方都得算力奖励</h1>
        <p class="referral-subtitle">把你的专属链接发给好友。好友完成注册和首次创作后，奖励会自动到账。</p>
      </div>

      <div class="referral-link-card">
        <span class="referral-link-card__label">专属邀请链接</span>
        <div class="referral-link-card__url">
          <Link2 class="h-4 w-4" aria-hidden="true" />
          <span>{{ inviteUrl }}</span>
        </div>
        <button type="button" class="referral-primary-button" @click="copyInviteLink">
          <Copy class="h-4 w-4" aria-hidden="true" />
          {{ copied ? "已复制" : "复制链接" }}
        </button>
      </div>
    </section>

    <section class="referral-rewards" aria-label="奖励权益">
      <article v-for="item in rewardCards" :key="item.title" class="referral-reward-card">
        <div class="referral-reward-card__icon">
          <component :is="item.icon" class="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <p>{{ item.title }}</p>
          <strong>{{ item.value }} <span>{{ item.unit }}</span></strong>
        </div>
      </article>
    </section>

    <section class="referral-panel">
      <div>
        <h2>邀请流程</h2>
        <ol class="referral-steps">
          <li v-for="(step, index) in steps" :key="step">
            <span>{{ index + 1 }}</span>
            <p>{{ step }}</p>
          </li>
        </ol>
      </div>
      <div class="referral-share-card">
        <Share2 class="h-6 w-6 text-cyan-300" aria-hidden="true" />
        <h2>活动说明</h2>
        <p>奖励规则可以后续接入后台配置。当前页面先承载推广入口、邀请链接和规则展示，方便后续接统计和返利结算。</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.referral-page {
  min-height: 100%;
  padding: 28px;
  color: #fff;
}

.referral-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 420px);
  gap: 24px;
  align-items: stretch;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgb(8 13 24 / 0.98), rgb(14 24 36 / 0.96)),
    radial-gradient(circle at 18% 0%, rgb(34 211 238 / 0.16), transparent 38%);
  padding: 30px;
}

.referral-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  color: rgb(103 232 249);
  font-size: 13px;
  font-weight: 700;
}

.referral-hero h1 {
  max-width: 760px;
  margin: 0;
  font-size: clamp(30px, 5vw, 56px);
  line-height: 1.04;
  letter-spacing: 0;
}

.referral-subtitle {
  max-width: 620px;
  margin: 16px 0 0;
  color: rgb(203 213 225 / 0.78);
  font-size: 15px;
  line-height: 1.8;
}

.referral-link-card,
.referral-share-card,
.referral-reward-card,
.referral-panel {
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.045);
}

.referral-link-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  padding: 20px;
}

.referral-link-card__label {
  color: rgb(148 163 184);
  font-size: 12px;
}

.referral-link-card__url {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  border-radius: 8px;
  background: rgb(0 0 0 / 0.26);
  padding: 12px;
  color: rgb(226 232 240);
  font-size: 13px;
}

.referral-link-card__url span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.referral-primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: #67e8f9;
  padding: 12px 14px;
  color: #06212a;
  font-weight: 800;
}

.referral-rewards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.referral-reward-card {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 18px;
}

.referral-reward-card__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 10px;
  background: rgb(103 232 249 / 0.12);
  color: rgb(103 232 249);
}

.referral-reward-card p {
  margin: 0 0 4px;
  color: rgb(148 163 184);
  font-size: 12px;
}

.referral-reward-card strong {
  font-size: 26px;
}

.referral-reward-card span {
  color: rgb(203 213 225);
  font-size: 13px;
}

.referral-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 360px);
  gap: 24px;
  margin-top: 18px;
  padding: 22px;
}

.referral-panel h2,
.referral-share-card h2 {
  margin: 0 0 14px;
  font-size: 18px;
}

.referral-steps {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.referral-steps li {
  display: flex;
  align-items: center;
  gap: 12px;
  border-radius: 10px;
  background: rgb(0 0 0 / 0.2);
  padding: 12px;
}

.referral-steps span {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 999px;
  background: rgb(103 232 249 / 0.14);
  color: rgb(103 232 249);
  font-size: 12px;
  font-weight: 800;
}

.referral-steps p,
.referral-share-card p {
  margin: 0;
  color: rgb(203 213 225 / 0.78);
  font-size: 13px;
  line-height: 1.7;
}

.referral-share-card {
  padding: 18px;
}

@media (max-width: 860px) {
  .referral-page {
    padding: 18px;
  }

  .referral-hero,
  .referral-panel,
  .referral-rewards {
    grid-template-columns: 1fr;
  }
}
</style>
