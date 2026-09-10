from pathlib import Path
import subprocess,sys
scripts=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions')
for case in ['prepared','flushed','committed']:
    subprocess.run([sys.executable,str(scripts/'setup-runtime.py'),case],check=True)
    for mode in [case,'restart','restart-again']:
        subprocess.run([sys.executable,str(scripts/'launch-runtime.py'),case,mode],check=True)
        subprocess.run([sys.executable,str(scripts/'snapshot-runtime.py'),case,mode],check=True)
