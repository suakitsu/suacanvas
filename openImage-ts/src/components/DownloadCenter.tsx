'use client'

import Image from 'next/image'
import Link from 'next/link'
import { useMemo, useState } from 'react'
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  Check,
  Copy,
  Download,
  FileCheck2,
  Monitor,
  ShieldCheck,
  Smartphone
} from 'lucide-react'
import { BrandLogo } from '@/components/BrandLogo'
import { LanguageMenu } from '@/components/LanguageMenu'
import { ThemeToggle } from '@/components/ThemeToggle'
import { useLanguage } from '@/components/i18n'

export type ClientRelease = {
  enabled: boolean
  version: string
  url: string
  size_bytes: number
  sha256: string
  minimum_os: string
}

export type ClientManifest = {
  schema_version: number
  published_at: string
  android: ClientRelease
  windows: ClientRelease
}

type DownloadCenterProps = {
  manifest: ClientManifest
}

const copy = {
  'zh-CN': {
    back: '返回首页',
    workspace: '进入工作台',
    eyebrow: 'PicPilot Desktop & Mobile',
    title: 'PicPilot 客户端',
    subtitle: '在电脑上处理批量素材与 AI 画布，在手机上随时上传、查看任务和下载成品。账号、积分与历史记录实时同步。',
    heroWindows: '下载 Windows 版',
    heroAndroid: '下载 Android 版',
    heroHint: 'Windows 与 Android 正式版现已开放',
    packageKicker: '选择你的设备',
    packageTitle: '一套账号，衔接桌面与移动创作',
    packageDesc: '安装包直接由 PicPilot 提供。下载前可核对版本、文件大小和 SHA-256。',
    windows: 'PicPilot for Windows',
    windowsDesc: '面向 AI 画布、批量上传和长时间创作任务的桌面客户端。',
    windowsPointOne: '适配 Windows 10 / 11 64 位系统',
    windowsPointTwo: '系统文件选择器，支持批量上传素材',
    windowsPointThree: '登录状态、积分与网页端实时同步',
    android: 'PicPilot for Android',
    androidDesc: '从相册选图、提交创作、查看进度并保存生成结果。',
    androidPointOne: '适配 Android 8.0 及以上系统',
    androidPointTwo: '原生相册多选与系统下载管理',
    androidPointThree: '六个核心入口使用原生底部导航',
    download: '下载安装包',
    unavailable: '暂未开放',
    version: '版本',
    size: '安装包',
    released: '发布时间',
    checksum: 'SHA-256',
    copyHash: '复制校验值',
    copied: '已复制',
    integrityKicker: '安装与校验',
    integrityTitle: '每一个安装包都可独立核验',
    integrityDesc: '下载完成后，可用系统工具计算 SHA-256，并与页面展示的完整校验值比对。',
    windowsNoteTitle: 'Windows 安装提示',
    windowsNote: '首版安装包尚未配置商业代码签名，Windows 可能显示“未知发布者”。请确认下载域名为 suakitsu.com，并核对 SHA-256 后继续安装。',
    androidNoteTitle: 'Android 安装提示',
    androidNote: '首次安装 APK 时，系统可能要求允许当前浏览器安装应用。PicPilot 只读取你主动选择的文件。',
    securityTitle: '账号与密钥',
    securityNote: '客户端使用 PicPilot 账号登录，不保存模型供应商密钥，也不会读取未选择的本地文件。',
    footer: 'PicPilot AI Commerce Studio'
  },
  'en-US': {
    back: 'Back to home', workspace: 'Open workspace', eyebrow: 'PicPilot Desktop & Mobile', title: 'PicPilot apps',
    subtitle: 'Handle batches and AI Canvas on desktop, then upload, monitor, and download results on mobile. Your account, credits, and history stay in sync.',
    heroWindows: 'Download for Windows', heroAndroid: 'Download for Android', heroHint: 'Windows and Android releases are available now',
    packageKicker: 'Choose your device', packageTitle: 'One account across desktop and mobile', packageDesc: 'Installers are distributed directly by PicPilot with version, file size, and SHA-256 details.',
    windows: 'PicPilot for Windows', windowsDesc: 'A desktop client for AI Canvas, batch uploads, and long-running creative work.', windowsPointOne: 'Windows 10 / 11, 64-bit', windowsPointTwo: 'Native file picker with batch uploads', windowsPointThree: 'Account, credits, and jobs stay synced',
    android: 'PicPilot for Android', androidDesc: 'Choose photos, submit work, monitor progress, and save finished assets.', androidPointOne: 'Android 8.0 or later', androidPointTwo: 'Native gallery selection and downloads', androidPointThree: 'Native navigation for six core areas',
    download: 'Download installer', unavailable: 'Not available', version: 'Version', size: 'Package', released: 'Published', checksum: 'SHA-256', copyHash: 'Copy checksum', copied: 'Copied',
    integrityKicker: 'Install and verify', integrityTitle: 'Every package can be verified independently', integrityDesc: 'After downloading, calculate SHA-256 with a system tool and compare it with the complete checksum on this page.',
    windowsNoteTitle: 'Windows installation', windowsNote: 'The first release is not yet commercially code-signed, so Windows may show “Unknown publisher.” Confirm the suakitsu.com domain and verify SHA-256 before installing.',
    androidNoteTitle: 'Android installation', androidNote: 'Android may ask permission for your browser to install an APK. PicPilot only reads files you explicitly select.', securityTitle: 'Account and keys', securityNote: 'The clients use your PicPilot account and never store model-provider keys or read unselected local files.', footer: 'PicPilot AI Commerce Studio'
  },
  'ja-JP': {
    back: 'ホームへ戻る', workspace: 'ワークスペースを開く', eyebrow: 'PicPilot Desktop & Mobile', title: 'PicPilot アプリ',
    subtitle: 'デスクトップで一括素材と AI キャンバスを扱い、モバイルでアップロード、進捗確認、保存まで行えます。アカウント、ポイント、履歴は同期されます。',
    heroWindows: 'Windows 版をダウンロード', heroAndroid: 'Android 版をダウンロード', heroHint: 'Windows / Android 正式版を公開中',
    packageKicker: 'デバイスを選択', packageTitle: 'ひとつのアカウントでデスクトップとモバイルを連携', packageDesc: 'インストーラーは PicPilot から直接配布され、バージョン、サイズ、SHA-256 を確認できます。',
    windows: 'PicPilot for Windows', windowsDesc: 'AI キャンバス、一括アップロード、長時間タスク向けのデスクトップアプリです。', windowsPointOne: 'Windows 10 / 11 64 ビット対応', windowsPointTwo: 'システムファイル選択と一括アップロード', windowsPointThree: 'アカウント、ポイント、タスクを同期',
    android: 'PicPilot for Android', androidDesc: '写真選択、生成依頼、進捗確認、完成データの保存に対応します。', androidPointOne: 'Android 8.0 以降', androidPointTwo: '写真の複数選択とシステムダウンロード', androidPointThree: '主要 6 機能のネイティブナビゲーション',
    download: 'インストーラーを取得', unavailable: '未公開', version: 'バージョン', size: 'サイズ', released: '公開日', checksum: 'SHA-256', copyHash: '検証値をコピー', copied: 'コピー済み',
    integrityKicker: 'インストールと検証', integrityTitle: 'すべてのパッケージを個別に検証できます', integrityDesc: 'ダウンロード後に SHA-256 を計算し、このページの完全な検証値と照合してください。',
    windowsNoteTitle: 'Windows のインストール', windowsNote: '初回版は商用コード署名が未設定のため、Windows で「不明な発行元」と表示される場合があります。suakitsu.com と SHA-256 を確認してください。',
    androidNoteTitle: 'Android のインストール', androidNote: 'APK の初回インストール時にブラウザへの許可が必要な場合があります。PicPilot は選択したファイルだけを読み取ります。', securityTitle: 'アカウントとキー', securityNote: 'PicPilot アカウントでログインし、モデル提供元のキーや未選択のローカルファイルは保存・読み取りません。', footer: 'PicPilot AI Commerce Studio'
  }
} as const

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '--'
  const megabytes = value / 1024 / 1024
  return `${megabytes >= 10 ? megabytes.toFixed(0) : megabytes.toFixed(1)} MB`
}

