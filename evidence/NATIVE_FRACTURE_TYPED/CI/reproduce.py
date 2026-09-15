from pathlib import Path
import subprocess,shutil,json,hashlib,base64
r=Path('/mnt/c/Users/wmc02/Documents/ChatGPT/tectonic2')
out=Path('/home/rocky/tectonic-native-fracture-ci');out.mkdir(exist_ok=False)
source=r/'.agent-work/nfa-ci';shutil.copytree(r/'contract',out/'contract',ignore=shutil.ignore_patterns('__pycache__'))
host=Path('/home/rocky/tectonic-native-fracture-final-linux/host-checks/build/bsi-hostd')
for folder in ['build/host','build/contract/host']:
 p=out/folder;p.mkdir(parents=True);shutil.copy2(host,p/'bsi-hostd')
raw=[];sha=lambda b:hashlib.sha256(b).hexdigest()
def run(name,args):
 q=subprocess.run(args,cwd=out,capture_output=True);row={'name':name,'command':args,'exit':q.returncode}
 for k,b in [('stdout',q.stdout),('stderr',q.stderr)]:row[k+'_base64']=base64.b64encode(b).decode();row[k+'_sha256']=sha(b)
 raw.append(row);(out/'raw.json').write_text(json.dumps(raw,indent=2)+'\n')
 print(name,q.returncode,(q.stdout+q.stderr).decode(errors='replace')[-1000:],flush=True);return q
for name in ['consumer-before','engine-after','consumer-after']:
 lines=(source/(name+'.yml')).read_text().splitlines()
 start=next(i for i,s in enumerate(lines) if '# ABI2 is supported;' in s or '# Use one past the current ABI;' in s)
 end=next(i for i in range(start,len(lines)) if 'grep -F ' in lines[i] and 'bsi_engine_entry returned NULL' in lines[i])
 script='\n'.join(s[10:] for s in lines[start:end+1])+'\n'
 (out/(name+'.sh')).write_text(script)
 q=run(name,['bash','-euo','pipefail',str(out/(name+'.sh'))])
 assert q.returncode==(1 if name=='consumer-before' else 0)
q=run('build-current',['g++','-std=c++17','-shared','-fPIC','contract/host/test/stub_engine.cpp','-o','build/host/libbsi_stub_engine_current.so']);assert q.returncode==0
q=run('current-accepted',['bash','-c','build/host/bsi-hostd --engine build/host/libbsi_stub_engine_current.so </dev/null']);assert q.returncode==0
metadata={'host_binary_sha256':sha(host.read_bytes()),'contract_sha256':(out/'contract/CONTRACT_SHA256').read_text().strip(),'current_abi':3,'unknown_abi':4,'criteria':'preserve original existence/exit/exact-version message checks; current stub accepted','workflow_sha256':{name:sha((source/name).read_bytes()) for name in ['consumer-before.yml','consumer-after.yml','engine-after.yml']}}
(out/'summary.json').write_text(json.dumps(metadata,indent=2)+'\n')
