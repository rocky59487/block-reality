from pathlib import Path
import subprocess,json,hashlib,zipfile
root=Path('C:/Users/wmc02/Desktop/block-reality');out=root/'build/forge-axis-transactions';javap=Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/javap.exe')
before=root/'build/live-chunk-capture/blockreality-0.4.0-dev-live-capture.jar';after=out/'blockreality-0.4.0-dev-axis-third.jar'
classes=['com.blockreality.core.RevisionGate$Display','com.blockreality.core.transaction.ManufacturedRegistry$Prepared','com.blockreality.impl.server.StructureManager$Gathered','com.blockreality.impl.server.WorldIndexData$RegistryGzip']
result={}
for cls in classes:
    outputs=[]
    for label,jar in [('before',before),('after',after)]:
        dest=out/('javap-'+label);dest.mkdir(exist_ok=True)
        command=[str(javap),'-classpath',str(jar),'-p','-c','-s',cls]
        r=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);assert r.returncode==0
        (dest/(cls+'.txt')).write_bytes(r.stdout);outputs.append(r.stdout)
    assert outputs[0]==outputs[1];result[cls]={'instruction_descriptor_output_identical':True,'sha256':hashlib.sha256(outputs[0]).hexdigest()}
(out/'companion-instructions.json').write_text(json.dumps(result,indent=2));print('Four companions have identical instructions and descriptors')
with zipfile.ZipFile(after) as z:assert len([n for n in z.namelist() if n.endswith('.class')])==261
