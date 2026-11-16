@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 goto failed
set "JAVA_HOME=C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "SDK_PROXY_HOST=127.0.0.1"
set "SDK_PROXY_PORT=7897"
set "SDK_PROXY_ARGS=-Dhttp.proxyHost=%SDK_PROXY_HOST% -Dhttp.proxyPort=%SDK_PROXY_PORT% -Dhttps.proxyHost=%SDK_PROXY_HOST% -Dhttps.proxyPort=%SDK_PROXY_PORT%"
set "GRADLE_OPTS=%GRADLE_OPTS% %SDK_PROXY_ARGS%"
set "ENGINE_DIR=%~dp0..\JMESources"
set "ENGINE_REPO=%ENGINE_DIR%\build\sdk-engine-repo"
if not exist "%JAVA_HOME%\bin\java.exe" goto failed
if not exist "%ENGINE_DIR%\gradlew.bat" goto failed
rem Close the SDK before rebuilding its loaded libraries.
echo Exporting local engine binaries and sources...
call "%ENGINE_DIR%\gradlew.bat" -p "%ENGINE_DIR%" -I "%~dp0gradle\local-engine-export.gradle" "-PsdkEngineRepository=%ENGINE_REPO%" -PjmeVersionName=3.10.0-local-SNAPSHOT %SDK_PROXY_ARGS% exportSdkEngine
if errorlevel 1 goto failed
echo Building SDK against the local engine...
set "SDK_TASK=buildSdk"
if /i "%~1"=="run" set "SDK_TASK=run"
call "%~dp0gradlew.bat" %SDK_PROXY_ARGS% -PuseLocalEngine=true "-PlocalEngineRepository=%ENGINE_REPO%" %SDK_TASK%
if errorlevel 1 goto failed
echo Local engine and SDK build completed.
if not defined SDK_NO_PAUSE pause
endlocal
exit /b 0
:failed
echo Build failed. Review the output above.
if not defined SDK_NO_PAUSE pause
endlocal
exit /b 1
