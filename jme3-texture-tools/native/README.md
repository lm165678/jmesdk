# Texture Tools JNI

Build the native library on a build machine and ship it with the SDK. The
Windows x64 target links the Compressonator 4.6.0 and astcenc 5.7.1 CPU codecs
into one DLL. Java reads the source image, generates mipmaps, and writes DDS
and KTX1 containers. The JNI library does not launch the codec CLI.

From the SDK root, use JDK 25, Visual Studio 2022 with the C++ workload, and
CMake 3.24 or later:

```powershell
& .\tools\build-texture-jni.ps1 -CMake 'C:\path\to\cmake.exe'
```

If using an x64 Developer PowerShell with Ninja on PATH, pass `-Ninja`.
The script downloads and verifies the pinned source archives on the build
machine only. Use `-Offline` to require previously verified archives in
`build/texture-jni`. It produces a reusable ZIP under
`build/texture-jni/bundles`. `-BuildOnly` skips copying files directly into
the module's release directory.

The SDK's `prepareTextureTools` task imports those archives before the module
is built. For a separate packaging machine, collect trusted build artifacts
in one directory and pass `-PtextureJniBundles=<directory>`. Each archive has
a platform directory such as `windows-x86_64/` containing
`jme-texture-codec.dll` and the upstream license files. Linux and macOS use
`libjme-texture-codec.so` and `libjme-texture-codec.dylib` in their own
`<os>-<arch>/` directories. Build and validate each platform archive on a
compatible host or toolchain before distribution. The build script currently
produces the Windows x64 archive only.

The packaged SDK loads its own installed library by absolute path. On hosts
without a JNI library the default backend uses the bundled CLI. For an SDK
installation where subprocess creation is prohibited, add
`-J-Djme.sdk.texture.backend=jni` to the SDK launcher. Missing DLLs then
produce an error without attempting to run a CLI. Use `cli` to select the old
backend explicitly. JNI failures do not trigger automatic CLI retries.

Run `tools/verify-texture-tools.ps1 -Jni` against a build with the JNI archive.
The smoke test covers all shipped DDS codecs, ASTC mip editing, odd
dimensions, output retention on error, and missing libraries in strict mode.
