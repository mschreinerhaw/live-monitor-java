"""Explicitly opted-in real Compose acceptance; operates only its unique new project."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid
import agent


@unittest.skipUnless(os.environ.get('LIVE_MONITOR_STACK_DOCKER_ACCEPTANCE') == '1', 'Explicit isolated stack acceptance required')
class StackDockerAcceptance(unittest.TestCase):
    def test_real_stack_backup_recovery_namespace_and_failed_release(self):
        os.umask(0o077)
        self.assertEqual(os.getuid(), 0)
        suffix = uuid.uuid4().hex[:8]
        project = 'lm-stack-acceptance-' + suffix
        root = Path('/opt/live-monitor-production-migration/tests') / suffix
        root.mkdir(mode=0o700, parents=True)
        data = root / 'data'; data.mkdir(mode=0o700); (data / 'original.txt').write_text('original-data')
        image = os.environ['LIVE_MONITOR_STACK_TEST_IMAGE']
        self.assertIn('@sha256:', image)
        docker_config = Path(os.environ['LIVE_MONITOR_STACK_TEST_DOCKER_CONFIG'])
        good = {'services': {'web': {'image': image, 'cpus': 0.25, 'mem_limit': '64m', 'restart': 'no',
            'command': ['python', '-u', '-c', "import signal,sys,pathlib,http.server; signal.signal(signal.SIGTERM,lambda *a:sys.exit(0)); pathlib.Path('/tmp/unhealthy').unlink(missing_ok=True); http.server.HTTPServer(('0.0.0.0',8080),http.server.SimpleHTTPRequestHandler).serve_forever()"],
            'healthcheck': {'test': ['CMD', 'python', '-c', "import pathlib,urllib.request; assert not pathlib.Path('/tmp/unhealthy').exists(); urllib.request.urlopen('http://127.0.0.1:8080/')"], 'interval': '1s', 'timeout': '1s', 'retries': 1},
            'volumes': [{'type': 'bind', 'source': str(data), 'target': '/data'}]},
            'sidecar': {'image': image, 'cpus': 0.1, 'mem_limit': '32m', 'restart': 'no', 'network_mode': 'service:web',
            'command': ['python', '-c', 'import signal,sys,time; signal.signal(signal.SIGTERM,lambda *a:sys.exit(0)); time.sleep(10000)']}}}
        config_file, rollback = root / 'compose.json', root / 'rollback.json'
        config_file.write_text(json.dumps(good)); rollback.write_text(json.dumps(good))
        manifest = {'schema': 1, 'stack_id': 'test-stack', 'environment': 'acceptance', 'project': project,
            'compose': {'file': 'compose.json', 'sha256': hashlib.sha256(config_file.read_bytes()).hexdigest()},
            'rollback_compose': {'file': 'rollback.json', 'sha256': hashlib.sha256(rollback.read_bytes()).hexdigest()},
            'readiness': {'web': [{'kind': 'container_tcp', 'port': 8080}]}, 'deadline_seconds': 15, 'stable_seconds': 1,
            'backup_paths': [str(data)], 'recovery': {'limit': 1, 'failure_window_seconds': 1}}
        package = root / 'manifest.json'; package.write_text(json.dumps(manifest))
        a = agent.Agent({'environment': 'acceptance', 'state_root': str(root / 'state'), 'backup_root': str(root / 'backups'),
            'secrets_root': str(docker_config.parent), 'docker_config': str(docker_config), 'docker_binary': '/usr/bin/docker',
            'allowed_registries': [image.split('/')[0]], 'stack_policy': {'packages': {'good': str(package)},
                'projects': [project], 'writable_bind_roots': [str(data)], 'backup_paths': [str(data)]}})
        payload = {'stack_id': 'test-stack', 'package_ref': 'good', 'package_sha256': hashlib.sha256(package.read_bytes()).hexdigest(), 'data_compatible': True}
        def call(action, body=payload):
            return a.execute({'protocol': 1, 'environment': 'acceptance', 'task_id': str(uuid.uuid4()), 'action': action, 'payload': body})['data']
        result = call('stack_plan'); self.assertEqual(len(result['services']), 2)
        result = call('stack_deploy'); self.assertTrue(result['healthy']); self.assertTrue(result['backup']['original_data_preserved'])
        from stack_ops import StackOperations
        ops = StackOperations(a)
        before = ops.containers(manifest); sidecar_before = before['sidecar']['Id']
        result = call('stack_service_restart', dict(payload, service='web'))
        after = ops.containers(manifest)
        self.assertNotEqual(sidecar_before, after['sidecar']['Id'])
        self.assertEqual(after['sidecar']['HostConfig']['NetworkMode'], 'container:' + after['web']['Id'])
        call('stack_service_stop', dict(payload, service='web'))
        result = call('stack_probe'); self.assertTrue(next(s for s in result['services'] if s['service'] == 'web')['manual_stop'])
        self.assertNotIn('web', result['recovery_candidates'])
        call('stack_start')
        bad = copy.deepcopy(good); bad['services']['web']['command'] = ['python', '-c', 'raise SystemExit(9)']
        bad_file = root / 'bad.json'; bad_file.write_text(json.dumps(bad))
        bad_manifest = dict(manifest, compose={'file': 'bad.json', 'sha256': hashlib.sha256(bad_file.read_bytes()).hexdigest()})
        bad_package = root / 'bad-manifest.json'; bad_package.write_text(json.dumps(bad_manifest)); a.config['stack_policy']['packages']['bad'] = str(bad_package)
        with self.assertRaisesRegex(agent.AgentError, 'STACK_DEPLOY_FAILED_PREVIOUS_CONFIGURATION_RESTORED'):
            call('stack_deploy', dict(payload, package_ref='bad', package_sha256=hashlib.sha256(bad_package.read_bytes()).hexdigest()))
        self.assertTrue(ops.containers(manifest)['web']['State']['Running'])
        self.assertEqual((data / 'original.txt').read_text(), 'original-data')
        web = ops.containers(manifest)['web']['Id']
        subprocess.run(['docker', 'exec', web, 'python', '-c', "from pathlib import Path; Path('/tmp/unhealthy').touch()"], check=True)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            sample = call('stack_probe')
            if 'web' in sample['recovery_candidates']:
                break
            time.sleep(2)
        else:
            self.fail('No sustained unhealthy recovery candidate')
        call('stack_recover')
        self.assertEqual(ops.state('test-stack')['services']['web']['recovery_attempts'], 1)
        call('stack_stop'); self.assertTrue(all(not c['State']['Running'] for c in ops.containers(manifest).values()))
        proof = {'project': project, 'twoServicesDeployed': True, 'namespaceFollowerRecreated': True,
            'manualStopProtected': True, 'failedReleaseRolledBack': True, 'originalDataPreserved': True,
            'sustainedUnhealthyRecovered': True, 'stoppedAtEnd': True, 'productionContainersOperated': False}
        (root / 'acceptance.json').write_text(json.dumps(proof, indent=2))
        print(json.dumps(proof), flush=True)


if __name__ == '__main__':
    unittest.main()
