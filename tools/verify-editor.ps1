$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$classes = Join-Path $sdkRoot 'build/editor-smoke'
New-Item -ItemType Directory -Force $classes | Out-Null
$entries = @('jme3-core-baselibs/release/modules/ext/*', 'jme3-core-libraries/release/modules/ext/*', 'jme3-core/build/classes', 'jme3-scenecomposer/build/classes', 'netbeans/platform/lib/*', 'netbeans/platform/modules/*', 'netbeans/platform/modules/ext/*')
$classpath = (($entries | ForEach-Object { Join-Path $sdkRoot $_ }) -join ';') + ';' + $classes
& "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $classpath -d $classes "$PSScriptRoot/tests/SdkEditorSmoke.java"
if ($LASTEXITCODE -ne 0) { throw 'Editor smoke compilation failed.' }
& "$jdk/bin/java.exe" --enable-native-access=ALL-UNNAMED -cp $classpath com.jme3.gde.scenecomposer.SdkEditorSmoke
if ($LASTEXITCODE -ne 0) { throw 'Editor smoke failed.' }
