param(
    [Parameter(Mandatory = $true)][long]$Seed,
    [string]$Pattern = "*RandomizedRaftTest*",
    [int]$Ticks = 1500
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

& .\gradlew.bat :flotilla-testing:test `
    --tests $Pattern `
    "-Dflotilla.sim.seed=$Seed" `
    "-Dflotilla.sim.ticks=$Ticks" `
    --rerun-tasks
