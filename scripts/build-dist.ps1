<#
.SYNOPSIS
    Construye el distribuible: dist\LOCALIS-<version>.zip con jar, plantillas, instalador y runtime Java incluido.
.PARAMETER JdkMajor
    Version mayor del JDK a incluir (por defecto 25, LTS).
.PARAMETER NoBundleJdk
    No incluye el runtime; el instalador lo descargara en el cliente (requiere internet alli).
.PARAMETER SkipTests
    Omite los tests de Maven.
#>
[CmdletBinding()]
param([int]$JdkMajor = 25, [switch]$NoBundleJdk, [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Resuelve JDK y Maven locales si no estan en el PATH.
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:ProgramFiles\Microsoft" -Directory -Filter "jdk-$JdkMajor*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) { throw "No se encontro JDK $JdkMajor para compilar" }
    $env:JAVA_HOME = $jdk.FullName
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
    $m = Get-ChildItem "$env:USERPROFILE\.maven" -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $m) { throw 'Maven no encontrado' }
    $env:Path = "$($m.FullName)\bin;$env:Path"
}

# Frontend (React): necesita Node 20+ solo en la maquina de build; el resultado va dentro del jar.
function Get-NodeMajor { $v = (& node.exe -v 2>$null); if ($v -match '^v(\d+)') { [int]$Matches[1] } else { 0 } }
if ((Get-Command node.exe -ErrorAction SilentlyContinue) -eq $null -or (Get-NodeMajor) -lt 20) {
    if (Test-Path "$env:ProgramFiles\nodejs\node.exe") { $env:Path = "$env:ProgramFiles\nodejs;$env:Path" }
}
if ((Get-NodeMajor) -lt 20) { throw 'Se necesita Node.js 20+ para compilar el frontend (winget install OpenJS.NodeJS.LTS)' }
Push-Location (Join-Path $root 'frontend')
$prevEap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # npm escribe avisos por stderr
try {
    if (-not (Test-Path node_modules)) { Write-Host '==> npm ci' -ForegroundColor Cyan; & npm.cmd ci 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'npm ci fallo' } }
    if (-not $SkipTests) { & npm.cmd test 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'Fallaron los tests del frontend' } }
    Write-Host '==> npm run build (frontend)' -ForegroundColor Cyan
    & npm.cmd run build 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion del frontend' }
} finally { $ErrorActionPreference = $prevEap; Pop-Location }

[xml]$pom = Get-Content pom.xml
$version = ($pom.project.version -replace '-SNAPSHOT', '')
$mvnArgs = @('-B', '-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Write-Host "==> mvn $($mvnArgs -join ' ')" -ForegroundColor Cyan
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # Maven escribe avisos por stderr
& mvn.cmd @mvnArgs 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^WARNING' } | Write-Host
$mvnExit = $LASTEXITCODE; $ErrorActionPreference = $prev
if ($mvnExit -ne 0 -or -not (Test-Path target\localis.jar)) { throw "Fallo la compilacion de Maven (codigo $mvnExit)" }

$name = "LOCALIS-$version"
$stage = Join-Path $root "dist\$name"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
$payload = Join-Path $stage 'payload'
New-Item -ItemType Directory -Force (Join-Path $payload 'runtime') | Out-Null

Copy-Item target\localis.jar $payload
Copy-Item templates (Join-Path $payload 'templates') -Recurse
Copy-Item localis.properties.example $payload
Copy-Item scripts\installer\* $stage

if (-not $NoBundleJdk) {
    $cache = Join-Path $root 'dist\cache'
    New-Item -ItemType Directory -Force $cache | Out-Null
    $zip = Join-Path $cache "microsoft-jdk-$JdkMajor-windows-x64.zip"
    $url = "https://aka.ms/download-jdk/microsoft-jdk-$JdkMajor-windows-x64.zip"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Host "==> Obteniendo runtime JDK $JdkMajor" -ForegroundColor Cyan
    Invoke-WebRequest "$url.sha256sum.txt" -OutFile "$zip.sha256sum.txt" -UseBasicParsing
    $expected = ((Get-Content "$zip.sha256sum.txt" -Raw).Trim() -split '\s+')[0].ToUpperInvariant()
    if (-not (Test-Path $zip) -or (Get-FileHash $zip -Algorithm SHA256).Hash -ne $expected) {
        Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
    }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $expected) { throw 'Hash SHA-256 del JDK incorrecto' }
    Copy-Item $zip, "$zip.sha256sum.txt" (Join-Path $payload 'runtime')
}

$out = Join-Path $root "dist\$name.zip"
if (Test-Path $out) { Remove-Item $out -Force }
Compress-Archive -Path $stage -DestinationPath $out
Write-Host "Distribuible listo: $out ($([math]::Round((Get-Item $out).Length / 1MB)) MB)" -ForegroundColor Green
