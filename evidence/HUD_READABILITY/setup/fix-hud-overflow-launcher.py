from pathlib import Path
out=Path('/home/rocky/br-hud-readability-final-overflow')
(out/'launcher-first-error.txt').write_text("FileNotFoundError before a child process started: cwd '/home/rocky/br-hud-readability-final-overflow-client/forge'. A setup string replacement also changed the client checkout path. The original launcher and empty first log are retained.\n")
s=(out/'launcher.py').read_text().replace("cwd='/home/rocky/br-hud-readability-final-overflow-client/forge'","cwd='/home/rocky/br-hud-readability-final-client/forge'").replace('client-first.log','client-second.log').replace('client-first-exit.json','client-second-exit.json').replace('client-xvfb-first.log','client-xvfb-second.log')
(out/'launcher-second.py').write_text(s)
