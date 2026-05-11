<script setup lang="ts">
import { CheckCircle2, Lightbulb, Save } from "lucide-vue-next"

const platforms = ["淘宝", "京东", "拼多多", "抖音电商", "小红书", "微信小店"]
const audiences = ["宝妈", "白领", "学生", "银发族", "Z 世代", "新中产"]
const styles = ["理性", "感性", "专业", "俏皮", "高端"]
</script>

<template>
  <div class="rounded-xl border border-border bg-card p-6 shadow-sm">
    <div class="flex items-center justify-between mb-5">
      <div>
        <h2 class="text-base font-semibold">参数填写</h2>
        <p class="text-xs text-muted-foreground mt-0.5">标 <span class="text-destructive">*</span> 为必填项</p>
      </div>
      <button
        type="button"
        class="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary"
      >
        <Save class="h-3.5 w-3.5" /> 保存为草稿
      </button>
    </div>

    <div class="space-y-5">
      <div class="space-y-2">
        <label class="text-sm font-medium">商品名称 <span class="text-destructive">*</span></label>
        <input
          class="flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          value="海岸夏季冰丝连衣裙"
        />
        <p class="text-[11px] text-muted-foreground">建议包含品牌词与核心品类，便于平台识别</p>
      </div>

      <div class="space-y-2">
        <label class="text-sm font-medium">核心卖点 <span class="text-destructive">*</span></label>
        <textarea
          rows="5"
          class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm resize-none"
        >100% 莫代尔冰丝面料
V 领设计显瘦显高
270° 大裙摆
体感降温 4-6℃
孕妇敏感肌可穿</textarea>
        <div class="flex items-center justify-between text-[11px] text-muted-foreground">
          <span>当前 5 条 · 推荐 3-5 条</span>
          <button type="button" class="inline-flex items-center gap-1 text-primary hover:underline">
            <Lightbulb class="h-3 w-3" /> 让 AI 帮我提取卖点
          </button>
        </div>
      </div>

      <div class="grid sm:grid-cols-2 gap-5">
        <div class="space-y-2">
          <label class="text-sm font-medium">投放平台 <span class="text-destructive">*</span></label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="(p, i) in platforms"
              :key="p"
              type="button"
              class="rounded-md border px-3 py-1.5 text-xs font-medium transition"
              :class="
                i < 2
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-background text-foreground/70 hover:border-primary/40'
              "
            >
              {{ p }}
              <CheckCircle2 v-if="i < 2" class="ml-1 inline h-3 w-3" />
            </button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium">目标人群 <span class="text-destructive">*</span></label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="(p, i) in audiences"
              :key="p"
              type="button"
              class="rounded-md border px-3 py-1.5 text-xs font-medium transition"
              :class="
                i === 1
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-background text-foreground/70 hover:border-primary/40'
              "
            >
              {{ p }}
            </button>
          </div>
        </div>
      </div>

      <div class="grid sm:grid-cols-2 gap-5">
        <div class="space-y-2">
          <label class="text-sm font-medium">文案风格</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="(p, i) in styles"
              :key="p"
              type="button"
              class="rounded-md border px-3 py-1.5 text-xs font-medium transition"
              :class="
                i === 1
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-background text-foreground/70 hover:border-primary/40'
              "
            >
              {{ p }}
            </button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium">字数限制</label>
          <div class="flex items-center gap-3">
            <input
              type="number"
              value="200"
              class="w-32 rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
            <span class="text-xs text-muted-foreground">字以内（默认 200）</span>
          </div>
        </div>
      </div>

      <div class="flex items-center justify-between rounded-lg border border-border bg-secondary/40 p-3">
        <label class="flex items-center gap-2 text-sm">
          <input type="checkbox" checked class="h-4 w-4 rounded accent-primary" />
          同时生成 5 条候选并自动评分排序
        </label>
        <span class="text-xs text-muted-foreground">将额外消耗 8 算力</span>
      </div>
    </div>
  </div>
</template>
