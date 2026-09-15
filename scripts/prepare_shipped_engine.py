#!/usr/bin/env python3
"""Validate the shipped JAR and extract its host library for CI's real-engine tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--dist', type=Path, default=ROOT / 'dist')
    ap.add_argument('--out', type=Path, required=True)
    a = ap.parse_args()
    lock = json.loads((ROOT / 'native-release.json').read_text())
    for line in (a.dist / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        p = (a.dist / name).resolve()
        if not p.is_relative_to(a.dist.resolve()) or hashlib.sha256(p.read_bytes()).hexdigest() != digest:
            raise ValueError('distribution checksum mismatch: ' + name)
    subprocess.run([sys.executable, str(ROOT / 'scripts/check_bundle.py'), str(a.dist)], check=True)
    jar = a.dist / f'blockreality-{lock["modVersion"]}.jar'
    subprocess.run([sys.executable, str(ROOT / 'scripts/check_native_only_jar.py'), str(jar)], check=True)
    provenance = json.loads((a.dist / 'engine-provenance.json').read_text())
    if (provenance['release']['sourceCommit'] != lock['sourceCommit'] or
            provenance['release']['sha256sums'] != lock['sumsSha256'] or
            provenance['contractSha256'] != (ROOT / 'contract/CONTRACT_SHA256').read_text().strip()):
        raise ValueError('shipped provenance differs from source release lock')
    if (json.loads((a.dist / 'native-release.json').read_text()) != lock or
            (ROOT / '.github/tectonic2-contract-ref').read_text().splitlines()[0] != lock['sourceCommit']):
        raise ValueError('source and distribution release locks disagree')
    payloads = {}
    with zipfile.ZipFile(jar) as z:
        for row in provenance['libraries']:
            data = z.read(f'blockreality-engine/{row["platform"]}/{row["file"]}')
            if (hashlib.sha256(data).hexdigest() != row['sha256'] or len(data) != row['size'] or
                    row['version'] != lock['version'] or row['buildSha'] != lock['sourceCommit'][:7]):
                raise ValueError('shipped library differs from locked SDK')
            payloads[row['platform']] = (row['file'], data)
    if set(payloads) != {'windows-x86_64', 'linux-x86_64'}:
        raise ValueError('both native platforms are required')
    platform = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    name, data = payloads[platform]
    a.out.mkdir(parents=True, exist_ok=False)
    library = (a.out / name).resolve()
    library.write_bytes(data)
    if os.environ.get('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as f:
            f.write(f'BR_ENGINE={library}\n')
    print('SHIPPED-ENGINE PASS', library)


if __name__ == '__main__':
    main()
