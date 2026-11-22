param(
    [Parameter(Mandatory = $true)]
    [string]$UfbxSource,
    [string]$CMake = 'cmake.exe',
    [string]$BuildDirectory,
    [switch]$Ninja,
    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
$sdk = Split-Path -Parent $PSScriptRoot
$source = (Resolve-Path -LiteralPath $UfbxSource).Path
if (!(Test-Path -LiteralPath (Join-Path $source 'ufbx.c') -PathType Leaf)) {
    throw "Missing ufbx.c in $source"
}
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath "$env:JAVA_HOME/include/jni.h")) {
    $env:JAVA_HOME = & (Join-Path $PSScriptRoot 'build-local-sdk.ps1') jdk
}
if (!(Test-Path -LiteralPath "$env:JAVA_HOME/include/jni.h")) {
    throw 'Set JAVA_HOME to a JDK installation.'
}
if (!$BuildDirectory) {
    $BuildDirectory = Join-Path $sdk $(if ($Ninja) { 'build/ufbx-jni/ninja-x64' } else { 'build/ufbx-jni/windows-x64' })
}
$generator = @('-G', 'Visual Studio 17 2022', '-A', 'x64')
if ($Ninja) {
    # Run from an x64 Visual Studio developer shell with Ninja on PATH.
    $generator = @('-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release')
}
& $CMake -S (Join-Path $sdk 'jme3-autodesk/native') -B $BuildDirectory @generator "-DUFBX_SOURCE_DIR=$source"
if ($LASTEXITCODE -ne 0) {
    throw 'ufbx JNI configuration failed.'
}
& $CMake --build $BuildDirectory --config Release
if ($LASTEXITCODE -ne 0) {
    throw 'ufbx JNI compilation failed.'
}
$stage = if ($BuildOnly) {
    Join-Path $sdk 'build/ufbx-jni/install/windows-x86_64'
} else {
    Join-Path $sdk 'jme3-autodesk/release/lib/ufbx/windows-x86_64'
}
& $CMake --install $BuildDirectory --config Release --prefix $stage
if ($LASTEXITCODE -ne 0) {
    throw 'ufbx JNI installation failed.'
}
Write-Output "ufbx JNI library: $stage"
