import path from 'node:path'
import { fileURLToPath } from 'node:url'

/** @type {import('next').NextConfig} */
const apiTarget = process.env.NEXT_PUBLIC_API_PROXY_TARGET || 'http://localhost:8080'
const adminBasePath = process.env.NEXT_PUBLIC_ADMIN_BASE_PATH || ''
const projectRoot = path.dirname(fileURLToPath(import.meta.url))
const nextConfig = {
  ...(adminBasePath ? { basePath: adminBasePath } : {}),
  experimental: {
    proxyClientMaxBodySize: '30mb',
  },
  turbopack: {
    root: projectRoot,
  },
  webpack: (config, { dev }) => { if (dev) { config.watchOptions = { poll: false }; } return config; },
  allowedDevOrigins: ['127.0.0.1', 'localhost'],
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiTarget}/api/:path*`,
        basePath: false,
      },
      {
        source: '/generated/:path*',
        destination: `${apiTarget}/generated/:path*`,
        basePath: false,
      },
    ]
  },
  async redirects() {
    if (!adminBasePath) return []
    return [
      {
        source: '/',
        destination: adminBasePath,
        permanent: false,
        basePath: false,
      },
    ]
  },
}

export default nextConfig
