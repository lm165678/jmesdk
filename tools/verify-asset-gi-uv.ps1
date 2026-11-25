param(
    [string]$EngineRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'jmonkeyengine'),
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipVisual
)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    throw 'Specify -JdkHome with JDK 25. This script performs isolated compilation, never an SDK build.'
}
$out = Join-Path $EngineRoot 'build/asset-gi-uv-verification'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $classes, "$out/temp", "$out/artifacts" | Out-Null
$jars = @(Get-ChildItem "$sdkRoot/build/cluster", "$sdkRoot/netbeans" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc|jbullet|jme3-bullet' } |
    ForEach-Object { $_.FullName })
$classpath = (@($classes, "$EngineRoot/jme3-core/build/classes/java/main",
    "$EngineRoot/jme3-effects/build/classes/java/main", "$EngineRoot/jme3-desktop/build/classes/java/main",
    "$EngineRoot/jme3-core/src/main/resources", "$EngineRoot/jme3-effects/src/main/resources",
    "$sdkRoot/jme3-core/src", "$sdkRoot/jme3-assetbrowser/src") + $jars) -join ';'
function Compile-Sources([string]$label, [string[]]$sources, [switch]$Java11) {
    $arguments = @('-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + $classpath + '"'), '-d', ('"' + $classes + '"'))
    if ($Java11) {
        $arguments += @('--release', '11')
    }
    $arguments += @($sources | ForEach-Object { '"' + $_ + '"' })
    $argFile = Join-Path $out "$label-compile.args"
    [IO.File]::WriteAllLines($argFile, @($arguments | ForEach-Object { $_.Replace('\', '/') }), [Text.UTF8Encoding]::new($false))
    & "$JdkHome/bin/javac.exe" "@$argFile"
    if ($LASTEXITCODE -ne 0) {
        throw "$label compilation failed"
    }
}
Compile-Sources 'core' @(
    "$EngineRoot/jme3-core/src/main/java/com/jme3/environment/gi/ProbeLightmapBaker.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/environment/gi/ProbeLightmapChartOwnership.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/environment/gi/ProbeLightmapMipChain.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/environment/gi/ProbeLightmapControl.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/post/WorldSettings.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/post/Filter.java",
    "$EngineRoot/jme3-core/src/main/java/com/jme3/renderer/gpuscene/GpuScenePipeline.java"
) -Java11
$relative = @(
    'jme3-core/src/com/jme3/gde/core/assets/LightmapUvGenerator.java',
    'jme3-core/src/com/jme3/gde/core/assets/LightmapUvPacking.java',
    'jme3-core/src/com/jme3/gde/core/assets/ModelConversionPreparation.java',
    'jme3-core/src/com/jme3/gde/core/scene/ProbeLightmapAssets.java',
    'jme3-core/src/com/jme3/gde/core/scene/GiPreview.java',
    'jme3-core/src/com/jme3/gde/core/scene/GiBakeReconstructionGuard.java',
    'jme3-core/src/com/jme3/gde/core/sceneexplorer/nodes/actions/impl/BakeGiProbeVolumeAction.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/LightmapUvEdits.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/SceneLightmapEdit.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/EditorLightmapPanel.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/SceneComposerTopComponent.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/ModelDetailTopComponent.java',
    'jme3-scenecomposer/src/com/jme3/gde/scenecomposer/LightmapUvView.java',
    'jme3-assetbrowser/src/com/jme3/gde/assetbrowser/AssetDependencies.java',
    'jme3-assetbrowser/src/com/jme3/gde/assetbrowser/AssetThumbnails.java',
    'jme3-assetbrowser/src/com/jme3/gde/assetbrowser/AssetContentPanel.java',
    'tools/tests/AssetDependencyRefreshSmoke.java',
    'tools/tests/AssetDependencyDialogSmoke.java',
    'tools/tests/GiBakeReconstructionSmoke.java',
    'tools/tests/GiInspectorBakeCheck.java',
    'tools/tests/LightmapUvSubtreeSmoke.java',
    'tools/tests/LightmapUvRegenerationSmoke.java'
)
$sources = @($relative | ForEach-Object { Join-Path $sdkRoot $_ })
$sources += @(
    "$EngineRoot/jme3-effects/src/main/java/com/jme3/post/filters/TemporalAAFilter.java",
    "$EngineRoot/jme3-effects/src/main/java/com/jme3/post/filters/UpscalingPass.java",
    "$EngineRoot/jme3-examples/src/main/java/jme3test/post/TestUpscaling.java"
)
Compile-Sources 'sdk-features' $sources
$osTag = if ($env:OS -eq 'Windows_NT') { 'windows' } elseif ($IsMacOS) { 'macos' } else { 'linux' }
$cpu = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString().ToLowerInvariant()
$arch = if ($cpu -eq 'x64') { 'x86_64' } elseif ($cpu -eq 'arm64') { 'arm64' } else { throw "Unsupported architecture: $cpu" }
$library = if ($osTag -eq 'windows') { 'jme-xatlas.dll' } elseif ($osTag -eq 'macos') { 'libjme-xatlas.dylib' } else { 'libjme-xatlas.so' }
$xatlas = Join-Path $sdkRoot "native-bundles/$osTag-$arch/$library"
if (-not (Test-Path -LiteralPath $xatlas)) {
    throw "Missing bundled xatlas: $xatlas"
}
$nativeOs = if ($osTag -eq 'macos') { 'osx' } else { $osTag }
$nativeDirectory = Join-Path $sdkRoot "build/cluster/native-libs/$nativeOs/$arch"
function Run-Check([string]$class, [string]$label, [string[]]$extra = @()) {
    $arguments = @('--enable-native-access=ALL-UNNAMED',
        '--add-opens=java.base/java.nio=ALL-UNNAMED', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
        '--add-opens=java.base/java.net=ALL-UNNAMED',
        ('-Djme.sdk.xatlas.library=' + $xatlas),
        ('-Djava.io.tmpdir=' + "$out/temp"), ('-Dnetbeans.user=' + "$out/user-$label"))
    if (Test-Path -LiteralPath $nativeDirectory) {
        $arguments += @('-Dcom.jme3.ExtractNativeLibraries=false',
            ('-Dcom.jme3.NativeLibraryExtractionFolder=' + $nativeDirectory))
    }
    $arguments += @('-cp', ('"' + $classpath + '"'), $class)
    $arguments += @($extra | ForEach-Object { '"' + $_ + '"' })
    $argFile = Join-Path $out "$label-run.args"
    [IO.File]::WriteAllLines($argFile, @($arguments | ForEach-Object { $_.Replace('\', '/') }), [Text.UTF8Encoding]::new($false))
    & "$JdkHome/bin/java.exe" "@$argFile"
    if ($LASTEXITCODE -ne 0) {
        throw "$label failed"
    }
}
Run-Check 'com.jme3.gde.assetbrowser.AssetDependencyRefreshSmoke' 'dependencies'
Run-Check 'com.jme3.gde.core.scene.GiBakeReconstructionSmoke' 'reconstruction'
Run-Check 'com.jme3.gde.scenecomposer.LightmapUvSubtreeSmoke' 'uv-subtree'
if (-not $SkipVisual) {
    Run-Check 'com.jme3.gde.core.sceneexplorer.nodes.actions.impl.GiInspectorBakeCheck' 'gi-inspector' @("$out/artifacts")
    Run-Check 'com.jme3.gde.assetbrowser.AssetDependencyDialogSmoke' 'dependency-dialog'
    Run-Check 'com.jme3.gde.scenecomposer.LightmapUvRegenerationSmoke' 'uv-render' @("$out/artifacts")
    Run-Check 'jme3test.post.TestUpscaling' 'upscaling-render'
}
Write-Output 'ASSET_GI_UV_VERIFICATION_PASS'
