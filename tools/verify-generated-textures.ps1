$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
}
Push-Location $sdkRoot
try {
    $env:JAVA_HOME = $jdk
    & './gradlew.bat' prepareTextureTools
    if ($LASTEXITCODE -ne 0) {
        throw 'Texture Tools resource preparation failed'
    }
    foreach ($module in @('jme3-texture-tools', 'jme3-core', 'jme3-assetbrowser')) {
        & './netbeans/extide/ant/bin/ant.bat' -f "$module/build.xml" netbeans
        if ($LASTEXITCODE -ne 0) {
            throw "$module build failed"
        }
    }
    $basic = 'jme3-texture-tools/build/classes;netbeans/platform/lib/*;netbeans/platform/core/*;netbeans/platform/modules/*'
    $mapping = "jme3-core/build/classes;$basic;jme3-core-baselibs/release/modules/ext/*;jme3-core-libraries/release/modules/ext/*;netbeans/ide/modules/*;netbeans/java/modules/*"
    New-Item -ItemType Directory -Force 'build/texture-mapping-test-classes', 'build/texture-tools-smoke/tmp' | Out-Null
    & "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $mapping -d build/texture-mapping-test-classes jme3-texture-tools/test/GeneratedTexturesSmoke.java tools/tests/ModelTextureMappingSmoke.java
    if ($LASTEXITCODE -ne 0) {
        throw 'Generated texture test compilation failed'
    }
    $temporary = Join-Path $sdkRoot 'build/texture-tools-smoke/tmp'
    $flags = @('--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.net=ALL-UNNAMED', "-Djava.io.tmpdir=$temporary")
    & "$jdk/bin/java.exe" @flags -cp "$basic;build/texture-mapping-test-classes" GeneratedTexturesSmoke jme3-texture-tools/release/texture-tools build/texture-tools-smoke
    if ($LASTEXITCODE -ne 0) {
        throw 'Generated texture filesystem integration failed'
    }
    & "$jdk/bin/java.exe" @flags "-Dnetbeans.dirs=$sdkRoot/build/cluster" "-Dnetbeans.home=$sdkRoot/netbeans/platform" -cp "$mapping;build/texture-mapping-test-classes" com.jme3.gde.core.assets.ModelTextureMappingSmoke build/texture-tools-smoke
    if ($LASTEXITCODE -ne 0) {
        throw 'Model texture mapping integration failed'
    }
} finally {
    Pop-Location
}
