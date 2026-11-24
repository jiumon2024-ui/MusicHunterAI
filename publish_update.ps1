$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git for Windows is not installed.' }
$remote = (& git remote get-url origin 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($remote)) {
    throw 'First create a public GitHub repo and set git remote origin.'
}
$source = Get-Content './app/build.gradle.kts' -Raw
$match = [regex]::Match($source, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $match.Success) { throw 'Version is missing. Set versionName to X.Y.Z in app/build.gradle.kts.' }
$tag = 'v' + $match.Groups[1].Value
$existing = & git tag --list $tag
if ($existing -eq $tag) { throw "Tag $tag already exists. Increase versionName and versionCode." }
Write-Host "Repository: $remote" -ForegroundColor Cyan
Write-Host "Release: $tag" -ForegroundColor Cyan
Write-Host 'Review files before publishing. Never commit passwords, signing keys, or personal files.' -ForegroundColor Yellow
& git status --short
$answer = Read-Host 'Type PUBLISH to commit, tag and push this release'
if ($answer -cne 'PUBLISH') { Write-Host 'Canceled.'; exit 0 }
& git add -A
if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
$bad = & git diff --cached --name-only | Where-Object { $_ -match '(?i)(^|/)(local\.properties|[^/]+\.(jks|keystore|env|p12))$' }
if ($bad) {
    & git reset --quiet
    throw 'Danger: detected a sensitive file. Commit rejected.'
}
& git diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
    & git commit -m "Music Hunter AI $tag"
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
}
& git tag $tag
if ($LASTEXITCODE -ne 0) { throw 'git tag failed' }
& git push origin HEAD
if ($LASTEXITCODE -ne 0) { throw 'Push branch failed, local tag still exists' }
& git push origin $tag
if ($LASTEXITCODE -ne 0) { throw "Push tag failed. Retry: git push origin $tag" }
Write-Host "Tag $tag published. GitHub Actions will build your signed APK." -ForegroundColor Green
