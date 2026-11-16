@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 goto failed

rem Run existing build only. Uses the same profile as build-and-run-sdk.bat.
rem Override SDK_JAVA_HOME to use another compatible JDK (25 or later).
if not defined SDK_JAVA_HOME set "SDK_JAVA_HOME=C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
set "SDK_LAUNCHER=%~dp0netbeans\platform\lib\nbexec64.exe"
set "SDK_USERDIR=%~dp0build\testuserdir"
set "SDK_CLUSTERS=%~dp0build\cluster;%~dp0netbeans\extide;%~dp0netbeans\groovy;%~dp0netbeans\harness;%~dp0netbeans\ide;%~dp0netbeans\java;%~dp0netbeans\nb;%~dp0netbeans\platform"

if not exist "%SDK_JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK not found at "%SDK_JAVA_HOME%". Set SDK_JAVA_HOME first.
    goto failed
)
if not exist "%SDK_LAUNCHER%" (
    echo ERROR: SDK platform launcher is missing. Build the SDK first.
    goto failed
)
if not exist "%~dp0build\cluster\modules\com-jme3-gde-core.jar" (
    echo ERROR: Compiled SDK is missing. Run build-and-run-sdk.bat first.
    goto failed
)
for %%C in (extide groovy harness ide java nb platform) do (
    if not exist "%~dp0netbeans\%%C\" (
        echo ERROR: Missing NetBeans cluster %%C. Build the SDK first.
        goto failed
    )
)

rem JVM access flags match nbproject/project.properties run.args.extra.
set "SDK_RUN_ARGS=-J-Dsun.java2d.noddraw=true -J--add-opens=java.base/java.net=ALL-UNNAMED -J--add-exports=java.desktop/sun.awt=ALL-UNNAMED -J--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED -J--add-opens=java.desktop/javax.swing=ALL-UNNAMED -J--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED -J--add-opens=java.base/java.security=ALL-UNNAMED -J--add-exports=java.base/sun.reflect.annotation=ALL-UNNAMED -J--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED -J--add-opens=java.desktop/java.awt=ALL-UNNAMED -J--add-opens=java.desktop/java.awt.event=ALL-UNNAMED -J--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED -J--add-opens=java.desktop/javax.swing.plaf.synth=ALL-UNNAMED -J--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED -J--add-opens=java.desktop/sun.awt.shell=ALL-UNNAMED -J--add-opens=java.desktop/sun.awt.im=ALL-UNNAMED -J--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED -J--add-exports=java.desktop/com.sun.beans.editors=ALL-UNNAMED -J--add-exports=java.desktop/sun.swing=ALL-UNNAMED -J--add-exports=java.desktop/sun.awt.im=ALL-UNNAMED -J--add-exports=java.desktop/com.sun.java.swing.plaf.motif=ALL-UNNAMED -J--add-exports=java.desktop/com.apple.eio=ALL-UNNAMED -J--add-opens=java.base/java.io=ALL-UNNAMED -J--add-opens=java.base/java.lang=ALL-UNNAMED -J--add-exports=jdk.internal.opt/jdk.internal.opt=ALL-UNNAMED"
if /i "%~1"=="--check" (
    echo SDK launcher and compiled core found.
    echo JDK: "%SDK_JAVA_HOME%"
    echo Profile: "%SDK_USERDIR%"
    echo No build or launch performed.
    exit /b 0
)

echo Starting compiled jMonkeyEngine SDK - no build...
"%SDK_LAUNCHER%" --jdkhome "%SDK_JAVA_HOME%" --userdir "%SDK_USERDIR%" --branding jmonkeyplatform --clusters "%SDK_CLUSTERS%" -J-Dnetbeans.logger.console=true -J-ea %SDK_RUN_ARGS% %*
if errorlevel 1 goto failed
endlocal
exit /b 0

:failed
echo.
echo SDK launch failed. Review the output above.
if not defined SDK_NO_PAUSE pause
endlocal
exit /b 1