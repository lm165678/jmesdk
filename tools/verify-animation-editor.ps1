param([switch]$Preview, [switch]$PreviewOnly, [switch]$SkipConversion, [string]$AssetRoot, [string]$Model)
$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$classes = Join-Path $sdkRoot 'build/animation-smoke/classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$entries = @('../JMESources/jme3-core/build/classes/java/main', '../JMESources/jme3-desktop/build/classes/java/main', 'jme3-core/build/classes',
    'jme3-core-baselibs/release/modules/ext/*', 'jme3-core-libraries/release/modules/ext/*',
    'netbeans/platform/core/*', 'netbeans/platform/lib/*', 'netbeans/platform/modules/*',
    'netbeans/platform/modules/ext/*', 'netbeans/ide/modules/*', 'netbeans/java/modules/*')
$classpath = $classes + ';' + (($entries | ForEach-Object { Join-Path $sdkRoot $_ }) -join ';')
$sources = @('AnimationEditorSupport', 'AnimationEditorTopComponent', 'AnimationEditorMessages', 'AnimationTargetOverlay', 'AnimationGraphComments', 'AssetDataNode',
    'ModelConversionOptions', 'ModelConversionPreparation', 'ModelConversionAnalysis') | ForEach-Object {
    Join-Path $sdkRoot "jme3-core/src/com/jme3/gde/core/assets/$_.java"
}
$testSources = @("$PSScriptRoot/tests/AnimationEditorSmoke.java", "$PSScriptRoot/tests/AnimationPreviewSmoke.java")
if ($SkipConversion) {
    $sources = $sources | Where-Object { [IO.Path]::GetFileName($_) -notlike 'ModelConversion*' }
} else {
    $testSources += "$PSScriptRoot/tests/ModelConversionSmoke.java"
}
& "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $classpath -d $classes $sources $testSources
if ($LASTEXITCODE -ne 0) { throw 'Animation editor compilation failed.' }
Push-Location $sdkRoot
try {
    $runtime = @($classes, "$sdkRoot/../JMESources/jme3-core/build/classes/java/main", "$sdkRoot/jme3-core/build/classes",
        "$sdkRoot/../JMESources/jme3-desktop/build/classes/java/main",
        "$sdkRoot/jme3-project-testdata/release/modules/ext/*",
        "$sdkRoot/jme3-core-baselibs/release/modules/ext/*", "$sdkRoot/jme3-core-libraries/release/modules/ext/*",
        "$sdkRoot/netbeans/platform/core/org-openide-filesystems.jar", "$sdkRoot/netbeans/platform/modules/ext/jsvg-1.7.1.jar", "$sdkRoot/netbeans/platform/lib/*",
        "$sdkRoot/netbeans/ide/modules/org-netbeans-modules-projectapi.jar",
        "$sdkRoot/netbeans/ide/modules/org-netbeans-modules-projectapi-nb.jar")
    $runtime += Get-ChildItem "$sdkRoot/netbeans/platform/modules/org-openide-*.jar" | ForEach-Object { $_.FullName }
    $runtime += "$sdkRoot/netbeans/platform/modules/org-netbeans-api-progress.jar"
    $runtime += "$sdkRoot/netbeans/platform/modules/org-netbeans-api-progress-nb.jar"
    New-Item -ItemType Directory -Force "$classes/com/jme3/gde/core/util", "$classes/META-INF/services" | Out-Null
    Copy-Item "$sdkRoot/jme3-core/src/com/jme3/gde/core/util/Bundle.properties" "$classes/com/jme3/gde/core/util/Bundle.properties"
    Copy-Item "$sdkRoot/jme3-core/src/com/jme3/gde/core/assets/AnimationEditorMessages*.properties" "$classes/com/jme3/gde/core/assets/"
    if (-not $PreviewOnly) {
    & "$jdk/bin/java.exe" '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -cp ($runtime -join ';') com.jme3.gde.core.assets.AnimationEditorSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Animation editor verification failed.' }
    if (-not $SkipConversion) {
    [IO.File]::WriteAllText("$classes/META-INF/services/org.openide.DialogDisplayer", 'com.jme3.gde.core.assets.ModelConversionSmoke$Dialogs')
    try {
        & "$jdk/bin/java.exe" '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -cp ($runtime -join ';') com.jme3.gde.core.assets.ModelConversionSmoke
        if ($LASTEXITCODE -ne 0) { throw 'Conversion preflight verification failed.' }
    } finally {
        Remove-Item -LiteralPath "$classes/META-INF/services/org.openide.DialogDisplayer"
    }
    }
    }
    if ($Preview -or $PreviewOnly) {
        & "$jdk/bin/java.exe" "-Djme.sdk.nativeDirectory=$sdkRoot/jme3-core-baselibs/release/native-libs" --enable-native-access=ALL-UNNAMED -cp ($runtime -join ';') com.jme3.gde.core.assets.AnimationPreviewSmoke $AssetRoot $Model
        if ($LASTEXITCODE -ne 0) { throw 'Animation preview verification failed.' }
    }
} finally {
    Pop-Location
}
