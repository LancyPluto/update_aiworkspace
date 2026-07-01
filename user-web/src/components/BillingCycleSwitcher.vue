<script setup lang="ts">
import { computed } from "vue"
import { BILLING_CYCLES, type BillingCycle } from "@/utils/billingCycleConfig"

const props = defineProps<{
  modelValue: BillingCycle
}>()

const emit = defineEmits<{
  "update:modelValue": [value: BillingCycle]
}>()

const activeIndex = computed(() => BILLING_CYCLES.findIndex((item) => item.value === props.modelValue))

function select(value: BillingCycle) {
  emit("update:modelValue", value)
}
</script>

<template>
  <div class="billing-cycle-shell">
    <div class="member-tabs" aria-hidden="true">
      <span class="member-tab member-tab--active">算力会员</span>
      <span class="member-tab">连续订阅</span>
    </div>

    <div class="billing-cycle-track" role="tablist" aria-label="订阅周期">
      <div
        class="billing-cycle-thumb"
        :style="{ transform: `translateX(${Math.max(activeIndex, 0) * 100}%)` }"
        aria-hidden="true"
      />

      <button
        v-for="tab in BILLING_CYCLES"
        :key="tab.value"
        type="button"
        role="tab"
        class="billing-cycle-option"
        :class="{ 'is-active': modelValue === tab.value }"
        :aria-selected="modelValue === tab.value"
        @click="select(tab.value)"
      >
        <span class="billing-cycle-label">{{ tab.label }}</span>
        <span
          v-if="tab.badge"
          class="billing-cycle-badge"
          :class="tab.badgeVariant === 'orange' ? 'billing-cycle-badge--orange' : 'billing-cycle-badge--teal'"
        >
          {{ tab.badge }}
        </span>
        <span v-else-if="tab.hint" class="billing-cycle-hint">{{ tab.hint }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.billing-cycle-shell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
}

.member-tabs {
  display: inline-flex;
  align-items: center;
  gap: 28px;
}

.member-tab {
  position: relative;
  padding-bottom: 10px;
  color: rgb(255 255 255 / 0.42);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.member-tab--active {
  color: #fff;
}

.member-tab--active::after {
  content: "";
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 999px;
  background: #fff;
}

.billing-cycle-track {
  position: relative;
  display: grid;
  width: min(100%, 520px);
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: center;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
  padding: 4px;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.04);
}

.billing-cycle-thumb {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc((100% - 8px) / 3);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.11);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.08),
    0 4px 14px rgb(0 0 0 / 0.22);
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
  pointer-events: none;
}

.billing-cycle-option {
  position: relative;
  z-index: 1;
  display: inline-flex;
  min-height: 42px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  background: transparent;
  padding: 8px 10px;
  color: rgb(255 255 255 / 0.48);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.2s ease;
}

.billing-cycle-option.is-active {
  color: #fff;
}

.billing-cycle-option:not(.is-active):hover {
  color: rgb(255 255 255 / 0.78);
}

.billing-cycle-label {
  line-height: 1.2;
}

.billing-cycle-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 6px;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0.01em;
}

.billing-cycle-badge--orange {
  background: linear-gradient(135deg, #f97316, #ea580c);
  color: #fff7ed;
}

.billing-cycle-badge--teal {
  background: linear-gradient(135deg, #14b8a6, #0891b2);
  color: #ecfeff;
}

.billing-cycle-hint {
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  font-weight: 500;
}

@media (max-width: 560px) {
  .billing-cycle-option {
    flex-direction: column;
    gap: 4px;
    padding: 10px 6px;
    font-size: 12px;
  }

  .member-tabs {
    gap: 20px;
    font-size: 14px;
  }
}
</style>
