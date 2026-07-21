<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue"
import {
  Check,
  ImageOff,
  Images,
  Loader2,
  Plus,
  Save,
  ShieldCheck,
  Sparkles,
  UserRound,
  X,
} from "lucide-vue-next"
import {
  comicProjectApi,
  type ComicCharacter,
  type ComicCharacterVersion,
  type ComicEntityId,
  type ComicEpisodeDetail,
  type ComicProjectDetail,
  type ComicScene,
  type ComicSceneVersion,
} from "@/api/comicProjectApi"
import { useAuthStore } from "@/store/authStore"
import {
  retainComicClientRequestAttempt,
  type ComicClientRequestAttempt,
} from "@/utils/comicProject"
import { randomUUID } from "@/utils/randomUUID"

type AssetDraft = { kind: "character" | "scene"; id: number; name: string } | null

const props = defineProps<{
  projectId: ComicEntityId
  project: ComicProjectDetail
  episode: ComicEpisodeDetail
}>()

const emit = defineEmits<{
  updated: [episode: ComicEpisodeDetail]
  "refresh-project": []
  "open-shots": []
}>()

const auth = useAuthStore()
const selectedCharacterVersions = reactive<Record<number, number | null>>({})
const shotCharacterRefs = reactive<Record<number, number[]>>({})
const shotSceneRefs = reactive<Record<number, number | null>>({})
const dirty = ref(false)
const submitting = ref(false)
const error = ref<string | null>(null)
const addKind = ref<"character" | "scene" | null>(null)
const assetName = ref("")
const assetDescription = ref("")
const versionTarget = ref<AssetDraft>(null)
const versionPrompt = ref("")
const characterVersionImages = reactive({ front: "", side: "", back: "" })
const sceneVersionImageUrl = ref("")
const gateOpen = ref(false)
const launchingVersionKeys = reactive(new Set<string>())
const assetGenerationAttempts = new Map<string, ComicClientRequestAttempt>()
let assetPollTimer: ReturnType<typeof setTimeout> | null = null

const characters = computed(() => props.project.characters ?? props.episode.characters ?? [])
const scenes = computed(() => props.project.scenes ?? props.episode.scenes ?? [])

function readyCharacterVersions(character: ComicCharacter): ComicCharacterVersion[] {
  return (character.versions ?? []).filter((version) => String(version.status ?? "READY").toUpperCase() === "READY")
}

function readySceneVersions(scene: ComicScene): ComicSceneVersion[] {
  return (scene.versions ?? []).filter((version) => String(version.status ?? "READY").toUpperCase() === "READY")
}

function assetStatus(version: { status?: string | null }): string {
  return String(version.status ?? "READY").toUpperCase()
}

function actionableCharacterVersions(character: ComicCharacter): ComicCharacterVersion[] {
  return (character.versions ?? []).filter((version) => ["DRAFT", "FAILED", "GENERATING"].includes(assetStatus(version)))
}

function actionableSceneVersions(scene: ComicScene): ComicSceneVersion[] {
  return (scene.versions ?? []).filter((version) => ["DRAFT", "FAILED", "GENERATING"].includes(assetStatus(version)))
}

function selectedCharacterVersion(character: ComicCharacter): ComicCharacterVersion | null {
  return readyCharacterVersions(character).find((version) => version.id === selectedCharacterVersions[character.id]) ?? null
}

function characterPreviewImages(version: ComicCharacterVersion | null): Array<{ label: string; url: string | null | undefined }> {
  return [
    { label: "正面", url: version?.frontImageUrl },
    { label: "侧面", url: version?.sideImageUrl },
    { label: "背面", url: version?.backImageUrl },
  ]
}

function isCharacterContactSheet(version: ComicCharacterVersion | null): boolean {
  if (!version?.frontImageUrl || !version.sideImageUrl || !version.backImageUrl) return false
  return version.frontImageUrl === version.sideImageUrl && version.sideImageUrl === version.backImageUrl
}

