/** @type {import('next').NextConfig} */
const apiTarget = process.env.NEXT_PUBLIC_API_PROXY_TARGET || 'http://localhost:8080'

const nextConfig = {
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
