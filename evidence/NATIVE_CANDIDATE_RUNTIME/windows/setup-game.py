from pathlib import Path
import hashlib,json,shutil,socket,tarfile,xml.etree.ElementTree as ET
base=Path('/home/rocky'); workspace=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-candidate-runtime')
root=base/'br-native-candidate-runtime'; qual=root/'qualification'
for project,folder in [('core',root/'mod/core/build/test-results/test'),('forge',root/'forge/build/test-results/test')]:
    cases=[c for f in sorted(folder.glob('TEST-*.xml')) for c in ET.parse(f).getroot().findall('testcase')]
    assert cases
    result={'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases),'skips':[{'class':c.get('classname'),'test':c.get('name')} for c in cases if c.find('skipped') is not None]}
    result['passed']=result['tests']-result['failures']-result['errors']-result['skipped']
    shutil.copytree(folder,qual/f'linux-{project}-first-xml')
    (qual/f'linux-{project}-first-tests.json').write_text(json.dumps(result,indent=2)+'\n')
    print(project,result,flush=True)
for port in [25594,25595,25596,25597]:
    with socket.socket() as sock:sock.bind(('127.0.0.1',port))
for name in ['br-ncr-render-server','br-ncr-render-client']:
    dest=base/name;dest.mkdir()
    with tarfile.open(workspace/'module-source.tar') as tar:tar.extractall(dest,filter='data')
staged=root/'staged';shutil.copytree(workspace/'staged',staged)
props=(base/'br-client-render-server/forge/run/server.properties').read_text()
forge_defaults=base/'br-client-render-server/forge/run/render-probe/serverconfig/forge-server.toml'
for directory,properties in [(root/'forge/run',props.replace('render-probe','runtime-smoke').replace('25594','25596').replace('25595','25597')),(base/'br-ncr-render-server/forge/run',props)]:
    directory.mkdir();(directory/'server.properties').write_text(properties);(directory/'eula.txt').write_text('eula=true\n')
    (directory/'defaultconfigs').mkdir();shutil.copy2(forge_defaults,directory/'defaultconfigs/forge-server.toml')
(root/'forge/run/defaultconfigs/blockreality-server.toml').write_text('[engine]\nmode = "OFF"\n')
profile=base/'br-ncr-render-client/forge/run-client-probe';profile.mkdir();(profile/'options.txt').write_text('onboardAccessibility:false\n')
out=base/'br-ncr-render-first';out.mkdir();(out/'CRP_OWNED').write_text('block-reality-client-render-probe-v1\n')
identity={'module_source':'c2a1b94','criteria':'d96d98a','native_source':'42e10f5f7af166788588afcd2fb2fd97101d16dc','native_sha256':hashlib.sha256((staged/'linux-x86_64/libbsi_tectonic.so').read_bytes()).hexdigest(),'loading':'bundled resources in development classpath; no BR_ENGINE/config path override','server':str(base/'br-ncr-render-server/forge/run'),'client':str(profile),'scope':'actual isolated Forge development client/server, not installed jar'}
(out/'identity.json').write_text(json.dumps(identity,indent=2)+'\n')
launcher=(base/'br-hud-readability-final/launcher.py').read_text().replace('br-hud-readability-final-client','br-ncr-render-client').replace('br-hud-readability-final','br-ncr-render-first')
launcher=launcher.replace("env=os.environ.copy()","env=os.environ.copy();env.pop('BR_ENGINE',None)")
launcher=launcher.replace("'runClient','--console=plain'",f"'-PbrNativesDir={staged}','runClient','--console=plain'")
(out/'launcher.py').write_text(launcher)
print('Fresh owned runtime and render fixtures ready; bundled dev resources selected')
