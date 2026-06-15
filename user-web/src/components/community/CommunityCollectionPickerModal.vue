<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { FolderPlus, Loader2, Plus, X } from "lucide-vue-next"
import { createCommunityCollection, fetchCommunityCollections } from "@/api/communityApi"
import type { CommunityCollection } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  open: boolean
  postTitle?: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [collectionId: number | null]
}>()

const auth = useAuthStore()

const loading = ref(false)
const creating = ref(false)
const loadError = ref("")
const collections = ref<CommunityCollection[]>([])
const collectionsSupported = ref(true)
const selectedId = ref<number | null>(null)
const createOpen = ref(false)
const createName = ref("")

const canConfirm = computed(() => {
  if (props.submitting || loading.value || creating.value) return false
  if (!collectionsSupported.value) return true
  return selectedId.value != null
})

async function loadCollections() {
  loading.value = true
  loadError.value = ""
  try {
    const result = await fetchCommunityCollections({ token: auth.token })
    collectionsSupported.value = result.supported
    collections.value = result.collections
    if (result.supported && result.collections.length) {
      const preferred = result.collections.find((item) => item.defaultCollection) || result.collections[0]
      selectedId.value = preferred?.id ?? null
    } else {
      selectedId.value = null
    }
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : "收藏夹加载失败"
    collections.value = []
    selectedId.value = null
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      createOpen.value = false
      createName.value = ""
      return
    }
    void loadCollections()
  },
)

function close() {
  if (props.submitting || creating.value) return
  emit("close")
}

function submit() {
  if (!canConfirm.value) return
  emit("confirm", collectionsSupported.value ? selectedId.value : null)
}

async function submitCreate() {
  const name = createName.value.trim()
  if (!name || creating.value || !collectionsSupported.value) return
  creating.value = true
  try {
    const created = await createCommunityCollection(name, { token: auth.token })
    collections.value = [created, ...collections.value.filter((item) => item.id !== created.id)]
    selectedId.value = created.id
    createOpen.value = false
    createName.value = ""
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : "创建收藏夹失败"
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="picker-backdrop"
      @click.self="close"
    >
      <div class="picker-panel" role="dialog" aria-labelledby="collection-picker-title">
        <div class="picker-head">
          <div>
            <p class="picker-eyebrow">灵感收藏</p>
            <h2 id="collection-picker-title" class="picker-title">选择收藏夹</h2>
            <p v-if="postTitle" class="picker-subtitle">将「{{ postTitle }}」加入收藏夹</p>
          </div>
          <button
            type="button"
            class="picker-close"
            aria-label="关闭"
            :disabled="submitting || creating"
            @click="close"
          >
            <X class="h-4 w-4" />
          </button>
        </div>

        <div class="picker-body">
          <p v-if="loadError" class="picker-error">{{ loadError }}</p>

          <p v-else-if="!collectionsSupported" class="picker-hint">
            当前环境暂不支持灵感收藏夹，确认后仍会收藏该作品。
          </p>

          <div v-else-if="loading" class="picker-loading">
            <Loader2 class="h-5 w-5 animate-spin" />
            正在加载收藏夹…
          </div>

          <template v-else>
            <div v-if="collections.length" class="collection-list" role="radiogroup" aria-label="收藏夹列表">
              <label
                v-for="collection in collections"
                :key="collection.id"
                class="collection-option"
                :class="{ active: selectedId === collection.id }"
              >
                <input
                  v-model="selectedId"
                  class="collection-radio"
                  type="radio"
                  name="community-collection"
                  :value="collection.id"
                />
                <span class="collection-option-body">
                  <span class="collection-option-name">{{ collection.name }}</span>
                  <span class="collection-option-meta">{{ collection.itemCount }} 个作品</span>
                </span>
              </label>
            </div>

            <p v-else class="picker-hint">还没有收藏夹，请先创建一个。</p>

            <div class="create-block">
              <button
                v-if="!createOpen"
                type="button"
                class="create-toggle"
                :disabled="submitting || creating"
                @click="createOpen = true"
              >
                <Plus class="h-4 w-4" />
                新建收藏夹
              </button>

              <form v-else class="create-form" @submit.prevent="submitCreate">
                <FolderPlus class="create-icon" />
                <input
                  v-model="createName"
                  autofocus
                  maxlength="80"
                  placeholder="收藏夹名称"
                  :disabled="creating"
                />
                <button type="button" class="create-cancel" :disabled="creating" @click="createOpen = false">取消</button>
                <button type="submit" class="create-submit" :disabled="!createName.trim() || creating">
                  <Loader2 v-if="creating" class="h-4 w-4 animate-spin" />
                  创建
                </button>
              </form>
            </div>
          </template>
        </div>

        <div class="picker-foot">
          <button type="button" class="ghost-btn" :disabled="submitting || creating" @click="close">取消</button>
          <button type="button" class="primary-btn" :disabled="!canConfirm" @click="submit">
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
            确认收藏
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.picker-backdrop {
  position: fixed;
  inset: 0;
  z-index: 130;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.7);
  padding: 16px;
  backdrop-filter: blur(4px);
}

.picker-panel {
  width: min(100%, 420px);
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 28px;
  background: #12131a;
  box-shadow: 0 30px 80px rgb(0 0 0 / 0.45);
}

.picker-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  padding: 20px 24px;
}

