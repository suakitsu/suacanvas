import { NextRequest, NextResponse } from 'next/server'

export function GET(request: NextRequest) {
  const code = request.nextUrl.searchParams.get('code')
  const error = request.nextUrl.searchParams.get('error')
  const state = request.nextUrl.searchParams.get('state')
  const apiBase = process.env.BACKEND_INTERNAL_BASE_URL || 'http://backend:8080'

  if (error) {
    return NextResponse.redirect(new URL(`/login?error=${encodeURIComponent(error)}`, request.url))
  }

  if (!code) {
    return NextResponse.redirect(new URL('/login?error=GitHub%20code%20missing', request.url))
  }

  const stateParam = state ? `&state=${encodeURIComponent(state)}` : ''
  return NextResponse.redirect(`${apiBase}/api/auth/github/callback?code=${encodeURIComponent(code)}${stateParam}`)
}
