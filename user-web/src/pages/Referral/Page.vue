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
  { title: "好友注册", value: "绑定", unit: "邀请关系", icon: Users },
  { title: "好友充值", value: "10%", unit: "算力奖励", icon: Ticket },
  { title: "自动到账", value: "幂等", unit: "防重复", icon: Sparkles },
]

const steps = [
  "分享邀请链接给好友",
  "好友通过链接注册并成功充值",
  "奖励自动进入你的赠送算力账户",
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
        <h1>邀请好友，获得算力奖励</h1>
        <p class="referral-subtitle">好友通过你的链接注册并完成普通算力充值后，系统会按充值到账算力的 10% 自动发放邀请奖励。</p>
      </div>

      <div class="referral-link-card">
        <div class="referral-link-card__header">
          <span class="referral-link-card__label">专属邀请链接</span>
          <code>{{ inviteCode }}</code>
        </div>
        <div class="referral-link-card__url">
          <Link2 class="h-4 w-4" aria-hidden="true" />
          <span>{{ inviteUrl }}</span>
        </div>
        <button type="button" class="referral-primary-button" :class="{ copied }" @click="copyInviteLink">
          <Copy class="h-4 w-4" aria-hidden="true" />
          {{ copied ? "已复制" : "复制链接" }}
        </button>
      </div>
    </section>

    <section class="referral-section" aria-label="奖励权益">
      <div class="referral-section__heading">
        <h2>奖励权益</h2>
        <p>围绕注册、充值和到账三个节点自动处理。</p>
      </div>
      <div class="referral-rewards">
        <article v-for="item in rewardCards" :key="item.title" class="referral-reward-card">
          <div class="referral-reward-card__icon">
            <component :is="item.icon" class="h-5 w-5" aria-hidden="true" />
          </div>
          <div>
            <p>{{ item.title }}</p>
            <strong>{{ item.value }} <span>{{ item.unit }}</span></strong>
          </div>
        </article>
      </div>
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
        <p>邀请关系在注册时绑定，普通算力充值成功入账后发放奖励；礼品卡订单不触发邀请奖励，重复支付回调不会重复发放。</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.referral-page {
  min-height: 100%;
  padding: 24px;
  background: #10110f;
  color: #f4f5f0;
}

.referral-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 420px);
  gap: 18px;
  align-items: end;
  width: min(1180px, 100%);
  margin: 0 auto;
  border: 1px solid rgb(173 164 143 / 0.16);
  border-radius: 8px;
  background: #181916;
  padding: 22px;
}

.referral-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: #5eead4;
  font-size: 13px;
  font-weight: 800;
}

.referral-hero h1 {
  margin: 0;
  max-width: 720px;
  color: #fafaf7;
  font-size: 30px;
  line-height: 1.18;
  letter-spacing: 0;
}

.referral-subtitle {
  max-width: 620px;
  margin: 10px 0 0;
  color: #9da397;
  font-size: 15px;
  line-height: 1.75;
}

.referral-link-card,
.referral-share-card,
.referral-reward-card,
.referral-panel,
.referral-section {
  border: 1px solid rgb(173 164 143 / 0.16);
  border-radius: 8px;
  background: #181916;
}

.referral-link-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
  padding: 16px;
  background: #171d19;
}

.referral-link-card__header,
.referral-section__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.referral-link-card__label {
  color: #9da397;
  font-size: 12px;
  font-weight: 800;
}

.referral-link-card code {
  border: 1px solid rgb(45 212 191 / 0.2);
  border-radius: 999px;
  background: rgb(6 95 70 / 0.18);
  color: #a7f3d0;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 800;
}

.referral-link-card__url {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  border-radius: 8px;
  border: 1px solid rgb(173 164 143 / 0.12);
  background: #111310;
  padding: 12px;
  color: #ecefe7;
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
  min-height: 42px;
  border: 1px solid rgb(45 212 191 / 0.45);
  border-radius: 8px;
  background: #0f766e;
  color: #fafaf7;
  font-weight: 800;
}

.referral-primary-button:hover {
  background: #0d9488;
}

.referral-primary-button.copied {
  border-color: rgb(52 211 153 / 0.42);
  background: rgb(6 95 70 / 0.38);
  color: #bbf7d0;
}

.referral-section {
  width: min(1180px, 100%);
  margin: 14px auto 0;
  padding: 18px;
}

.referral-section__heading {
  margin-bottom: 14px;
}

.referral-section__heading h2,
.referral-panel h2,
.referral-share-card h2 {
  margin: 0;
  color: #fafaf7;
  font-size: 18px;
}

.referral-section__heading p {
  margin: 0;
  color: #9da397;
  font-size: 13px;
}

.referral-rewards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.referral-reward-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #121411;
  padding: 16px;
}

.referral-reward-card__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 8px;
  background: rgb(45 212 191 / 0.12);
  color: #5eead4;
}

.referral-reward-card p {
  margin: 0 0 4px;
  color: #9da397;
  font-size: 12px;
}

.referral-reward-card strong {
  color: #fafaf7;
  font-size: 26px;
}

.referral-reward-card span {
  color: #c4c8bd;
  font-size: 13px;
}

.referral-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 360px);
  gap: 18px;
  width: min(1180px, 100%);
  margin: 14px auto 0;
  padding: 18px;
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
  border: 1px solid rgb(173 164 143 / 0.12);
  border-radius: 8px;
  background: #121411;
  padding: 12px;
}

.referral-steps span {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 999px;
  background: rgb(45 212 191 / 0.14);
  color: #5eead4;
  font-size: 12px;
  font-weight: 800;
}

.referral-steps p,
.referral-share-card p {
  margin: 0;
  color: #9da397;
  font-size: 13px;
  line-height: 1.7;
}

.referral-share-card {
  padding: 18px;
  background: #171d19;
}

.referral-share-card svg {
  color: #5eead4;
}

@media (max-width: 860px) {
  .referral-page {
    padding: 16px;
  }

  .referral-hero,
  .referral-panel,
  .referral-rewards {
    grid-template-columns: 1fr;
  }

  .referral-link-card__header,
  .referral-section__heading {
    align-items: stretch;
    flex-direction: column;
  }

  .referral-primary-button {
    width: 100%;
  }
}
</style>
