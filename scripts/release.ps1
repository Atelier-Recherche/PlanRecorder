#Requires -Version 5.1
<#
.SYNOPSIS
  Build a PlanRecorder release APK and optionally publish a GitHub Release.

.DESCRIPTION
  Outputs go to ./release/ (gitignored). Does not commit or push source.
  Can bump versionCode / versionName in app/build.gradle.kts before building.

.PARAMETER Version
  Set versionName explicitly (e.g. 1.1.0). Also used for the output folder / GitHub tag.
  Writes to build.gradle.kts and increments versionCode by 1.

.PARAMETER Bump
  Increment patch of versionName (1.0.0 → 1.0.1) and versionCode by 1 in build.gradle.kts.
  If -Version is also provided, versionName is set to that value instead of patch-bumping.

.PARAMETER Flavor
  Gradle product flavor. Default: debugConfig (signed debug-friendly build).

.PARAMETER CreateGithubRelease
  If set, creates a GitHub release with gh and uploads the APK.

.EXAMPLE
  .\scripts\release.ps1
  .\scripts\release.ps1 -Bump
  .\scripts\release.ps1 -Version 1.1.0 -CreateGithubRelease
#>
param(
    [string]$Version = "",
    [switch]$Bump,
    [ValidateSet("debugConfig", "releaseConfig")]
    [string]$Flavor = "debugConfig",
    [switch]$CreateGithubRelease
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$GradleFile = Join-Path $Root "app\build.gradle.kts"
if (-not (Test-Path $GradleFile)) {
    throw "Gradle file not found: $GradleFile"
}

function Get-GradleVersions {
    param([string]$Path)
    $text = Get-Content -Raw -Encoding utf8 $Path
    if ($text -notmatch 'versionCode\s*=\s*(\d+)') {
        throw "versionCode not found in $Path"
    }
    $code = [int]$Matches[1]
    if ($text -notmatch 'versionName\s*=\s*"([^"]+)"') {
        throw "versionName not found in $Path"
    }
    return @{ Code = $code; Name = $Matches[1]; Text = $text }
}

function Set-GradleVersions {
    param(
        [string]$Path,
        [string]$Text,
        [int]$Code,
        [string]$Name
    )
    $updated = $Text -replace 'versionCode\s*=\s*\d+', "versionCode = $Code"
    $updated = $updated -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$Name`""
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $updated, $utf8)
}

function Get-BumpedPatchVersion {
    param([string]$Name)
    if ($Name -match '^(\d+)\.(\d+)\.(\d+)') {
        $major = [int]$Matches[1]
        $minor = [int]$Matches[2]
        $patch = [int]$Matches[3] + 1
        return "$major.$minor.$patch"
    }
    throw "Cannot bump versionName '$Name' (expected x.y.z)"
}

$current = Get-GradleVersions -Path $GradleFile
$versionCode = $current.Code
$versionName = $current.Name

if ($Bump -or $Version) {
    $versionCode = $versionCode + 1
    if ($Version) {
        $versionName = $Version
    } else {
        $versionName = Get-BumpedPatchVersion -Name $versionName
    }
    Write-Host "==> Bumping version: $($current.Name) ($($current.Code)) → $versionName ($versionCode)" -ForegroundColor Cyan
    Set-GradleVersions -Path $GradleFile -Text $current.Text -Code $versionCode -Name $versionName
}

$label = $versionName
$OutDir = Join-Path $Root "release\$label"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Task = if ($Flavor -eq "releaseConfig") {
    ":app:assembleReleaseConfigRelease"
} else {
    ":app:assembleDebugConfigDebug"
}

Write-Host "==> Building $Task (versionName=$versionName versionCode=$versionCode) ..." -ForegroundColor Cyan
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

$ApkName = "PlanRecorder-$label.apk"
$ApkDst = Join-Path $OutDir $ApkName
Copy-Item -Force $ApkSrc $ApkDst

$Sha = (Get-FileHash -Algorithm SHA256 $ApkDst).Hash.ToLowerInvariant()
@"
PlanRecorder $label
Built: $(Get-Date -Format o)
Flavor: $Flavor
Gradle task: $Task
versionName: $versionName
versionCode: $versionCode
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
    $repo = "Morglaf/PlanRecorder"
    try {
        $originUrl = git remote get-url origin 2>$null
        if ($originUrl -match 'github\.com[:/](.+?)/(.+?)(?:\.git)?$') {
            $repo = "$($Matches[1])/$($Matches[2])"
        }
    } catch { }
    gh repo set-default $repo 2>$null | Out-Null

    $Tag = "v$label"
    $Notes = @"
## PlanRecorder $label

APK: ``$ApkName``
SHA256: ``$Sha``

See README for setup (calendar, SAF export, Opus, markers).
"@
    Write-Host "==> Creating GitHub release $Tag on $repo ..." -ForegroundColor Cyan
    gh release create $Tag $ApkDst --repo $repo --title "PlanRecorder $label" --notes $Notes
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub release failed (exit $LASTEXITCODE). Try: gh repo set-default $repo"
    }
    Write-Host "==> GitHub release published: https://github.com/$repo/releases/tag/$Tag" -ForegroundColor Green
}

Write-Host "Done." -ForegroundColor Green
