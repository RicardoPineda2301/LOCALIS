<#
.SYNOPSIS
    Instala LOCALIS en este equipo: runtime Java (si falta), aplicacion, configuracion, comando 'localis' y PATH.
.PARAMETER Scope
    Machine (por defecto, requiere administrador; se auto-eleva) o User (sin administrador).
.PARAMETER MinJava
    Version minima de Java aceptada. Si el equipo ya tiene una, se reutiliza; si no, se instala el runtime incluido.
.PARAMETER ForceJavaHome
    Sobrescribe JAVA_HOME aunque ya apunte a otro Java valido (por defecto solo se define si falta o es invalido).
.PARAMETER IgnoreExistingJava
    Instala siempre el runtime incluido, aunque ya exista un Java valido.
.PARAMETER SkipPath
    No modifica variables de entorno (PATH/JAVA_HOME).
#>
[CmdletBinding()]
param(
    [ValidateSet('Machine', 'User')][string]$Scope = 'Machine',
    [string]$InstallDir,
    [string]$DataDir,
    [int]$MinJava = 25,
    [switch]$ForceJavaHome,
    [switch]$IgnoreExistingJava,
    [switch]$SkipPath
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if ($Scope -eq 'Machine' -and -not (Test-IsAdmin)) {
    Write-Host 'Se necesitan permisos de administrador; solicitando elevacion...'
    Invoke-SelfElevated $MyInvocation.MyCommand.Path $PSBoundParameters
}

if (-not $InstallDir) {
    $InstallDir = if ($Scope -eq 'Machine') { Join-Path $env:ProgramFiles 'LOCALIS' } else { Join-Path $env:LOCALAPPDATA 'Programs\LOCALIS' }
}
if (-not $DataDir) {
    $DataDir = if ($Scope -eq 'Machine') { Join-Path $env:ProgramData 'LOCALIS' } else { Join-Path $env:LOCALAPPDATA 'LOCALIS' }
}
$payload = Join-Path $PSScriptRoot 'payload'
if (-not (Test-Path (Join-Path $payload 'localis.jar'))) { throw "Paquete incompleto: falta $payload\localis.jar" }

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

try {
    Step "Instalando LOCALIS en $InstallDir (ambito: $Scope)"
    New-Item -ItemType Directory -Force $InstallDir, (Join-Path $InstallDir 'bin'), $DataDir | Out-Null

    # --- Java ---
    $javaHome = if ($IgnoreExistingJava) { $null } else { Find-Java $MinJava }
    $installedRuntime = $false
    if ($javaHome) {
        Step "Java $(Get-JavaMajor $javaHome) encontrado en $javaHome; se reutiliza"
    } else {
        Step "No hay Java >= $MinJava; instalando el runtime incluido"
        $zip = Get-ChildItem (Join-Path $payload 'runtime') -Filter '*.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ('localis-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force $tmp | Out-Null
        if ($zip) { $zipPath = $zip.FullName }
        else {
            Step 'Runtime no incluido en el paquete; descargando Microsoft OpenJDK (requiere internet)'
            $zipPath = Join-Path $tmp 'jdk.zip'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest "https://aka.ms/download-jdk/microsoft-jdk-$MinJava-windows-x64.zip" -OutFile $zipPath -UseBasicParsing
        }
        $sumFile = "$zipPath.sha256sum.txt"
        if (Test-Path $sumFile) {
            $expected = ((Get-Content $sumFile -Raw).Trim() -split '\s+')[0]
            if ((Get-FileHash $zipPath -Algorithm SHA256).Hash -ne $expected.ToUpperInvariant()) { throw 'El hash SHA-256 del runtime no coincide' }
        }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $x = Join-Path $tmp 'x'
        [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $x)
        $inner = Get-ChildItem $x -Directory | Select-Object -First 1
        $target = Join-Path $InstallDir 'runtime\jdk'
        if (Test-Path $target) { Remove-Item $target -Recurse -Force }
        New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
        Move-Item $inner.FullName $target
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        $javaHome = $target
        $installedRuntime = $true
        if ((Get-JavaMajor $javaHome) -lt $MinJava) { throw "El runtime instalado no es Java $MinJava+" }
    }

    # --- Aplicacion ---
    Step 'Copiando aplicacion y plantillas'
    Copy-Item (Join-Path $payload 'localis.jar') $InstallDir -Force
    $tplTarget = Join-Path $InstallDir 'templates'
    if (Test-Path $tplTarget) { Remove-Item $tplTarget -Recurse -Force }
    Copy-Item (Join-Path $payload 'templates') $tplTarget -Recurse -Force
    Copy-Item (Join-Path $payload 'localis.properties.example') $DataDir -Force
    Copy-Item (Join-Path $PSScriptRoot 'uninstall.ps1'), (Join-Path $PSScriptRoot 'common.ps1') $InstallDir -Force

    # Launcher: fija el Java elegido, asi no depende de JAVA_HOME/PATH del cliente.
    $launcher = @"
@echo off
setlocal
set "JAVA_HOME=$javaHome"
cd /d "$DataDir"
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -Dlocalis.config="$DataDir\localis.properties" -jar "$InstallDir\localis.jar" %*
exit /b %errorlevel%
"@
    Set-Content -LiteralPath (Join-Path $InstallDir 'bin\localis.cmd') -Value $launcher -Encoding ASCII

    # --- Configuracion (no se pisa en actualizaciones) ---
    $cfg = Join-Path $DataDir 'localis.properties'
    if (-not (Test-Path $cfg)) {
        $f = { param($p) $p.Replace('\', '/') }   # en .properties la barra invertida es un escape
        $text = (Get-Content (Join-Path $payload 'localis.properties.example')) | ForEach-Object {
            if ($_ -match '^data\.dir=') { 'data.dir=' + (& $f (Join-Path $DataDir 'data')) }
            elseif ($_ -match '^templates\.dir=') { 'templates.dir=' + (& $f $tplTarget) }
            elseif ($_ -match '^license\.file=') { 'license.file=' + (& $f (Join-Path $DataDir 'license.json')) }
            else { $_ }
        }
        Set-Content -LiteralPath $cfg -Value $text -Encoding ASCII
        Step "Configuracion creada en $cfg"
    } else { Step "Se conserva la configuracion existente en $cfg" }
    if ($Scope -eq 'Machine') {
        & icacls.exe $DataDir /grant '*S-1-5-32-545:(OI)(CI)M' | Out-Null   # Users: modificar datos y config
    }

    # --- Variables de entorno ---
    $record = [ordered]@{ scope = $Scope; installDir = $InstallDir; dataDir = $DataDir; javaHome = $javaHome
        pathEntries = @(); javaHomeSet = $false }
    if (-not $SkipPath) {
        Step "Actualizando variables de entorno ($Scope)"
        $bin = Join-Path $InstallDir 'bin'
        Add-PathEntry $bin $Scope; $record.pathEntries += $bin
        $curHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', $Scope)
        if ($installedRuntime -and ($ForceJavaHome -or -not $curHome -or (Get-JavaMajor $curHome) -eq 0)) {
            [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, $Scope)
            $env:JAVA_HOME = $javaHome
            $jb = Join-Path $javaHome 'bin'
            Add-PathEntry $jb $Scope; $record.pathEntries += $jb
            $record.javaHomeSet = $true
        } elseif ($installedRuntime) {
            Write-Warning "JAVA_HOME ya apunta a $curHome; no se modifica (use -ForceJavaHome). LOCALIS usa su propio runtime."
        }
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $InstallDir 'install.json') -Encoding ASCII

    # --- Motor de IA (Ollama) ---
    $ollama = Get-Command ollama.exe -ErrorAction SilentlyContinue
    $bundled = Join-Path $payload 'ollama\OllamaSetup.exe'
    if (-not $ollama -and (Test-Path $bundled)) {
        Step 'Instalando Ollama incluido en el paquete'
        Start-Process $bundled -ArgumentList '/SILENT' -Wait
    } elseif (-not $ollama) {
        Write-Warning 'Ollama no esta instalado. LOCALIS lo necesita para el modelo de IA local (https://ollama.com).'
        Write-Warning 'Modelos por defecto: ollama pull codellama:13b-instruct ; ollama pull nomic-embed-text'
    }

    Write-Host ''
    Write-Host 'LOCALIS instalado correctamente.' -ForegroundColor Green
    Write-Host "  Configuracion : $cfg  (defina repo.path y, para clientes, license.enforce=true)"
    Write-Host '  Ejecutar      : abra una terminal NUEVA y escriba: localis'
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