function versionStatusLabel(version: { status?: string | null }): string {
  const status = assetStatus(version)
  if (status === "GENERATING") return "AI 生成中"
  if (status === "FAILED") return "生成失败"
  return "待生成"
}

function versionStatusClass(version: { status?: string | null }): string {
  const status = assetStatus(version)
  if (status === "GENERATING") return "bg-primary/10 text-primary"
  if (status === "FAILED") return "bg-destructive/10 text-destructive"
  return "bg-secondary text-muted-foreground"
}

function versionKey(kind: "character" | "scene", versionId: number): string {
  return `${kind}:${versionId}`
}

function assetGenerationSignature(
  kind: "character" | "scene",
  assetId: number,
  version: ComicCharacterVersion | ComicSceneVersion,
): string {
  return JSON.stringify({
    operation: kind === "character" ? "comic.character.generate" : "comic.scene.generate",
    projectId: String(props.projectId),
    assetId,
    versionId: version.id,
    versionNo: version.versionNo ?? null,
    versionStatus: assetStatus(version),
  })
}

function isVersionLaunching(kind: "character" | "scene", versionId: number): boolean {
  return launchingVersionKeys.has(versionKey(kind, versionId))
}

const hasGeneratingAssets = computed(() => (
  launchingVersionKeys.size > 0
  || characters.value.some((character) => (character.versions ?? []).some((version) => assetStatus(version) === "GENERATING"))
  || scenes.value.some((scene) => (scene.versions ?? []).some((version) => assetStatus(version) === "GENERATING"))
))

const selectedCharacterVersionIds = computed(() => Object.values(selectedCharacterVersions).filter((id): id is number => typeof id === "number"))
const characterVersionImageCount = computed(() => Object.values(characterVersionImages).filter((url) => url.trim()).length)
const characterVersionImagesValid = computed(() => characterVersionImageCount.value === 0 || characterVersionImageCount.value === 3)
const versionCanSubmit = computed(() => {
  if (!versionTarget.value || versionPrompt.value.trim().length < 5 || submitting.value) return false
  return versionTarget.value.kind === "scene" || characterVersionImagesValid.value
})
const allShotsAssigned = computed(() => {
  const shots = props.episode.shots ?? []
  if (!shots.length) return false
  return shots.every((shot) => {
    if (shot.id == null) return false
    const hasScene = typeof shotSceneRefs[shot.id] === "number"
    const hasCharacters = characters.value.length === 0 || (shotCharacterRefs[shot.id]?.length ?? 0) > 0
    return hasScene && hasCharacters
  })
})

watch(characters, () => {
  const characterIds = new Set(characters.value.map((character) => character.id))
  for (const key of Object.keys(selectedCharacterVersions).map(Number)) {
    if (!characterIds.has(key)) delete selectedCharacterVersions[key]
  }
  for (const character of characters.value) {
    const versions = readyCharacterVersions(character)
    const current = selectedCharacterVersions[character.id]
    if (current != null && versions.some((version) => version.id === current)) continue
    const preferred = versions.find((version) => version.id === character.selectedVersionId)
    selectedCharacterVersions[character.id] = preferred?.id ?? versions[0]?.id ?? null
  }
}, { immediate: true, deep: true })

watch(
  () => [props.episode.id, props.episode.revision, props.episode.assetsConfirmed] as const,
  () => {
    for (const key of Object.keys(shotCharacterRefs)) delete shotCharacterRefs[Number(key)]
    for (const key of Object.keys(shotSceneRefs)) delete shotSceneRefs[Number(key)]
    for (const shot of props.episode.shots ?? []) {
      if (shot.id == null) continue
      shotCharacterRefs[shot.id] = [...(shot.characterVersionIds ?? [])]
      shotSceneRefs[shot.id] = shot.sceneVersionId ?? null
    }
    dirty.value = false
    error.value = null
  },
  { immediate: true },
)

