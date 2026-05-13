/* One-off generator: writes white-background vendor SVGs into public/assets/vendor-icons
 * Brand paths from simple-icons (CC0-1.0). Run: node scripts/generate-vendor-icons.cjs
 */
const fs = require("fs")
const path = require("path")
const {
  siAnthropic,
  siClaude,
  siDeepseek,
  siGooglegemini,
  siMinimax,
  siMoonshotai,
  siQwen,
  siBaidu,
  siBytedance,
  siOpenrouter,
  siAlibabacloud,
} = require("simple-icons")

const outDir = path.join(__dirname, "..", "public", "assets", "vendor-icons")
fs.mkdirSync(outDir, { recursive: true })

function wrapFile(fileBase, label, hex, pathD) {
  const fill = "#" + hex
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="${label}">
  <rect width="64" height="64" rx="14" fill="#ffffff"/>
  <g transform="translate(8,8) scale(2)"><path fill="${fill}" d="${pathD}"/></g>
</svg>`
  fs.writeFileSync(path.join(outDir, `${fileBase}.svg`), svg, "utf8")
}

function fromSimpleIcon(fileBase, icon) {
  wrapFile(fileBase, icon.title, icon.hex, icon.path)
}

fromSimpleIcon("anthropic", siAnthropic)
fromSimpleIcon("claude", siClaude)
fromSimpleIcon("gemini", siGooglegemini)
fromSimpleIcon("deepseek", siDeepseek)
fromSimpleIcon("moonshot", siMoonshotai)
fromSimpleIcon("minimax", siMinimax)
fromSimpleIcon("qwen", siQwen)
fromSimpleIcon("baidu", siBaidu)
fromSimpleIcon("doubao", siBytedance)
fromSimpleIcon("openrouter", siOpenrouter)
fromSimpleIcon("alibabacloud", siAlibabacloud)

const openai = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="OpenAI compatible">
  <rect width="64" height="64" rx="14" fill="#ffffff"/>
  <g transform="translate(32,32)" stroke="#10a37f" stroke-width="3" fill="none" stroke-linecap="round">
    <circle r="18" />
    <path d="M0,-18V-7M15.6,-9.4l-8.7,5M15.6,9.4l-8.7,-5M0,18V7M-15.6,9.4l8.7,-5M-15.6,-9.4l8.7,5" />
  </g>
</svg>`
fs.writeFileSync(path.join(outDir, "openai.svg"), openai, "utf8")

const zhipu = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="智谱 GLM">
  <rect width="64" height="64" rx="14" fill="#ffffff"/>
  <text x="32" y="40" text-anchor="middle" font-family="system-ui,Segoe UI,sans-serif" font-size="18" font-weight="700" fill="#3859FF">GLM</text>
</svg>`
fs.writeFileSync(path.join(outDir, "zhipu.svg"), zhipu, "utf8")

const siliconflow = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="SiliconFlow">
  <rect width="64" height="64" rx="14" fill="#ffffff"/>
  <path fill="#4F8BF7" d="M14 38c6-14 18-22 32-20-4 8-4 18 2 26-10 2-22-1-34-6z"/>
  <path fill="#7EB6FF" opacity="0.9" d="M22 28c8-6 20-8 28-2-6 10-16 16-28 2z"/>
</svg>`
fs.writeFileSync(path.join(outDir, "siliconflow.svg"), siliconflow, "utf8")

const api = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64" role="img" aria-label="API">
  <rect width="64" height="64" rx="14" fill="#ffffff"/>
  <g stroke="#64748b" stroke-width="2" fill="none">
    <rect x="16" y="16" width="32" height="32" rx="4"/>
    <path d="M24 24h16M24 32h16M24 40h10"/>
  </g>
</svg>`
fs.writeFileSync(path.join(outDir, "api.svg"), api, "utf8")

console.log("vendor-icons:", fs.readdirSync(outDir).length, "files in", outDir)
