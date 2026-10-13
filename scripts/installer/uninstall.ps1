<#
.SYNOPSIS
    Desinstala LOCALIS y revierte los cambios de PATH/JAVA_HOME hechos por el instalador.
.PARAMETER RemoveData
    Borra tambien la configuracion, el indice y la licencia.
#>
[CmdletBinding()]
param([switch]$RemoveData)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$recFile = Join-Path $PSScriptRoot 'install.json'
if (-not (Test-Path $recFile)) { throw "No se encuentra $recFile" }
$rec = Get-Content $recFile -Raw | ConvertFrom-Json

if ($rec.scope -eq 'Machine' -and -not (Test-IsAdmin)) {
    Invoke-SelfElevated $MyInvocation.MyCommand.Path $PSBoundParameters
}

foreach ($e in @($rec.pathEntries)) { Remove-PathEntry $e $rec.scope }
if ($rec.javaHomeSet -and ([Environment]::GetEnvironmentVariable('JAVA_HOME', $rec.scope) -eq $rec.javaHome)) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $null, $rec.scope)
}
if ($RemoveData -and (Test-Path $rec.dataDir)) { Remove-Item $rec.dataDir -Recurse -Force }

# El script vive dentro de InstallDir: se borra desde un proceso aparte tras salir.
$cmd = "ping -n 3 127.0.0.1 >nul & rmdir /s /q `"$($rec.installDir)`""
Start-Process cmd.exe -ArgumentList '/c', $cmd -WindowStyle Hidden
Write-Host 'LOCALIS desinstalado.' -ForegroundColor Green
if (-not $RemoveData) { Write-Host "Se conservaron los datos en $($rec.dataDir) (use -RemoveData para borrarlos)." }
