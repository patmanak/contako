"""Compare a developer-signed Contako APK with an independent unsigned rebuild.

Uses apksigcopier's signature-copy/byte-comparison mechanism, not a new signing
scheme. This checks artifacts only; source/build provenance is a separate gate.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

SIGNER_SHA256 = '2620844332bce89ff471d0c64629f25fe3fabd1d465d01780f7649f4d023f4fb'


def compare(published, rebuilt):
    if published.resolve() == rebuilt.resolve() or os.path.samefile(published, rebuilt):
        raise ValueError('DISTINCT_ARTIFACTS_REQUIRED')
    if any(name.startswith('APKSIGCOPIER_') for name in os.environ):
        raise ValueError('COMPARISON_ENVIRONMENT_OVERRIDE')
    apksigner = shutil.which('apksigner')
    if not apksigner:
        raise ValueError('APKSIGNER_REQUIRED')
    signed = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', '--', str(published)],
                            capture_output=True, text=True, check=False)
    digests = re.findall(r'^(?:Signer #\d+|V[0-9.]+ Signer):? certificate SHA-256 digest: ([0-9a-fA-F]+)$',
                         signed.stdout, re.MULTILINE)
    signer_counts = re.findall(r'^Number of signers: (\d+)$', signed.stdout, re.MULTILINE)
    if signed.returncode or signer_counts != ['1'] or {x.lower() for x in digests} != {SIGNER_SHA256}:
        raise ValueError('PUBLISHED_SIGNER_MISMATCH')
    unsigned = subprocess.run([apksigner, 'verify', '--', str(rebuilt)],
                              capture_output=True, check=False)
    if unsigned.returncode == 0:
        raise ValueError('UNSIGNED_REBUILD_REQUIRED')
    # The upstream comparator validates the APK structure and verifies copied
    # signatures. A broken/malformed unsigned APK cannot pass by failing verify.
    from apksigcopier import do_compare
    do_compare(str(published), str(rebuilt), unsigned=True, verify_cmd=(apksigner, 'verify'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('published', type=Path)
    parser.add_argument('rebuilt', type=Path)
    args = parser.parse_args()
    try:
        compare(args.published, args.rebuilt)
    except (ValueError, OSError, ImportError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1
    print('PASS: APK reproduction matches the pinned developer signature.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
