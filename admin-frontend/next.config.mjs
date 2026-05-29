import path from 'node:path'

/** @type {import('next').NextConfig} */
const apiTarget = process.env.NEXT_PUBLIC_API_PROXY_TARGET || 'http://localhost:8080'

const nextConfig = {
  turbopack: {
    root: path.resolve(process.cwd()),
  },
  webpack: (config, { dev }) => { if (dev) { config.watchOptions = { poll: false }; } return config; },
  allowedDevOrigins: ['127.0.0.1', 'localhost'],
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiTarget}/api/:path*`,
      },
    ]
  },
}

export default nextConfig
