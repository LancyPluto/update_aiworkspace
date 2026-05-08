<script setup>
  import { onMounted, ref, computed } from 'vue'
  import { RouterLink } from 'vue-router'
  import * as toolsApi from '../api/tools'
  import { requestErrorMessage } from '../utils/errors'

  const loading = ref(true)
  const errorMsg = ref('')
  const categories = ref([])
  const tools = ref([])
  const selectedCategoryId = ref('')

  const filteredTools = computed(() => {
    if (!selectedCategoryId.value) return tools.value
    return tools.value.filter(
      (t) =>
        String(t.categoryId ?? t.category?.id ?? '') ===
        selectedCategoryId.value,
    )
  })

  onMounted(async () => {
    loading.value = true
    errorMsg.value = ''
    try {
      const [cats, list] = await Promise.all([
        toolsApi.listCategories(),
        toolsApi.listTools(),
      ])
      categories.value = Array.isArray(cats) ? cats : []
      tools.value = Array.isArray(list) ? list : []
    } catch (e) {
      errorMsg.value = requestErrorMessage(e)
    } finally {
      loading.value = false
    }
  })

  function categoryLabel(c) {
    return c.name ?? c.categoryName ?? c.label ?? c.id
  }

  function selectCategory(id) {
    selectedCategoryId.value = selectedCategoryId.value === id ? '' : id
  }
</script>

<template>
  <div class="page tools-page">
    <div class="page-head">
      <h1 class="page-title">工具</h1>
      <p class="page-desc">选择工具，填写参数即可发起 AI 任务</p>
    </div>

    <div v-if="categories.length" class="chips">
      <button
        type="button"
        class="chip"
        :class="{ active: !selectedCategoryId }"
        @click="selectedCategoryId = ''"
      >
        全部
      </button>
      <button
        v-for="c in categories"
        :key="c.id ?? c.categoryId ?? categoryLabel(c)"
        type="button"
        class="chip"
        :class="{
          active:
            selectedCategoryId === String(c.id ?? c.categoryId ?? ''),
        }"
        @click="selectCategory(String(c.id ?? c.categoryId ?? ''))"
      >
        {{ categoryLabel(c) }}
      </button>
    </div>

    <p v-if="loading" class="state">加载中…</p>
    <p v-else-if="errorMsg" class="state error">{{ errorMsg }}</p>
    <div v-else-if="!filteredTools.length" class="empty">
      暂无工具，请稍后再试
    </div>

    <ul v-else class="tool-grid">
      <li v-for="t in filteredTools" :key="t.toolCode ?? t.id" class="tool-card">
        <RouterLink
          class="tool-link"
          :to="`/tools/${encodeURIComponent(t.toolCode)}`"
        >
          <div class="cover-wrap">
            <img
              v-if="t.coverUrl"
              :src="t.coverUrl"
              :alt="t.toolName"
              class="cover"
            />
            <div v-else class="cover placeholder" />
          </div>
          <div class="body">
            <h2 class="name">{{ t.toolName }}</h2>
            <p class="desc">{{ t.description }}</p>
            <div class="meta">
              <span v-if="t.categoryName" class="tag">{{
                t.categoryName
              }}</span>
              <span v-if="t.estimatedCreditCost != null" class="credit"
                >约 {{ t.estimatedCreditCost }} 算力</span
              >
            </div>
          </div>
        </RouterLink>
      </li>
    </ul>
  </div>
</template>

<style scoped>
  .tools-page {
    text-align: left;
    padding: 24px;
    max-width: 960px;
    margin: 0 auto;
  }

  .page-head {
    margin-bottom: 20px;
  }

  .page-title {
    font-size: 28px;
    margin: 0 0 8px;
  }

  .page-desc {
    margin: 0;
    color: var(--text);
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 24px;
  }

  .chip {
    padding: 6px 14px;
    border-radius: 999px;
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--text);
    cursor: pointer;
    font: inherit;
    font-size: 14px;
  }

  .chip.active {
    border-color: var(--accent-border);
    background: var(--accent-bg);
    color: var(--text-h);
  }

  .state {
    text-align: center;
    padding: 40px;
    color: var(--text);
  }

  .state.error {
    color: #ef4444;
  }

  .empty {
    text-align: center;
    padding: 48px 20px;
    color: var(--text);
    border: 1px dashed var(--border);
    border-radius: 12px;
  }

  .tool-grid {
    list-style: none;
    padding: 0;
    margin: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 20px;
  }

  .tool-card {
    border: 1px solid var(--border);
    border-radius: 12px;
    overflow: hidden;
    transition: box-shadow 0.2s;
  }

  .tool-card:hover {
    box-shadow: var(--shadow);
  }

  .tool-link {
    display: block;
    text-decoration: none;
    color: inherit;
  }

  .cover-wrap {
    aspect-ratio: 16 / 9;
    background: var(--code-bg);
  }

  .cover {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .cover.placeholder {
    width: 100%;
    height: 100%;
    background: linear-gradient(
      135deg,
      var(--accent-bg),
      var(--code-bg)
    );
  }

  .body {
    padding: 16px;
  }

  .name {
    font-size: 18px;
    margin: 0 0 8px;
    color: var(--text-h);
  }

  .desc {
    font-size: 14px;
    color: var(--text);
    margin: 0 0 12px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .meta {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    font-size: 13px;
  }

  .tag {
    padding: 2px 8px;
    border-radius: 6px;
    background: var(--social-bg);
    color: var(--text-h);
  }

  .credit {
    color: var(--accent);
  }
</style>
