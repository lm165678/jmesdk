param(
    [string]$EngineRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'jmonkeyengine'),
    [string]$JdkHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $JdkHome = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
}
$outDir = Join-Path $EngineRoot 'build/probe-preview-check'
$classes = Join-Path $outDir 'classes'
New-Item $classes -ItemType Directory -Force | Out-Null
$jars = @(Get-ChildItem "$sdkRoot/build/cluster", "$sdkRoot/netbeans" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc|test' } |
    ForEach-Object { $_.FullName })
$classpath = (@($classes, "$EngineRoot/jme3-core/build/classes/java/main",
    "$EngineRoot/jme3-core/src/main/resources", "$EngineRoot/jme3-desktop/build/classes/java/main",
    "$sdkRoot/jme3-core/src") + $jars) -join ';'
$sources = @(
    'jme3-core/src/com/jme3/gde/core/scene/GiPreview.java',
    'jme3-core/src/com/jme3/gde/core/scene/ProbeLightmapAssets.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/EditorGiStreamingPanel.java',
    'jme3-core/src/com/jme3/gde/core/scene/SceneApplication.java',
    'jme3-core/src/com/jme3/gde/core/sceneviewer/SceneViewerTopComponent.java',
    'jme3-core/src/com/jme3/gde/core/sceneviewer/ProbeDisplayMenu.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/gizmo/GiProbeGizmo.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/gizmo/GiProbeMarkers.java',
    'tools/tests/ProbeDisplaySmoke.java'
)
$compileArgs = @('-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + $classpath + '"'), '-d', ('"' + $classes + '"')) +
    @($sources | ForEach-Object { '"' + (Join-Path $sdkRoot $_) + '"' })
$compileFile = Join-Path $outDir 'compile.args'
[IO.File]::WriteAllLines($compileFile, @($compileArgs | ForEach-Object { $_.Replace('\', '/') }))
& "$JdkHome/bin/javac.exe" "@$compileFile"
if ($LASTEXITCODE -ne 0) { throw 'Probe display compilation failed' }
$runArgs = @('--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '-cp', ('"' + $classpath + '"'),
    'com.jme3.gde.scenecomposer.gizmo.ProbeDisplaySmoke')
$runFile = Join-Path $outDir 'run.args'
[IO.File]::WriteAllLines($runFile, @($runArgs | ForEach-Object { $_.Replace('\', '/') }))
& "$JdkHome/bin/java.exe" "@$runFile"
if ($LASTEXITCODE -ne 0) { throw 'Probe display verification failed' }
