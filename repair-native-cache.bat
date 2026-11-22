@echo off
setlocal
rem Run elevated from the same Windows account that runs the game.
rem --check shows the current ACL state without changing the filesystem.
set "JME_REPAIR_MODE=%~1"
set "JME_REPAIR_SETUP=%~dp0setup-native-cache.bat"
powershell.exe -NoLogo -NoProfile -NonInteractive -Command ^
    "$ErrorActionPreference = 'Stop'; $path = 'C:\JmeNativeCache'; $backup = $null; try {" ^
    "if ($env:JME_REPAIR_MODE -eq '--check') {" ^
    "  if (Test-Path -LiteralPath $path) { Write-Host ('Cache: ' + $path); [IO.Directory]::GetAccessControl($path) | Format-List Owner,AreAccessRulesProtected } else { Write-Host ('Cache does not exist: ' + $path) }; exit 0 };" ^
    "if ($env:JME_REPAIR_MODE) { throw 'Usage: repair-native-cache.bat [--check]' };" ^
    "$identity = [Security.Principal.WindowsIdentity]::GetCurrent();" ^
    "$principal = [Security.Principal.WindowsPrincipal]::new($identity);" ^
    "if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script as administrator from the Windows account that runs the game.' };" ^
    "if (-not (Test-Path -LiteralPath $env:JME_REPAIR_SETUP -PathType Leaf)) { throw 'setup-native-cache.bat is missing from this directory.' };" ^
    "if (Test-Path -LiteralPath $path) {" ^
    "  $item = Get-Item -LiteralPath $path -Force;" ^
    "  if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw ('Expected an ordinary directory: ' + $path) };" ^
    "  $candidate = $path + '.old-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff');" ^
    "  Rename-Item -LiteralPath $path -NewName ([IO.Path]::GetFileName($candidate));" ^
    "  $backup = $candidate; Write-Host ('Previous cache kept at ' + $backup) };" ^
    "& $env:JME_REPAIR_SETUP; if ($LASTEXITCODE -ne 0) { throw 'Private cache creation failed.' };" ^
    "if (-not ([IO.Directory]::GetAccessControl($path)).AreAccessRulesProtected) { throw 'New cache still inherits permissions.' };" ^
    "Write-Host 'Private cache created. You can now run the game without elevation.';" ^
    "} catch {" ^
    "  [Console]::Error.WriteLine($_.Exception.Message);" ^
    "  if ($backup -and -not (Test-Path -LiteralPath $path)) {" ^
    "    try { Rename-Item -LiteralPath $backup -NewName ([IO.Path]::GetFileName($path)); Write-Host 'Previous cache restored.' }" ^
    "    catch { [Console]::Error.WriteLine('Previous cache kept at ' + $backup + ': ' + $_.Exception.Message) } };" ^
    "  exit 1 }"
exit /b %errorlevel%
