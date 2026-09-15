from pathlib import Path
import subprocess,tarfile,shutil,json,xml.etree.ElementTree as ET
root=Path('/home/rocky/br-mg-mutations');root.mkdir()
with tarfile.open('/mnt/c/Users/wmc02/Desktop/block-reality/build/material-geometry/module-final-source.tar') as archive:archive.extractall(root,filter='data')
out=root/'qualification';out.mkdir();reports=[]
arms=[('swapped-depth-width','engine/ProductForm.java',
       'double width = product.dimensionsMetres().get(0), depth = product.dimensionsMetres().get(1);',
       'double width = product.dimensionsMetres().get(1), depth = product.dimensionsMetres().get(0);',
       '*ProductFormTest','unequalDeclaredWidthsAndDepthsKeepTheirAxesInAllThreeOrientations()'),
      ('noncontact-face-cull','engine/ProductForm.java',
       'if (sign == 1 ? max(axis) != 1 || neighbor.min(axis) != 0 : min(axis) != 0 || neighbor.max(axis) != 1)',
       'if (false)', '*ProductFormTest','onlyFullFaceContactHidesAScanSurface()'),
      ('equalized-sample-extents','render/BeamSurfacePatch.java',
       'd.dot(field.az()) / widthHalfMm','d.dot(field.az()) / depthHalfMm',
       '*BsiBeamDisplayTest','unequalVisualExtentsRetainIndependentNativeFacesAndBothSidesOfAJump()')]
for name,relative,before,after,selector,test in arms:
    path=root/'mod/core/src/main/java/com/blockreality/core'/relative;original=path.read_bytes()
    text=original.decode();assert text.count(before)==1
    try:
        path.write_text(text.replace(before,after));arm=out/name;arm.mkdir()
        with (arm/'gradle.log').open('w') as log:
            code=subprocess.run(['bash','gradlew','--no-daemon',':core:test','--tests',selector,'--rerun-tasks','--console=plain'],cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT).returncode
        folder=root/'mod/core/build/test-results/test';shutil.copytree(folder,arm/'xml')
        cases=[c for f in folder.glob('TEST-*.xml') for c in ET.parse(f).getroot().findall('testcase')]
        failures=[c.get('name') for c in cases if c.find('failure') is not None]
        report={'arm':name,'exit':code,'cases':len(cases),'failed':failures,'expectedFailure':test}
        reports.append(report);(out/'results.json').write_text(json.dumps(reports,indent=2))
        assert code!=0 and test in failures and '> Task :core:test FAILED' in (arm/'gradle.log').read_text(),report
        print(name+': compiled behavioral failure observed',flush=True)
    finally:path.write_bytes(original)
with (out/'restored.log').open('w') as log:
    code=subprocess.run(['bash','gradlew','--no-daemon',':core:test','--tests','*ProductFormTest','--tests','*BsiBeamDisplayTest','--console=plain'],cwd=root/'mod',stdout=log,stderr=subprocess.STDOUT).returncode
shutil.copytree(root/'mod/core/build/test-results/test',out/'restored-xml');assert code==0
print('restored production sources: PASS',flush=True)
