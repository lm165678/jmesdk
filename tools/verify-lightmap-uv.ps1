param(
    [string]$EngineRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'jmonkeyengine'),
    [string]$JdkHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $JdkHome = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
}
# Isolated compilation and diagnostics only; never builds or starts SDK.
$outDir = Join-Path $EngineRoot 'build/lightmap-uv-check'
$classes = Join-Path $outDir 'classes'
New-Item $classes -ItemType Directory -Force | Out-Null
$jars = @(Get-ChildItem "$sdkRoot/build/cluster", "$sdkRoot/netbeans" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc|jbullet|jme3-bullet' } |
    ForEach-Object { $_.FullName })
$classpath = (@($classes, "$EngineRoot/jme3-core/build/classes/java/main",
    "$EngineRoot/jme3-core/src/main/resources", "$EngineRoot/jme3-desktop/build/classes/java/main",
    "$sdkRoot/jme3-core/src") + $jars) -join ';'
$sources = @(
    'jme3-core/src/com/jme3/gde/core/assets/LightmapUvGenerator.java',
    'jme3-core/src/com/jme3/gde/core/assets/LightmapUvPacking.java',
    'jme3-core/src/com/jme3/gde/core/scene/ProbeLightmapAssets.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/ModelDetailTopComponent.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/LightmapUvView.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/LightmapUvEdits.java',
    'tools/tests/LightmapUvRegenerationSmoke.java'
)
$compileArgs = @('-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + $classpath + '"'),
    '-d', ('"' + $classes + '"')) + @($sources | ForEach-Object { '"' + (Join-Path $sdkRoot $_) + '"' })
$compileFile = Join-Path $outDir 'verify-compile.args'
[IO.File]::WriteAllLines($compileFile, @($compileArgs | ForEach-Object { $_.Replace('\', '/') }))
& "$JdkHome/bin/javac.exe" "@$compileFile"
if ($LASTEXITCODE -ne 0) { throw 'Lightmap UV compilation failed' }
$osTag = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'windows' } elseif ($IsMacOS) { 'macos' } else { 'linux' }
$cpu = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString().ToLowerInvariant()
$arch = if ($cpu -eq 'x64') { 'x86_64' } elseif ($cpu -eq 'arm64') { 'arm64' } else { throw "Unsupported architecture: $cpu" }
$libraryName = if ($osTag -eq 'windows') { 'jme-xatlas.dll' } elseif ($osTag -eq 'macos') { 'libjme-xatlas.dylib' } else { 'libjme-xatlas.so' }
$native = Join-Path $sdkRoot "native-bundles/$osTag-$arch/$libraryName"
if (-not (Test-Path -LiteralPath $native)) { throw "Missing bundled xatlas: $native" }
$runArgs = @('--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/java.net=ALL-UNNAMED',
    ('-Dnetbeans.user=' + $outDir.Replace('\', '/') + '/netbeans-user'),
    ('-Djme.sdk.xatlas.library=' + $native.Replace('\', '/')),
    '-cp', ('"' + $classpath.Replace('\', '/') + '"'),
    'com.jme3.gde.scenecomposer.LightmapUvRegenerationSmoke', ('"' + $outDir.Replace('\', '/') + '/artifacts"'))
$runFile = Join-Path $outDir 'verify-run.args'
[IO.File]::WriteAllLines($runFile, $runArgs)
& "$JdkHome/bin/java.exe" "@$runFile"
if ($LASTEXITCODE -ne 0) { throw 'Lightmap UV verification failed' }
