#!/usr/bin/env python3
"""Exercise release staging with actual published archives and deliberately broken chains.

Each nested corruption is rehashed through outer inventories so an earlier checksum
cannot mask the guard being exercised. Copies are in a fresh output directory.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import zipfile


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--release-dir',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--sums-sha256',required=True)
    ap.add_argument('--version', default='1.3.0')
    ap.add_argument('--source-commit', default='c90b448194b52b9c7b4a48dc049581d4b640d2d4')
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=False)
    spec=importlib.util.spec_from_file_location('stage',Path(__file__).with_name('stage_release_natives.py'))
    stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)
    revision=args.source_commit;version=args.version
    payloads,provenance=stage.verify_release(args.release_dir,version,revision,args.sums_sha256)
    assert len(provenance['libraries'])==2
    asset=f'tectonic2-{version}-windows-x86_64.zip'
    original=stage.sums((args.release_dir/'SHA256SUMS').read_bytes())
    with zipfile.ZipFile(args.release_dir/asset) as z:
        original_files={n:z.read(n) for n in z.namelist()}
    records={}
    cases={
        'ROOT':'published SHA256SUMS hash mismatch',
        'ASSET':'release asset hash mismatch',
        'MISSING_PLATFORM':'release asset inventory mismatch',
        'PATH':'unsafe path',
        'DUPLICATE':'duplicate native archive entry',
        'INNER':'native archive hash mismatch',
        'REVISION':'native provenance identity mismatch',
        'CONTRACT':'native provenance identity mismatch',
        'BINARY':'binary provenance mismatch',
        'SDK':'SDK mismatch',
    }
    for name,want in cases.items():
        directory=args.out/name;directory.mkdir();hashes=dict(original);files=dict(original_files)
        # Unmodified assets are read-only hard links; only rewritten ZIPs/SUMS use new files.
        for n in original:
            if n != asset: os.link(args.release_dir/n,directory/n)
        if name in ['REVISION','CONTRACT','BINARY']:
            doc=json.loads(files['provenance.json'])
            if name=='REVISION':doc['source_commit']='0'*40
            elif name=='CONTRACT':doc['contract_sha256']='0'*64
            else:doc['binary']['bytes']+=1
            files['provenance.json']=(json.dumps(doc)+'\n').encode()
        if name=='SDK':files['include/bsi.schema.json']+=b'\n'
        if name=='PATH':files['../escape']=b'rejected'
        if name=='INNER':files['bsi_tectonic.dll']=files['bsi_tectonic.dll'][:-1]+bytes([files['bsi_tectonic.dll'][-1]^1])
        if name!='INNER':
            files['SHA256SUMS']=''.join(stage.sha(b)+'  '+n+'\n' for n,b in files.items() if n!='SHA256SUMS').encode()
        with zipfile.ZipFile(directory/asset,'w',zipfile.ZIP_DEFLATED) as z:
            for n,b in files.items():z.writestr(n,b)
            if name=='DUPLICATE':z.writestr('provenance.json',files['provenance.json'])
        if name!='ASSET':hashes[asset]=stage.sha((directory/asset).read_bytes())
        if name=='MISSING_PLATFORM':del hashes[f'tectonic2-{version}-linux-x86_64.zip']
        inventory=''.join(h+'  '+n+'\n' for n,h in hashes.items()).encode()
        (directory/'SHA256SUMS').write_bytes(inventory)
        trusted='0'*64 if name=='ROOT' else stage.sha(inventory)
        def expect_rejected():
            try:stage.verify_release(directory,version,revision,trusted)
            except ValueError as ex:
                assert want in str(ex),(name,str(ex))
                return str(ex)
            raise AssertionError(f'{name} corruption accepted')
        reason=expect_rejected()
        records[name]={'rejected':reason,'sums_sha256':trusted,'asset_sha256':stage.sha((directory/asset).read_bytes())}
        # Bypass only this predicate; the same adversarial input must now fail the oracle.
        # Inventory/path corruptions can have secondary failures, so mutation biting is
        # restricted to independent identity/content guards below.
        if name in ['REVISION','CONTRACT','BINARY','SDK']:
            original_require=stage.require
            def weakened(condition,message):
                if not message.startswith(want):original_require(condition,message)
            stage.require=weakened
            try:
                try:expect_rejected()
                except AssertionError as ex:
                    assert str(ex)==f'{name} corruption accepted',str(ex)
                    records[name]['guard_removal']={'failure_type':type(ex).__name__,'message':str(ex)}
                else:raise AssertionError(f'{name} guard removal survived the rejection oracle')
            finally:stage.require=original_require
        print(name,'REJECTED',flush=True)
        (args.out/'verification.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
    print(f'PASS {len(records)} real-archive corruption cases; 4 independent guard removals exposed')


if __name__=='__main__':main()
