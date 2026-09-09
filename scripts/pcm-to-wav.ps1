<#
.SYNOPSIS
  Wraps raw PCM from /tts/synthesize in a WAV header so it will play.

.DESCRIPTION
  The backend streams headerless signed 16-bit little-endian PCM, which is what AudioTrack wants
  on the phone and what nothing on a desktop will open. This adds the 44-byte RIFF header, and
  nothing else: the samples are copied through untouched.

.EXAMPLE
  .\scripts\pcm-to-wav.ps1 -Path test.pcm
  .\scripts\pcm-to-wav.ps1 -Path test.pcm -SampleRateHz 16000
#>
# ASCII ONLY. Windows PowerShell 5.1 reads a .ps1 without a BOM as ANSI, so a UTF-8 dash arrives
# as mojibake that can terminate the string it sits in. See scripts/check-secrets.sh's neighbours.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$OutFile,
    # The backend requests pcm_24000 from ElevenLabs (see OPERATOR_TTS_PROVIDER).
    [int]$SampleRateHz = 24000,
    [int]$Channels = 1
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Path)) { throw "No such file: $Path" }
$pcm = [System.IO.File]::ReadAllBytes((Resolve-Path $Path))
if ($pcm.Length -eq 0) { throw "$Path is empty - the synthesis call returned nothing." }

# A JSON error body is text, not audio. Catching it here is friendlier than a burst of noise.
if ($pcm.Length -lt 2048 -and [System.Text.Encoding]::ASCII.GetString($pcm, 0, [Math]::Min(1, $pcm.Length)) -eq "{") {
    throw "$Path looks like a JSON error, not audio. Open it in a text editor."
}

if (-not $OutFile) { $OutFile = [System.IO.Path]::ChangeExtension($Path, ".wav") }

$bitsPerSample = 16
$blockAlign    = $Channels * ($bitsPerSample / 8)
$byteRate      = $SampleRateHz * $blockAlign

$stream = [System.IO.File]::Create($OutFile)
$writer = New-Object System.IO.BinaryWriter($stream)
try {
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes("RIFF"))
    $writer.Write([int](36 + $pcm.Length))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes("WAVE"))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes("fmt "))
    $writer.Write([int]16)                       # PCM header size
    $writer.Write([int16]1)                      # format 1 = uncompressed PCM
    $writer.Write([int16]$Channels)
    $writer.Write([int]$SampleRateHz)
    $writer.Write([int]$byteRate)
    $writer.Write([int16]$blockAlign)
    $writer.Write([int16]$bitsPerSample)
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes("data"))
    $writer.Write([int]$pcm.Length)
    $writer.Write($pcm)
} finally {
    $writer.Dispose()
    $stream.Dispose()
}

$seconds = [Math]::Round($pcm.Length / $byteRate, 2)
Write-Host ("Wrote {0} - {1} seconds of {2} Hz mono audio." -f $OutFile, $seconds, $SampleRateHz)
Write-Host "Double-click it, or: Start-Process '$OutFile'"
