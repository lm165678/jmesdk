param(
    [string]$CMake = 'cmake.exe',
    [string]$BuildDirectory,
    [switch]$Ninja,
    [switch]$Offline,
    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
$sdk = Split-Path -Parent $PSScriptRoot
$work = Join-Path $sdk 'build/texture-jni'
$sources = Join-Path $work 'src'
New-Item -ItemType Directory -Force $sources | Out-Null

$packages = @(
    @{
        name = 'astc-encoder-5.7.1'
        url = 'https://codeload.github.com/ARM-software/astc-encoder/zip/refs/tags/5.7.1'
        sha = '7B041EDCF14D5B9F912B9054913686605CA74437AA3D06E3DE62F82274BBB0A4'
    },
    @{
        name = 'compressonator-4.6.0'
        url = 'https://codeload.github.com/GPUOpen-Tools/compressonator/zip/refs/tags/V4.6.0'
        sha = '4C849F5AB75FA865FA451423257AE5DCB7906360C550F3575C7D2C5195B2C429'
    }
)
foreach ($package in $packages) {
    $archive = Join-Path $work ($package.name + '.zip')
    if (!(Test-Path -LiteralPath $archive -PathType Leaf)) {
        if ($Offline) {
            throw "Missing cached source archive: $archive"
        }
        $partial = "$archive.partial"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $package.url -OutFile $partial
            if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $package.sha) {
                throw "Unexpected SHA-256 for $($package.name)"
            }
            Move-Item -LiteralPath $partial -Destination $archive
        } finally {
            if (Test-Path -LiteralPath $partial) {
                Remove-Item -LiteralPath $partial
            }
        }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $package.sha) {
        throw "Unexpected SHA-256 for $archive"
    }
    $dir = Join-Path $sources $package.name
    if (!(Test-Path -LiteralPath "$dir/.extracted")) {
        Expand-Archive -LiteralPath $archive -DestinationPath $sources -Force
        New-Item -ItemType File -Path "$dir/.extracted" | Out-Null
    }
}

if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = & (Join-Path $PSScriptRoot 'build-local-sdk.ps1') jdk
}
if (!(Test-Path -LiteralPath "$env:JAVA_HOME/include/jni.h")) {
    throw 'Set JAVA_HOME to a JDK installation.'
}

$astc = Join-Path $sources 'astc-encoder-5.7.1'
$cmp = Join-Path $sources 'compressonator-4.6.0'
$build = Join-Path $work 'windows-x64'
$generator = @('-G', 'Visual Studio 17 2022', '-A', 'x64')
if ($Ninja) {
    # Run from an x64 VS developer shell with Ninja on PATH.
    $build = Join-Path $work 'ninja-x64'
    $generator = @('-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_TRY_COMPILE_CONFIGURATION=Release')
}
if ($BuildDirectory) {
    $build = $BuildDirectory
}
& $CMake -S (Join-Path $sdk 'jme3-texture-tools/native') -B $build @generator "-DASTC_SOURCE=$astc" "-DCMP_SOURCE=$cmp"
if ($LASTEXITCODE -ne 0) {
    throw 'Texture JNI configure failed.'
}
& $CMake --build $build --config Release --parallel 8
if ($LASTEXITCODE -ne 0) {
    throw 'Texture JNI compilation failed.'
}

$stage = Join-Path $work 'install/windows-x86_64'
& $CMake --install $build --config Release --prefix $stage
if ($LASTEXITCODE -ne 0) {
    throw 'Texture JNI staging failed.'
}
Copy-Item -LiteralPath "$astc/LICENSE.txt" -Destination "$stage/LICENSE-astcenc.txt"
Copy-Item -LiteralPath "$cmp/license/sdklicense.txt" -Destination "$stage/LICENSE-compressonator.txt"
Copy-Item -LiteralPath "$cmp/license/corelicense.txt" -Destination "$stage/LICENSE-compressonator-core.txt"
Copy-Item -LiteralPath "$cmp/license/etcpack/licence.txt" -Destination "$stage/LICENSE-etcpack.txt"
$bundles = Join-Path $work 'bundles'
New-Item -ItemType Directory -Force $bundles | Out-Null
$bundle = Join-Path $bundles 'jme-texture-codec-windows-x86_64.zip'
Compress-Archive -LiteralPath $stage -DestinationPath $bundle -Force
Write-Output "Texture JNI platform archive: $bundle"
if (!$BuildOnly) {
    $release = Join-Path $sdk 'jme3-texture-tools/release/texture-tools/jni/windows-x86_64'
    New-Item -ItemType Directory -Force $release | Out-Null
    Get-ChildItem -LiteralPath $stage -File | Copy-Item -Destination $release
    Write-Output "Texture JNI files staged for the next SDK module build: $release"
}
