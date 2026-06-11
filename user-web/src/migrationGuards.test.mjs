import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { readdir, readFile } from "node:fs/promises"
import path from "node:path"
import test from "node:test"
import { fileURLToPath } from "node:url"

const srcRoot = new URL("./", import.meta.url)
const webRoot = new URL("../", srcRoot)

async function readSource(relativePath) {
  return readFile(new URL(relativePath, srcRoot), "utf8")
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}

function findRouteBlock(routerSource, routePath) {
  return routerSource.match(new RegExp(`\\{\\s*\\n\\s*path: "${escapeRegExp(routePath)}",[\\s\\S]*?\\n\\s*\\},`))?.[0] ?? ""
}

function findCssRuleBlock(css, selector) {
  return css.match(new RegExp(`${escapeRegExp(selector)}\\s*\\{[\\s\\S]*?\\n\\}`))?.[0] ?? ""
}

async function collectSourceFiles(dirUrl) {
  const entries = await readdir(dirUrl, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    if (entry.name === "dist" || entry.name === "node_modules") continue
    const entryUrl = new URL(`${entry.name}${entry.isDirectory() ? "/" : ""}`, dirUrl)
    if (entry.isDirectory()) {
      files.push(...await collectSourceFiles(entryUrl))
    } else if (/\.(ts|vue|mjs|css)$/.test(entry.name) && !entry.name.endsWith(".test.mjs")) {
      files.push(entryUrl)
    }
  }
  return files
}

test("migrated workspace source uses generic file and component naming", async () => {
  const files = await collectSourceFiles(srcRoot)
  const violations = []
  const legacyLower = "po" + "llo"
  const legacyUpper = "Po" + "llo"
  const legacyNamePattern = new RegExp(`${legacyLower}|${legacyUpper}`)
  const legacyImportPattern = new RegExp(`from\\s+["'][^"']*(?:${legacyLower}|${legacyUpper})[^"']*["']`)
  const legacySymbolPattern = new RegExp(`\\b(?:${legacyUpper}[A-Z]\\w*|${legacyLower}[A-Z]\\w*)\\b`)
  for (const fileUrl of files) {
    const relative = path.relative(srcRoot.pathname, fileUrl.pathname).replace(/\\/g, "/")
    const source = await readFile(fileUrl, "utf8")
    if (legacyNamePattern.test(relative)) violations.push(`${relative} path contains legacy naming`)
    if (legacyImportPattern.test(source)) {
      violations.push(`${relative} imports a legacy-named module path`)
    }
    if (legacySymbolPattern.test(source)) {
      violations.push(`${relative} contains a legacy-named symbol`)
    }
  }

  assert.deepEqual(violations, [])
})

test("router keeps original user-web routes or explicit compatibility redirects", async () => {
  const router = await readSource("router/index.ts")
  const publicRoutes = await readSource("app/routes/publicRoutes.ts")
  const protectedRoutes = await readSource("app/routes/protectedRoutes.ts")
  const routeSources = `${router}\n${publicRoutes}\n${protectedRoutes}`
  const requiredRouteFragments = [
    'path: "/"',
    'path: "/home"',
    'path: "/login"',
    'path: "/create"',
    'path: "/dashboard"',
    'path: "/video"',
    'path: "/image"',
    'path: "/tool"',
    'path: "/marketplace"',
    'path: "/agents"',
    'path: "/chat/:toolId"',
    'path: "/tools/:id"',
    'path: "/tools/:id/use"',
    'path: "/tasks"',
    'path: "/assets"',
    'path: "/library"',
    'path: "/profile"',
    'path: "/community"',
    'path: "/community/inspirations"',
    'path: "/u/:userId"',
    'path: "/community/posts/:postId"',
    'path: "/billing"',
    'path: "/pricing"',
    'path: "/tasks/:taskId/status"',
    'path: "/tasks/:taskId/result"',
    'path: "/tools/banana_ppt_generator/workspace"',
    'path: "/tools/banana_ppt_generator/workspace/:bindingId"',
  ]

  for (const fragment of requiredRouteFragments) {
    assert.match(routeSources, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `missing ${fragment}`)
  }
  assert.match(protectedRoutes, /path: "\/dashboard"[\s\S]*redirect:[\s\S]*"\/create"/)
  assert.match(publicRoutes, /path: "\/marketplace"[\s\S]*redirect:[\s\S]*"\/tool"/)
  assert.match(protectedRoutes, /path: "\/library"[\s\S]*redirect:[\s\S]*"\/assets"/)
  assert.match(protectedRoutes, /path: "\/pricing"[\s\S]*redirect:[\s\S]*"\/billing"/)
  const chatRoute = publicRoutes.match(/path: "\/chat\/:toolId"[\s\S]*?\n  \},/)?.[0] ?? ""
  assert.match(chatRoute, /component: \(\) => import\("@\/pages\/Chat\/Page\.vue"\)/)
  assert.doesNotMatch(chatRoute, /redirect:/)
  assert.match(publicRoutes, /path: "\/"[\s\S]*component: LoginPage/)
  assert.match(protectedRoutes, /path: "\/home"[\s\S]*component: WorkspaceHomePage/)
  assert.match(router, /routes:\s*\[\s*\.\.\.publicRoutes,\s*\.\.\.protectedRoutes,\s*\]/)
  assert.doesNotMatch(router, /component: LoginPage/)
})

