from pathlib import Path
import subprocess, json, zipfile, hashlib, difflib

root = Path('/home/rocky/br-native-v15-consumer'); out = root / 'qualification/incremental-notice'; out.mkdir()
notice = root / 'NOTICE'; build = root / 'forge/build.gradle'
original_notice = notice.read_bytes(); original_build = build.read_bytes()
probe_notice = original_notice + b'\nIncremental packaging test marker; not a distributable notice.\n'
line = "    inputs.files(rootProject.file('../LICENSE'), rootProject.file('../NOTICE')).withPropertyName('distributionNotices')"
source = original_build.decode(); assert source.count(line) == 1
summary = {}

def run(name):
    dest = out / name; dest.mkdir()
    command = ['bash', 'gradlew', '--no-daemon', '--console=plain', 'jar', '-PbrNativesDir=' + str(root / 'staged')]
    with (dest / 'gradle.log').open('x') as log: result = subprocess.run(command, cwd=root / 'forge', stdout=log, stderr=subprocess.STDOUT)
    (dest / 'command.json').write_text(json.dumps({'command': command, 'exit': result.returncode}))
    assert result.returncode == 0, name
    generated = (root / 'forge/build/generated/engine/META-INF/NOTICE').read_bytes()
    with zipfile.ZipFile(root / 'forge/build/libs/blockreality-0.4.0-dev.jar') as z: packaged = z.read('META-INF/NOTICE')
    (dest / 'source-NOTICE.txt').write_bytes(notice.read_bytes()); (dest / 'generated-NOTICE.txt').write_bytes(generated); (dest / 'packaged-NOTICE.txt').write_bytes(packaged)
    summary[name] = {'source_sha256': hashlib.sha256(notice.read_bytes()).hexdigest(), 'generated_sha256': hashlib.sha256(generated).hexdigest(), 'packaged_sha256': hashlib.sha256(packaged).hexdigest(), 'bundle_up_to_date': '> Task :bundleEngines UP-TO-DATE' in (dest / 'gradle.log').read_text()}
    return generated, packaged

try:
    assert run('control') == (original_notice, original_notice)
    assert run('unchanged') == (original_notice, original_notice); assert summary['unchanged']['bundle_up_to_date']
    notice.write_bytes(probe_notice); assert run('notice-changed') == (probe_notice, probe_notice); assert not summary['notice-changed']['bundle_up_to_date']
    notice.write_bytes(original_notice); assert run('notice-restored') == (original_notice, original_notice)
    changed = source.replace(line, '    // Mutant: omitted distribution notice inputs.')
    build.write_text(changed); (out / 'mutation.diff').write_text(''.join(difflib.unified_diff(source.splitlines(True), changed.splitlines(True))))
    assert run('mutant-settle') == (original_notice, original_notice)
    notice.write_bytes(probe_notice); actual = run('mutant-stale')
    assert summary['mutant-stale']['bundle_up_to_date']
    try: assert actual == (probe_notice, probe_notice), 'Incremental jar lost updated NOTICE'
    except AssertionError as error: summary['guard_removal'] = {'failure_type': type(error).__name__, 'message': str(error), 'gradle_exit': 0}
    else: raise AssertionError('Missing-input mutant survived artifact comparison')
finally:
    notice.write_bytes(original_notice); build.write_bytes(original_build)
assert run('restored') == (original_notice, original_notice)
(out / 'summary.json').write_text(json.dumps(summary, indent=2)); print('PASS incremental source/generated/jar identity; successful-task mutant exposes stale NOTICE; originals restored')
