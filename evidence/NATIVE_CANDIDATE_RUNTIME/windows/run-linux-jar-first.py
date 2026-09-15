from pathlib import Path
import shutil,hashlib,subprocess,os
root=Path('/home/rocky/br-native-candidate-runtime');jar=root/'native/blockreality-0.4.0-dev.jar'
shutil.copy2('/mnt/c/Users/wmc02/Desktop/block-reality/build/native-candidate-runtime/blockreality-0.4.0-dev.jar',jar)
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='5a93c66bfe5f5a3dd8c1104a6e97fe000a2a0d2a8e66bf602821993d2a4c9b79'
env=os.environ.copy();env['OPENBLAS_CORETYPE']='Haswell';env['OPENBLAS_NUM_THREADS']='1'
args=['python3',str(root/'scripts/check_native_jar.py'),'--jar',str(jar),'--java','/usr/bin/java','--classes',str(root/'mod/core/build/classes/java/test'),'--jna','/home/rocky/.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.12.1/b1e93a735caea94f503e95e6fe79bf9cdc1e985d/jna-5.12.1.jar','--library',str(root/'native/libbsi_tectonic.so'),'--out',str(root/'qualification/jar-linux-first'),'--version','1.3.0','--build-sha','42e10f5','--eigen']
with (root/'qualification/jar-linux-first.log').open('x') as log:
 result=subprocess.run(args,env=env,stdout=log,stderr=subprocess.STDOUT)
print('Linux same-jar gate exit',result.returncode);raise SystemExit(result.returncode)
