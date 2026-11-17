@echo off
setlocal
set "SDK_MODE=sdk"
if /i "%~1"=="check" set "SDK_MODE=check"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-local-sdk.ps1" -Mode "%SDK_MODE%"
set "SDK_EXIT=%ERRORLEVEL%"
if not defined SDK_NO_PAUSE pause
exit /b %SDK_EXIT%
