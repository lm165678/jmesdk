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
    & './netbeans/extide/ant/bin/ant.bat' -f jme3-texture-tools/build.xml netbeans
    if ($LASTEXITCODE -ne 0) {
        throw 'Texture Tools module build failed'
    }
    $classpath = 'jme3-texture-tools/build/classes;netbeans/platform/lib/*;netbeans/platform/core/*;netbeans/platform/modules/*'
    New-Item -ItemType Directory -Force 'jme3-texture-tools/build/test-classes', 'build/texture-tools-smoke/tmp' | Out-Null
    & "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $classpath -d jme3-texture-tools/build/test-classes jme3-texture-tools/test/TextureToolsSmoke.java jme3-texture-tools/test/TextureRoutingSmoke.java
    if ($LASTEXITCODE -ne 0) {
        throw 'Texture smoke test compilation failed'
    }
    $temporary = Join-Path $sdkRoot 'build/texture-tools-smoke/tmp'
    & "$jdk/bin/java.exe" --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED "-Djava.io.tmpdir=$temporary" -cp "$classpath;jme3-texture-tools/build/test-classes" TextureToolsSmoke jme3-texture-tools/release/texture-tools build/texture-tools-smoke
    if ($LASTEXITCODE -ne 0) {
        throw 'Texture Tools integration verification failed'
    }
    & "$jdk/bin/java.exe" --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED -cp "$classpath;jme3-texture-tools/build/test-classes" TextureRoutingSmoke build/texture-tools-smoke/BC3.dds build/texture-tools-smoke/ASTC.ktx
    if ($LASTEXITCODE -ne 0) {
        throw 'Texture Tools automatic MIME routing verification failed'
    }
} finally {
    Pop-Location
}
