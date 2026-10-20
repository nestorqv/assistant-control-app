#Requires -Version 5.1
<#
.SYNOPSIS
  Creates the dev role (asistencia_app) and database (asistencia) in local PostgreSQL 16.
.DESCRIPTION
  Prompts for the app password, then runs create-dev-db.sql as the postgres superuser.
  psql itself prompts for the postgres password.
#>
param(
    [string]$PsqlPath = 'C:\Program Files\PostgreSQL\16\bin\psql.exe',
    [string]$DbHost = 'localhost',
    [int]$Port = 5432
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $PsqlPath)) {
    $found = Get-Command psql -ErrorAction SilentlyContinue
    if (-not $found) {
        throw "psql not found at '$PsqlPath' and not on PATH. Use -PsqlPath."
    }
    $PsqlPath = $found.Source
}

$secure = Read-Host -Prompt 'Password for the app role asistencia_app' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if ([string]::IsNullOrWhiteSpace($plain)) {
    throw 'The app password must not be empty.'
}

$sqlFile = Join-Path $PSScriptRoot 'create-dev-db.sql'

& $PsqlPath -U postgres -h $DbHost -p $Port -v ON_ERROR_STOP=1 -v "app_password=$plain" -f $sqlFile
if ($LASTEXITCODE -ne 0) {
    Write-Error "psql failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host 'Done. If the role already existed, its password was NOT changed.'
Write-Host 'Put these in backend/.env (and the password you chose as DB_PASSWORD):'
Write-Host "DB_URL=jdbc:postgresql://${DbHost}:${Port}/asistencia"
Write-Host 'DB_USER=asistencia_app'
