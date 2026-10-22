<#
.SYNOPSIS
  Starts the whole local setup in one go: exactly one JonnyBot Dev bot and the website on a branch that has every open website PR merged in.

.DESCRIPTION
  1. Stops every running bot (anything running com.younglings.bot.Main or JonnyBot.jar) and whatever is listening on the website's port, so there is never a second copy.
  2. Refreshes the website's local preview branch (local/wip-preview, never pushed): resets it to origin/dev and merges each open PR into it.
     A PR that conflicts is skipped and reported, the rest still go in.
  3. Builds the bot from whatever branch is checked out in this repository and starts it, with the environment of the IntelliJ "JonnyBot Dev" run
     configuration (read from .idea/runConfigurations, which is git-ignored, so no secret is stored in this script).
  4. Starts the website and waits until both answer. Logs go to %TEMP%\jonnybot-dev.log and %TEMP%\website-dev.log.

.PARAMETER Site
  The website worktree used for the preview branch. Default: ..\younglings-website-p3
.PARAMETER SitePort
  Port for the website (the Discord sign-in callback is registered for 3000).
.PARAMETER NoRefresh
  Skip step 2 and run the website branch as it is.
#>
param(
  [string]$Site = (Join-Path $PSScriptRoot '..\..\younglings-website-p3'),
  [int]$SitePort = 3000,
  [switch]$NoRefresh
)

$ErrorActionPreference = 'Stop'
$bot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Site = (Resolve-Path $Site).Path
$temp = $env:TEMP

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }

# 1. Stop old copies ---------------------------------------------------------------------------------------------------------------------------
Step 'Stopping old bot and website processes'
$bots = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'JonnyBot\.jar|com\.younglings\.bot\.Main' }
foreach ($p in $bots) { Write-Host "  stopping bot process $($p.ProcessId)"; Stop-Process -Id $p.ProcessId -Force }
foreach ($port in @($SitePort, 8081)) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "  stopping process $($_.OwningProcess) on port $port"
    Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
  }
}
Start-Sleep -Seconds 2

# 2. Website preview branch --------------------------------------------------------------------------------------------------------------------
if (-not $NoRefresh) {
  Step 'Refreshing the website preview branch (local/wip-preview)'
  Push-Location $Site
  try {
    git fetch --quiet origin
    git checkout --quiet -B local/wip-preview origin/dev
    $prs = gh pr list --repo metorrite/younglings-website --state open --json number,headRefName | ConvertFrom-Json
    foreach ($pr in $prs) {
      git fetch --quiet origin $pr.headRefName
      git merge --no-edit --quiet "origin/$($pr.headRefName)" 2>&1 | Out-Null
      if ($LASTEXITCODE -ne 0) {
        git merge --abort 2>&1 | Out-Null
        Write-Host "  #$($pr.number) $($pr.headRefName): CONFLICT, left out" -ForegroundColor Yellow
      } else {
        Write-Host "  #$($pr.number) $($pr.headRefName): merged"
      }
    }
  } finally { Pop-Location }
}

# 3. Bot ---------------------------------------------------------------------------------------------------------------------------------------
Step "Starting the bot from $(git -C $bot branch --show-current)"
$config = Join-Path $bot '.idea\runConfigurations\JonnyBot_Dev.xml'
if (-not (Test-Path $config)) { throw "Missing $config (the IntelliJ 'JonnyBot Dev' run configuration); the bot's environment comes from it." }
[xml]$xml = Get-Content $config
foreach ($e in $xml.SelectNodes('//envs/env')) { [Environment]::SetEnvironmentVariable($e.name, $e.value, 'Process') }

Push-Location $bot
try {
  # The same shaded jar that is deployed, so what runs here is what ships.
  mvn -q package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw 'The bot did not build.' }
  $jar = Join-Path $bot 'target\JonnyBot.jar'
  Start-Process -FilePath java -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $bot `
    -RedirectStandardOutput (Join-Path $temp 'jonnybot-dev.log') -RedirectStandardError (Join-Path $temp 'jonnybot-dev.err.log') -WindowStyle Hidden
} finally { Pop-Location }

# 4. Website -----------------------------------------------------------------------------------------------------------------------------------
Step "Starting the website on port $SitePort"
Start-Process -FilePath npm.cmd -ArgumentList @('run', 'dev', '--', '-p', $SitePort) -WorkingDirectory $Site `
  -RedirectStandardOutput (Join-Path $temp 'website-dev.log') -RedirectStandardError (Join-Path $temp 'website-dev.err.log') -WindowStyle Hidden

# 5. Wait and check ----------------------------------------------------------------------------------------------------------------------------
Step 'Waiting for both to answer'
$deadline = (Get-Date).AddSeconds(120)
$botUp = $false; $siteUp = $false
while ((Get-Date) -lt $deadline -and -not ($botUp -and $siteUp)) {
  Start-Sleep -Seconds 3
  if (-not $botUp)  { $botUp  = [bool](Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) }
  if (-not $siteUp) { try { $siteUp = (Invoke-WebRequest "http://localhost:$SitePort/hub" -UseBasicParsing -TimeoutSec 5).StatusCode -lt 500 } catch { $siteUp = $false } }
}
$count = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'JonnyBot\.jar|com\.younglings\.bot\.Main' }).Count
Write-Host ("  bot  (port 8081): " + $(if ($botUp) { 'up' } else { 'NOT UP, see %TEMP%\jonnybot-dev.err.log' }))
Write-Host ("  site (port $SitePort): " + $(if ($siteUp) { 'up' } else { 'NOT UP, see %TEMP%\website-dev.err.log' }))
Write-Host ("  bot processes running: $count" + $(if ($count -ne 1) { '  <-- should be exactly 1' } else { '' }))
if ($botUp -and $siteUp -and $count -eq 1) { Write-Host "`nReady: http://localhost:$SitePort/dashboard  and  http://localhost:$SitePort/hub" -ForegroundColor Green }
