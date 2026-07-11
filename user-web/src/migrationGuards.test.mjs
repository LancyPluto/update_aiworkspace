import assert from "node:assert/strict"
import { access, readdir, readFile } from "node:fs/promises"
import path from "node:path"
import test from "node:test"

const srcRoot = new URL("./", import.meta.url)

async function readSource(relativePath) {
  return readFile(new URL(relativePath, srcRoot), "utf8")
}

async function collectSourceFiles(dirUrl) {
  const entries = await readdir(dirUrl, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    if (entry.name === "dist" || entry.name === "node_modules") continue
    const entryUrl = new URL(`${entry.name}${entry.isDirectory() ? "/" : ""}`, dirUrl)
    if (entry.isDirectory()) files.push(...await collectSourceFiles(entryUrl))
    else if (/\.(ts|vue|mjs|css)$/.test(entry.name) && !entry.name.endsWith(".test.mjs")) files.push(entryUrl)
  }
  return files
}

test("runtime router exposes the current production frontend contract", async () => {
  const router = await readSource("router/index.ts")
  for (const route of [
    'path: "home"',
    'path: "dashboard"',
    'path: "marketplace"',
    'path: "tools/:id"',
    'path: "tools/:id/use"',
    'path: "library"',
    'path: "agent"',
    'path: "community"',
    'path: "billing"',
    'path: "profile"',
  ]) {
    assert.match(router, new RegExp(route.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `missing ${route}`)
  }

  for (const retiredRoute of ["create", "tool", "video", "image", "assets", "banana_ppt_generator/workspace"]) {
    assert.doesNotMatch(router, new RegExp(`path:\\s*["']/?${retiredRoute}["']`), `retired route remains: ${retiredRoute}`)
  }
})

test("public legal and contact routes are stable and do not require login", async () => {
  const router = await readSource("router/index.ts")
  for (const route of ["/legal/privacy", "/legal/terms", "/legal/aigc-labeling", "/legal/refund", "/contact"]) {
    const escaped = route.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
    assert.match(router, new RegExp(`path: ["']${escaped}["'][^\n]*requiresAuth: false`), `missing public route ${route}`)
  }
})

test("brand logo is a real local image shared by landing and app shell", async () => {
  const logo = await readFile(new URL("../asset/logo.png", srcRoot))
  const brand = await readSource("config/brand.ts")
  const navigation = await readSource("components/landing/Navigation.vue")
  const shell = await readSource("components/AppShell.vue")

  assert.deepEqual([...logo.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10])
  assert.match(brand, /BASE_URL}logo\.png/)
  assert.match(navigation, /BRAND_LOGO_URL/)
  assert.match(shell, /BRAND_LOGO_URL/)
  assert.doesNotMatch(shell, /cdn\.wlcloudai\.com\/static\/logo\.png/)
})