watch(
  () => [props.project.characters, props.project.scenes] as const,
  () => {
    for (const key of launchingVersionKeys) {
      const [kind, rawVersionId] = key.split(":")
      const versionId = Number(rawVersionId)
      const version = kind === "character"
        ? characters.value.flatMap((character) => character.versions ?? []).find((item) => item.id === versionId)
        : scenes.value.flatMap((scene) => scene.versions ?? []).find((item) => item.id === versionId)
      if (version && !["DRAFT", "FAILED"].includes(assetStatus(version))) {
        launchingVersionKeys.delete(key)
        assetGenerationAttempts.delete(key)
      }
    }
  },
  { deep: true },
)

function clearAssetPoll() {
  if (assetPollTimer) clearTimeout(assetPollTimer)
  assetPollTimer = null
}

function scheduleAssetPoll(delay = 2_500) {
  clearAssetPoll()
  if (!hasGeneratingAssets.value) return
  assetPollTimer = setTimeout(() => {
    emit("refresh-project")
    scheduleAssetPoll()
  }, delay)
}

watch(hasGeneratingAssets, (generating) => {
  if (generating) scheduleAssetPoll()
  else clearAssetPoll()
}, { immediate: true })

function versionLabel(version: { versionNo?: number | null; id: number }): string {
  return version.versionNo ? `V${version.versionNo}` : `版本 ${version.id}`
}

function selectCharacterVersion(characterId: number, value: string) {
  const previous = selectedCharacterVersions[characterId]
  const next = value ? Number(value) : null
  selectedCharacterVersions[characterId] = next
  if (previous !== next) {
    for (const shotId of Object.keys(shotCharacterRefs).map(Number)) {
      const refs = shotCharacterRefs[shotId] ?? []
      const withoutPrevious = previous == null ? refs : refs.filter((id) => id !== previous)
      if (previous != null && refs.includes(previous) && next != null) withoutPrevious.push(next)
      shotCharacterRefs[shotId] = [...new Set(withoutPrevious)]
    }
    dirty.value = true
  }
}

function toggleCharacterForShot(shotId: number, versionId: number | null) {
  if (versionId == null || props.episode.assetsConfirmed) return
  const current = shotCharacterRefs[shotId] ?? []
  shotCharacterRefs[shotId] = current.includes(versionId)
    ? current.filter((id) => id !== versionId)
    : [...current, versionId]
  dirty.value = true
}

function applyCharactersToAllShots() {
  if (props.episode.assetsConfirmed) return
  for (const shot of props.episode.shots ?? []) {
    if (shot.id != null) shotCharacterRefs[shot.id] = [...selectedCharacterVersionIds.value]
  }
  dirty.value = true
}

function applySceneToAllShots(value: string) {
  if (props.episode.assetsConfirmed) return
  const versionId = value ? Number(value) : null
  for (const shot of props.episode.shots ?? []) {
    if (shot.id != null) shotSceneRefs[shot.id] = versionId
  }
  dirty.value = true
}

async function createAsset() {
  if (!addKind.value || !assetName.value.trim() || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    if (addKind.value === "character") {
      await comicProjectApi.createCharacter(props.projectId, {
        name: assetName.value.trim(),
        description: assetDescription.value.trim() || undefined,
      }, { token: auth.token })
    } else {
      await comicProjectApi.createScene(props.projectId, {
        name: assetName.value.trim(),
        description: assetDescription.value.trim() || undefined,
      }, { token: auth.token })
    }
    addKind.value = null
    assetName.value = ""
    assetDescription.value = ""
    emit("refresh-project")
  } catch (createError) {
    error.value = createError instanceof Error ? createError.message : "资产创建失败"
  } finally {
    submitting.value = false
  }
}

function openVersion(kind: "character" | "scene", id: number, name: string) {
  versionTarget.value = { kind, id, name }
  versionPrompt.value = ""
  characterVersionImages.front = ""
  characterVersionImages.side = ""
  characterVersionImages.back = ""
  sceneVersionImageUrl.value = ""
  error.value = null
}

