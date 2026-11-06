$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$current = Resolve-Path (Get-Location)
$workDir = "/workspace/openImage"

if (Test-Path (Join-Path $current "pom.xml")) {
  $relative = Resolve-Path -Relative $current
  if ($relative -eq ".") {
    $workDir = "/workspace"
  } else {
    $workDir = "/workspace/$($relative.TrimStart('.', '\', '/').Replace('\', '/'))"
  }
}

$m2 = Join-Path $HOME ".m2"
New-Item -ItemType Directory -Force -Path $m2 | Out-Null

docker run --rm `
  -v "${repoRoot}:/workspace" `
  -v "${m2}:/root/.m2" `
  -w $workDir `
  maven:3.9-eclipse-temurin-17 `
  mvn @args
