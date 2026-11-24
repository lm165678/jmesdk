# SDK JNI binaries

This directory is versioned source-delivery data, like the Bullet native package.
Commit these binaries, licenses and manifest with the matching JNI bridge changes.
Ignored build outputs or temporary CI artifacts are not a replacement.

Includes ufbx, xatlas and SPIRV-Cross for Windows/Linux/macOS on x86_64 and ARM64.
`manifest.json` records pinned upstream commits, the build run and SHA-256 hashes.
The current six-platform build passed all jobs. Windows ARM64 is cross-compiled;
the other five runners also loaded and exercised each JNI library natively.

SDK `prepareUfbxNatives` defaults to this directory, checks the complete matrix and
hashes, and stages module release files. Ordinary SDK users need no native compiler
or runtime download. Java selects the installed platform/architecture package.
The xatlas bridge is in `jme3-core/native/xatlas`; the Java loader is
`LightmapUvGenerator`. Runtime copies under module release/build are generated.

To update:

1. Change the bridge or pinned upstream revision and run `.github/workflows/ufbx-jni.yml` in the fork.
2. Download all six `ufbx-jni-*` ZIP artifacts into one directory.
3. Run `python tools/import-native-bundles.py --artifacts PATH --run-url BUILD_URL`.
   This checks PE/ELF/Mach-O architecture, JNI exports, licenses and all six targets
   before replacing the tracked package and regenerating the manifest.
4. Run `gradlew -PlocalEngineRepository=PATH prepareUfbxNatives`, then build the SDK.
5. Verify the relevant Java workflows on native hosts. Commit and push all package
   files and source changes together; record cross-compiled-only coverage separately.

To generate static Lightmaps for an existing model: set resolved Mobility to Static,
run the GI bake through completion, confirm the Lightmaps saved count, then save J3O.
The SDK uses existing UV2 or bundled xatlas. Old scenes lacking Lightmap references
keep live probing until rebaked and saved; installing the DLL does not rewrite them.
