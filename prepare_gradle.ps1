param()
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $projectDir 'gradle\wrapper\gradle-wrapper.jar'
$checksum = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
if (Test-Path $jar) {
    $existing = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existing -eq $checksum) {
        Write-Host 'Gradle Wrapper is ready.' -ForegroundColor Green
        exit 0
    }
    Remove-Item -LiteralPath $jar -Force
}
$urls = @(
  'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar',
  'https://github.com/gradle/gradle/raw/refs/tags/v8.11.1/gradle/wrapper/gradle-wrapper.jar'
)
$temp = "$jar.download"
foreach ($url in $urls) {
    try {
        Write-Host 'Downloading official Gradle Wrapper...' -ForegroundColor Cyan
        Invoke-WebRequest -Uri $url -OutFile $temp -UseBasicParsing -MaximumRedirection 8 -TimeoutSec 40
        $actual = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $checksum) {
            Remove-Item $temp -Force -ErrorAction SilentlyContinue
            throw "Gradle Wrapper checksum mismatch. Expected $checksum, got $actual"
        }
        Move-Item -LiteralPath $temp -Destination $jar -Force
        Write-Host 'Gradle Wrapper checksum verified.' -ForegroundColor Green
        exit 0
    } catch {
        Write-Host $_.Exception.Message -ForegroundColor Yellow
        Remove-Item $temp -Force -ErrorAction SilentlyContinue
    }
}
Write-Error 'Cannot download official Gradle Wrapper. Check github.com connectivity and retry.'
exit 1
