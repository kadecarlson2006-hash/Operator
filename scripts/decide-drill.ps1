<#
.SYNOPSIS
  Stage 6 backend drill: puts a fixed set of conversations to POST /decide and tabulates
  what Operator decided.

.DESCRIPTION
  The central question of the project is not whether Operator says clever things. It is
  whether it stays quiet. This runs the same scenarios every time so two runs — across a
  prompt change, a model change, a threshold change — can be compared.

  Scenario groups, in the order they run and why:

    FREE      Refused by the local rules before any model call. ConversationPolicy.gate()
              returns before recordDecision(), so these cost nothing and do not consume the
              decision interval or the five-minute budget. Safe to run back to back.
    AMBIENT   Uninvited. This is the real test. Each one that reaches the model records a
              decision, so the next ambient call inside MIN_DECISION_INTERVAL_SECONDS is
              refused as DECIDED_RECENTLY — hence the wait between them.
    INVITED   DIRECT_ADDRESS and COMMENT_NOW bypass the interval and budget entirely (mute
              and OFF still stop them). Run last so they do not disturb ambient timing.

  Nothing here writes to the transcript or speaks. /decide only judges.

.EXAMPLE
  .\scripts\decide-drill.ps1
  .\scripts\decide-drill.ps1 -GapSeconds 25
  .\scripts\decide-drill.ps1 -SkipAmbientWaits   # fast, but ambient results become meaningless
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    # Must exceed MIN_DECISION_INTERVAL_SECONDS (default 20) or ambient calls refuse each other.
    [int]$GapSeconds = 21,
    [switch]$SkipAmbientWaits,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"
try { Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue } catch { }
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(60)

function Invoke-Decide($payload) {
    $json = $payload | ConvertTo-Json -Depth 6 -Compress
    $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
    try {
        $res = $client.PostAsync("$BaseUrl/decide", $content).GetAwaiter().GetResult()
        $body = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $res.IsSuccessStatusCode) {
            return [PSCustomObject]@{ error = "HTTP $([int]$res.StatusCode): $body" }
        }
        return $body | ConvertFrom-Json
    } catch {
        return [PSCustomObject]@{ error = $_.Exception.Message }
    }
}

# --- The scenarios ------------------------------------------------------------------
# Transcripts are written the way the phone sends them: "Speaker: line", oldest first.

$chitchat = @(
    "Someone: did you watch the game last night",
    "Someone: yeah the second half was rough",
    "Someone: I fell asleep before the end honestly"
)
$plans = @(
    "Someone: what are you doing this weekend",
    "Someone: nothing much, might go to the lake if the weather holds",
    "Someone: same, I need a quiet one"
)
$factualError = @(
    "Someone: the invoice terms are net sixty right",
    "Someone: no I'm pretty sure we agreed net thirty with them",
    "Someone: hm, one of us is wrong"
)
$openQuestion = @(
    "Someone: what was the name of that supplier we used in March",
    "Someone: I want to say it started with a K",
    "Someone: nobody remember it?"
)

$free = @(
    @{ name = "muted";                 body = @{ trigger = "AMBIENT"; transcript = $chitchat; muted = $true } }
    @{ name = "mode OFF";              body = @{ trigger = "AMBIENT"; transcript = $chitchat; mode = "OFF" } }
    @{ name = "mode QUIET (ambient)";  body = @{ trigger = "AMBIENT"; transcript = $chitchat; mode = "QUIET" } }
    @{ name = "mode STANDBY (ambient)";body = @{ trigger = "AMBIENT"; transcript = $chitchat; mode = "STANDBY" } }
    @{ name = "empty transcript";      body = @{ trigger = "AMBIENT"; transcript = @() } }
    @{ name = "muted + direct address";body = @{ trigger = "DIRECT_ADDRESS"; transcript = $chitchat; muted = $true } }
)

$ambient = @(
    @{ name = "chit-chat about a game";   body = @{ trigger = "AMBIENT"; transcript = $chitchat;     mode = "ACTIVE" } }
    @{ name = "weekend small talk";       body = @{ trigger = "AMBIENT"; transcript = $plans;        mode = "ACTIVE" } }
    @{ name = "disputed invoice terms";   body = @{ trigger = "AMBIENT"; transcript = $factualError; mode = "WORK" } }
    @{ name = "unanswered question";      body = @{ trigger = "AMBIENT"; transcript = $openQuestion; mode = "WORK" } }
)

$invited = @(
    @{ name = "direct address";        body = @{ trigger = "DIRECT_ADDRESS"; transcript = @("Someone: operator, what is the capital of France"); mode = "ACTIVE" } }
    @{ name = "comment now";           body = @{ trigger = "COMMENT_NOW";    transcript = $chitchat; mode = "ACTIVE" } }
)

# --- Run ----------------------------------------------------------------------------

$results = [System.Collections.Generic.List[object]]::new()

