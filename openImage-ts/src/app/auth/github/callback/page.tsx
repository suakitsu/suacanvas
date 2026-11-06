'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { useI18n } from '@/components/i18n'
import { saveSession, type AuthResponse } from '@/lib/api'

function decodeUser(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  return JSON.parse(window.atob(padded)) as AuthResponse['user']
}

export default function GithubCallbackPage() {
  const [message, setMessage] = useState('')
  const t = useI18n()

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const token = params.get('token')
    const user = params.get('user')
    const error = params.get('error')
    const next = params.get('next') || '/workspace'

    if (error) {
      setMessage(error)
      return
    }

    if (!token || !user) {
      setMessage(t('githubCallbackMissing'))
      return
    }

    try {
      saveSession({ access_token: token, user: decodeUser(user) })
      window.location.href = next.startsWith('/') && !next.startsWith('//') ? next : '/workspace'
    } catch {
      setMessage(t('githubCallbackParseFailed'))
    }
  }, [t])

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="auth-brand"><span className="brand-mark">PP</span><div><h1>{t('githubCallbackTitle')}</h1><p>{message || t('githubCallbackLoading')}</p></div></div>
        <div className="auth-footer"><Link href="/login">{t('backLogin')}</Link></div>
      </section>
    </main>
  )
}
