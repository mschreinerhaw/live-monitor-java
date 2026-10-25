"""Atomically install verified Agent code, retaining old code and all host configuration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    args = parser.parse_args()
    assert os.getuid() == 0
    os.umask(0o077)
    release, target = args.release.resolve(), args.target.resolve()
    assert release.is_dir() and target.parent == Path('/opt') and target != release
    records = json.loads((release / 'agent-release.json').read_text())
    assert set(records) == {'agent.py', 'stack_ops.py'}
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
        for name in ['stack_ops.py', 'agent.py']:
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
    print(json.dumps({'installedCode': records, 'previousCodeDirectory': str(backup), 'hostConfigurationUnchanged': True}))


if __name__ == '__main__':
    main()
