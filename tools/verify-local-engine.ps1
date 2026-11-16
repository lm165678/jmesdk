param([switch]$OpenGL33)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
}
$classes = Join-Path $sdkRoot 'build/local-engine-smoke'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$entries = @(
    'jme3-core-baselibs/release/modules/ext/*',
    'jme3-core-libraries/release/modules/ext/*',
    'jme3-project-testdata/release/modules/ext/*',
    'jme3-core/build/classes',
    'jme3-materialeditor/build/classes',
    'netbeans/platform/lib/*',
    'netbeans/platform/modules/*'
)
$classpath = (($entries | ForEach-Object { Join-Path $sdkRoot $_ }) -join ';') + ';' + $classes
& "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $classpath -d $classes "$PSScriptRoot/tests/SdkPreviewSmoke.java"
if ($LASTEXITCODE -ne 0) {
    throw 'SDK smoke compilation failed. Build the SDK first.'
}
$profileArgs = @()
if ($OpenGL33) {
    $profileArgs += '-Djme.sdk.previewOpenGL=3.3'
}
& "$jdk/bin/java.exe" @profileArgs "-Djme.sdk.nativeDirectory=$sdkRoot/jme3-core-baselibs/release/native-libs" --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp $classpath SdkPreviewSmoke
if ($LASTEXITCODE -ne 0) {
    throw 'SDK preview smoke test failed.'
}
