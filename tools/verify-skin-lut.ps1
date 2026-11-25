# Checks skin tool sources against Java 11 and runs its CPU/EDT smoke in an isolated directory.
# No Ant/Gradle SDK build, installation or engine export. Optional DDS inputs may use the bundled CPU decoder.
# JDK 25 hosts the existing NetBeans dependencies; --release 11 checks source/API compatibility.
param([string]$JdkHome, [string]$NormalSource, [switch]$VerifyGpu, [string]$NativeRoot)

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
        (Join-Path $engineRoot 'jme3-core/src/main/resources'), $moduleSource,
        (Join-Path $sdkRoot 'jme3-materialeditor/src')) + @($jars)) -join ';'
    $source = 'jme3-texture-tools/src/com/jme3/gde/texturetools'
    $sources = @((Get-ChildItem -LiteralPath $source -Filter 'Skin*.java').FullName) + @(
        "$source/TextureToolchain.java", "$source/CompressedTexture.java", "$source/NativeTextureCodec.java",
        'jme3-core/src/com/jme3/gde/core/assets/SkinProjectAssetSelection.java',
        (Join-Path $engineRoot 'jme3-core/src/plugins/java/com/jme3/texture/plugins/DDSLoader.java')) + @(
        (Get-ChildItem -LiteralPath (Join-Path $engineRoot 'jme3-core/src/main/java/com/jme3/material/skin') -Filter '*.java').FullName)
    $compile = @('--release','11','-encoding','UTF-8','-implicit:none','-proc:full','-processor','org.netbeans.modules.openide.awt.ActionProcessor,org.netbeans.modules.openide.util.ServiceProviderProcessor',
        '-sourcepath',('"'+$moduleSource+'"'),
        '-cp',('"'+$classpath+'"'),'-d',('"'+$classes+'"')) + $sources
    [IO.File]::WriteAllLines((Join-Path $output 'compile.args'), @($compile | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/javac.exe" ('@'+(Join-Path $output 'compile.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin LUT source compilation failed' }
    # Check the editor consumer too; its existing UI source uses the SDK JDK level.
    $editorCompile = @('-encoding','UTF-8','-implicit:none','-proc:none',
        '-cp',('"'+$classes+';'+$classpath+'"'),'-d',('"'+$classes+'"'),
        'jme3-materialeditor/src/com/jme3/gde/materials/EditableMaterialFile.java',
        'jme3-materialeditor/src/com/jme3/gde/materials/multiview/MaterialEditorTopComponent.java')
    [IO.File]::WriteAllLines((Join-Path $output 'editor.args'), @($editorCompile | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/javac.exe" ('@'+(Join-Path $output 'editor.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin material editor source compilation failed' }
    # Test sources are compiled separately so production classes are always checked against Java 11.
    $testClasspath = $classes + ';' + $classpath
    $testCompile = @('--release','11','-encoding','UTF-8','-implicit:none','-proc:none',
        '-sourcepath',('"'+$classes+'"'),'-cp',('"'+$testClasspath+'"'),'-d',('"'+$classes+'"'),
        'tools/tests/SkinLutSmoke.java', 'tools/tests/SkinTextureWorkflowSmoke.java',
        'tools/tests/SkinSurfaceSmoke.java', 'tools/tests/SkinDdsGpuSmoke.java',
        'tools/tests/SkinMaterialEditorStateSmoke.java', 'tools/tests/SkinSurfaceBindingGpuSmoke.java')
    [IO.File]::WriteAllLines((Join-Path $output 'tests.args'), @($testCompile | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/javac.exe" ('@'+(Join-Path $output 'tests.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin tool test compilation failed' }
    $run = @('-ea','-Djava.awt.headless=true','--add-opens=java.base/java.net=ALL-UNNAMED',
        '-cp',('"'+$classes+';'+$classpath+'"'),'com.jme3.gde.texturetools.SkinLutSmoke',
        ('"'+$output+'"'),('"'+(Join-Path $classes 'META-INF/generated-layer.xml')+'"'))
    if ($NormalSource) {
        $run = @('"-Dskin.test.normal='+$NormalSource+'"') + $run
    }
    [IO.File]::WriteAllLines((Join-Path $output 'run.args'), @($run | ForEach-Object { $_.Replace('\','/') }))
    & "$JdkHome/bin/java.exe" ('@'+(Join-Path $output 'run.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Skin LUT smoke failed' }
    foreach ($entry in @('SkinSurfaceSmoke', 'SkinMaterialEditorStateSmoke')) {
        $check = @('-ea','-Djava.awt.headless=true','--add-opens=java.base/java.net=ALL-UNNAMED',
            '-cp',('"'+$testClasspath+'"'),('com.jme3.gde.texturetools.'+$entry),('"'+$output+'"'))
        [IO.File]::WriteAllLines((Join-Path $output ($entry+'.args')), @($check | ForEach-Object { $_.Replace('\','/') }))
        & "$JdkHome/bin/java.exe" ('@'+(Join-Path $output ($entry+'.args')))
        if ($LASTEXITCODE -ne 0) { throw ($entry+' failed') }
    }
    if ($VerifyGpu) {
        $gpu = @('-ea','-Djava.awt.headless=false','--add-opens=java.base/java.net=ALL-UNNAMED')
        if ($NativeRoot) {
            $gpu += @('-Dcom.jme3.ExtractNativeLibraries=false',
                ('"-Dcom.jme3.NativeLibraryExtractionFolder='+[IO.Path]::GetFullPath($NativeRoot)+'"'))
        }
        $gpu += @('-cp',('"'+$testClasspath+'"'),'com.jme3.gde.texturetools.SkinSurfaceBindingGpuSmoke')
        [IO.File]::WriteAllLines((Join-Path $output 'surface-gpu.args'), @($gpu | ForEach-Object { $_.Replace('\','/') }))
        & "$JdkHome/bin/java.exe" ('@'+(Join-Path $output 'surface-gpu.args'))
        if ($LASTEXITCODE -ne 0) { throw 'Surface binding native GPU check failed' }
    }
} finally {
    Pop-Location
}
