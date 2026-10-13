@echo off
rem Doble clic para instalar LOCALIS (pide permisos de administrador si hace falta).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
if errorlevel 1 pause
