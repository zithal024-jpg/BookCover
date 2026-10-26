@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Build.ps1"
if errorlevel 1 echo Build failed. Please share the message above with Codex.
pause
