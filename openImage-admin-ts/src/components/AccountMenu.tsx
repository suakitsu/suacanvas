'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { LogOut } from 'lucide-react'
import { assetUrl, clearSession, getStoredUser, type UserProfile } from '@/lib/api'
import { useI18n } from '@/components/i18n'

export function AccountMenu() {
  const [user, setUser] = useState<UserProfile | null>(null)
  const t = useI18n()

  useEffect(() => {
    const sync = () => setUser(getStoredUser())
    sync()
    window.addEventListener('openimage:user-updated', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('openimage:user-updated', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  if (!user) {
    return (
      <div className="admin-actions">
        <Link href="/login" className="btn btn-primary btn-compact">{t('login')}</Link>
      </div>
    )
  }

  function logout() {
    clearSession()
    window.location.href = '/login'
  }

  return (
    <div className="admin-actions">
      <Link href="/profile" className="admin-user">
        {user.avatar_url ? <img className="avatar" src={assetUrl(user.avatar_url)} alt={t('profile')} /> : <span className="avatar">{user.email_masked.slice(0, 1).toUpperCase()}</span>}
        <span className="admin-user-copy"><b>{user.email_masked}</b><small>{user.role}</small></span>
      </Link>
      <button className="text-button admin-logout-button" type="button" aria-label={t('logout')} title={t('logout')} onClick={logout}>
        <LogOut size={15} aria-hidden="true" />
        <span className="logout-label">{t('logout')}</span>
      </button>
    </div>
  )
}
