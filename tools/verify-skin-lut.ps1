# Compiles only the skin tool and its smoke test into an isolated directory.
# No Ant/Gradle SDK build, installation, engine export or native process is used.
param([string]$JdkHome, [string]$NormalSource)

$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
if (!$JdkHome) { $JdkHome = $env:SDK_JAVA_HOME }
if (!$JdkHome) { $JdkHome = $env:JAVA_HOME }
if (!$JdkHome) { $JdkHome = 'C:/Users/JohnKkk/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2' }
$jdkRelease = Join-Path $JdkHome 'release'
if (!(Test-Path -LiteralPath $jdkRelease) -or !([IO.File]::ReadAllText($jdkRelease) -match 'JAVA_VERSION="(\d+)')) {
    throw 'Select a JDK 25 or newer using -JdkHome.'
}
if ([int]$Matches[1] -lt 25) { throw 'This SDK source check requires JDK 25 or newer; pass -JdkHome.' }
$engineRoot = Join-Path (Split-Path -Parent $sdkRoot) 'jmonkeyengine'
if (!(Test-Path -LiteralPath $engineRoot)) { $engineRoot = Join-Path (Split-Path -Parent $sdkRoot) 'JMESources' }
Push-Location $sdkRoot
try {
    $output = Join-Path $sdkRoot 'build/skin-lut-check'
    $classes = Join-Path $output 'classes'
    New-Item -ItemType Directory -Force $classes | Out-Null
    $moduleSource = Join-Path $sdkRoot 'jme3-texture-tools/src'
    $jars = Get-ChildItem build/cluster,netbeans -Recurse -Filter *.jar | ForEach-Object { $_.FullName }
    $classpath = (@((Join-Path $engineRoot 'jme3-core/build/classes/java/main'),
        (Join-Path $engineRoot 'jme3-core/src/main/resources'), $moduleSource) + @($jars)) -join ';'
    $source = 'jme3-texture-tools/src/com/jme3/gde/texturetools'
    $sources = @("$source/SkinLutAction.java", "$source/SkinLutPanel.java", "$source/SkinLutExport.java",
        "$source/SkinToolsPanel.java", "$source/SkinGenerationSettings.java", "$source/SkinNormalAssets.java",
        "$source/SkinNormalPanel.java", "$source/SkinTexturePreview.java",
        'tools/tests/SkinLutSmoke.java')
    $compile = @('-encoding','UTF-8','-implicit:none','-proc:full','-processor','org.netbeans.modules.openide.awt.ActionProcessor',
        '-sourcepath',('"'+$moduleSource+'"'),
        '-cp',('"'+$classpath+'"'),'-d',('"'+$classes+'"')) + $sources
    [IO.File]::WriteAllLines((Join-Path $output 'compile.args'), @($compile | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/javac.exe" ('@'+(Join-Path $output 'compile.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin LUT source compilation failed' }
    $run = @('-ea','-Djava.awt.headless=true','--add-opens=java.base/java.net=ALL-UNNAMED',
        '-cp',('"'+$classes+';'+$classpath+'"'),'com.jme3.gde.texturetools.SkinLutSmoke',
        ('"'+$output+'"'),('"'+(Join-Path $classes 'META-INF/generated-layer.xml')+'"'))
    if ($NormalSource) {
        $run = @('"-Dskin.test.normal='+$NormalSource+'"') + $run
    }
    [IO.File]::WriteAllLines((Join-Path $output 'run.args'), @($run | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/java.exe" ('@'+(Join-Path $output 'run.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin LUT smoke failed' }
} finally {
    Pop-Location
}
