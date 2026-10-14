# Starts the application. Run from the project root:   .\scripts\run.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $PSScriptRoot "set-env.ps1"
if (Test-Path $envFile) { . $envFile }

foreach ($name in @("DB_PASSWORD", "JWT_SECRET")) {
    if (-not (Get-Item "Env:$name" -ErrorAction SilentlyContinue)) {
        Write-Host "Missing environment variable $name. Copy scripts\set-env.example.ps1 to scripts\set-env.ps1, edit it, then run again." -ForegroundColor Red
        exit 1
    }
}
mvn spring-boot:run
