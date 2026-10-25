"""Build a signed offline installer from a reviewed definition and explicit Docker images.

No live Compose, host credentials or data directories are read. The signing key is
an external CI input and is never copied into the package.
"""
import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile


def digest(file):
    result=hashlib.sha256()
    with file.open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):result.update(block)
    return result.hexdigest()


def run(args):
    process=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=900)
    if process.returncode:raise RuntimeError('Installer packaging command failed: '+args[0])
    return process.stdout


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--definition',required=True,type=Path)
    parser.add_argument('--image-map',required=True,type=Path)
    parser.add_argument('--assets-dir',required=True,type=Path)
    parser.add_argument('--signing-key',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--image-archive',type=Path,help='Reuse an already exported Docker-save archive after verifying all image identities')
    args=parser.parse_args()
    assert not args.output.exists(),'Do not overwrite an existing distribution'
    doc=json.loads(args.definition.read_text(encoding='utf-8-sig'))
    assert doc.get('format')=='live-monitor.install.v1'
    mapping=json.loads(args.image_map.read_text(encoding='utf-8-sig'))
    images={name:json.loads(run(['docker','image','inspect',ref]))[0]['Id'] for name,ref in mapping.items()}
    def resolve(value):
        if isinstance(value,str) and value.startswith('@image:'):
            assert value[7:] in images,'Unknown declared image input'
            return images[value[7:]]
        if isinstance(value,dict):return {key:resolve(item) for key,item in value.items()}
        if isinstance(value,list):return [resolve(item) for item in value]
        return value
    doc=resolve(copy.deepcopy(doc))
    for service in doc['compose']['services'].values():
        targets=[mount['target'] for mount in service.get('volumes',[])]
        assert len(targets)==len(set(targets)),'Duplicate mount targets in installation definition'
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='installer-build-',dir=args.output.parent) as temp:
        root=Path(temp);(root/'images').mkdir()
        archive=root/'images/docker-images.tar.gz'
        if args.image_archive:
            documents={};total=0
            with tarfile.open(args.image_archive,'r|*') as saved:
                for item in saved:
                    if item.isfile() and 0<item.size<=1048576:
                        data=saved.extractfile(item).read()
                        if data.lstrip().startswith((b'{',b'[')):
                            total+=len(data);assert total<=16777216
                            documents[item.name]=data
            entries=json.loads(documents['manifest.json'])
            archived={'sha256:'+hashlib.sha256(documents[item['Config']]).hexdigest() for item in entries}
            assert archived==set(images.values()),'Cached image archive does not match explicit image inputs'
            shutil.copyfile(args.image_archive,archive)
        else:
            with gzip.open(archive,'wb',compresslevel=1) as output:
                process=subprocess.Popen(['docker','save',*sorted(set(images.values()))],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                try:shutil.copyfileobj(process.stdout,output,1048576)
                finally:process.stdout.close()
                if process.wait():raise RuntimeError('Docker image export failed')
        files={'images/docker-images.tar.gz':digest(archive)}
        declared={asset['source'] for asset in doc.get('assets',[])}
        for relative in declared:
            path=PurePosixPath(relative)
            assert not path.is_absolute() and '..' not in path.parts and '\\' not in relative
            source=args.assets_dir.resolve()/relative
            assert source.is_file() and not source.is_symlink() and args.assets_dir.resolve() in source.resolve().parents
            if source.stat().st_size<1048576:
                assert b'PRIVATE KEY-----' not in source.read_bytes(),'Private signing/key material cannot be distributed'
            destination=root/relative;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,destination);files[relative]=digest(destination)
        doc['images']=[{'file':'images/docker-images.tar.gz','ids':sorted(set(images.values()))}]
        doc['files']=files
        manifest=root/'installer.json';manifest.write_text(json.dumps(doc,sort_keys=True,separators=(',',':')),encoding='utf-8')
        run(['openssl','dgst','-sha256','-sign',str(args.signing_key),'-out',str(root/'installer.sig'),str(manifest)])
        with tarfile.open(args.output,'w:gz',compresslevel=1) as package:
            for name in ['installer.json','installer.sig',*sorted(files)]:
                file=root/name;info=package.gettarinfo(str(file),arcname=name);info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o600
                with file.open('rb') as source:package.addfile(info,source)
    report={'bundle':str(args.output),'bundleSha256':digest(args.output),'bundleBytes':args.output.stat().st_size,'product':doc['product'],'version':doc['version'],'images':images,'signingKeyIncluded':False,'liveConfigurationIncluded':False}
    args.output.with_suffix(args.output.suffix+'.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))


if __name__=='__main__':main()
