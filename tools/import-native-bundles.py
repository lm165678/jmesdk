"""Import six-platform SDK JNI artifacts; verify format, architecture and exports.

Run after the fork's 'Build SDK asset JNI libraries' workflow succeeds:
    python tools/import-native-bundles.py --artifacts PATH_TO_DOWNLOADED_ZIPS
The tracked native-bundles directory is authoritative. Gradle stages runtime copies.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import zipfile

TARGETS = {
    'windows-x86_64': ('jme-{}.dll', 'PE', 0x8664),
    'windows-arm64': ('jme-{}.dll', 'PE', 0xaa64),
    'linux-x86_64': ('libjme-{}.so', 'ELF', 62),
    'linux-arm64': ('libjme-{}.so', 'ELF', 183),
    'macos-x86_64': ('libjme-{}.dylib', 'MachO', 0x01000007),
    'macos-arm64': ('libjme-{}.dylib', 'MachO', 0x0100000c),
}
LIBRARIES = {
    'ufbx': ('26a482ae66871d7de36eb722aa060bce95bce274', b'Java_com_jme3_gde_autodesk_UfbxLoader_'),
    'xatlas': ('16ace528acd2cf1f16a7c0dde99c42c486488dbe', b'Java_com_jme3_gde_core_assets_LightmapUvGenerator_unwrap'),
    'spirv-cross': ('7affe74d77f93a622bb5002789d5332d32e512ee', b'Java_com_jme3_gde_core_assets_SpirvCross_'),
}

def verify_binary(data, kind, arch, symbol):
    if kind == 'PE':
        assert data[:2] == b'MZ', 'Not a PE library'
        offset = struct.unpack_from('<I', data, 0x3c)[0]
        assert data[offset:offset + 4] == b'PE\0\0', 'Invalid PE header'
        actual = struct.unpack_from('<H', data, offset + 4)[0]
    elif kind == 'ELF':
        assert data[:6] == b'\x7fELF\x02\x01', 'Expected 64-bit little-endian ELF'
        actual = struct.unpack_from('<H', data, 18)[0]
    else:
        assert data[:4] == b'\xcf\xfa\xed\xfe', 'Expected 64-bit Mach-O'
        actual = struct.unpack_from('<I', data, 4)[0]
    assert actual == arch, f'Architecture mismatch: {actual:#x} != {arch:#x}'
    assert symbol in data, 'Missing JNI export'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', required=True, type=Path)
    parser.add_argument('--run-url', default='')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / 'native-bundles'
    payloads = {}
    for archive in sorted(args.artifacts.glob('*.zip')):
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                parts = PurePosixPath(entry.filename).parts
                if len(parts) < 2 or parts[-2] not in TARGETS or entry.is_dir():
                    continue
                key = '/'.join(parts[-2:])
                data = source.read(entry)
                assert key not in payloads or payloads[key] == data, f'Conflicting artifact: {key}'
                payloads[key] = data
    files = []
    # Validate everything before replacing the tracked package.
    for target, (pattern, kind, arch) in TARGETS.items():
        for library, (revision, symbol) in LIBRARIES.items():
            binary = target + '/' + pattern.format(library)
            license_path = target + '/LICENSE-' + library + '.txt'
            assert binary in payloads and license_path in payloads, f'Incomplete {target}/{library}'
            verify_binary(payloads[binary], kind, arch, symbol)
            assert len(payloads[license_path]) > 100, f'Missing license: {license_path}'
            for name in [binary, license_path]:
                files.append({'path': name, 'sha256': hashlib.sha256(payloads[name]).hexdigest()})
    for entry in files:
        path = root / entry['path']
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payloads[entry['path']])
    manifest = {
        'schema': 1,
        'upstreamRevisions': {name: revision for name, (revision, _) in LIBRARIES.items()},
        'buildRun': args.run_url,
        'nativeSmokeTargets': [target for target in TARGETS if target != 'windows-arm64'],
        'crossCompiledOnly': ['windows-arm64'],
        'files': files,
    }
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Imported and verified {len(files)} files for {len(TARGETS)} platforms.')

if __name__ == '__main__':
    main()
