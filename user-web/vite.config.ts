import { fileURLToPath, URL } from "node:url"
import { defineConfig } from "vite"
import vue from "@vitejs/plugin-vue"
import tailwindcss from "@tailwindcss/vite"

function stripBrowserOrigin(proxyReq: import("node:http").ClientRequest) {
  proxyReq.removeHeader("origin")
}

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  publicDir: "asset",
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    allowedHosts: ["wlcloudai.com", "www.wlcloudai.com", "8.134.93.203", "localhost", "127.0.0.1"],
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("proxyReq", stripBrowserOrigin)
        },
      },
      "/generated": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("proxyReq", stripBrowserOrigin)
        },
      },
      "/tool-covers": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("proxyReq", stripBrowserOrigin)
        },
      },
    },
  },
  // Production uses `vite preview` behind nginx; without this, Host: wlcloudai.com returns 403.
  preview: {
    host: "0.0.0.0",
    port: 5173,
    allowedHosts: ["wlcloudai.com", "www.wlcloudai.com", "8.134.93.203", "localhost", "127.0.0.1"],
    proxy: {
      "/api": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://backend:8080",
        changeOrigin: true,
      },
      "/generated": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://backend:8080",
        changeOrigin: true,
      },
      "/tool-covers": {
        target: process.env.VITE_DEV_PROXY_TARGET ?? "http://backend:8080",
        changeOrigin: true,
      },
    },
  },
})