test("compatibility redirects preserve original query parameters", async () => {
  const routeSources = [
    await readSource("app/routes/publicRoutes.ts"),
    await readSource("app/routes/protectedRoutes.ts"),
  ].join("\n")
  const redirects = [
    ["/dashboard", "/create"],
    ["/marketplace", "/tool"],
    ["/tools", "/tool"],
    ["/library", "/assets"],
    ["/pricing", "/billing"],
  ]

  for (const [from, target] of redirects) {
    const route = findRouteBlock(routeSources, from)
    assert.match(
      route,
      new RegExp(`redirect:\\s*\\(to\\)\\s*=>\\s*\\(\\{\\s*path:\\s*"${escapeRegExp(target)}",\\s*query:\\s*to\\.query\\s*\\}\\)`),
      `${from} should preserve query parameters when redirecting to ${target}`,
    )
  }
})

test("migrated business entry points do not send users to the legacy dashboard route", async () => {
  const files = [
    "utils/assetReplay.ts",
    "pages/MaterialLibrary/Page.vue",
    "pages/CommunityPost/Page.vue",
    "pages/InspirationCollections/Page.vue",
    "pages/AgentHome/AgentChatPane.vue",
    "pages/ToolCenter/Page.vue",
  ]

  for (const file of files) {
    const source = await readSource(file)
    assert.doesNotMatch(source, /\/dashboard/, `${file} should route new user actions through /create`)
  }
})

