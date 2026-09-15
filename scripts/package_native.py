#!/usr/bin/env python3
"""Build a complete Forge distribution from the exact SDK in native-release.json."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from stage_release_natives import verify_release

ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd=ROOT, env=None):
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--release-dir', type=Path, help='offline SDK assets including SHA256SUMS')
    ap.add_argument('--out', type=Path, required=True, help='new distribution directory')
    ap.add_argument('--candidate', action='store_true', help='record local assets as unpublished')
    a = ap.parse_args()
    out = a.out.resolve()
    if out.exists():
        raise ValueError('output already exists; use a new directory')
    lock = json.loads((ROOT / 'native-release.json').read_text())
    version = re.search(r"^version = '([^']+)'", (ROOT / 'forge/build.gradle').read_text(), re.M)[1]
    if version != lock['modVersion']:
        raise ValueError('Forge version differs from release lock')
    out.parent.mkdir(parents=True, exist_ok=True)
    # The output appears only after all checks pass. Temporary work is on the same
    # filesystem so the final rename is atomic and never replaces an existing dist.
    with tempfile.TemporaryDirectory(prefix='.native-package-', dir=out.parent) as temporary:
        work = Path(temporary)
        release = a.release_dir.resolve() if a.release_dir else work / 'download'
        if not a.release_dir:
            release.mkdir()
            gh = shutil.which('gh')
            if not gh:
                raise ValueError('gh is required to download the SDK; or pass --release-dir')
            run(gh, 'release', 'download', lock['tag'], '--repo', lock['repository'],
                '--dir', release, '--pattern', 'SHA256SUMS', '--pattern', 'tectonic2-*.zip',
                '--pattern', 'tectonic2-*-source.tar.gz')
        payloads, provenance = verify_release(release, lock['version'], lock['sourceCommit'],
            lock['sumsSha256'], 'candidate' if a.candidate else 'published', 'sdk')
        natives = work / 'natives'
        for name, data in payloads.items():
            p = natives / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        platform = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
        lib = natives / platform / ('bsi_tectonic.dll' if os.name == 'nt' else 'libbsi_tectonic.so')
        env = dict(os.environ, BR_ENGINE=str(lib), OPENBLAS_CORETYPE='Haswell', OPENBLAS_NUM_THREADS='4')
        wrapper = 'gradlew.bat' if os.name == 'nt' else 'gradlew'
        def gradle(directory, *tasks):
            command = [str(ROOT / directory / wrapper), '--no-daemon', *tasks]
            if os.name != 'nt':
                command.insert(0, 'bash')
            run(*command, cwd=ROOT / directory, env=env)
        gradle('mod', 'test', ':core:constructionRecoveryGate',
               '-Dbr.transactionEvidence=' + str(work / 'recovery'))
        gradle('forge', 'build', '-PbrEngineDir=none', '-PbrNativesDir=' + str(natives))
        dist = work / 'dist'
        dist.mkdir()
        jar = ROOT / 'forge/build/libs' / f'blockreality-{version}.jar'
        shutil.copy2(jar, dist / jar.name)
        for name in ['LICENSE', 'NOTICE', 'native-release.json']:
            shutil.copy2(ROOT / name, dist / name)
        (dist / 'engine-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8', newline='\n')
        (dist / 'START-HERE.txt').write_text(
            f'Block Reality {version} / Tectonic {lock["tag"]}\n\n'
            'Minecraft Java 1.20.1 + Forge 47.x, Java 17.\n'
            'Copy the single blockreality-*.jar into mods/ on the client and server.\n'
            'The jar contains Windows x86_64 and Linux x86_64 native engines.\n'
            'Linux requires glibc 2.35 or newer. No sidecar or FrameCore installation.\n'
            'Alpha: full v2 roadmap and in-game qualification are not complete.\n', encoding='utf-8', newline='\n')
        (dist / 'SHA256SUMS.txt').write_text(''.join(
            hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
            for p in sorted(dist.iterdir())), encoding='ascii', newline='\n')
        run(sys.executable, ROOT / 'scripts/check_bundle.py', dist)
        run(sys.executable, ROOT / 'scripts/check_native_only_jar.py', dist / jar.name)
        dist.rename(out)
    print('NATIVE-PACKAGE PASS', out)


if __name__ == '__main__':
    main()
