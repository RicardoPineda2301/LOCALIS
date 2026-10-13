# Funciones compartidas por install.ps1 y uninstall.ps1 (PowerShell 5.1+, solo ASCII).

function Test-IsAdmin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Relanza el script elevado (UAC) reenviando los parametros; termina el proceso actual con el codigo del hijo.
function Invoke-SelfElevated([string]$ScriptPath, [hashtable]$Bound) {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$ScriptPath`"")
    foreach ($k in $Bound.Keys) {
        $v = $Bound[$k]
        if ($v -is [System.Management.Automation.SwitchParameter]) { if ($v.IsPresent) { $argList += "-$k" } }
        else { $argList += "-$k"; $argList += "`"$v`"" }
    }
    $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $argList -Verb RunAs -Wait -PassThru
    exit $p.ExitCode
}

function Get-JavaMajor([string]$JavaHome) {
    if (-not $JavaHome) { return 0 }
    $java = Join-Path $JavaHome 'bin\java.exe'
    $rel = Join-Path $JavaHome 'release'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $rel)) { return 0 }
    $line = Get-Content -LiteralPath $rel | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
    if ($line -match 'JAVA_VERSION="?(\d+)(?:\.(\d+))?') {
        if ($Matches[1] -eq '1') { return [int]$Matches[2] }
        return [int]$Matches[1]
    }
    return 0
}

# Busca un JDK/JRE con version >= $Min en JAVA_HOME (proceso/maquina/usuario) y en el PATH.
function Find-Java([int]$Min) {
    $cands = @($env:JAVA_HOME,
        [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'),
        [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'))
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $cands += (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }
    foreach ($c in ($cands | Where-Object { $_ } | Select-Object -Unique)) {
        if ((Get-JavaMajor $c) -ge $Min) { return $c }
    }
    return $null
}

function ConvertTo-NormalPath([string]$p) { return $p.Trim().TrimEnd('\').ToLowerInvariant() }

function Add-PathEntry([string]$Dir, [string]$Scope) {
    $cur = [Environment]::GetEnvironmentVariable('Path', $Scope)
    $parts = @(); if ($cur) { $parts = $cur.Split(';') | Where-Object { $_ } }
    if (-not ($parts | Where-Object { (ConvertTo-NormalPath $_) -eq (ConvertTo-NormalPath $Dir) })) {
        [Environment]::SetEnvironmentVariable('Path', (($parts + $Dir) -join ';'), $Scope)
    }
    if (-not (($env:Path.Split(';')) | Where-Object { (ConvertTo-NormalPath $_) -eq (ConvertTo-NormalPath $Dir) })) {
        $env:Path = "$env:Path;$Dir"
    }
}

function Remove-PathEntry([string]$Dir, [string]$Scope) {
    $cur = [Environment]::GetEnvironmentVariable('Path', $Scope)
    if (-not $cur) { return }
    $keep = $cur.Split(';') | Where-Object { $_ -and (ConvertTo-NormalPath $_) -ne (ConvertTo-NormalPath $Dir) }
    [Environment]::SetEnvironmentVariable('Path', ($keep -join ';'), $Scope)
}
