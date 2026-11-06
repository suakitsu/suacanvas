'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { assetUrl, clearSession, getStoredUser, githubLoginUrl, type UserProfile } from '@/lib/api'
import { useI18n } from '@/components/i18n'

export function AccountMenu() {
  const [user, setUser] = useState<UserProfile | null>(null)
  const [githubNext, setGithubNext] = useState('/')
  const t = useI18n()

  useEffect(() => {
    const sync = () => setUser(getStoredUser())
    sync()
    setGithubNext(`${window.location.pathname}${window.location.search}`)
    window.addEventListener('openimage:user-updated', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('openimage:user-updated', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  if (!user) {
    return (
      <div className="account-actions">
        <Link href="/login" className="btn">{t('login')}</Link>
        <Link href="/register" className="btn btn-primary">{t('register')}</Link>
        <a href={githubLoginUrl(githubNext)} className="btn btn-github">GitHub</a>
      </div>
    )
  }

  function logout() {
    clearSession()
    window.location.href = '/login'
  }

  return (
    <div className="account-menu">
      <Link href="/profile" className="avatar-link" title={t('profile')}>
        {user.avatar_url ? <img className="avatar" src={assetUrl(user.avatar_url)} alt={t('profile')} /> : <span className="avatar">{(user.email_display || user.email_masked).slice(0, 1).toUpperCase()}</span>}
        <span className="account-copy"><b>{user.email_display || user.email_masked}</b><small>{t('profile')}</small></span>
      </Link>
      <button className="btn" onClick={logout}>{t('logout')}</button>
    </div>
  )
}
