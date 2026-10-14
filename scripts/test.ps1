# Runs all unit + integration tests against the REAL MySQL test database.   .\scripts\test.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $PSScriptRoot "set-env.ps1"
if (Test-Path $envFile) { . $envFile }

foreach ($name in @("DB_PASSWORD")) {
    if (-not (Get-Item "Env:$name" -ErrorAction SilentlyContinue)) {
        Write-Host "Missing environment variable $name. See scripts\set-env.example.ps1" -ForegroundColor Red
        exit 1
    }
}
mvn clean test
