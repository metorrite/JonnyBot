<#
.SYNOPSIS
  Sets up the RSN link service (rsnlink) for LOCAL development: creates the jonnybot_link database and writes fresh, random, local-only
  LINK_* keys into the git-ignored .env file. Prints variable names only, never a value.

.DESCRIPTION
  1. Reads DATABASE_URL from the git-ignored IntelliJ run configuration (.idea/runConfigurations/JonnyBot_Dev.xml) and swaps the database
     name for jonnybot_link, so the link database uses the same local Postgres login but is a separate database.
  2. Creates that database with psql if it doesn't exist (psql finds the password in %APPDATA%\postgresql\pgpass.conf).
  3. Adds each missing LINK_* variable to .env (the bot reads .env when a variable isn't in its run configuration). Variables already in .env
     are left alone, so running this twice changes nothing and existing links keep working.

  The keys are made here, on this PC, from the system's secure random generator. They are for local development only: never copy them to
  Railway or anywhere else. Production keys are made separately when the service goes live (T-120).

.PARAMETER EnvFile
  The .env file to write. Default: .env in the repository root (git-ignored).
.PARAMETER PublicBaseUrl
  The address the plugin shows players. Default: http://localhost:3000/link (the link page on the local website; the site's
  root is the clan site, so players typing only localhost:3000 found nowhere to enter the code, beta test 2026-10-22).
#>
param(
  [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
  [string]$PublicBaseUrl = 'http://localhost:3000/link'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runConfig = Join-Path $repo '.idea\runConfigurations\JonnyBot_Dev.xml'

function New-Key {
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  return [Convert]::ToBase64String($bytes)
}

# 1. The link database address, from the dev bot's own (never printed).
if (-not (Test-Path $runConfig)) { throw "No run configuration at $runConfig. Create 'JonnyBot Dev' first (docs/DEV-SETUP.md section 3)." }
$m = [regex]::Match((Get-Content -Raw $runConfig), 'name="DATABASE_URL" value="([^"]+)"')
if (-not $m.Success) { throw 'The JonnyBot Dev run configuration has no DATABASE_URL.' }
$botUrl = [uri]$m.Groups[1].Value
if ($botUrl.Host -ne '127.0.0.1' -and $botUrl.Host -ne 'localhost') { throw 'DATABASE_URL is not a local database; refusing.' }
$linkUrl = $m.Groups[1].Value -replace '/[^/]+$', '/jonnybot_link'
$user = $botUrl.UserInfo.Split(':')[0]

# 2. The database itself.
$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) { $psql = 'C:\Program Files\PostgreSQL\16\bin\psql.exe' }
$env:PGPASSFILE = Join-Path $env:APPDATA 'postgresql\pgpass.conf'
$exists = & $psql -h $botUrl.Host -p $botUrl.Port -U $user -d postgres -w -tAc "SELECT 1 FROM pg_database WHERE datname = 'jonnybot_link'"
if ($exists -ne '1') {
  & $psql -h $botUrl.Host -p $botUrl.Port -U $user -d postgres -w -c 'CREATE DATABASE jonnybot_link' | Out-Null
  Write-Host 'Created the database jonnybot_link.'
} else {
  Write-Host 'The database jonnybot_link already exists.'
}

# 3. The keys.
$existing = @{}
if (Test-Path $EnvFile) {
  foreach ($line in Get-Content $EnvFile) {
    $k = ($line -split '=', 2)[0].Trim().TrimStart([char]0xFEFF)
    if ($k) { $existing[$k] = $true }
  }
}
$wanted = [ordered]@{
  'LINK_DATABASE_URL'    = $linkUrl
  'LINK_ENC_KEY'         = New-Key
  'LINK_LOOKUP_KEY'      = New-Key
  'LINK_ALIAS_KEY'       = New-Key
  'LINK_SIGNING_KEY'     = New-Key
  'LINK_PUBLIC_BASE_URL' = $PublicBaseUrl
}
$added = @()
$lines = ''
foreach ($name in $wanted.Keys) {
  if (-not $existing.ContainsKey($name)) {
    $lines += "$name=$($wanted[$name])`n"
    $added += $name
  }
}
if ($lines) {
  # UTF-8 WITHOUT a byte-order mark: Windows PowerShell 5.1's "-Encoding utf8" writes one, and it hid the first key from
  # the bot's .env reader (beta test 2026-10-22). Start on a new line if the file doesn't end with one.
  if ((Test-Path $EnvFile) -and (Get-Item $EnvFile).Length -gt 0) {
    $raw = [System.IO.File]::ReadAllText((Resolve-Path $EnvFile).Path)
    if (-not $raw.EndsWith("`n")) { $lines = "`n" + $lines }
  }
  [System.IO.File]::AppendAllText([System.IO.Path]::GetFullPath($EnvFile), $lines, (New-Object System.Text.UTF8Encoding $false))
}
if ($added.Count -gt 0) { Write-Host ('Added to ' + $EnvFile + ': ' + ($added -join ', ')) } else { Write-Host 'Every LINK_ variable was already set; nothing changed.' }
Write-Host 'Restart the dev bot; its log should say "RSN link service is on (schema rsnlink)."'
