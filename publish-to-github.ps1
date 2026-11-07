# Publish VagiiArk TV (`vagiiark-tv`) to GitHub (`Vagii-Kavagi/vagiiark-tv`)
$ErrorActionPreference = "Stop"
$RepoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $RepoDir

Write-Host "==> Checking GitHub authentication via Git Credential Manager..." -ForegroundColor Cyan
$accounts = git credential-manager github list
if (-not ($accounts -match "Vagii-Kavagi")) {
    Write-Host "Logging in to GitHub via Browser Device Flow..." -ForegroundColor Yellow
    git credential-manager github login --device
}

# Create the repository on GitHub if it does not exist yet using the stored credential token
$credInput = "protocol=https`nhost=github.com`nusername=Vagii-Kavagi`n`n"
$credOutput = $credInput | git credential-manager get
$pat = ($credOutput | Select-String "^password=(.+)$").Matches.Groups[1].Value

if ($pat) {
    $headers = @{
        "Authorization" = "Bearer $pat"
        "Accept"        = "application/vnd.github+json"
        "User-Agent"    = "VagiiArk-TV-Publisher"
    }
    try {
        Invoke-RestMethod -Uri "https://api.github.com/repos/Vagii-Kavagi/vagiiark-tv" -Headers $headers -Method Get | Out-Null
        Write-Host "==> Repository Vagii-Kavagi/vagiiark-tv already exists on GitHub." -ForegroundColor Green
    } catch {
        Write-Host "==> Creating public repository Vagii-Kavagi/vagiiark-tv on GitHub..." -ForegroundColor Cyan
        $body = @{
            name        = "vagiiark-tv"
            description = "Ultra-fast Android TV YouTube client with Zero-Latency AdBlock, SponsorBlock, 4x Speed Control & OTA Updates"
            private     = $false
        } | ConvertTo-Json
        Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Headers $headers -Method Post -Body $body -ContentType "application/json" | Out-Null
        Write-Host "==> Created https://github.com/Vagii-Kavagi/vagiiark-tv" -ForegroundColor Green
    }
}

Write-Host "==> Pushing main branch and latest tags to GitHub..." -ForegroundColor Cyan
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    git commit -m "Update VagiiArk TV"
}
git push -u origin main
git push -f origin --tags

Write-Host "`nDone! Your code and release tags are live at: https://github.com/Vagii-Kavagi/vagiiark-tv" -ForegroundColor Green
Write-Host "GitHub Actions will automatically build and attach app-release.apk to the v1.0.0 Release for in-app OTA updates." -ForegroundColor Green