async function createVersion() {
  const target = versionTarget.value
  if (!target || !versionCanSubmit.value) return
  submitting.value = true
  error.value = null
  try {
    if (target.kind === "character") {
      await comicProjectApi.createCharacterVersion(props.projectId, target.id, {
        visualPrompt: versionPrompt.value.trim(),
        frontImageUrl: characterVersionImages.front.trim() || undefined,
        sideImageUrl: characterVersionImages.side.trim() || undefined,
        backImageUrl: characterVersionImages.back.trim() || undefined,
        status: characterVersionImageCount.value === 3 ? "READY" : "DRAFT",
      }, { token: auth.token })
    } else {
      await comicProjectApi.createSceneVersion(props.projectId, target.id, {
        visualPrompt: versionPrompt.value.trim(),
        anchorImageUrl: sceneVersionImageUrl.value.trim() || undefined,
        status: sceneVersionImageUrl.value.trim() ? "READY" : "DRAFT",
      }, { token: auth.token })
    }
    versionTarget.value = null
    emit("refresh-project")
  } catch (createError) {
    error.value = createError instanceof Error ? createError.message : "版本创建失败"
  } finally {
    submitting.value = false
  }
}

async function generateAssetVersion(
  kind: "character" | "scene",
  assetId: number,
  version: ComicCharacterVersion | ComicSceneVersion,
) {
  const status = assetStatus(version)
  const key = versionKey(kind, version.id)
  if (props.episode.assetsConfirmed || !["DRAFT", "FAILED"].includes(status) || launchingVersionKeys.has(key)) return
  launchingVersionKeys.add(key)
  error.value = null
  try {
    const attempt = retainComicClientRequestAttempt(
      assetGenerationAttempts.get(key) ?? null,
      assetGenerationSignature(kind, assetId, version),
      randomUUID,
    )
    assetGenerationAttempts.set(key, attempt)
    const body = { clientRequestId: attempt.clientRequestId }
    if (kind === "character") {
      await comicProjectApi.generateCharacterVersion(props.projectId, assetId, version.id, body, { token: auth.token })
    } else {
      await comicProjectApi.generateSceneVersion(props.projectId, assetId, version.id, body, { token: auth.token })
    }
    assetGenerationAttempts.delete(key)
    emit("refresh-project")
  } catch (generateError) {
    launchingVersionKeys.delete(key)
    error.value = generateError instanceof Error ? generateError.message : "AI 素材生成启动失败"
  }
}

async function saveAssignments(): Promise<ComicEpisodeDetail | null> {
  if (submitting.value || props.episode.assetsConfirmed) return null
  submitting.value = true
  error.value = null
  let current = props.episode
  try {
    for (const shot of current.shots ?? []) {
      if (shot.id == null) throw new Error("存在尚未保存的镜头")
      current = await comicProjectApi.patchShotAssetRefs(props.projectId, current.id, shot.id, {
        characterVersionIds: [...(shotCharacterRefs[shot.id] ?? [])],
        sceneVersionId: shotSceneRefs[shot.id] ?? null,
        expectedRevision: current.revision ?? 0,
      }, { token: auth.token })
    }
    dirty.value = false
    emit("updated", current)
    return current
  } catch (saveError) {
    error.value = saveError instanceof Error ? saveError.message : "镜头资产分配保存失败"
    return null
  } finally {
    submitting.value = false
  }
}

