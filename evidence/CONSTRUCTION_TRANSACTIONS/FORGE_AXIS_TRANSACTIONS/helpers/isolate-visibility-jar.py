from pathlib import Path
import zipfile,json,hashlib,shutil
root=Path('/home/rocky/br-forge-axis-transactions');out=root/'qualification/mutations';win=Path('/mnt/c/Users/wmc02/Desktop/block-reality/build/forge-axis-transactions')
shutil.copy2(win/'setup-audit.py',out/'setup-audit-first.py')
(out/'first-inspection-failure.json').write_text(json.dumps({'status':'FAIL','stage':'setup before first visibility runtime','observed_tool_output':'AssertionError at setup-audit.py line 18: assert all(n.startswith("com/blockreality/impl/server/StructureManager") for n in differences)','transcribed_from_tool_output_not_raw_subprocess_log':True,'cause':'The mutation was built from Git LF resources; the Windows ordinary control contains CRLF in five text resources. No class besides StructureManager differs. The failed owned server had only libraries and an empty mods folder.','correction':'Validate compiled mutant against the Linux build, then place its sole changed class into an exact resource-preserving copy of the Windows control. Only the negative artifact is repackaged.'},indent=2))
cls='com/blockreality/impl/server/StructureManager.class';negative=out/'provisional-observation.jar';control=win/'blockreality-0.4.0-dev-axis-third.jar'
with zipfile.ZipFile(root/'qualification/blockreality-0.4.0-dev-axis-third.jar') as a,zipfile.ZipFile(negative) as b:
    differences=[n for n in a.namelist() if a.read(n)!=b.read(n)];assert differences==[cls],differences
    compiled=b.read(cls)
result=out/'isolated-provisional-observation.jar'
with zipfile.ZipFile(control) as a,zipfile.ZipFile(result,'x') as b:
    for info in a.infolist():b.writestr(info,compiled if info.filename==cls else a.read(info.filename))
with zipfile.ZipFile(control) as a,zipfile.ZipFile(result) as b:
    assert a.namelist()==b.namelist()
    differences=[n for n in a.namelist() if a.read(n)!=b.read(n)];assert differences==[cls],differences
(out/'isolated-artifact.json').write_text(json.dumps({'ordinary_control_sha256':hashlib.sha256(control.read_bytes()).hexdigest(),'original_gradle_mutant_sha256':hashlib.sha256(negative.read_bytes()).hexdigest(),'isolated_negative_sha256':hashlib.sha256(result.read_bytes()).hexdigest(),'only_changed_entry':cls,'compiled_class_sha256':hashlib.sha256(compiled).hexdigest(),'ordinary_control_untouched':True,'all_resources_licenses_provenance_and_AT_byte_identical':True},indent=2));print('Isolated exactly one compiled negative class',flush=True)
