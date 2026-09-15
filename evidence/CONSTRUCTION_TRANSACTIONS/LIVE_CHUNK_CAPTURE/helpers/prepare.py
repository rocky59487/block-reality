from pathlib import Path
import json,subprocess,hashlib
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/live-chunk-capture'
for source,target,replacements in [
    ('setup.py','setup.py',{}),
    ('build-harness.py','build-harness.py',{'testchunkprobe':'testcaptureprobe','br-chunk-probe':'br-capture-probe'}),
    ('launch-installed.py','launch-installed.py',{'bounded-reader':'live-capture','bounded-chunk-runtime':'live-capture-runtime','chunk-read':'live-capture'})]:
    text=(root/'build/bounded-chunk-read'/source).read_text(encoding='utf-8-sig').replace('bounded-chunk-read','live-chunk-capture')
    for a,b in replacements.items():text=text.replace(a,b)
    if source=='setup.py':text=text.replace("['run.py','test.gradle']","['run.py']")
    (out/target).write_text(text,encoding='utf-8')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
(out/'source.json').write_text(json.dumps({'source':head,'archive_sha256':hashlib.sha256((out/'source.tar').read_bytes()).hexdigest()},indent=2))
