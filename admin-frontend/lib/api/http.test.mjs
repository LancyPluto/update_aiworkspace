import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

async function loadTypeScript() {
  for (const specifier of ["typescript", "../../../user-web/node_modules/typescript/lib/typescript.js"]) {
    try {
      const module = await import(specifier)
      return module.default ?? module
    } catch {
      // Try the next known workspace location.
    }
  }
  throw new Error("TypeScript is required to run this test")
}

async function importTsModule(path) {
  const ts = await loadTypeScript()
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

function installBrowserWindow() {
  const session = new Map()
  const local = new Map()
  globalThis.window = {
    location: {
      hostname: "127.0.0.1",
      port: "5174",
      pathname: "/tools",
      href: "http://127.0.0.1:5174/tools",
    },
    sessionStorage: {
      getItem: (key) => session.get(key) ?? null,
      setItem: (key, value) => session.set(key, String(value)),
      removeItem: (key) => session.delete(key),
    },
    localStorage: {
      getItem: (key) => local.get(key) ?? null,
      setItem: (key, value) => local.set(key, String(value)),
      removeItem: (key) => local.delete(key),
    },
  }
}

function uploadForm() {
  const form = new FormData()
  form.append("file", new Blob(["video"], { type: "video/mp4" }), "preview.mp4")
  return form
}

test("postForm sends multipart uploads with credentials and bearer token", async () => {
  installBrowserWindow()
  const { http, setToken } = await importTsModule("./http.ts")
  setToken("admin-token")

  let captured
  globalThis.fetch = async (url, init) => {
    captured = { url, init }
    return new Response(
      JSON.stringify({
        code: "SUCCESS",
        message: "ok",
        data: { url: "/generated/tool-covers/preview.mp4" },
        requestId: null,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    )
  }

  const result = await http.postForm("/api/admin/v1/tools/cover-upload", uploadForm())

  assert.equal(result.url, "/generated/tool-covers/preview.mp4")
  assert.equal(captured.url, "/api/admin/v1/tools/cover-upload")
  assert.equal(captured.init.credentials, "include")
  assert.equal(captured.init.headers.Authorization, "Bearer admin-token")
  assert.equal(captured.init.headers["Content-Type"], undefined)
})

test("postForm clears stale admin sessions on unauthorized uploads", async () => {
  installBrowserWindow()
  const { http, setToken, getToken } = await importTsModule("./http.ts")
  setToken("expired-token")

  globalThis.fetch = async () =>
    new Response(JSON.stringify({ code: "UNAUTHORIZED", message: "expired", data: null, requestId: null }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    })

  await assert.rejects(() => http.postForm("/api/admin/v1/tools/cover-upload", uploadForm()), {
    code: "UNAUTHORIZED",
    status: 401,
  })
  assert.equal(getToken(), null)
  const basePath = (process.env.NEXT_PUBLIC_ADMIN_BASE_PATH || "").replace(/\/$/, "")
  assert.equal(globalThis.window.location.href, `${basePath}/login`)
})

test("postForm reports fetch failures as network errors", async () => {
  installBrowserWindow()
  const { http } = await importTsModule("./http.ts")
  globalThis.fetch = async () => {
    throw new Error("connection refused")
  }

  await assert.rejects(() => http.postForm("/api/admin/v1/tools/cover-upload", uploadForm()), {
    code: "NETWORK_ERROR",
  })
})
