$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$downloads = Join-Path $repo 'openImage-ts\public\downloads'
$manifestPath = Join-Path $downloads 'manifest.json'
$androidPath = Join-Path $downloads 'PicPilot-Android-1.0.0.apk'
$windowsPath = Join-Path $downloads 'PicPilot-Windows-1.0.0-setup.exe'

function New-ReleaseEntry([string]$Path, [string]$Url, [string]$Version, [string]$MinimumOs) {
  if (-not (Test-Path $Path)) {
    return [ordered]@{ enabled = $false; version = $Version; url = ''; size_bytes = 0; sha256 = ''; minimum_os = $MinimumOs }
  }
  $item = Get-Item $Path
  $hash = (Get-FileHash -Algorithm SHA256 $Path).Hash.ToLowerInvariant()
  return [ordered]@{
    enabled = $true
    version = $Version
    url = $Url
    size_bytes = $item.Length
    sha256 = $hash
    minimum_os = $MinimumOs
  }
}

$manifest = [ordered]@{
  schema_version = 1
  published_at = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
  android = New-ReleaseEntry $androidPath '/downloads/PicPilot-Android-1.0.0.apk' '1.0.0' 'Android 8.0+'
  windows = New-ReleaseEntry $windowsPath '/downloads/PicPilot-Windows-1.0.0-setup.exe' '1.0.0' 'Windows 10/11 64-bit'
}

$manifest | ConvertTo-Json -Depth 5 | Set-Content -Path $manifestPath -Encoding UTF8
Write-Host "Release manifest: $manifestPath"
