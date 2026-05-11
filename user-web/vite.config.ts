import { fileURLToPath, URL } from "node:url"
import { defineConfig } from "vite"
import vue from "@vitejs/plugin-vue"
import tailwindcss from "@tailwindcss/vite"

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
<<<<<<< Updated upstream
  server: { port: 5173 },
=======
  server: {
    port: 5173,
    proxy: {
      "/api": {
        /** 与浏览器常用「localhost」一致，避免 127.0.0.1 与 localhost 混用导致 Cookie 作用域异常 */
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
        /** 本地 HTTP 后端；避免误把 target 当 HTTPS 校验失败 */
        secure: false,
      },
    },
  },
>>>>>>> Stashed changes
})