.picker-eyebrow {
  margin: 0;
  color: rgb(255 255 255 / 0.35);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.picker-title {
  margin: 4px 0 0;
  color: #fff;
  font-size: 20px;
  font-weight: 700;
}

.picker-subtitle {
  margin: 8px 0 0;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
  line-height: 1.5;
}

.picker-close {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.6);
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}

.picker-close:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.1);
  color: #fff;
}

.picker-body {
  display: grid;
  gap: 14px;
  max-height: min(52vh, 420px);
  overflow-y: auto;
  padding: 18px 24px;
}

.picker-loading,
.picker-hint,
.picker-error {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
}

.picker-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: rgb(255 255 255 / 0.55);
  padding: 24px 0;
}

.picker-hint {
  color: rgb(255 255 255 / 0.55);
}

.picker-error {
  color: #fca5a5;
}

.collection-list {
  display: grid;
  gap: 8px;
}

.collection-option {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.03);
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.collection-option.active {
  border-color: rgb(168 85 247 / 0.42);
  background: rgb(124 58 237 / 0.12);
}

.collection-radio {
  margin: 0;
  accent-color: #a855f7;
}

.collection-option-body {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.collection-option-name {
  overflow: hidden;
  color: rgb(255 255 255 / 0.88);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-option-meta {
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
}

.create-block {
  padding-top: 4px;
}

.create-toggle {
  display: inline-flex;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.18);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.03);
  color: rgb(255 255 255 / 0.78);
  padding: 10px 12px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.create-toggle:hover:not(:disabled) {
  border-color: var(--primary, #a855f7);
  background: rgb(124 58 237 / 0.1);
}

.create-form {
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 14px;
  background: rgb(255 255 255 / 0.04);
  padding: 10px 12px;
}

.create-icon {
  width: 18px;
  height: 18px;
  color: rgb(255 255 255 / 0.45);
}

.create-form input {
  min-width: 0;
  border: 0;
  background: transparent;
  color: #fff;
  font-size: 14px;
  outline: none;
}

.create-cancel,
.create-submit {
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
  padding: 4px 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.create-submit {
  color: var(--primary, #c084fc);
}

.create-submit:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.picker-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid rgb(255 255 255 / 0.08);
  padding: 16px 24px 20px;
}

.ghost-btn,
.primary-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 999px;
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.18s ease, transform 0.18s ease, background 0.18s ease;
}

.ghost-btn {
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
}

.ghost-btn:hover:not(:disabled) {
  color: #fff;
}

.primary-btn {
  border: 0;
  background: linear-gradient(135deg, #7c3aed, #db2777);
  color: #fff;
}

.primary-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
