# Optional: native jpackage on a real Windows host (not required).
# Preferred: build on WSL with scripts/build-windows-dist.sh
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $Root "backend")

Write-Host "==> Building bootJar (includes frontend)..."
.\gradlew.bat clean bootJar
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> Packaging with jpackage..."
.\gradlew.bat jpackageImage
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$Out = Join-Path $Root "backend\build\jpackage\EvidenceAuto"
Write-Host ""
Write-Host "Done. App image:"
Write-Host "  $Out\EvidenceAuto.exe"
Write-Host ""
Write-Host "Prefer WSL builds? Use: bash scripts/build-windows-dist.sh"
Write-Host "Chrome must be installed on the target PC for screenshots / folder picker."
