#Requires -Version 5.1
<#
.SYNOPSIS
  Build a PlanRecorder release APK and optionally publish a GitHub Release.

.DESCRIPTION
  Outputs go to ./release/ (gitignored). Does not commit or push source.

.PARAMETER Version
  Version label used in the output folder / GitHub tag (e.g. 1.0.0). Default: timestamp.

.PARAMETER Flavor
  Gradle product flavor. Default: debugConfig (signed debug-friendly build).

.PARAMETER CreateGithubRelease
  If set, creates a GitHub release with gh and uploads the APK.

.EXAMPLE
  .\scripts\release.ps1
  .\scripts\release.ps1 -Version 1.0.0 -CreateGithubRelease
#>
param(
    [string]$Version = (Get-Date -Format "yyyy.MM.dd-HHmm"),
    [ValidateSet("debugConfig", "releaseConfig")]
    [string]$Flavor = "debugConfig",
    [switch]$CreateGithubRelease
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$OutDir = Join-Path $Root "release\$Version"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Task = if ($Flavor -eq "releaseConfig") {
    ":app:assembleReleaseConfigRelease"
} else {
    ":app:assembleDebugConfigDebug"
}

Write-Host "==> Building $Task ..." -ForegroundColor Cyan
if ($IsWindows -or $env:OS -match "Windows") {
    & .\gradlew.bat $Task --quiet
} else {
    & ./gradlew $Task --quiet
}
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }

$ApkSrc = if ($Flavor -eq "releaseConfig") {
    Join-Path $Root "app\build\outputs\apk\releaseConfig\release\app-releaseConfig-release.apk"
} else {
    Join-Path $Root "app\build\outputs\apk\debugConfig\debug\app-debugConfig-debug.apk"
}

if (-not (Test-Path $ApkSrc)) {
    throw "APK not found: $ApkSrc"
}

$ApkName = "PlanRecorder-$Version.apk"
$ApkDst = Join-Path $OutDir $ApkName
Copy-Item -Force $ApkSrc $ApkDst

$Sha = (Get-FileHash -Algorithm SHA256 $ApkDst).Hash.ToLowerInvariant()
@"
PlanRecorder $Version
Built: $(Get-Date -Format o)
Flavor: $Flavor
Gradle task: $Task
SHA256: $Sha
File: $ApkName
"@ | Set-Content -Encoding utf8 (Join-Path $OutDir "CHECKSUMS.txt")

Write-Host "==> Release artifacts in $OutDir" -ForegroundColor Green
Write-Host "    $ApkName"
Write-Host "    SHA256 $Sha"

if ($CreateGithubRelease) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "gh CLI not found; install GitHub CLI or omit -CreateGithubRelease"
    }
    $Tag = "v$Version"
    $Notes = @"
## PlanRecorder $Version

APK: ``$ApkName``
SHA256: ``$Sha``

See README for setup (calendar, SAF export, Opus, markers).
"@
    Write-Host "==> Creating GitHub release $Tag ..." -ForegroundColor Cyan
    gh release create $Tag $ApkDst --title "PlanRecorder $Version" --notes $Notes
    Write-Host "==> GitHub release published" -ForegroundColor Green
}

Write-Host "Done." -ForegroundColor Green
