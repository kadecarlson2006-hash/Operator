<#
.SYNOPSIS
  Checks everything Operator needs before a test session, and says what is missing.

.DESCRIPTION
  Run this instead of guessing why nothing happens on the phone. It verifies the local
  configuration, brings up the database, and reports which provider slots are actually
  configured — reading the backend's own /health rather than re-deriving it here, so it
  cannot disagree with what the server thinks.

  It never prints secrets: /health masks them, and this only reports set/not set.

.EXAMPLE
  .\scripts\preflight.ps1
  .\scripts\preflight.ps1 -SkipDatabase
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipDatabase,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo

$problems = [System.Collections.Generic.List[string]]::new()
function Ok    ($m) { Write-Host "  [ ok ] $m" -ForegroundColor Green }
function Warn  ($m) { Write-Host "  [warn] $m" -ForegroundColor Yellow }
function Bad   ($m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $problems.Add($m) }
function Head  ($m) { Write-Host ""; Write-Host $m -ForegroundColor Cyan }

try {
    Head "Configuration"

    if (-not (Test-Path ".env")) {
        Bad ".env does not exist. Copy .env.example to .env and fill it in."
    } else {
        Ok ".env exists"

        # The mistake that costs you an evening: keys in the tracked template.
        $exampleHasSecret = Select-String -Path ".env.example" `
            -Pattern '^(OPENROUTER_API_KEY|ELEVENLABS_API_KEY|TRANSCRIPTION_API_KEY)=\S' -Quiet
        if ($exampleHasSecret) {
            Bad ".env.example contains a key. That file is TRACKED BY GIT. Move it to .env and run: git checkout -- .env.example"
        } else {
            Ok ".env.example is clean (it is tracked by git, so it must stay that way)"
        }

        $tracked = git ls-files --error-unmatch .env 2>$null
        if ($LASTEXITCODE -eq 0) { Bad ".env is tracked by git. It must not be." } else { Ok ".env is not tracked by git" }
    }

    if (-not $SkipDatabase) {
        Head "Database"
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if (-not $docker) {
            Warn "Docker not found. The backend still runs, but memory is in-memory and lost on restart."
        } else {
            docker info *> $null
            if ($LASTEXITCODE -ne 0) {
                Warn "Docker is installed but not running. Start Docker Desktop, or use -SkipDatabase."
            } else {
                Ok "Docker is running"
                docker compose -f backend/docker-compose.yml up -d *> $null
                $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
                $ready = $false
                while ((Get-Date) -lt $deadline) {
                    docker exec operator-postgres pg_isready -U operator -d operator *> $null
                    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
                    Start-Sleep -Seconds 2
                }
                if ($ready) { Ok "PostgreSQL is accepting connections" }
                else { Bad "PostgreSQL did not become ready within $TimeoutSeconds s" }
            }
        }
    }

    Head "Backend"
    $health = $null
    $healthError = $null
    try {
        # Deliberately not Invoke-RestMethod: /health answers 503 when it is degraded (no
        # database, say) while still describing everything. Invoke-RestMethod throws on any
        # non-2xx, which would report a perfectly healthy-but-degraded backend as "not running"
        # — the exact case anyone without Docker hits first. HttpClient reads the body either
        # way, and works the same on Windows PowerShell 5.1 and PowerShell 7.
        try { Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue } catch { }
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(5)
        $response = $client.GetAsync("$BaseUrl/health").GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $client.Dispose()
        $health = $body | ConvertFrom-Json
    } catch {
        $healthError = $_.Exception.Message
    }

    if (-not $health) {
        Warn "Not responding at $BaseUrl ($healthError). Start it in another window with:"
        Write-Host "         .\gradlew.bat :backend:run -Poperator.skipAndroid=true" -ForegroundColor Gray
    }

    if ($health) {
        Ok "Backend $($health.version) is up (status: $($health.status))"

        if ($health.database.reachable) {
            Ok "Database reachable — pgvector $($health.database.pgvector), $($health.database.migrationsApplied) migrations"
        } elseif ($health.database.configured) {
            Bad "DATABASE_URL is set but unreachable: $($health.database.error)"
        } else {
            Warn "No database configured — memory is '$($health.memoryBackend)' and will not survive a restart"
        }

        Head "Provider slots"
        $hints = @{
            ai            = "OPENROUTER_API_KEY + OPERATOR_FAST_MODEL_ID   (unlocks Ask Operator, memory, deciding)"
            transcription = "TRANSCRIPTION_API_KEY + OPERATOR_TRANSCRIPTION_MODEL_ID   (unlocks listening)"
            tts           = "ELEVENLABS_API_KEY + voice and model IDs   (unlocks speech out)"
        }
        foreach ($slot in "ai", "transcription", "tts") {
            $s = $health.providers.$slot
            if ($s.configured) { Ok  ("{0,-14} {1} — {2}" -f $slot, $s.provider, $s.detail) }
            else               { Warn ("{0,-14} not configured. Set: {1}" -f $slot, $hints[$slot]) }
        }

        if ($health.providers.ai.configured) {
            Head "First call"
            # A literal here-string: no escape processing at all, so the quotes below are exactly
            # what gets printed. The request body goes in a file rather than inline because
            # Windows PowerShell 5.1 and PowerShell 7 disagree about how embedded double quotes
            # reach a native executable; a file sidesteps the question entirely.
            $firstCall = @'
    Set-Content -Path ask.json -Encoding ascii -Value '{"prompt":"Say hello in one short sentence."}'
    curl.exe -s -X POST __BASE__/ai/respond -H "Content-Type: application/json" -d "@ask.json"
'@
            Write-Host "  Ready. Try:" -ForegroundColor Gray
            Write-Host $firstCall.Replace("__BASE__", $BaseUrl) -ForegroundColor Gray
            Write-Host "  The full running order is in TESTING.md." -ForegroundColor Gray
        }
    }

    Head "Summary"
    if ($problems.Count -eq 0) {
        Write-Host "  Nothing blocking. Warnings above are optional features, not faults." -ForegroundColor Green
    } else {
        Write-Host "  $($problems.Count) thing(s) to fix:" -ForegroundColor Red
        $problems | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
        exit 1
    }
} finally {
    Pop-Location
}
