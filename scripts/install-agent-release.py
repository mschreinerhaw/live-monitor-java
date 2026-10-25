"""Atomically install verified Agent code, retaining old code and all host configuration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--installer-product', help='Explicitly enable installation of this product')
    parser.add_argument('--trust-public-key', type=Path, help='Out-of-band trusted release public key; never taken from an uploaded bundle')
    parser.add_argument('--installer-capability', action='append', default=[])
    parser.add_argument('--config', type=Path, default=Path('/etc/live-monitor-agent.json'))
    parser.add_argument('--installer-max-cpus',type=float)
    parser.add_argument('--installer-max-memory-mb',type=int)
    args = parser.parse_args()
    assert os.getuid() == 0
    os.umask(0o077)
    release, target = args.release.resolve(), args.target.resolve()
    assert release.is_dir() and target.parent == Path('/opt') and target != release
    records = json.loads((release / 'agent-release.json').read_text())
    assert {'agent.py', 'stack_ops.py'} <= set(records) <= {'agent.py', 'stack_ops.py', 'installer.py'}
    for name, expected in records.items():
        file = release / name
        assert not file.is_symlink() and file.stat().st_uid == 0 and file.stat().st_mode & 0o022 == 0
        assert hashlib.sha256(file.read_bytes()).hexdigest() == expected
    target.mkdir(mode=0o755, exist_ok=True)
    assert target.stat().st_uid == 0 and target.stat().st_mode & 0o022 == 0
    backup = release / 'code-before'
    backup.mkdir(mode=0o700, exist_ok=False)
    installed = []
    try:
        for name in [file for file in ['stack_ops.py', 'installer.py', 'agent.py'] if file in records]:
            destination = target / name
            assert not destination.is_symlink()
            if destination.exists():
                shutil.copy2(destination, backup / name)
            temporary = target / (name + '.new')
            assert not temporary.exists()
            shutil.copyfile(release / name, temporary)
            temporary.chmod(0o644)
            os.replace(temporary, destination)
            installed.append(name)
    except Exception:
        for name in installed:
            if (backup / name).exists():
                shutil.copy2(backup / name, target / name)
        raise
    if args.installer_product:
        assert 'installer.py' in records and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}',args.installer_product)
        public=args.trust_public_key
        assert public and public.is_file() and not public.is_symlink() and public.stat().st_uid==0 and public.stat().st_mode&0o022==0
        subprocess.run(['openssl','pkey','-pubin','-in',str(public),'-noout'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        config_file=args.config.resolve();assert config_file.is_file() and config_file.stat().st_uid==0 and config_file.stat().st_mode&0o022==0
        config=json.loads(config_file.read_text());shutil.copy2(config_file,backup/'agent-config-before-private.json')
        trust=Path('/etc/live-monitor-agent-trust');trust.mkdir(mode=0o755,exist_ok=True)
        assert not trust.is_symlink() and trust.stat().st_uid==0 and trust.stat().st_mode&0o022==0
        key=trust/(hashlib.sha256(public.read_bytes()).hexdigest()+'.pub')
        assert not key.is_symlink()
        if not key.exists():shutil.copyfile(public,key);key.chmod(0o644)
        else:assert key.read_bytes()==public.read_bytes()
        policy=config.setdefault('install_policy',{})
        policy.update(enabled=True,products=sorted(set(policy.get('products',[]))|{args.installer_product}),trusted_public_keys=sorted(set(policy.get('trusted_public_keys',[]))|{str(key)}),capabilities=sorted(set(policy.get('capabilities',[]))|set(args.installer_capability)))
        if args.installer_max_cpus is not None:
            assert 0<args.installer_max_cpus<=256;policy['max_service_cpus']=args.installer_max_cpus
        if args.installer_max_memory_mb is not None:
            assert 16<=args.installer_max_memory_mb<=1048576;policy['max_service_memory_mb']=args.installer_max_memory_mb
        config['allow_image_import']=True
        temporary=config_file.with_suffix('.installer-new');temporary.write_text(json.dumps(config));temporary.chmod(0o600);os.replace(temporary,config_file)
    print(json.dumps({'installedCode': records, 'previousCodeDirectory': str(backup), 'hostConfigurationUnchanged': not bool(args.installer_product)}))


if __name__ == '__main__':
    main()
