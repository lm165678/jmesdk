@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 goto failed

set "JAVA_HOME=C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK not found at "%JAVA_HOME%".
    goto failed
)
if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found in "%CD%".
    goto failed
)

rem Local HTTP proxy. Change these values if your proxy port changes.
set "SDK_PROXY_HOST=127.0.0.1"
set "SDK_PROXY_PORT=7897"
rem GRADLE_OPTS also covers the initial Gradle Wrapper download.
set "SDK_PROXY_ARGS=-Dhttp.proxyHost=%SDK_PROXY_HOST% -Dhttp.proxyPort=%SDK_PROXY_PORT% -Dhttps.proxyHost=%SDK_PROXY_HOST% -Dhttps.proxyPort=%SDK_PROXY_PORT%"
set "GRADLE_OPTS=%GRADLE_OPTS% %SDK_PROXY_ARGS%"

rem The run task depends on buildSdk: build first, then launch the SDK.
echo Building and starting jMonkeyEngine SDK...
call "%~dp0gradlew.bat" %SDK_PROXY_ARGS% run
if errorlevel 1 goto failed

endlocal
exit /b 0

:failed
echo.
echo SDK build or launch failed. Review the output above.
pause
endlocal
exit /b 1
