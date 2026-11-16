param([string]$RenderDoc = 'E:/RenderDoc/qrenderdoc.exe',
      [string]$Jdk = 'C:/Users/JohnKkk/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2')
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
Push-Location $sdkRoot
try {
    $out = Join-Path $sdkRoot 'build/renderdoc-tests'
    New-Item -ItemType Directory -Force $out | Out-Null
    $entries = @('build/cluster/modules/*', 'jme3-core/build/classes',
        '../JMESources/jme3-core/build/classes/java/main', '../JMESources/jme3-desktop/build/classes/java/main',
        'jme3-core-baselibs/release/modules/ext/*', 'jme3-core-libraries/release/modules/ext/*',
        'netbeans/platform/core/*', 'netbeans/platform/lib/*', 'netbeans/platform/modules/*',
        'netbeans/platform/modules/ext/*', 'netbeans/ide/modules/*', 'netbeans/java/modules/*')
    $cp = $out + ';' + (($entries | ForEach-Object { Join-Path $sdkRoot $_ }) -join ';')
    & "$Jdk/bin/javac.exe" '-J-Duser.language=en' -encoding UTF-8 -proc:none -cp $cp -d $out `
        jme3-core/src/com/jme3/gde/core/renderdoc/*.java tools/tests/RenderDocSmoke.java
    if ($LASTEXITCODE -ne 0) { throw 'RenderDoc verification compilation failed' }
    New-Item -ItemType Directory -Force "$out/META-INF/services" | Out-Null
    [IO.File]::WriteAllText("$out/META-INF/services/org.openide.DialogDisplayer", 'com.jme3.gde.core.renderdoc.RenderDocSmoke$Dialogs')
    $runtime = @($out, "$sdkRoot/build/cluster/modules/com-jme3-gde-core.jar",
        "$sdkRoot/../JMESources/jme3-core/build/classes/java/main", "$sdkRoot/../JMESources/jme3-desktop/build/classes/java/main",
        "$sdkRoot/jme3-core-baselibs/release/modules/ext/*", "$sdkRoot/jme3-core-libraries/release/modules/ext/*",
        "$sdkRoot/netbeans/platform/core/org-openide-filesystems.jar", "$sdkRoot/netbeans/platform/lib/*",
        "$sdkRoot/netbeans/platform/modules/ext/jna-5.19.1.jar", "$sdkRoot/netbeans/platform/modules/org-netbeans-modules-options-api.jar")
    $runtime += Get-ChildItem "$sdkRoot/netbeans/platform/modules/org-openide-*.jar" | ForEach-Object { $_.FullName }
    $nativeOutput = & "$Jdk/bin/java.exe" --enable-native-access=ALL-UNNAMED "-Dnetbeans.user=$out/userdir" `
        "-Djme.sdk.nativeDirectory=$sdkRoot/jme3-core-baselibs/release/native-libs" `
        "-Djava.util.prefs.userRoot=$out/preferences" -cp ($runtime -join ';') com.jme3.gde.core.renderdoc.RenderDocSmoke $RenderDoc 2>&1
    $nativeExit = $LASTEXITCODE
    $nativeOutput | Tee-Object -FilePath "$out/verification.log" | Write-Output
    if ($nativeExit -ne 0 -or ($nativeOutput -join "`n") -match 'fatal error|EXCEPTION_INVALID_HANDLE') {
        throw 'RenderDoc native verification failed (including shutdown); inspect build/renderdoc-tests/verification.log'
    }
} finally {
    Pop-Location
}
