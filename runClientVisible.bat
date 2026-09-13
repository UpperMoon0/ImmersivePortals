@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_client_visible.ps1"
exit /b %ERRORLEVEL%