function Run-Group($label, $scenarios, $waitBetween) {
    Write-Host ""
    Write-Host $label -ForegroundColor Cyan
    $first = $true
    foreach ($s in $scenarios) {
        if ($waitBetween -and -not $first) {
            Write-Host ("  waiting {0}s so the decision interval does not refuse the next one..." -f $GapSeconds) -ForegroundColor DarkGray
            Start-Sleep -Seconds $GapSeconds
        }
        $first = $false
        $r = Invoke-Decide $s.body
        if ($r.PSObject.Properties.Name -contains "error") {
            Write-Host ("  [FAIL] {0}: {1}" -f $s.name, $r.error) -ForegroundColor Red
            $results.Add([PSCustomObject]@{ Group=$label; Scenario=$s.name; Spoke="ERROR"; Reason=$r.error; Cost=""; Ms=""; Said="" })
            continue
        }
        if ($r.gatedLocally) { $cost = "free" } elseif ($r.modelCalled) { $cost = "model" } else { $cost = "-" }
        if ($r.shouldSpeak) { $spoke = "SPOKE" } else { $spoke = "silent" }
        if ($r.shouldSpeak) { $colour = "Yellow" } else { $colour = "Green" }
        Write-Host ("  {0,-26} {1,-7} {2,-24} {3,-6} {4,5} ms" -f $s.name, $spoke, $r.reasonCode, $cost, $r.latencyMillis) -ForegroundColor $colour
        if ($r.response) { Write-Host ("      -> `"{0}`"" -f $r.response) -ForegroundColor Gray }
        if ($r.suppressedAfterModel) { Write-Host "      (the model wanted to speak; the local rules overruled it)" -ForegroundColor DarkGray }
        $results.Add([PSCustomObject]@{
            Group=$label; Scenario=$s.name; Spoke=$spoke; Reason=$r.reasonCode; Cost=$cost
            Ms=$r.latencyMillis; Confidence=$r.confidence; Relevance=$r.relevance
            Suppressed=$r.suppressedAfterModel; Said=$r.response
        })
    }
}

Write-Host "Stage 6 decision drill against $BaseUrl" -ForegroundColor Cyan
Write-Host "Silence is the expected outcome. SPOKE on ambient is the thing to judge." -ForegroundColor DarkGray

Run-Group "FREE — refused by local rules, no model call" $free $false
Run-Group "AMBIENT — uninvited, the real test" $ambient (-not $SkipAmbientWaits)

# An ambient call immediately after the last one: the interval should refuse it for nothing.
Write-Host ""
Write-Host "IMMEDIATE REPEAT — should be refused free as DECIDED_RECENTLY" -ForegroundColor Cyan
$r = Invoke-Decide @{ trigger = "AMBIENT"; transcript = $chitchat; mode = "ACTIVE" }
if ($r.PSObject.Properties.Name -contains "error") {
    Write-Host ("  [FAIL] {0}" -f $r.error) -ForegroundColor Red
} else {
    if ($r.gatedLocally) { $cost = "free" } else { $cost = "model" }
    if ($r.reasonCode -eq "DECIDED_RECENTLY" -and $r.gatedLocally) { $col = "Green" } else { $col = "Red" }
    Write-Host ("  {0,-26} {1,-7} {2,-24} {3}" -f "repeat immediately", "silent", $r.reasonCode, $cost) -ForegroundColor $col
    $results.Add([PSCustomObject]@{ Group="IMMEDIATE REPEAT"; Scenario="repeat immediately"; Spoke="silent"; Reason=$r.reasonCode; Cost=$cost; Ms=$r.latencyMillis })
}

Run-Group "INVITED — bypasses the interval and budget" $invited $false

# --- Summary ------------------------------------------------------------------------

$amb = $results | Where-Object { $_.Group -like "AMBIENT*" }
$spokeCount = @($amb | Where-Object { $_.Spoke -eq "SPOKE" }).Count
$modelCalls = @($results | Where-Object { $_.Cost -eq "model" }).Count
$freeRefusals = @($results | Where-Object { $_.Cost -eq "free" }).Count

Write-Host ""
Write-Host "Summary" -ForegroundColor Cyan
Write-Host ("  ambient scenarios : {0}" -f @($amb).Count)
Write-Host ("  ambient spoke     : {0}   <- the number that matters" -f $spokeCount)
Write-Host ("  model calls       : {0}" -f $modelCalls)
Write-Host ("  free refusals     : {0}" -f $freeRefusals)
Write-Host ""
Write-Host "  Check GET /usage for what the model calls cost." -ForegroundColor DarkGray
Write-Host "  Judge the SPOKE lines yourself: was any of it worth hearing?" -ForegroundColor DarkGray

if ($OutFile) {
    $results | Export-Csv -NoTypeInformation -Path $OutFile
    Write-Host ("  Saved {0} rows to {1}" -f $results.Count, $OutFile) -ForegroundColor DarkGray
}
