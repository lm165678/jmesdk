@echo off
setlocal
set "SDK_MODE=build"
if not "%~1"=="" set "SDK_MODE=%~1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-local-sdk.ps1" -Mode "%SDK_MODE%"
set "SDK_EXIT=%ERRORLEVEL%"
if not defined SDK_NO_PAUSE pause
exit /b %SDK_EXIT%
