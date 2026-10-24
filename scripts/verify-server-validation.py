"""Exercise only newly created validation applications on an authorized server.

This runner deliberately injects faults only after checking the platform-owned
application/environment labels. Existing workloads are fingerprinted, never operated.
"""
import argparse
import copy
import hashlib
import http.cookiejar
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--allow-validation-faults', action='store_true', required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    assert root.parent == Path('/opt') and root.name.endswith('-validation')
    deployment = json.loads((root / 'deployment.json').read_text())
    credentials = json.loads((root / 'config/browser-auth-private.json').read_text())
    assert deployment['bind'] == '127.0.0.1' and deployment['status'] == 'DEPLOYED'
    base = 'http://127.0.0.1:' + str(deployment['port'])
    def client():
        return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    session = client()
    def api(path, data=None, method=None, browser=session):
        request = urllib.request.Request(base + path, data=json.dumps(data).encode() if data is not None else None,
            method=method, headers={'Content-Type': 'application/json', 'X-Ops-Request': '1'})
        with browser.open(request, timeout=20) as response:
            raw = response.read()
            value = json.loads(raw) if raw else None
            return value['data'] if path.startswith('/api/ops/') else value
    api('/api/auth/login', {'username': credentials['username'], 'password': credentials['password']})
    checks, task_ids = {}, []
    def record(name):
        checks[name] = True
        print(json.dumps({'check': name, 'status': 'PASSED'}), flush=True)
    def wait(test, seconds=180):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = test()
            if value:
                return value
            time.sleep(2)
        raise AssertionError('Validation deadline exceeded')
    def finish(task, expected='SUCCEEDED'):
        task_ids.append(task['id'])
        value = wait(lambda: (lambda t: t if t['status'] in ['SUCCEEDED', 'FAILED', 'INTERRUPTED'] else None)(api('/api/ops/tasks/' + task['id'])))
        assert value['status'] == expected, value.get('message')
        return value
    def operation(action, resource, parameters=None, expected='SUCCEEDED'):
        task = api('/api/ops/operations', {'action': action, 'resource_id': resource,
            'parameters': parameters or {}, 'confirmation': action + ':' + resource + ':validation',
            'idempotency_key': str(uuid.uuid4())})
        return finish(task, expected)
    for path in ['/api/dashboard', '/api/hosts', '/api/services', '/api/alert-channels']:
        api(path)
    record('originalMonitoringApis')
    operation('probe', 'validation-host')
    record('pinnedSshRestrictedAgent')
    metadata = json.loads(operation('registry_catalog', 'validation-host', {'registry_id': 'validation-registry'})['result_json'])
    assert 'live-monitor/validation-python' in metadata['repositories']
    resolved = json.loads(operation('resolve_image', 'validation-host', {'registry_id': 'validation-registry',
        'repository': 'live-monitor/validation-python', 'tag': deployment['sourceRevision'][:7]})['result_json'])
    assert resolved['image'] == deployment['applicationImage']
    record('authenticatedTlsRegistryAndImmutableDigest')
    suffix = uuid.uuid4().hex[:8]
    alpha, beta = 'server-recovery-' + suffix, 'server-backup-' + suffix
    original = api('/api/ops/templates/application')
    original.update({'host_id': 'validation-host', 'environment': 'validation', 'image': deployment['applicationImage'],
        'cpus': 0.25, 'memory_mb': 64, 'restart_policy': 'no', 'restart_limit': 1,
        'recover_unhealthy': True, 'failure_window_seconds': 2, 'health_deadline_seconds': 20,
        'health': {'command': ['python', '-c', "import pathlib,urllib.request; assert not pathlib.Path('/tmp/lm-unhealthy').exists(); urllib.request.urlopen('http://127.0.0.1:8080/')"], 'interval_seconds': 1, 'timeout_seconds': 1}})
    command = "import signal,sys,pathlib,http.server; signal.signal(signal.SIGTERM,lambda *a:sys.exit(0)); pathlib.Path('/tmp/lm-unhealthy').unlink(missing_ok=True); http.server.HTTPServer(('0.0.0.0',8080),http.server.SimpleHTTPRequestHandler).serve_forever()"
    original['command'] = ['python', '-u', '-c', command]
    a = dict(copy.deepcopy(original), id=alpha, name='Server recovery validation ' + suffix)
    b = dict(copy.deepcopy(original), id=beta, name='Server backup validation ' + suffix,
        data_kind='volume', mounts=[{'type': 'volume', 'source': 'lm-server-validation-' + suffix, 'target': '/data', 'read_only': False}])
    api('/api/ops/resources/application', a)
    api('/api/ops/resources/application', b)
    for app in [alpha, beta]:
        operation('validate', app)
        operation('deploy', app)
    record('multipleApplicationsDeployment')
    def own(app):
        assert app in [alpha, beta]
        name = 'lm-validation-' + app
        data = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
        assert data['Config']['Labels']['live-monitor.app'] == app
        assert data['Config']['Labels']['live-monitor.environment'] == 'validation'
        return name, data
    def docker(app, *command):
        name, _ = own(app)
        return subprocess.check_output(['docker', command[0], name, *command[1:]], text=True).strip()
    def saved(app):
        return next(x for x in api('/api/ops/resources/application') if x['id'] == app)
    v1 = saved(alpha)['active_version']
    changed = saved(alpha)
    changed['environment_variables'] = {'RELEASE_VERSION': '2'}
    api('/api/ops/resources/application', changed)
    operation('deploy', alpha)
    operation('rollback', alpha, {'version_id': v1})
    assert 'RELEASE_VERSION=2' not in own(alpha)[1]['Config']['Env']
    record('versionHistoryAndControlledRollback')
    changed = saved(alpha)
    changed['command'] = ['python', '-c', 'raise SystemExit(7)']
    api('/api/ops/resources/application', changed)
    failed = operation('deploy', alpha, expected='FAILED')
    assert own(alpha)[1]['State']['Running'] and failed.get('message')
    restored = dict(a, revision=saved(alpha)['revision'])
    api('/api/ops/resources/application', restored)
    record('failedDeploymentDiagnosisAndPreviousContainerPreserved')
    expected = ('server-backup-content-' + suffix).encode()
    docker(beta, 'exec', 'python', '-c', "from pathlib import Path; Path('/data/evidence.txt').write_bytes(" + repr(expected) + ")")
    operation('stop', beta)
    operation('backup', beta)
    backup = next(x for x in api('/api/ops/resources/backup') if x['app_id'] == beta)
    operation('restore_test', beta, {'backup_id': backup['id']})
    recovered = json.loads(operation('restore', beta, {'backup_id': backup['id'], 'target_suffix': 'verified-' + suffix})['result_json'])
    assert recovered['targets'][0]['volume'] != b['mounts'][0]['source']
    assert backup['verified']
    operation('start', beta)
    actual = docker(beta, 'exec', 'python', '-c', "from pathlib import Path; print(Path('/data/evidence.txt').read_text())")
    assert actual.encode() == expected
    record('volumeBackupRestoreTestAndOriginalDataPreserved')
    def recovery_tasks():
        return [t for t in api('/api/ops/tasks') if t['resource_id'] == alpha and t['action'] == 'recover' and t['actor'] == 'system']
    docker(alpha, 'exec', 'python', '-c', "from pathlib import Path; Path('/tmp/lm-unhealthy').touch()")
    unhealthy = wait(lambda: next((t for t in recovery_tasks() if t['status'] == 'SUCCEEDED' and json.loads(t['result_json']).get('reason') == 'unhealthy'), None))
    record('runningUnhealthyContainerAutomaticRecovery')
    operation('stop', alpha)
    time.sleep(35)
    assert not own(alpha)[1]['State']['Running'] and saved(alpha)['desired_state'] == 'STOPPED'
    record('manualStopNotAutomaticallyRestarted')
    operation('start', alpha)
    previous_ids = {t['id'] for t in recovery_tasks()}
    docker(alpha, 'kill')
    recovered_exit = wait(lambda: next((t for t in recovery_tasks() if t['id'] not in previous_ids and t['status'] == 'SUCCEEDED'), None))
    assert json.loads(recovered_exit['result_json'])['reason'] == 'exited'
    record('abnormalExitAutomaticRecovery')
    docker(alpha, 'kill')
    wait(lambda: next((x for x in api('/api/ops/resources/alert') if x.get('app_id') == alpha and x['code'] == 'CIRCUIT_OPEN' and x['status'] == 'OPEN'), None))
    time.sleep(35)
    assert len(recovery_tasks()) == len(previous_ids) + 1 and not own(alpha)[1]['State']['Running']
    record('restartLimitCircuitBreaker')
    operation('stop', alpha)
    viewer = 'validation-viewer-' + suffix
    password = uuid.uuid4().hex
    api('/api/admin/users', {'user_id': viewer, 'password': password, 'name': viewer, 'enabled': True})
    api('/api/ops/resources/grant', {'id': viewer, 'user_id': viewer, 'host_id': 'validation-host',
        'environment': 'validation', 'role': 'viewer', 'actions': 'read,logs'})
    read_only = client()
    api('/api/auth/login', {'username': viewer, 'password': password}, browser=read_only)
    for path, data in [('/api/hosts', None), ('/api/ops/operations', {'action': 'restart', 'resource_id': beta})]:
        try:
            api(path, data, browser=read_only)
            raise AssertionError('Unauthorized operation accepted')
        except urllib.error.HTTPError as e:
            assert e.code == 403
    assert api('/api/ops/capabilities', browser=read_only)['ops_only']
    assert any(x['outcome'] == 'DENIED' for x in api('/api/ops/audit'))
    record('scopedPermissionsLegacyIsolationAndAudit')
    for identity, original_state in json.loads((root / 'baseline.json').read_text()).items():
        current = json.loads(subprocess.check_output(['docker', 'inspect', identity]))[0]
        assert current['Image'] == original_state['image'] and current['State']['StartedAt'] == original_state['startedAt']
        assert current['RestartCount'] == original_state['restartCount']
    record('existingProductionContainersUnchanged')
    result = {'checks': checks, 'applications': [alpha, beta], 'backupId': backup['id'],
        'unhealthyRecoveryTask': unhealthy['id'], 'exitRecoveryTask': recovered_exit['id'],
        'taskIds': task_ids, 'sourceRevision': deployment['sourceRevision'],
        'physicalServersVerified': 1, 'existingProductionWorkloadsOperated': False,
        'completedAt': int(time.time() * 1000)}
    (root / 'acceptance.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
