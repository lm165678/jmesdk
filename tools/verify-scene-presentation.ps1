param(
    [string]$EngineRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'jmonkeyengine'),
    [string]$JdkHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $JdkHome = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
}
# Compile to an isolated directory. Do not build, install or start the SDK.
$outDir = Join-Path $EngineRoot 'build/scene-presentation-check'
$classes = Join-Path $outDir 'classes'
New-Item $classes -ItemType Directory -Force | Out-Null
$jars = @(Get-ChildItem "$sdkRoot/build/cluster", "$sdkRoot/netbeans" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc|jbullet|jme3-bullet' } |
    ForEach-Object { $_.FullName })
$classpath = (@($classes, "$EngineRoot/jme3-core/build/classes/java/main",
    "$EngineRoot/jme3-core/src/main/resources", "$EngineRoot/jme3-desktop/build/classes/java/main",
    "$sdkRoot/jme3-core/src") + $jars) -join ';'
$sources = @(
    "$EngineRoot/jme3-desktop/src/main/java/com/jme3/system/awt/AwtPanel.java",
    "$sdkRoot/jme3-core/src/com/jme3/gde/core/assets/AssetOpenProgress.java",
    "$sdkRoot/jme3-core/src/com/jme3/gde/core/scene/SceneOpenPresentation.java",
    "$sdkRoot/jme3-core/src/com/jme3/gde/core/scene/SceneApplication.java",
    "$sdkRoot/tools/tests/SceneOpenPresentationSmoke.java",
    "$sdkRoot/tools/tests/AssetOpenProgressSmoke.java"
)
$compileArgs = @('-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + $classpath + '"'),
    '-d', ('"' + $classes + '"')) + @($sources | ForEach-Object { '"' + $_ + '"' })
$compileFile = Join-Path $outDir 'compile.args'
[IO.File]::WriteAllLines($compileFile, @($compileArgs | ForEach-Object { $_.Replace('\', '/') }))
& "$JdkHome/bin/javac.exe" "@$compileFile"
if ($LASTEXITCODE -ne 0) { throw 'Scene presentation compilation failed' }
foreach ($test in @('com.jme3.gde.core.scene.SceneOpenPresentationSmoke',
    'com.jme3.gde.core.assets.AssetOpenProgressSmoke')) {
    $runArgs = @('--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
        '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/java.net=ALL-UNNAMED',
        ('-Dnetbeans.user=' + $outDir.Replace('\', '/') + '/netbeans-user'),
        '-cp', ('"' + $classpath.Replace('\', '/') + '"'), $test)
    $runFile = Join-Path $outDir 'verify.args'
    [IO.File]::WriteAllLines($runFile, $runArgs)
    & "$JdkHome/bin/java.exe" "@$runFile"
    if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