test("internal user navigation does not target legacy marketplace compatibility alias", async () => {
  const files = await collectSourceFiles(srcRoot)
  const allowed = new Set([
    "app/routes/publicRoutes.ts",
    "data/creativeHub.ts",
  ])
  const violations = []
  for (const fileUrl of files) {
    const relative = path.relative(srcRoot.pathname, fileUrl.pathname).replace(/\\/g, "/")
    if (allowed.has(relative)) continue
    const source = await readFile(fileUrl, "utf8")
    if (/["'`]\/marketplace/.test(source)) {
      violations.push(relative)
    }
  }

  assert.deepEqual(violations, [])
})

test("workspace creative hub data is tracked for clean CI checkouts", () => {
  const trackedFile = execFileSync("git", ["ls-files", "--", "src/data/creativeHub.ts"], {
    cwd: fileURLToPath(webRoot),
    encoding: "utf8",
  }).trim()

  assert.equal(trackedFile, "src/data/creativeHub.ts")
})

test("create page consumes migrated replay route context directly", async () => {
  const assetReplay = await readSource("utils/assetReplay.ts")
  const createPage = await readSource("pages/CreatorWorkspace/Page.vue")
  const composer = await readSource("components/workspace/WorkspaceComposer.vue")

  assert.match(assetReplay, /return `\/create\?\$\{query\.toString\(\)\}`/)
  assert.doesNotMatch(assetReplay, /Dashboard|dashboard/)
  assert.match(createPage, /consumeCreatePendingAsset/)
  assert.match(createPage, /routeStringParam\("modality"\)/)
  assert.match(createPage, /sourcePostId:\s*sourcePostIdFromRoute\(\)/)
  assert.match(composer, /initialUploadedAssetUrl/)
  assert.match(composer, /initialPrompt/)
})

test("create page renders a chronological generation feed with a bottom composer", async () => {
  const createPage = await readSource("pages/CreatorWorkspace/Page.vue")
  const createTimeline = await readSource("utils/createTimeline.ts")
  const composer = await readSource("components/workspace/WorkspaceComposer.vue")

  assert.match(createPage, /fetchTasks/)
  assert.match(createPage, /buildCreateTimelineItems/)
  assert.match(createPage, /ResultRenderer/)
  assert.match(createPage, /workspace-create-feed/)
  assert.match(createPage, /workspace-create-composer-dock/)
  assert.match(createPage, /CREATE_TOOL_MODES/)
  assert.match(createPage, /refreshTimeline/)
  assert.match(createPage, /:tools="tools"/)
  assert.match(createPage, /upsertTask/)
  assert.match(createPage, /startTimelinePolling/)
  assert.match(createPage, /composerCondensed/)
  assert.match(createPage, /expandComposerFromClick/)
  assert.match(createPage, /@click\.capture="expandComposerFromClick"/)
  assert.match(createPage, /deleteTask/)
  assert.match(createPage, /Trash2/)
  assert.match(createPage, /scrollTimelineToBottom/)
  assert.match(createPage, /setInterval\(\(\)\s*=>\s*void refreshTimeline\(\{ showLoading: false \}\)/)
  assert.doesNotMatch(createPage, /router\.push\(\{\s*name:\s*"TaskStatus"/)
  assert.doesNotMatch(createPage, /workspace-create-tool-select/)
  assert.doesNotMatch(createPage, /workspace-create-advanced/)
  assert.match(createTimeline, /brandName:\s*"科创点AI"/)
  assert.match(createTimeline, /startedAtLabel/)
  assert.match(composer, /modeOptions/)
  assert.match(composer, /mode\.value === "audio"[\s\S]*audio\/\*/)
})

test("create feed renders generated image and video previews at compact half width", async () => {
  const css = await readSource("styles/workspace.css")
  const createResultRule = css.match(/\.workspace-create-result\s*\{[\s\S]*?\n\}/)?.[0] ?? ""

  assert.match(createResultRule, /width:\s*min\(280px,\s*100%\);/)
})

test("migrated user shell uses 科创点AI brand and browser title", async () => {
  const html = await readFile(new URL("../index.html", srcRoot), "utf8")
  const shell = await readSource("components/workspace/WorkspaceShell.vue")

  assert.match(html, /<title>科创点AI<\/title>/)
  assert.match(shell, />科创点AI</)
  assert.doesNotMatch(shell, /Studio\.ai|Studio<\/span>/)
  assert.doesNotMatch(html, /智效 AI 工作台|Studio\.ai/)
})

test("my tasks overview is removed from user-facing navigation and routes", async () => {
  const shell = await readSource("components/workspace/WorkspaceShell.vue")
  const nav = await readSource("data/creativeHub.ts")
  const protectedRoutes = await readSource("app/routes/protectedRoutes.ts")
  const userRoutes = await readSource("router/userRoutes.ts")
  const toolUse = await readSource("pages/ToolUse/Page.vue")
  const taskStatus = await readSource("pages/TaskStatus/Page.vue")
  const taskResult = await readSource("pages/TaskResult/Page.vue")

  assert.doesNotMatch(nav, /label:\s*"我的任务"/)
  assert.doesNotMatch(nav, /to:\s*"\/tasks"/)
  assert.doesNotMatch(shell, /to="\/tasks"/)
  assert.doesNotMatch(userRoutes, /get myTasks/)
  assert.match(protectedRoutes, /path:\s*"\/tasks"[\s\S]*redirect:[\s\S]*path:\s*"\/create"/)
  assert.doesNotMatch(protectedRoutes, /name:\s*"MyTasks"|component:\s*MyTasksPage/)
  assert.doesNotMatch(toolUse, /userRoutes\.myTasks|>我的任务</)
  assert.doesNotMatch(taskStatus, /userRoutes\.myTasks|我的任务/)
  assert.doesNotMatch(taskResult, /userRoutes\.myTasks|返回我的任务|我的任务/)
})

test("workspace sidebar and content animate together when collapsed", async () => {
  const shell = await readSource("components/workspace/WorkspaceShell.vue")
  const css = await readSource("styles/workspace.css")

  assert.match(shell, /:inert="!sidebarOpen && !mobileNavOpen"/)
  assert.match(shell, /workspace-sidebar-inner/)
  assert.match(css, /@property --workspace-sidebar-width/)
  assert.match(css, /\.workspace-workspace\s*\{[\s\S]*transition:[\s\S]*--workspace-sidebar-width/)
  assert.match(css, /\.workspace-sidebar\s*\{[\s\S]*width:\s*var\(--workspace-sidebar-width\)/)
  assert.match(css, /\.workspace-sidebar\.desktop-collapsed\s*\{[\s\S]*pointer-events:\s*none/)
  assert.match(css, /\.workspace-sidebar\.desktop-collapsed \.workspace-sidebar-inner\s*\{[\s\S]*transform:\s*translateX\(-20px\)/)
  assert.match(css, /\.workspace-workspace-content\s*\{[\s\S]*padding-left:\s*calc\(var\(--workspace-sidebar-width\) \+ 28px\)/)
  assert.doesNotMatch(findCssRuleBlock(css, ".workspace-sidebar.desktop-collapsed"), /display:\s*none/)
})

test("cleanup removes old visual-only dashboard and tool list pages from production paths", async () => {
  const files = await collectSourceFiles(srcRoot)
  const filePaths = files.map((fileUrl) => fileUrl.pathname.replace(/\\/g, "/"))
  assert.equal(filePaths.some((filePath) => filePath.endsWith("/pages/Dashboard/Page.vue")), false)
  assert.equal(filePaths.some((filePath) => filePath.endsWith("/pages/ToolList/Page.vue")), false)

  const routeSources = [
    await readSource("app/routes/publicRoutes.ts"),
    await readSource("app/routes/protectedRoutes.ts"),
  ].join("\n")
  assert.doesNotMatch(routeSources, /pages\/Dashboard|pages\/ToolList|DashboardPage|ToolListPage/)

  for (const script of [
    "../../deploy/scripts/sync_tool_cover_media.py",
    "../../deploy/scripts/sync_user_web_uuid_fix.py",
  ]) {
    const source = await readFile(new URL(script, srcRoot), "utf8")
    assert.doesNotMatch(source, /pages\/Dashboard|pages\/ToolList/, `${script} should not reference removed visual pages`)
  }
})

test("migration does not reintroduce old qianduan mock APIs into production source", async () => {
  const files = await collectSourceFiles(srcRoot)
  const banned = ["studioApi", "/api/tasks", "@/data/workspace", "qianduan/src/data"]
  const violations = []
  for (const fileUrl of files) {
    const content = await readFile(fileUrl, "utf8")
    for (const term of banned) {
      if (content.includes(term)) {
        violations.push(`${path.basename(fileUrl.pathname)} contains ${term}`)
      }
    }
  }

  assert.deepEqual(violations, [])
})

test("production source does not contain replacement or private-use mojibake characters", async () => {
  const files = await collectSourceFiles(srcRoot)
  const violations = []
  for (const fileUrl of files) {
    const content = await readFile(fileUrl, "utf8")
    for (const char of content) {
      const code = char.codePointAt(0)
      if (code === 0xfffd || (code >= 0xe000 && code <= 0xf8ff)) {
        const relative = path.relative(srcRoot.pathname, fileUrl.pathname).replace(/\\/g, "/")
        violations.push(`${relative} contains U+${code.toString(16).toUpperCase().padStart(4, "0")}`)
        break
      }
    }
  }

  assert.deepEqual(violations, [])
})

test("production API barrel does not expose unconditional mock marketplace tools", async () => {
  const aiToolApi = await readSource("api/aiToolApi.ts")
  const apiIndex = await readSource("api/index.ts")

  assert.doesNotMatch(aiToolApi, /export async function fetchMarketplaceAITools/)
  assert.doesNotMatch(aiToolApi, /return mockFetchEnabledAITools\(\)/)
  assert.doesNotMatch(apiIndex, /fetchMarketplaceAITools/)
})

test("unauthorized API responses redirect to the root login page before entering the qianduan app", async () => {
  const client = await readSource("api/client.ts")
  assert.match(client, /const loginPath = normalizedBase \|\| "\/"/)
  assert.doesNotMatch(client, /const loginPath = `\$\{normalizedBase\}\/login`/)
})

test("login page routes successful login to the qianduan home by default", async () => {
  const loginPage = await readSource("pages/Login/Page.vue")
  const router = await readSource("router/index.ts")
  const app = await readSource("App.vue")
  const createPage = await readSource("pages/CreatorWorkspace/Page.vue")

  assert.match(loginPage, /return '\/home'/)
  assert.match(router, /to\.name === "Login" \|\| to\.name === "RootLogin"/)
  assert.match(router, /next\(resolvePostLoginRedirect\(to\.query\.redirect\)\)/)
  assert.match(app, /name: "RootLogin"/)
  assert.match(createPage, /name: "RootLogin"/)
})

test("workspace shell navigation exposes preserved business routes without dead qianduan links", async () => {
  const nav = await readSource("data/creativeHub.ts")
  for (const route of [
    'to: "/community"',
    'to: "/community/inspirations"',
    'to: "/tools/banana_ppt_generator/workspace"',
    'to: "/billing"',
    'to: "/agent"',
  ]) {
    assert.match(nav, new RegExp(route.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `missing nav ${route}`)
  }
  for (const deadRoute of [
    'to: "/avatar"',
    'to: "/marketing-studio"',
    'to: "/video-effects"',
    'to: "/download"',
    'to: "/api-platform/explore"',
  ]) {
    assert.doesNotMatch(nav, new RegExp(deadRoute.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `dead nav ${deadRoute}`)
  }
})

test("PPT editable export enables partial output before submitting the export task", async () => {
  const exportStep = await readSource("pages/PptWorkspace/steps/ExportStep.vue")
  assert.match(exportStep, /updatePptProject/)
  assert.match(exportStep, /export_allow_partial:\s*true/)
  assert.match(
    exportStep,
    /await updatePptProject\([\s\S]*?export_allow_partial:\s*true[\s\S]*?await exportEditablePptx\(/,
  )
})

test("workspace public pages use backend tool APIs for primary business content", async () => {
  const home = await readSource("pages/WorkspaceHome/Page.vue")
  const tools = await readSource("pages/ToolCenter/Page.vue")

  assert.match(home, /fetchTools/)
  assert.match(home, /toWorkspaceToolCards/)
  assert.doesNotMatch(home, /<WorkspaceUiKit v-else kind="toolGrid"/)
  assert.match(tools, /fetchToolCategories/)
  assert.match(tools, /searchTools/)
  assert.match(tools, /pageNo/)
})

test("composer format dropdowns are not clipped by their chip groups", async () => {
  const css = await readSource("styles/workspace.css")
  const formatGroupRule = css.match(/\.workspace-format-chip-group,\s*\n\.workspace-image-format-chip-group\s*\{[\s\S]*?\n\}/)?.[0] ?? ""

  assert.match(formatGroupRule, /overflow:\s*visible;/)
  assert.doesNotMatch(formatGroupRule, /overflow:\s*hidden;/)
})

test("my tasks preserves cancel, delete, and regenerate actions", async () => {
  const myTasks = await readSource("pages/MyTasks/Page.vue")

  assert.match(myTasks, /cancelTask/)
  assert.match(myTasks, /deleteTask/)
  assert.match(myTasks, /regenerateTask/)
  assert.match(myTasks, /handleCancel/)
  assert.match(myTasks, /handleDelete/)
  assert.match(myTasks, /handleRegenerate/)
  assert.match(myTasks, /id: "CANCELLED"/)
  assert.match(myTasks, /statusCounts\.value\.cancelled/)
  assert.doesNotMatch(myTasks, /failed:\s*failed \+ timeout \+ cancelled/)
})

test("asset library recognizes generated image and video task results", async () => {
  const taskResultBlocks = await readSource("utils/taskResultBlocks.ts")
  const taskResultBlocksTest = await readSource("utils/taskResultBlocks.test.mjs")
  const assetPreviewAdapter = await readSource("utils/assetPreviewAdapter.ts")
  const materialLibrary = await readSource("pages/MaterialLibrary/Page.vue")

  assert.match(taskResultBlocks, /Array\.isArray\(root\.images\).*return "IMAGE"/s)
  assert.match(taskResultBlocks, /Array\.isArray\(root\.videos\).*return "VIDEO"/s)
  assert.match(taskResultBlocks, /generated\\\/\.\*\\\.mp4/)
  assert.match(taskResultBlocksTest, /reference upload URLs/)
  assert.match(taskResultBlocksTest, /generated output image URLs infer image modality/)
  assert.match(assetPreviewAdapter, /if \(block\.type === "image"\)/)
  assert.match(assetPreviewAdapter, /if \(block\.type === "video"\)/)
  assert.match(materialLibrary, /buildTaskResultBlocks\(task\.result\?\.contentText/)
  assert.match(materialLibrary, /assetFromTask\(item\.task/)
})

test("asset library keeps loading successful task pages beyond the first page", async () => {
  const materialLibrary = await readSource("pages/MaterialLibrary/Page.vue")

  assert.match(materialLibrary, /const currentPage = ref\(1\)/)
  assert.match(materialLibrary, /const hasNextPage = ref\(false\)/)
  assert.match(materialLibrary, /async function loadMoreMaterials/)
  assert.match(materialLibrary, /tasks\.value = reset \? response\.list : \[\.\.\.tasks\.value, \.\.\.response\.list\]/)
  assert.match(materialLibrary, /hasNextPage\.value = response\.hasNext/)
  assert.match(materialLibrary, /!loading && \(hasNextPage \|\| loadingMore\)/)
  assert.doesNotMatch(materialLibrary, /pageNo:\s*1,\s*pageSize:\s*80/)
})

test("local vite proxy does not forward temporary browser origins to backend CORS", async () => {
  const viteConfig = await readFile(new URL("../vite.config.ts", srcRoot), "utf8")

  assert.match(viteConfig, /function stripBrowserOrigin/)
  assert.match(viteConfig, /proxyReq\.removeHeader\("origin"\)/)
  assert.match(viteConfig, /proxy\.on\("proxyReq", stripBrowserOrigin\)/)
})

test("nginx compose overlay uses the shared registry override", async () => {
  const nginxCompose = await readFile(new URL("../../deploy/docker-compose.nginx.yml", srcRoot), "utf8")

  assert.match(nginxCompose, /image:\s*\$\{DOCKER_REGISTRY:-docker\.1ms\.run\/\}nginx:1\.27-alpine/)
})

test("route map documents the migrated deployment and cutover contract", async () => {
  const routeMap = await readFile(new URL("../../docs/qianduan-user-web-route-map.md", srcRoot), "utf8")

  for (const sourceAnchor of [
    "user-web/src/router/index.ts",
    "user-web/src/app/routes/publicRoutes.ts",
    "user-web/src/app/routes/protectedRoutes.ts",
    "user-web/vite.config.ts",
    "deploy/nginx/default.conf",
    "scripts/migration_cutover_smoke.py",
  ]) {
    assert.match(routeMap, new RegExp(sourceAnchor.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `missing ${sourceAnchor}`)
  }

  for (const route of ["/dashboard", "/marketplace", "/tools", "/library", "/pricing"]) {
    assert.match(routeMap, new RegExp(`\\| \`${route.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\` \\|`), `missing redirect row for ${route}`)
  }

  assert.match(routeMap, /\/\?redirect=<original-full-path>/)
  assert.match(routeMap, /\/api\/internal\/\*/)
  assert.match(routeMap, /real production media providers/)
  assert.match(routeMap, /banana-slides provider settings/)
  assert.match(routeMap, /--require-all-gates/)
})

test("migration handoff docs do not contain known mojibake tokens", async (t) => {
  // docs/superpowers/plans/ 已被 .gitignore 排除，新克隆的仓库不存在该文档，跳过而非失败。
  const planUrl = new URL("../../docs/superpowers/plans/2026-05-31-qianduan-user-web-migration.md", srcRoot)
  let plan
  try {
    plan = await readFile(planUrl, "utf8")
  } catch (error) {
    if (error?.code === "ENOENT") {
      t.skip("migration plan doc not present in this checkout (path is gitignored)")
      return
    }
    throw error
  }
  const bannedTokens = [
    "閱嬮吀閽燶",
    "浠诲姟宸插彇娑",
    "宸插彇娑",
  ]

  for (const token of bannedTokens) {
    assert.doesNotMatch(plan, new RegExp(escapeRegExp(token)), `plan contains mojibake token ${token}`)
  }
})

test("task status production copy does not contain known mojibake tokens", async () => {
  const sources = {
    "api/taskApi.ts": await readSource("api/taskApi.ts"),
    "pages/TaskStatus/Page.vue": await readSource("pages/TaskStatus/Page.vue"),
  }
  const bannedTokens = [
    "浠诲姟",
    "杩涘害",
    "鐘舵",
    "鑾峰彇",
    "鍔犺浇",
    "鑴氭湰",
  ]

  for (const [file, source] of Object.entries(sources)) {
    for (const token of bannedTokens) {
      assert.doesNotMatch(source, new RegExp(escapeRegExp(token)), `${file} contains mojibake token ${token}`)
    }
  }
})

test("migrated tool cards do not expose broken placeholder images", async () => {
  const adapter = await readSource("adapters/toolPresentationAdapter.ts")
  const card = await readSource("components/workspace/WorkspaceToolCard.vue")

  assert.doesNotMatch(adapter, /\/placeholder\.jpg/)
  assert.match(adapter, /DEFAULT_TOOL_COVER_URL/)
  assert.match(card, /<video/)
  assert.match(card, /v-if="tool\.mediaType === 'video'"/)
  assert.match(card, /@error="handleCoverError"/)
})