test("visible agreement and footer service links are not placeholders", async () => {
  const login = await readSource("pages/Login/Page.vue")
  const footer = await readSource("components/landing/FooterSection.vue")

  assert.match(login, /to="\/legal\/privacy"/)
  assert.match(login, /to="\/legal\/terms"/)
  for (const route of ["/legal/privacy", "/legal/terms", "/legal/aigc-labeling", "/legal/refund", "/contact"]) {
    assert.match(footer, new RegExp(route.replaceAll("/", "\\/")), `footer missing ${route}`)
  }
  assert.doesNotMatch(footer, /href:\s*["']#["']/)
})

test("markdown rendering registers supported highlight languages without bundling every language", async () => {
  const markdown = await readSource("utils/markdownRender.ts")
  assert.match(markdown, /highlight\.js\/lib\/core/)
  assert.match(markdown, /registerLanguage\("javascript"/)
  assert.match(markdown, /registerLanguage\("python"/)
  assert.doesNotMatch(markdown, /from ["']highlight\.js["']/)
})

test("dashboard remains the tool-aware generation workspace", async () => {
  const router = await readSource("router/index.ts")
  const dashboard = await readSource("pages/Dashboard/Page.vue")
  const replay = await readSource("utils/assetReplay.ts")
  const adapter = await readSource("adapters/toolPresentationAdapter.ts")

  assert.match(router, /path: "dashboard"[\s\S]*component: DashboardPage/)
  assert.match(dashboard, /route\.query\.tool|query\.tool/)
  assert.match(replay, /return `\/dashboard\?\$\{query\.toString\(\)\}`/)
  assert.match(adapter, /return `\/dashboard\?tool=\$\{encoded\}`/)
})

test("legacy chat links preserve tool identity when entering dashboard", async () => {
  const router = await readSource("router/index.ts")
  assert.match(router, /path: "\/chat\/:toolId"[\s\S]*path: "\/dashboard"[\s\S]*tool: String\(to\.params\.toolId/)
})

test("login redirects use the current home and preserve valid internal redirects", async () => {
  const login = await readSource("pages/Login/Page.vue")
  const router = await readSource("router/index.ts")
  assert.match(login, /return ['"]\/home['"]/)
  assert.match(router, /return "\/home"/)
  assert.match(router, /next\(resolvePostLoginRedirect\(to\.query\.redirect\)\)/)
})

test("route helpers only expose routes present in the runtime router", async () => {
  const helpers = await readSource("router/userRoutes.ts")
  const router = await readSource("router/index.ts")
  const names = [...helpers.matchAll(/name: "([^"]+)"/g)].map((match) => match[1])
  for (const name of names) assert.match(router, new RegExp(`name:\\s*"${name}"`), `missing route name ${name}`)
})

test("retired copied frontend modules are absent", async () => {
  const retired = [
    "app/routes/publicRoutes.ts",
    "app/routes/protectedRoutes.ts",
    "data/creativeHub.ts",
    "styles/workspace.css",
    "pages/CreatorWorkspace/Page.vue",
    "pages/WorkspaceHome/Page.vue",
    "pages/VideoTools/Page.vue",
    "pages/ImageTools/Page.vue",
    "pages/ToolCenter/Page.vue",
    "components/workspace/WorkspaceShell.vue",
  ]
  for (const relativePath of retired) {
    await assert.rejects(access(new URL(relativePath, srcRoot)), { code: "ENOENT" }, `${relativePath} should be removed`)
  }
})

test("production navigation does not target retired frontend paths", async () => {
  const files = await collectSourceFiles(srcRoot)
  const violations = []
  const retiredTarget = /(?:to=|to:|path:|router\.(?:push|replace)\()\s*[({]?\s*["'`](\/create|\/tool(?:[?"'`]|$)|\/video(?:[?"'`]|$)|\/image(?:[?"'`]|$)|\/assets(?:[?"'`]|$))/
  for (const fileUrl of files) {
    const source = await readFile(fileUrl, "utf8")
    if (retiredTarget.test(source)) violations.push(path.relative(srcRoot.pathname, fileUrl.pathname).replace(/\\/g, "/"))
  }
  assert.deepEqual(violations, [])
})

test("community actions use Cookie-backed login state instead of requiring a bearer token", async () => {
  for (const file of ["components/community/CommunityGallery.vue", "pages/CommunityPost/Page.vue"]) {
    const source = await readSource(file)
    assert.match(source, /auth\.isLoggedIn/)
    assert.doesNotMatch(source, /if \(!auth\.token\)/)
    assert.doesNotMatch(source, /if \([^\n]*!sessionToken[^\n]*\) return router\.push/)
  }
})

test("asset replay and community same-style creation share the dashboard flow", async () => {
  const replay = await readSource("utils/assetReplay.ts")
  const gallery = await readSource("components/community/CommunityGallery.vue")
  const detail = await readSource("pages/CommunityPost/Page.vue")
  assert.match(replay, /openDashboardWithAsset/)
  assert.match(gallery, /openDashboardWithAsset/)
  assert.match(detail, /openCreateWithAssetRecommendation/)
})

test("my tasks preserves cancel delete and regenerate actions", async () => {
  const source = await readSource("pages/MyTasks/Page.vue")
  for (const symbol of ["cancelTask", "deleteTask", "regenerateTask", "handleCancel", "handleDelete", "handleRegenerate"]) {
    assert.match(source, new RegExp(symbol), `missing ${symbol}`)
  }
})

test("asset library keeps generated media and pagination behavior", async () => {
  const source = await readSource("pages/MaterialLibrary/Page.vue")
  assert.match(source, /buildTaskResultBlocks/)
  assert.match(source, /assetFromTask/)
  assert.match(source, /loadMoreMaterials/)
  assert.match(source, /hasNextPage/)
})

test("production source does not contain replacement or private-use characters", async () => {
  const files = await collectSourceFiles(srcRoot)
  const violations = []
  for (const fileUrl of files) {
    const source = await readFile(fileUrl, "utf8")
    for (const char of source) {
      const code = char.codePointAt(0)
      if (code === 0xfffd || (code >= 0xe000 && code <= 0xf8ff)) {
        violations.push(path.relative(srcRoot.pathname, fileUrl.pathname).replace(/\\/g, "/"))
        break
      }
    }
  }
  assert.deepEqual(violations, [])
})

test("production API does not enable unconditional mock marketplace tools", async () => {
  const api = await readSource("api/aiToolApi.ts")
  const barrel = await readSource("api/index.ts")
  const mock = await readSource("api/aiToolMock.ts")
  assert.doesNotMatch(api, /return mockFetchEnabledAITools\(\)/)
  assert.doesNotMatch(barrel, /fetchMarketplaceAITools/)
  assert.match(mock, /VITE_AI_TOOL_MOCK === "1" && !import\.meta\.env\.PROD/)
})

test("unauthorized responses redirect to the root login page", async () => {
  const client = await readSource("api/client.ts")
  assert.match(client, /const loginPath = normalizedBase \|\| "\/"/)
})
