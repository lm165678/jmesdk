# Independent source checks only; does not build/deploy SDK modules.
param([switch]$ClipboardOnly)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = $env:SDK_JAVA_HOME
if (!$jdk) { $jdk = $env:JAVA_HOME }
if (!$jdk) { $jdk = 'C:/Users/JohnKkk/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2' }
Push-Location $root
try {
    $output = 'build/asset-import-check'
    New-Item -ItemType Directory -Force $output | Out-Null
    $classpath = (Get-ChildItem build/cluster,netbeans -Filter *.jar -Recurse | ForEach-Object { $_.FullName.Replace('\','/') }) -join ';'
    $engine = '../jmonkeyengine'
    if (!(Test-Path $engine)) { $engine = '../JMESources' }
    $sources = @(
        'jme3-core/src/com/jme3/gde/core/assets/ExternalAssetImport.java',
        'jme3-core/src/com/jme3/gde/core/assets/ModelConversionOptions.java',
        'jme3-core/src/com/jme3/gde/core/assets/ModelConversionPreparation.java',
        'jme3-core/src/com/jme3/gde/core/assets/ModelTexturePreparation.java',
        'jme3-core/src/com/jme3/gde/core/assets/PortableModelDds.java',
        'jme3-core/src/com/jme3/gde/core/assets/nodes/AssetNode.java',
        'jme3-core/src/com/jme3/gde/core/assets/nodes/ProjectAssetsNode.java',
        'jme3-core/src/com/jme3/gde/core/dnd/SceneViewerDropTargetListener.java',
        'jme3-core/src/com/jme3/gde/core/sceneviewer/SceneViewerTopComponent.java',
        'jme3-assetbrowser/src/com/jme3/gde/assetbrowser/AssetContentPanel.java',
        "$engine/jme3-core/src/main/java/com/jme3/scene/control/LodControl.java",
        'tools/tests/ExternalAssetImportSmoke.java'
    )
    @('-encoding','UTF-8','-proc:none','-cp',('"'+$classpath+'"'),'-d',$output) + $sources | Set-Content "$output/compile.args"
    & "$jdk/bin/javac.exe" "@$output/compile.args"
    if ($LASTEXITCODE -ne 0) { throw 'Source compilation failed' }
    @('-ea','-Djava.awt.headless=true','--add-opens=java.base/java.net=ALL-UNNAMED','--enable-native-access=ALL-UNNAMED',
        '-cp',('"'+$output+';'+$classpath+'"'),'com.jme3.gde.core.assets.ExternalAssetImportSmoke',$output) | Set-Content "$output/run.args"
    $testOptions = @()
    if ($ClipboardOnly) {
        $testOptions += '--clipboard-only'
    }
    & "$jdk/bin/java.exe" "@$output/run.args" @testOptions
    if ($LASTEXITCODE -ne 0) { throw 'Import smoke failed' }
} finally {
    Pop-Location
}
