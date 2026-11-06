const backendInternalBaseUrl = process.env.BACKEND_INTERNAL_BASE_URL || 'http://backend:8080'

const nextConfig = {
  output: 'standalone',
  poweredByHeader: false,
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${backendInternalBaseUrl}/api/:path*`
      }
    ]
  }
}

export default nextConfig
