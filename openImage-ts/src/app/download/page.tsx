import type { Metadata } from 'next'
import manifest from '../../../public/downloads/manifest.json'
import { ClientManifest, DownloadCenter } from '@/components/DownloadCenter'

export const metadata: Metadata = {
  title: 'PicPilot 客户端下载',
  description: '下载 PicPilot Windows 与 Android 客户端，并核对版本、系统要求和 SHA-256。'
}

export default function DownloadPage() {
  return <DownloadCenter manifest={manifest as ClientManifest} />
}
