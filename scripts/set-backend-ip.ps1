<#
.SYNOPSIS
  Points local.properties at this machine's current LAN address.

.DESCRIPTION
  OPERATOR_BACKEND_URL is compiled into the APK, and the phone cannot reach localhost, so it has
  to carry the laptop's LAN IP. DHCP moves that address - it changed three times in one evening
  during testing - and every move looks exactly like a broken app: "backend unreachable", with
  nothing to say the address is simply stale.

  This finds the current address and writes it in, so the failure becomes a command to run rather
  than something to diagnose.

  Rebuild afterwards. A Gradle sync is not enough: the value is baked in at compile time.

.EXAMPLE
  .\scripts\set-backend-ip.ps1
  .\scripts\set-backend-ip.ps1 -IPAddress 192.168.1.215
  .\scripts\set-backend-ip.ps1 -InterfaceAlias Ethernet
#>
# ASCII ONLY - Windows PowerShell 5.1 reads a .ps1 without a BOM as ANSI.
[CmdletBinding()]
param(
    [string]$IPAddress,
    [string]$InterfaceAlias = "Wi-Fi",
    [int]$Port = 8080,
    [string]$PropertiesFile
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (-not $PropertiesFile) { $PropertiesFile = Join-Path $repo "local.properties" }

if (-not (Test-Path $PropertiesFile)) {
    throw "No local.properties at $PropertiesFile. Copy local.properties.example to local.properties first."
}

if (-not $IPAddress) {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.InterfaceAlias -eq $InterfaceAlias -and $_.IPAddress -notlike "169.254.*" }
    if (-not $candidates) {
        $others = (Get-NetIPAddress -AddressFamily IPv4 |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -ExpandProperty InterfaceAlias -Unique) -join ", "
        throw "No IPv4 address on '$InterfaceAlias'. Try -InterfaceAlias with one of: $others"
    }
    $IPAddress = @($candidates)[0].IPAddress
}

$url = "http://${IPAddress}:${Port}"
$lines = Get-Content $PropertiesFile
$existing = $lines | Where-Object { $_ -match '^OPERATOR_BACKEND_URL=' }

if ($existing) {
    $was = ($existing -split '=', 2)[1]
    $lines = $lines -replace '^OPERATOR_BACKEND_URL=.*', "OPERATOR_BACKEND_URL=$url"
} else {
    $was = "(absent)"
    $lines += "OPERATOR_BACKEND_URL=$url"
}
Set-Content -Path $PropertiesFile -Value $lines -Encoding ascii

if ($was -eq $url) {
    Write-Host "Unchanged: $url" -ForegroundColor Green
    Write-Host "  If the phone still cannot reach it, the address is not the problem." -ForegroundColor DarkGray
} else {
    Write-Host "Was:  $was"
    Write-Host "Now:  $url" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Rebuild in Android Studio (Run). A sync alone will not do it - the URL is" -ForegroundColor DarkGray
    Write-Host "  compiled into the APK." -ForegroundColor DarkGray
}
