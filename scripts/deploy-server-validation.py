"""Install an isolated server validation release; never replace existing services.

Run on the authorized Linux server with the uploaded delivery and image archives.
Credentials remain in root-only files. A local authenticated TLS registry supports
environments whose Docker daemon cannot reach the public registry.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request

ERROR_LOG = None

def run(*args, input=None):
    p = subprocess.run(args, input=input, text=True, capture_output=True, timeout=300)
    if p.returncode:
        if ERROR_LOG is not None:
            write(ERROR_LOG, p.stdout + p.stderr)
        raise RuntimeError('Command failed: ' + args[0] + ' (private diagnostics retained on server)')
    return p.stdout.strip()


def write(path, value, mode=0o600):
    path.write_text(value)
    path.chmod(mode)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def inventory():
    ids = run('docker', 'ps', '-aq').split()
    if not ids:
        return {}
    return {x['Id']: {'name': x['Name'], 'image': x['Image'],
                      'startedAt': x['State']['StartedAt'], 'restartCount': x['RestartCount']}
            for x in json.loads(run('docker', 'inspect', *ids))}


def validate_workspace(root, resume, source):
    """Never adopt unknown data/config directories as a new installation."""
    marker = root / 'pending-installation.json'
    if resume:
        assert marker.is_file(), 'No owned installation marker; reconcile manually'
        assert json.loads(marker.read_text()) == {'source': source, 'root': str(root)}
    else:
        for name in ['config', 'certs', 'registry-data', 'data', 'logs', 'agent-state',
                     'backups', 'secrets', 'baseline.json', 'pending-installation.json']:
            assert not (root / name).exists(), 'Existing path must not be overwritten: ' + name


def main():
    global ERROR_LOG
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, required=True)
    p.add_argument('--release', required=True)
    p.add_argument('--port', type=int, default=18088)
    p.add_argument('--registry-port', type=int, default=18443)
    p.add_argument('--ssh-address', required=True)
    p.add_argument('--ssh-port', type=int, required=True)
    p.add_argument('--support-sha256', required=True)
    p.add_argument('--resume-owned-registry', action='store_true')
    a = p.parse_args()
    root = a.root.resolve()
    assert root.parent == Path('/opt') and root.name.endswith('-validation')
    assert a.release.isalnum() and 1024 < a.port < 65536
    assert os.getuid() == 0
    os.umask(0o077)
    ERROR_LOG = root / 'command-error-private.log'
    release = root / 'releases' / a.release
    manifest = json.loads((release / 'manifest.json').read_text())
    assert sha(release / 'platform.tar') == manifest['artifacts']['live-monitor-platform-container-ops-20261024.tar']['sha256']
    assert sha(release / 'agent.py') == manifest['artifacts']['agent/agent.py']['sha256']
    assert sha(release / 'validation-images.tar') == a.support_sha256
    assert not (root / 'deployment.json').exists(), 'Existing validation deployment: reconcile explicitly'
    assert not Path('/opt/live-monitor-agent/agent.py').exists(), 'Existing Agent must not be overwritten'
    assert not Path('/etc/live-monitor-agent.json').exists(), 'Existing Agent config must not be overwritten'
    validate_workspace(root, a.resume_owned_registry, manifest['sourceCommit'])
    for port in [a.port] if a.resume_owned_registry else [a.port, a.registry_port]:
        with socket.socket() as s:
            s.bind(('127.0.0.1', port))
    before = json.loads((root / 'baseline.json').read_text()) if a.resume_owned_registry else inventory()
    if not a.resume_owned_registry:
        import crypt  # Linux Python 3.11 deployment prerequisite; never expose the generated credential.
        write(root / 'pending-installation.json', json.dumps({'source': manifest['sourceCommit'], 'root': str(root)}))
        write(root / 'baseline.json', json.dumps(before, indent=2))
        for name in ['config', 'certs', 'registry-data', 'data', 'logs', 'agent-state', 'backups', 'secrets']:
            (root / name).mkdir(mode=0o700, exist_ok=True)
        for name in ['data', 'logs']:
            os.chown(root / name, 10001, 10001)
        run('docker', 'load', '-i', str(release / 'platform.tar'))
        run('docker', 'load', '-i', str(release / 'validation-images.tar'))
        cert, key = root / 'certs/registry.crt', root / 'certs/registry.key'
        certificate_config = root / 'certs/openssl.cnf'
        write(certificate_config, '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=localhost\n[extensions]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\n')
        run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '365',
            '-keyout', str(key), '-out', str(cert), '-config', str(certificate_config))
        trust = Path('/etc/docker/certs.d') / ('localhost:' + str(a.registry_port))
        assert not trust.exists(), 'Registry trust already exists'
        trust.mkdir(parents=True, mode=0o755)
        shutil.copyfile(cert, trust / 'ca.crt')
        password = secrets.token_urlsafe(32)
        write(root / 'config/htpasswd', 'validation:' + crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH)) + '\n')
        docker_config = root / 'secrets/registry'
        docker_config.mkdir(mode=0o700)
        registry = 'localhost:' + str(a.registry_port)
        write(docker_config / 'config.json', json.dumps({'auths': {registry: {'auth': base64.b64encode(('validation:' + password).encode()).decode()}}}))
        registry_name = 'live-monitor-validation-registry'
        run('docker', 'run', '-d', '--name', registry_name, '--label', 'live-monitor.validation=true',
            '--restart', 'unless-stopped', '-p', '127.0.0.1:' + str(a.registry_port) + ':5000',
            '--memory', '256m', '--cpus', '0.5', '--security-opt', 'no-new-privileges:true',
            '--mount', 'type=bind,src=' + str(root / 'registry-data') + ',dst=/var/lib/registry',
            '--mount', 'type=bind,src=' + str(root / 'certs') + ',dst=/certs,readonly',
            '--mount', 'type=bind,src=' + str(root / 'config/htpasswd') + ',dst=/auth/htpasswd,readonly',
            '-e', 'REGISTRY_HTTP_TLS_CERTIFICATE=/certs/registry.crt', '-e', 'REGISTRY_HTTP_TLS_KEY=/certs/registry.key',
            '-e', 'REGISTRY_AUTH=htpasswd', '-e', 'REGISTRY_AUTH_HTPASSWD_REALM=Validation',
            '-e', 'REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd', 'registry:2')
    else:
        current = json.loads(run('docker', 'inspect', 'live-monitor-validation-registry'))[0]
        assert current['Config']['Labels'].get('live-monitor.validation') == 'true'
        assert any(m['Source'] == str(root / 'registry-data') for m in current['Mounts'])
    cert = root / 'certs/registry.crt'
    docker_config = root / 'secrets/registry'
    registry = 'localhost:' + str(a.registry_port)
    def promote(source, repository):
        target = registry + '/' + repository + ':' + a.release
        run('docker', 'tag', source, target)
        for attempt in range(15):
            try:
                run('docker', '--config', str(docker_config), 'push', target)
                break
            except RuntimeError:
                if attempt == 14:
                    raise
                time.sleep(2)
        digests = json.loads(run('docker', 'image', 'inspect', target))[0]['RepoDigests']
        return next(d for d in digests if d.startswith(registry + '/' + repository + '@sha256:'))
    platform_image = promote(manifest['localImage'], 'live-monitor/platform')
    test_image = promote('python:3.11-alpine', 'live-monitor/validation-python')
    # Docker resolves the published manifest by digest, including in an offline deployment.
    run('docker', '--config', str(docker_config), 'pull', platform_image)
    run('docker', '--config', str(docker_config), 'pull', test_image)
    identity = root / 'config/agent-identity'
    run('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(identity))
    user = 'lm-ops-validation'
    assert subprocess.run(['id', user], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0
    run('useradd', '--create-home', '--shell', '/bin/sh', user)
    config = {'environment': 'validation', 'state_root': str(root / 'agent-state'),
              'backup_root': str(root / 'backups'), 'secrets_root': str(root / 'secrets'),
              'allowed_registries': [registry], 'allowed_bind_roots': [],
              'publish_address': '127.0.0.1', 'max_cpus': 2, 'max_memory_mb': 1024,
              'docker_binary': shutil.which('docker'), 'docker_config': str(docker_config),
              'backup_helper_image': test_image}
    Path('/opt/live-monitor-agent').mkdir(mode=0o755)
    shutil.copyfile(release / 'agent.py', '/opt/live-monitor-agent/agent.py')
    Path('/opt/live-monitor-agent/agent.py').chmod(0o644)
    write(Path('/etc/live-monitor-agent.json'), json.dumps(config, indent=2))
    command = '/usr/bin/env SSL_CERT_FILE=' + str(cert) + ' /usr/bin/python3 /opt/live-monitor-agent/agent.py'
    sudoers = Path('/etc/sudoers.d/live-monitor-ops-validation')
    assert not sudoers.exists()
    write(sudoers, user + ' ALL=(root) NOPASSWD: ' + command + '\n', 0o440)
    run('visudo', '-cf', str(sudoers))
    home = Path('/home') / user
    (home / '.ssh').mkdir(mode=0o700)
    write(home / '.ssh/authorized_keys', 'restrict,command="sudo -n ' + command + '" ' + identity.with_suffix('.pub').read_text())
    run('chown', '-R', user + ':' + user, str(home / '.ssh'))
    secret_key, admin_password = secrets.token_urlsafe(48), secrets.token_urlsafe(32)
    env_file = root / 'config/platform.env'
    write(env_file, 'LIVE_MONITOR_SECRET_KEY=' + secret_key + '\nLIVE_MONITOR_DB_PASSWORD=' + secrets.token_urlsafe(32)
          + '\nDEBUG=false\nLOGGING_LEVEL_ROOT=INFO\nJAVA_TOOL_OPTIONS=-Xmx512m\nLIVE_MONITOR_OPS_STARTUP_GRACE_MS=60000\n')
    container = 'live-monitor-container-platform-validation'
    run('docker', 'run', '-d', '--name', container, '--label', 'live-monitor.validation=true',
        '--restart', 'unless-stopped', '--user', '10001:10001', '--env-file', str(env_file),
        '-p', '127.0.0.1:' + str(a.port) + ':8000', '--memory', '1g', '--cpus', '1',
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
        '--mount', 'type=bind,src=' + str(root / 'data') + ',dst=/app/data',
        '--mount', 'type=bind,src=' + str(root / 'logs') + ',dst=/app/logs', platform_image)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base = 'http://127.0.0.1:' + str(a.port)
    def api(path, data=None, method=None):
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(base + path, data=body, method=method,
                                     headers={'Content-Type': 'application/json', 'X-Ops-Request': '1'})
        with client.open(req, timeout=15) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    for attempt in range(90):
        try:
            api('/api/health')
            break
        except OSError:
            if attempt == 89:
                raise
            time.sleep(2)
    api('/api/auth/login', {'username': 'admin', 'password': '000000'})
    api('/api/admin/users/admin/password', {'new_password': admin_password}, 'PUT')
    api('/api/auth/logout', {})
    api('/api/auth/login', {'username': 'admin', 'password': admin_password})
    credential_file = root / 'config/browser-auth-private.json'
    write(credential_file, json.dumps({'username': 'admin', 'password': admin_password, 'baseUrl': base}))
    host_key = Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
    monitor = api('/api/hosts', {'host_name': 'validation-agent', 'ip': a.ssh_address, 'ssh_port': a.ssh_port,
        'ssh_user': user, 'private_key': identity.read_text(), 'enabled': True, 'check_interval': 31536000})
    api('/api/ops/resources/host', {'id': 'validation-host', 'name': 'Authorized server validation',
        'monitor_host_id': monitor['id'], 'environment': 'validation', 'group': 'server-validation',
        'ssh_host_key': ' '.join(host_key[:2])})
    api('/api/ops/resources/registry', {'id': 'validation-registry', 'name': 'Authenticated TLS validation registry',
                                      'address': registry, 'credential_ref': 'registry'})
    after = inventory()
    assert all(after.get(i) == state for i, state in before.items()), 'Existing container identity/runtime changed'
    running_hash = run('docker', 'exec', container, 'sha256sum', '/app/app.jar').split()[0]
    assert running_hash == manifest['artifacts']['app.jar']['sha256']
    result = {'status': 'DEPLOYED', 'container': container, 'platformImage': platform_image,
              'applicationImage': test_image, 'registry': registry, 'port': a.port, 'bind': '127.0.0.1',
              'artifactSha256': running_hash, 'sourceRevision': manifest['sourceCommit'],
              'existingContainersUnchanged': True, 'existingContainerCount': len(before),
              'newDataDirectory': str(root / 'data'), 'defaultPasswordChanged': True,
              'agentUser': user, 'dockerTcpExposed': False, 'deployedAt': int(time.time() * 1000)}
    write(root / 'deployment.json', json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
