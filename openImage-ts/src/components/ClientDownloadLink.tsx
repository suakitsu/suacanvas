'use client'

import Link from 'next/link'
import { Download } from 'lucide-react'
import { useLanguage } from '@/components/i18n'

type ClientDownloadLinkProps = {
  compact?: boolean
  className?: string
}

const labels: Record<string, string> = {
  'zh-CN': '下载客户端',
  'en-US': 'Download apps',
  'ja-JP': 'アプリをダウンロード'
}

export function ClientDownloadLink({ compact = false, className = '' }: ClientDownloadLinkProps) {
  const language = useLanguage()
  const label = labels[language] || labels['zh-CN']

  return (
    <Link
      className={['client-download-trigger', compact ? 'client-download-trigger-compact' : '', className].filter(Boolean).join(' ')}
      href="/download"
      title={label}
      aria-label={label}
    >
      <Download size={compact ? 16 : 17} />
      {compact ? null : <span>{label}</span>}
    </Link>
  )
}
