[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$requiredVariables = @(
    'SQLSERVER_HOST',
    'SQLSERVER_PORT',
    'SQLSERVER_USER',
    'SQLSERVER_PASSWORD',
    'SQLSERVER_DB'
)

foreach ($variableName in $requiredVariables) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variableName))) {
        throw "Required environment variable '$variableName' is not set."
    }
}

if ($env:SQLSERVER_PORT -notmatch '^\d{1,5}$' -or [int]$env:SQLSERVER_PORT -lt 1 -or [int]$env:SQLSERVER_PORT -gt 65535) {
    throw 'SQLSERVER_PORT must be a number from 1 to 65535.'
}

if ($env:SQLSERVER_DB -notmatch '^[A-Za-z0-9_-]+$') {
    throw 'SQLSERVER_DB may contain only letters, numbers, underscores, and hyphens.'
}

$sqlcmd = Get-Command sqlcmd -ErrorAction SilentlyContinue
if (-not $sqlcmd) {
    throw 'sqlcmd is required. Install the Microsoft SQL Server command-line tools and retry.'
}

$server = "$($env:SQLSERVER_HOST),$($env:SQLSERVER_PORT)"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$schemaScript = Join-Path $scriptDirectory '001_mvp_schema.sql'
$seedScript = Join-Path $scriptDirectory '002_seed.sql'
$databaseName = $env:SQLSERVER_DB
$escapedDatabaseName = $databaseName.Replace(']', ']]')
$escapedDatabaseLiteral = $databaseName.Replace("'", "''")
$previousSqlcmdPassword = $env:SQLCMDPASSWORD

try {
    $env:SQLCMDPASSWORD = $env:SQLSERVER_PASSWORD

    & $sqlcmd.Source -S $server -U $env:SQLSERVER_USER -d master -b -Q "IF DB_ID(N'$escapedDatabaseLiteral') IS NULL EXEC(N'CREATE DATABASE [$escapedDatabaseName]');"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create or access the target database.' }

    & $sqlcmd.Source -S $server -U $env:SQLSERVER_USER -d $databaseName -b -i $schemaScript
    if ($LASTEXITCODE -ne 0) { throw 'Schema initialization failed.' }

    & $sqlcmd.Source -S $server -U $env:SQLSERVER_USER -d $databaseName -b -i $seedScript
    if ($LASTEXITCODE -ne 0) { throw 'Seed initialization failed.' }

    Write-Host "Metric Mint database '$databaseName' initialized successfully on '$server'."
}
finally {
    $env:SQLCMDPASSWORD = $previousSqlcmdPassword
}
