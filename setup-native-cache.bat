@echo off
setlocal

rem Run as administrator from the same Windows account that runs the game.
rem Create a new cache with a private ACL from the start. Leave existing paths alone.
set "JME_NATIVE_CACHE=C:\JmeNativeCache"
powershell.exe -NoLogo -NoProfile -NonInteractive -Command ^
    "$ErrorActionPreference = 'Stop'; try {" ^
    "$identity = [Security.Principal.WindowsIdentity]::GetCurrent();" ^
    "$principal = [Security.Principal.WindowsPrincipal]::new($identity);" ^
    "if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script as administrator from the Windows account that runs the game.' };" ^
    "$path = $env:JME_NATIVE_CACHE;" ^
    "if (Test-Path -LiteralPath $path) { throw ('Path already exists: ' + $path) };" ^
    "$acl = [Security.AccessControl.DirectorySecurity]::new();" ^
    "$acl.SetOwner($identity.User); $acl.SetAccessRuleProtection($true, $false);" ^
    "foreach ($sid in @($identity.User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {" ^
    "$rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow');" ^
    "$acl.AddAccessRule($rule);" ^
    "}; [void][IO.Directory]::CreateDirectory($path, $acl);" ^
    "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }"
if errorlevel 1 exit /b 1

echo Add this to the game's Run VM Options:
echo -Dcom.jme3.NativeLibraryExtractionFolder=%JME_NATIVE_CACHE%
exit /b 0