function isValidRelease(release: ClientRelease) {
  return release.enabled && release.url.startsWith('/downloads/') && release.size_bytes > 0 && /^[a-f0-9]{64}$/i.test(release.sha256)
}

export function DownloadCenter({ manifest }: DownloadCenterProps) {
  const language = useLanguage()
  const t = copy[language as keyof typeof copy] || copy['zh-CN']
  const [copied, setCopied] = useState<string | null>(null)
  const publishedAt = useMemo(() => {
    const locale = language === 'en-US' ? 'en-US' : language === 'ja-JP' ? 'ja-JP' : 'zh-CN'
    return new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(manifest.published_at))
  }, [language, manifest.published_at])

  const platforms = [
    {
      key: 'windows',
      Icon: Monitor,
      title: t.windows,
      description: t.windowsDesc,
      points: [t.windowsPointOne, t.windowsPointTwo, t.windowsPointThree],
      release: manifest.windows,
      note: t.windowsNote
    },
    {
      key: 'android',
      Icon: Smartphone,
      title: t.android,
      description: t.androidDesc,
      points: [t.androidPointOne, t.androidPointTwo, t.androidPointThree],
      release: manifest.android,
      note: t.androidNote
    }
  ]

  async function copyChecksum(key: string, value: string) {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(key)
      window.setTimeout(() => setCopied(current => current === key ? null : current), 1600)
    } catch {
      setCopied(null)
    }
  }

  return (
    <main className="download-page">
      <header className="download-nav">
        <Link className="download-brand" href="/" aria-label="PicPilot">
          <BrandLogo className="picpilot-brand-landing" />
        </Link>
        <div className="download-nav-actions">
          <Link className="download-back-link" href="/"><ArrowLeft size={16} />{t.back}</Link>
          <LanguageMenu />
          <ThemeToggle />
          <Link className="btn btn-primary download-workspace-link" href="/workspace">{t.workspace}<ArrowRight size={16} /></Link>
        </div>
      </header>

      <section className="download-hero">
        <Image className="download-hero-image" src="/landing-picpilot-studio-v2.webp" alt="PicPilot AI Commerce Studio" fill priority sizes="100vw" />
        <div className="download-hero-shade" aria-hidden="true" />
        <div className="download-hero-content">
          <span className="download-eyebrow"><BadgeCheck size={16} />{t.eyebrow}</span>
          <h1>{t.title}</h1>
          <p>{t.subtitle}</p>
          <div className="download-hero-actions">
            {isValidRelease(manifest.windows) ? <a className="btn btn-primary" href={manifest.windows.url} download><Monitor size={17} />{t.heroWindows}</a> : null}
            {isValidRelease(manifest.android) ? <a className="download-hero-secondary" href={manifest.android.url} download><Smartphone size={17} />{t.heroAndroid}</a> : null}
          </div>
          <span className="download-hero-hint"><Check size={15} />{t.heroHint}</span>
        </div>
      </section>

      <section className="download-packages" id="client-packages">
        <div className="download-section-heading">
          <span>{t.packageKicker}</span>
          <h2>{t.packageTitle}</h2>
          <p>{t.packageDesc}</p>
        </div>
        <div className="download-platform-grid">
          {platforms.map(({ key, Icon, title, description, points, release }) => {
            const available = isValidRelease(release)
            return (
              <article className="download-platform-card" key={key}>
                <div className="download-platform-heading">
                  <span className="download-platform-icon"><Icon size={27} /></span>
                  <div><h3>{title}</h3><p>{description}</p></div>
                </div>
                <ul className="download-platform-points">
                  {points.map(point => <li key={point}><Check size={15} />{point}</li>)}
                </ul>
                <dl className="download-release-meta">
                  <div><dt>{t.version}</dt><dd>{release.version}</dd></div>
                  <div><dt>{t.size}</dt><dd>{formatBytes(release.size_bytes)}</dd></div>
                  <div><dt>{t.released}</dt><dd>{publishedAt}</dd></div>
                </dl>
                {available ? <a className="btn btn-primary download-package-action" href={release.url} download><Download size={17} />{t.download}</a> : <button className="btn download-package-action" type="button" disabled>{t.unavailable}</button>}
                <div className="download-checksum-row">
                  <div><span>{t.checksum}</span><code>{release.sha256}</code></div>
                  <button type="button" title={t.copyHash} aria-label={t.copyHash} onClick={() => copyChecksum(key, release.sha256)}>
                    {copied === key ? <Check size={16} /> : <Copy size={16} />}
                    <span>{copied === key ? t.copied : t.copyHash}</span>
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      </section>

      <section className="download-integrity">
        <div className="download-section-heading download-section-heading-left">
          <span>{t.integrityKicker}</span>
          <h2>{t.integrityTitle}</h2>
          <p>{t.integrityDesc}</p>
        </div>
        <div className="download-notes">
          <article><Monitor size={21} /><div><h3>{t.windowsNoteTitle}</h3><p>{t.windowsNote}</p></div></article>
          <article><Smartphone size={21} /><div><h3>{t.androidNoteTitle}</h3><p>{t.androidNote}</p></div></article>
          <article><ShieldCheck size={21} /><div><h3>{t.securityTitle}</h3><p>{t.securityNote}</p></div></article>
        </div>
      </section>

      <footer className="download-footer">
        <BrandLogo />
        <span><FileCheck2 size={15} />{t.footer}</span>
      </footer>
    </main>
  )
}