async function confirmAssets() {
  if (dirty.value || !allShotsAssigned.value || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    const result = await comicProjectApi.confirmAssets(
      props.projectId,
      props.episode.id,
      props.episode.revision ?? 0,
      { token: auth.token },
    )
    gateOpen.value = false
    emit("updated", result)
    emit("open-shots")
  } catch (confirmError) {
    error.value = confirmError instanceof Error ? confirmError.message : "资产确认失败"
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(clearAssetPoll)
</script>

<template>
  <section>
    <header class="flex flex-col gap-4 border-b border-border pb-5 xl:flex-row xl:items-start xl:justify-between">
      <div>
        <p class="text-xs font-medium text-primary">阶段 3 · 第二道人审门槛</p>
        <h2 class="mt-1 text-xl font-semibold">角色与场景</h2>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">选择角色三视图与场景锚点版本，再逐镜绑定需要保持一致的参考资产。</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button v-if="!episode.assetsConfirmed" type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="submitting || !dirty" @click="saveAssignments">
          <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" /><Save v-else class="h-4 w-4" /> 保存分配
        </button>
        <button v-if="!episode.assetsConfirmed" type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="submitting || dirty || !allShotsAssigned" @click="gateOpen = true">
          <ShieldCheck class="h-4 w-4" /> 确认资产
        </button>
        <span v-else class="inline-flex h-9 items-center gap-2 rounded-md border border-success/30 bg-success/10 px-3 text-sm text-success"><Check class="h-4 w-4" /> 资产已确认</span>
      </div>
    </header>

    <p v-if="error" class="mt-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ error }}</p>

    <section class="border-b border-border py-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div><h3 class="text-base font-semibold">角色版本</h3><p class="mt-1 text-sm text-muted-foreground">每个角色选择一个本集采用的外观版本。</p></div>
        <div v-if="!episode.assetsConfirmed" class="flex gap-2">
          <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="addKind = 'character'"><Plus class="h-4 w-4" /> 添加角色</button>
          <button type="button" class="h-9 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="!selectedCharacterVersionIds.length" @click="applyCharactersToAllShots">套用全部镜头</button>
        </div>
      </div>
      <div v-if="characters.length" class="mt-5 grid gap-4 md:grid-cols-2">
        <article v-for="character in characters" :key="character.id" class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0"><h4 class="truncate font-medium">{{ character.name }}</h4><p class="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">{{ character.description || "暂无角色描述" }}</p></div>
            <UserRound class="h-5 w-5 shrink-0 text-primary" />
          </div>
          <div v-if="selectedCharacterVersion(character)" class="mt-4">
            <div v-if="isCharacterContactSheet(selectedCharacterVersion(character))" class="relative aspect-[4/3] overflow-hidden rounded-md border border-border bg-background">
              <img :src="selectedCharacterVersion(character)?.frontImageUrl ?? ''" :alt="`${character.name}三视图合板`" class="h-full w-full object-contain" />
              <span class="absolute bottom-2 left-2 rounded bg-background/90 px-2 py-1 text-[11px] text-muted-foreground">三视图合板</span>
            </div>
            <div v-else class="grid grid-cols-3 gap-2">
              <div v-for="preview in characterPreviewImages(selectedCharacterVersion(character))" :key="preview.label" class="aspect-[3/4] overflow-hidden rounded-md border border-border bg-background">
                <img v-if="preview.url" :src="preview.url" :alt="`${character.name}${preview.label}`" class="h-full w-full object-cover" />
                <span v-else class="flex h-full flex-col items-center justify-center gap-1 text-[11px] text-muted-foreground"><ImageOff class="h-4 w-4" />{{ preview.label }}</span>
              </div>
            </div>
          </div>
          <div v-else class="mt-4 flex h-24 items-center justify-center border-y border-border text-xs text-muted-foreground">暂无可用三视图版本</div>
          <div class="mt-4 flex gap-2">
            <select :value="selectedCharacterVersions[character.id] ?? ''" class="h-9 min-w-0 flex-1 rounded-md border border-input bg-background px-2 text-sm" :disabled="Boolean(episode.assetsConfirmed)" @change="selectCharacterVersion(character.id, ($event.target as HTMLSelectElement).value)">
              <option value="">选择 READY 版本</option>
              <option v-for="version in readyCharacterVersions(character)" :key="version.id" :value="version.id">{{ versionLabel(version) }}</option>
            </select>
            <button v-if="!episode.assetsConfirmed" type="button" class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-border hover:bg-secondary" :aria-label="`为 ${character.name} 创建新版本`" title="创建新版本" @click="openVersion('character', character.id, character.name)"><Plus class="h-4 w-4" /></button>
          </div>
          <div v-if="actionableCharacterVersions(character).length" class="mt-3 divide-y divide-border border-t border-border">
            <div v-for="version in actionableCharacterVersions(character)" :key="version.id" class="flex min-w-0 flex-col gap-2 py-2.5 sm:flex-row sm:items-center sm:justify-between">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="text-xs font-medium">{{ versionLabel(version) }}</span>
                  <span class="rounded px-1.5 py-0.5 text-[11px]" :class="versionStatusClass(version)">{{ versionStatusLabel(version) }}</span>
                </div>
                <p class="mt-1 line-clamp-2 break-words text-xs leading-5 text-muted-foreground">{{ version.visualPrompt || "暂无视觉提示词" }}</p>
              </div>
              <span v-if="assetStatus(version) === 'GENERATING' || isVersionLaunching('character', version.id)" class="inline-flex h-8 shrink-0 items-center gap-2 text-xs text-primary">
                <Loader2 class="h-3.5 w-3.5 animate-spin" />{{ isVersionLaunching('character', version.id) ? '正在启动' : 'AI 生成中' }}
              </span>
              <button v-else-if="!episode.assetsConfirmed" type="button" class="inline-flex h-8 w-full shrink-0 items-center justify-center gap-2 rounded-md border border-primary/30 px-2 text-xs text-primary hover:bg-primary/10 sm:w-auto" @click="generateAssetVersion('character', character.id, version)">
                <Sparkles class="h-3.5 w-3.5" />AI 生成
              </button>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="mt-5 border-y border-border py-8 text-center text-sm text-muted-foreground">还没有角色资产。</p>
    </section>

    <section class="border-b border-border py-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div><h3 class="text-base font-semibold">场景版本</h3><p class="mt-1 text-sm text-muted-foreground">场景锚点用于固定空间结构、光线和色调。</p></div>
        <button v-if="!episode.assetsConfirmed" type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="addKind = 'scene'"><Plus class="h-4 w-4" /> 添加场景</button>
      </div>
      <div v-if="scenes.length" class="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <article v-for="scene in scenes" :key="scene.id" class="overflow-hidden rounded-lg border border-border bg-card">
          <div class="aspect-video bg-background">
            <img v-if="readySceneVersions(scene)[0]?.anchorImageUrl" :src="readySceneVersions(scene)[0]?.anchorImageUrl ?? ''" :alt="scene.name" class="h-full w-full object-cover" />
            <span v-else class="flex h-full flex-col items-center justify-center gap-2 text-xs text-muted-foreground"><Images class="h-5 w-5" />暂无场景图</span>
          </div>
          <div class="p-4"><h4 class="font-medium">{{ scene.name }}</h4><p class="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">{{ scene.description || "暂无场景描述" }}</p>
            <button v-if="!episode.assetsConfirmed" type="button" class="mt-3 inline-flex h-8 items-center gap-2 rounded-md border border-border px-2 text-xs hover:bg-secondary" @click="openVersion('scene', scene.id, scene.name)"><Plus class="h-3.5 w-3.5" /> 新版本</button>
            <div v-if="actionableSceneVersions(scene).length" class="mt-3 divide-y divide-border border-t border-border">
              <div v-for="version in actionableSceneVersions(scene)" :key="version.id" class="flex min-w-0 flex-col gap-2 py-2.5 sm:flex-row sm:items-center sm:justify-between">
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="text-xs font-medium">{{ versionLabel(version) }}</span>
                    <span class="rounded px-1.5 py-0.5 text-[11px]" :class="versionStatusClass(version)">{{ versionStatusLabel(version) }}</span>
                  </div>
                  <p class="mt-1 line-clamp-2 break-words text-xs leading-5 text-muted-foreground">{{ version.visualPrompt || "暂无视觉提示词" }}</p>
                </div>
                <span v-if="assetStatus(version) === 'GENERATING' || isVersionLaunching('scene', version.id)" class="inline-flex h-8 shrink-0 items-center gap-2 text-xs text-primary">
                  <Loader2 class="h-3.5 w-3.5 animate-spin" />{{ isVersionLaunching('scene', version.id) ? '正在启动' : 'AI 生成中' }}
                </span>
                <button v-else-if="!episode.assetsConfirmed" type="button" class="inline-flex h-8 w-full shrink-0 items-center justify-center gap-2 rounded-md border border-primary/30 px-2 text-xs text-primary hover:bg-primary/10 sm:w-auto" @click="generateAssetVersion('scene', scene.id, version)">
                  <Sparkles class="h-3.5 w-3.5" />AI 生成
                </button>
              </div>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="mt-5 border-y border-border py-8 text-center text-sm text-muted-foreground">还没有场景资产。</p>
    </section>

    <section class="py-6">
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div><h3 class="text-base font-semibold">逐镜资产分配</h3><p class="mt-1 text-sm text-muted-foreground">勾选出镜角色，并为每个镜头选择一个 READY 场景版本。</p></div>
        <label v-if="!episode.assetsConfirmed" class="text-xs text-muted-foreground">批量场景
          <select class="ml-2 h-9 rounded-md border border-input bg-background px-2 text-sm text-foreground" @change="applySceneToAllShots(($event.target as HTMLSelectElement).value)">
            <option value="">选择后套用全部</option>
            <optgroup v-for="scene in scenes" :key="scene.id" :label="scene.name"><option v-for="version in readySceneVersions(scene)" :key="version.id" :value="version.id">{{ scene.name }} · {{ versionLabel(version) }}</option></optgroup>
          </select>
        </label>
      </div>
      <div class="mt-4 divide-y divide-border border-y border-border">
        <article v-for="shot in episode.shots ?? []" :key="shot.id ?? shot.sequenceNo" class="grid gap-4 py-4 lg:grid-cols-[110px_minmax(0,1fr)_minmax(220px,0.8fr)] lg:items-center">
          <div><strong class="text-sm">分镜 {{ shot.sequenceNo }}</strong><p class="mt-1 text-xs text-muted-foreground">{{ shot.shotScale || "未设景别" }}</p></div>
          <div class="flex flex-wrap gap-2">
            <button v-for="character in characters" :key="character.id" type="button" class="inline-flex h-8 items-center gap-1.5 rounded-md border px-2 text-xs transition disabled:opacity-40" :class="shot.id != null && selectedCharacterVersions[character.id] != null && shotCharacterRefs[shot.id]?.includes(selectedCharacterVersions[character.id] as number) ? 'border-primary/40 bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-secondary'" :disabled="Boolean(episode.assetsConfirmed) || selectedCharacterVersions[character.id] == null" @click="shot.id != null && toggleCharacterForShot(shot.id, selectedCharacterVersions[character.id])">
              <Check v-if="shot.id != null && selectedCharacterVersions[character.id] != null && shotCharacterRefs[shot.id]?.includes(selectedCharacterVersions[character.id] as number)" class="h-3.5 w-3.5" />{{ character.name }}
            </button>
            <span v-if="!characters.length" class="text-xs text-muted-foreground">本镜无角色</span>
          </div>
          <select v-if="shot.id != null" v-model="shotSceneRefs[shot.id]" class="h-9 w-full rounded-md border border-input bg-background px-2 text-sm" :disabled="Boolean(episode.assetsConfirmed)" @change="dirty = true">
            <option :value="null">选择场景版本</option>
            <optgroup v-for="scene in scenes" :key="scene.id" :label="scene.name"><option v-for="version in readySceneVersions(scene)" :key="version.id" :value="version.id">{{ scene.name }} · {{ versionLabel(version) }}</option></optgroup>
          </select>
        </article>
      </div>
      <p v-if="!allShotsAssigned && !episode.assetsConfirmed" class="mt-3 text-xs text-warning">所有镜头都完成场景选择后才能确认；有角色时，每镜至少选择一个角色版本。</p>
    </section>

    <div v-if="addKind" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5" @click.self="addKind = null">
      <section class="w-full max-w-md rounded-lg border border-border bg-card p-6" role="dialog" aria-modal="true">
        <h3 class="text-base font-semibold">添加{{ addKind === 'character' ? '角色' : '场景' }}</h3>
        <label class="mt-5 block text-sm">名称<input v-model="assetName" class="mt-2 h-10 w-full rounded-md border border-input bg-background px-3" /></label>
        <label class="mt-4 block text-sm">描述<textarea v-model="assetDescription" rows="4" class="mt-2 w-full rounded-md border border-input bg-background px-3 py-2" /></label>
        <div class="mt-5 flex justify-end gap-3"><button type="button" class="h-9 rounded-md border border-border px-3 text-sm" @click="addKind = null">取消</button><button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground" :disabled="submitting || !assetName.trim()" @click="createAsset"><Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />添加</button></div>
      </section>
    </div>

    <div v-if="versionTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5" @click.self="versionTarget = null">
      <section class="w-full max-w-lg rounded-lg border border-border bg-card p-6" role="dialog" aria-modal="true">
        <div class="flex items-start justify-between gap-4"><div><h3 class="text-base font-semibold">{{ versionTarget.name }} · 新版本</h3><p class="mt-1 text-sm text-muted-foreground">{{ versionTarget.kind === 'character' ? '三视图全部填写后可直接用于分镜；留空则保存提示词草稿。' : '填写场景锚点图后可直接用于分镜；留空则保存提示词草稿。' }}</p></div><button type="button" class="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary" aria-label="关闭" @click="versionTarget = null"><X class="h-4 w-4" /></button></div>
        <label class="mt-5 block text-sm">视觉提示词<textarea v-model="versionPrompt" rows="5" class="mt-2 w-full rounded-md border border-input bg-background px-3 py-2 leading-6" placeholder="固定外貌、服装、色彩、材质和光影等可复用特征。" /></label>
        <div v-if="versionTarget.kind === 'character'" class="mt-4 grid gap-3 sm:grid-cols-3">
          <label class="block text-sm">正面图地址<input v-model="characterVersionImages.front" type="url" class="mt-2 h-10 w-full min-w-0 rounded-md border border-input bg-background px-3" placeholder="https://..." /></label>
          <label class="block text-sm">侧面图地址<input v-model="characterVersionImages.side" type="url" class="mt-2 h-10 w-full min-w-0 rounded-md border border-input bg-background px-3" placeholder="https://..." /></label>
          <label class="block text-sm">背面图地址<input v-model="characterVersionImages.back" type="url" class="mt-2 h-10 w-full min-w-0 rounded-md border border-input bg-background px-3" placeholder="https://..." /></label>
        </div>
        <label v-else class="mt-4 block text-sm">场景锚点图地址（可选）<input v-model="sceneVersionImageUrl" type="url" class="mt-2 h-10 w-full rounded-md border border-input bg-background px-3" placeholder="https://..." /></label>
        <p v-if="versionTarget.kind === 'character' && !characterVersionImagesValid" class="mt-3 text-xs leading-5 text-warning">角色可用版本必须同时提供正面、侧面和背面三张图；也可以全部留空先保存草稿。</p>
        <div class="mt-5 flex justify-end gap-3"><button type="button" class="h-9 rounded-md border border-border px-3 text-sm" @click="versionTarget = null">取消</button><button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground disabled:opacity-50" :disabled="!versionCanSubmit" @click="createVersion"><Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />{{ versionTarget.kind === 'character' ? characterVersionImageCount === 3 ? '创建可用版本' : '保存草稿' : sceneVersionImageUrl.trim() ? '创建可用版本' : '保存草稿' }}</button></div>
      </section>
    </div>

    <div v-if="gateOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5" @click.self="gateOpen = false">
      <section class="w-full max-w-md rounded-lg border border-border bg-card p-6" role="dialog" aria-modal="true">
        <h3 class="text-base font-semibold">确认角色与场景资产</h3>
        <p class="mt-2 text-sm leading-6 text-muted-foreground">确认后将进入付费镜头生成，当前资产引用不能再修改。</p>
        <div class="mt-5 flex justify-end gap-3"><button type="button" class="h-9 rounded-md border border-border px-3 text-sm" @click="gateOpen = false">返回检查</button><button type="button" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground" :disabled="submitting" @click="confirmAssets"><Loader2 v-if="submitting" class="h-4 w-4 animate-spin" /><ShieldCheck v-else class="h-4 w-4" />确认并继续</button></div>
      </section>
    </div>
  </section>
</template>
