@echo off
REM LocalSync Windows Installer Build Wrapper
REM Runs the PowerShell build script.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_installer.ps1"
pause
