"""Verify JNI compatibility and package a deterministic source-built native AAR.

No artifact is published if API, architecture, runtime or source identity checks fail.
Only public library source/provenance is read. Outputs remain in ignored build paths.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

SOURCE = Path(__file__).resolve().parent
WORK = SOURCE / 'build'
VERSION = '2.10.0-2-go1.27.1-16k-linux2'
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}


def elf_page_layout(data, abi):
    """Reject incompatible LOAD/RELRO layouts before publishing the AAR.

    ELF segment alignment is distinct from APK ZIP entry alignment. The latter
    must also be verified on the assembled APK.
    """
    expected = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40),
                'x86': (1, 3), 'x86_64': (2, 62)}
    if abi not in expected or len(data) < 16 or data[:4] != b'\x7fELF':
        raise ValueError('NATIVE_ELF_INVALID')
    elf_class, machine = expected[abi]
    if data[4] != elf_class or data[5] != 1:
        raise ValueError('NATIVE_ELF_ARCHITECTURE_MISMATCH')
    header_format = '<HHIQQQIHHHHHH' if elf_class == 2 else '<HHIIIIIHHHHHH'
    header_size = struct.calcsize(header_format)
    if len(data) < 16 + header_size:
        raise ValueError('NATIVE_ELF_TRUNCATED')
    header = struct.unpack_from(header_format, data, 16)
    if header[1] != machine:
        raise ValueError('NATIVE_ELF_ARCHITECTURE_MISMATCH')
    offset, entry_size, count = header[4], header[8], header[9]
    segment_format = '<IIQQQQQQ' if elf_class == 2 else '<IIIIIIII'
    if entry_size != struct.calcsize(segment_format) or not count or offset + count * entry_size > len(data):
        raise ValueError('NATIVE_ELF_PROGRAM_HEADERS_INVALID')
    loads, relros = [], []
    for index in range(count):
        values = struct.unpack_from(segment_format, data, offset + index * entry_size)
        if elf_class == 2:
            kind, flags, file_offset, virtual, _, file_size, memory_size, alignment = values
        else:
            kind, file_offset, virtual, _, file_size, memory_size, flags, alignment = values
        if file_offset + file_size > len(data) or file_size > memory_size:
            raise ValueError('NATIVE_ELF_SEGMENT_INVALID')
        if kind == 1:
            if alignment < 16384 or alignment & (alignment - 1) or file_offset % alignment != virtual % alignment:
                raise ValueError('NATIVE_ELF_LOAD_NOT_16K_ALIGNED')
            loads.append((virtual, memory_size, flags, alignment))
        elif kind == 0x6474e552:
            relros.append((virtual, memory_size))
    if not loads:
        raise ValueError('NATIVE_ELF_LOAD_MISSING')
    for virtual, memory_size in relros:
        end = virtual + memory_size
        if end % 16384 == 0:
            continue
        containing = [(start, size, flags) for start, size, flags, _ in loads
                      if start <= virtual and end <= start + size]
        if not containing or any(flags & 2 and start + size != end for start, size, flags in containing):
            raise ValueError('NATIVE_ELF_RELRO_NOT_16K_COMPATIBLE')
    return {'minimum_load_alignment': min(load[3] for load in loads),
            'relro_16k_compatible': True}


def command(arguments):
    return subprocess.run(arguments, capture_output=True, text=True, encoding='utf-8',
                          check=True, timeout=180).stdout


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_text(data):
    """Use the same LF source representation on every checkout host."""
    return data.replace(b'\r\n', b'\n')


def api(aar, label, java_home):
    jar = WORK / (label + '.jar')
    with ZipFile(aar) as archive:
        jar.write_bytes(archive.read('classes.jar'))
    with ZipFile(jar) as archive:
        classes = sorted(n[:-6].replace('/', '.') for n in archive.namelist()
                         if n.endswith('.class') and n.startswith('com/proton/')
                         and not n.startswith('com/proton/gopenpgp/ktclient/'))
    if not classes:
        raise ValueError('PROTON_API_MISSING')
    javap = str(java_home / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap'))
    output = command([javap, '-public', '-classpath', str(jar), *classes])
    return '\n'.join(sorted(line.strip() for line in output.splitlines()
                           if line.strip() and not line.startswith('Compiled from') and line.strip() != '}'))


def read_json_stream(raw):
    decoder = json.JSONDecoder()
    while raw.strip():
        raw = raw.lstrip()
        value, end = decoder.raw_decode(raw)
        yield value
        raw = raw[end:]


def normalized_zip(contents):
    import io
    output = io.BytesIO()
    with ZipFile(output, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(contents.items()):
            if name.startswith('/') or '..' in name.split('/') or '\\' in name:
                raise ValueError('UNSAFE_ZIP_ENTRY')
            info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def main():
    global WORK
    parser = argparse.ArgumentParser()
    parser.add_argument('--go', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--work', type=Path, default=WORK)
    parser.add_argument('--repository', type=Path, default=SOURCE.parent / 'build/maven')
    args = parser.parse_args()
    WORK = args.work.resolve()
    official_api = api(WORK / 'official.aar', 'official', args.java_home)
    rebuilt_api = api(WORK / 'raw.aar', 'rebuilt', args.java_home)
    if official_api != rebuilt_api:
        raise ValueError('PROTON_PUBLIC_API_CHANGED')
    with ZipFile(WORK / 'raw.aar') as archive:
        contents = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    # javac's jar timestamps do not identify source. Normalize both ZIP layers.
    import io
    with ZipFile(io.BytesIO(contents['classes.jar'])) as archive:
        contents['classes.jar'] = normalized_zip({n: archive.read(n) for n in archive.namelist() if not n.endswith('/')})
    native = []
    modules = {}
    for abi in sorted(ABIS):
        entry = f'jni/{abi}/libgojni.so'
        library = WORK / (abi + '.so')
        library.write_bytes(contents[entry])
        metadata = command([str(args.go), 'version', '-m', str(library)])
        if not metadata.splitlines()[0].endswith(': go1.27.1'):
            raise ValueError('NATIVE_GO_VERSION_MISMATCH')
        layout = elf_page_layout(contents[entry], abi)
        native.append({'entry': entry, 'sha256': sha(contents[entry]), 'go': '1.27.1', **layout})
        for path, version, checksum in re.findall(r'^\s+dep\s+(\S+)\s+(\S+)\s+(\S+)$', metadata, re.M):
            value = {'path': path, 'version': version, 'sum': checksum}
            if path in modules and modules[path] != value:
                raise ValueError('ABI_MODULE_GRAPH_MISMATCH')
            modules[path] = value
    actual_abis = {n.split('/')[1] for n in contents if n.startswith('jni/') and n.endswith('.so')}
    if actual_abis != ABIS:
        raise ValueError('NATIVE_ARCHITECTURE_MISMATCH')
    for module, version in {
        'github.com/ProtonMail/gopenpgp/v2': 'v2.10.0-proton',
        'github.com/ProtonMail/go-crypto': 'v1.4.1-proton',
        'github.com/ProtonMail/go-srp': 'v0.0.7',
    }.items():
        if modules.get(module, {}).get('version') != version:
            raise ValueError('PROTON_CRYPTO_VERSION_CHANGED')
    if 'github.com/ProtonMail/pm-key-transparency-go-client' in modules:
        raise ValueError('UNUSED_KEY_TRANSPARENCY_MODULE_LINKED')
    module_directories = {m['Path']: Path(m['Dir']) for m in read_json_stream((WORK / 'modules.json').read_text()) if 'Dir' in m}
    legal_modules = {**modules, 'Go': {'path': 'Go', 'version': '1.27.1'}}
    for module in sorted(legal_modules):
        directory = args.go.resolve().parents[1] if module == 'Go' else module_directories[module]
        licenses = sorted(p for p in directory.iterdir() if p.is_file() and
                          re.fullmatch(r'(LICENSE|LICENCE|COPYING|COPYRIGHT|NOTICE)(\..*)?', p.name, re.I))
        if not licenses:
            raise ValueError('NATIVE_MODULE_LICENSE_MISSING: ' + module)
        prefix = 'legal/' + module.replace('/', '_') + '/'
        for license_file in licenses:
            contents[prefix + license_file.name] = license_file.read_bytes()
    provenance = {
        'schema': 1, 'coordinate': 'com.patmanak.contako.crypto:android-golib:' + VERSION,
        'upstream_reference_sha256': sha((WORK / 'official.aar').read_bytes()),
        'public_java_api_sha256': sha(rebuilt_api.encode()), 'native': native,
        'modules': sorted(modules.values(), key=lambda m: m['path']),
        'source_descriptors': {f: sha(canonical_text((SOURCE / f).read_bytes()))
                               for f in ('go.mod', 'go.sum', 'dependencies.go', 'build.ps1', 'build.sh', 'package-aar.py', 'golib.pom')},
    }
    contents['META-INF/contako-native-provenance.json'] = (json.dumps(provenance, indent=2) + '\n').encode()
    output = normalized_zip(contents)
    repository = args.repository / 'com/patmanak/contako/crypto/android-golib' / VERSION
    repository.mkdir(parents=True, exist_ok=True)
    artifact = repository / ('android-golib-' + VERSION + '.aar')
    # Never leave a partially packaged file at the dependency's final path.
    temporary = artifact.with_suffix('.aar.tmp')
    temporary.write_bytes(output)
    temporary.replace(artifact)
    pom = repository / ('android-golib-' + VERSION + '.pom')
    pom.write_bytes(canonical_text((SOURCE / 'golib.pom').read_bytes()))
    print(json.dumps({'aar_sha256': sha(output), 'pom_sha256': sha(pom.read_bytes()),
                      'public_api_equal': True, 'native_architectures': len(native), 'go': '1.27.1'}))


if __name__ == '__main__':
    main()
