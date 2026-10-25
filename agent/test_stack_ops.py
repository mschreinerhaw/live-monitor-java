import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import agent
from stack_ops import StackOperations


class StackTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.a = agent.Agent({'environment': 'test', 'state_root': str(self.root / 'state'),
            'backup_root': str(self.root / 'backups'), 'secrets_root': str(self.root / 'secrets'),
            'allowed_registries': ['registry.test'], 'stack_policy': {'projects': ['example'],
                'writable_bind_roots': [str(self.root / 'data')], 'read_only_bind_roots': [str(self.root / 'data')]}})
        (self.root / 'data').mkdir()
        self.a.current_task = 'task-example'
        self.a.run = Mock(return_value='')
        self.ops = StackOperations(self.a)
        self.config = {'services': {'web': {'image': 'registry.test/example@sha256:' + 'a' * 64,
            'cpus': 1, 'mem_limit': '128m', 'restart': 'no'}}}
        self.manifest = {'schema': 1, 'stack_id': 'stack-example', 'environment': 'test', 'project': 'example',
                         'recovery': {'limit': 1, 'failure_window_seconds': 1}}
        self.files = {'compose': (self.root / 'compose.json', self.config), 'rollback_compose': (self.root / 'old.json', self.config)}

    def tearDown(self):
        self.tmp.cleanup()

    def test_docker_health_starting_is_not_ready_for_installation(self):
        item={'Id':'container-a','Config':{'Image':self.config['services']['web']['image']},'State':{'Running':True,'Status':'running','Health':{'Status':'starting'}},'HostConfig':{'RestartPolicy':{'Name':'no'}}}
        self.ops.containers=Mock(return_value={'web':item})
        state={'desired_state':'RUNNING','services':{}}
        self.assertFalse(self.ops.probe(self.manifest,self.files,state)['healthy'])
        item['State']['Health']['Status']='healthy'
        self.assertTrue(self.ops.probe(self.manifest,self.files,state)['healthy'])

    def test_immutable_images_and_resource_limits(self):
        self.ops.validate_compose(copy.deepcopy(self.config))
        for change in [{'image': 'registry.test/example:latest'}, {'cpus': 0}, {'mem_limit': ''}]:
            bad = copy.deepcopy(self.config); bad['services']['web'].update(change)
            with self.assertRaises(agent.AgentError):
                self.ops.validate_compose(bad)

    def test_privileged_build_and_host_namespaces_rejected(self):
        for change in [{'privileged': True}, {'build': '.'}, {'network_mode': 'host'}, {'network_mode': 'container:other'}, {'pid': 'host'}, {'cap_add': ['SYS_ADMIN']}]:
            bad = copy.deepcopy(self.config); bad['services']['web'].update(change)
            with self.assertRaises(agent.AgentError):
                self.ops.validate_compose(bad)

    def test_same_release_namespace_sidecar_is_allowed(self):
        config = copy.deepcopy(self.config)
        config['services']['sidecar'] = dict(config['services']['web'], network_mode='service:web')
        self.ops.validate_compose(config)
        config['services']['sidecar']['network_mode'] = 'service:outside'
        with self.assertRaises(agent.AgentError):
            self.ops.validate_compose(config)

    def test_write_mount_requires_explicit_host_policy(self):
        config = copy.deepcopy(self.config)
        config['services']['web']['volumes'] = [{'type': 'bind', 'source': str(self.root / 'data'), 'target': '/data'}]
        self.ops.validate_compose(config)
        self.assertFalse(config['services']['web']['volumes'][0]['bind']['create_host_path'])
        config['services']['web']['volumes'][0]['source'] = '/etc'
        with self.assertRaises(agent.AgentError):
            self.ops.validate_compose(config)

    def test_docker_socket_denied_by_default(self):
        config = copy.deepcopy(self.config)
        config['services']['web']['volumes'] = [{'type': 'bind', 'source': '/var/run/docker.sock', 'target': '/var/run/docker.sock'}]
        with self.assertRaisesRegex(agent.AgentError, 'STACK_DOCKER_SOCKET_DENIED'):
            self.ops.validate_compose(config)

    def test_unknown_package_and_digest_cannot_execute(self):
        with self.assertRaisesRegex(agent.AgentError, 'STACK_PACKAGE_NOT_APPROVED'):
            self.ops.bundle({'package_ref': 'unknown'})
        self.ops.policy['packages'] = {'approved': str(self.root / 'package.json')}
        self.ops.document = Mock(return_value=b'{}')
        with self.assertRaisesRegex(agent.AgentError, 'STACK_PACKAGE_DIGEST_MISMATCH'):
            self.ops.bundle({'package_ref': 'approved', 'package_sha256': 'b' * 64})

    def test_stop_intent_persists_even_when_remote_stop_fails(self):
        self.ops.bundle = Mock(return_value=(self.manifest, self.files))
        self.ops.compose = Mock(side_effect=agent.AgentError('OFFLINE'))
        with self.assertRaises(agent.AgentError):
            self.ops.execute('stack_stop', {})
        state = self.ops.state('stack-example')
        self.assertEqual(state['desired_state'], 'STOPPED')
        self.assertTrue(state['services']['web']['manual_stop'])

    def test_recovery_cannot_override_manual_stop(self):
        self.ops.bundle = Mock(return_value=(self.manifest, self.files))
        self.a.run = Mock()
        with self.assertRaisesRegex(agent.AgentError, 'STACK_RECOVERY_MANUALLY_STOPPED'):
            self.ops.execute('stack_recover', {})
        self.a.run.assert_not_called()

    def test_recovery_limit_and_manual_service_are_respected(self):
        item = {'Id': 'container', 'Config': {'Image': 'immutable'}, 'State': {'Running': True, 'Status': 'running', 'Health': {'Status': 'unhealthy'}}, 'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'}}, 'RestartCount': 0}
        self.ops.containers = Mock(return_value={'web': item})
        state = {'desired_state': 'RUNNING', 'services': {'web': {'container_id': 'container', 'failure_since': 1, 'recovery_attempts': 1}}}
        result = self.ops.probe(self.manifest, self.files, state)
        self.assertEqual(result['circuits'], ['web']); self.assertEqual(result['recovery_candidates'], [])
        state['services']['web']['manual_stop'] = True
        result = self.ops.probe(self.manifest, self.files, state)
        self.assertEqual(result['circuits'], []); self.assertEqual(result['recovery_candidates'], [])

    def test_backoff_avoids_repeated_restart(self):
        item = {'Id': 'container', 'Config': {'Image': 'immutable'}, 'State': {'Running': True, 'Status': 'running', 'Health': {'Status': 'unhealthy'}}, 'HostConfig': {'RestartPolicy': {'Name': 'no'}}, 'RestartCount': 0}
        self.ops.containers = Mock(return_value={'web': item})
        state = {'desired_state': 'RUNNING', 'services': {'web': {'container_id': 'container', 'failure_since': 1, 'next_recovery_at': 9999999999999}}}
        self.assertEqual(self.ops.probe(self.manifest, self.files, state)['recovery_candidates'], [])

    def test_unhealthy_recovery_policy_and_diagnostics_are_visible(self):
        item = {'Id': 'container', 'Config': {'Image': 'immutable'}, 'State': {'Running': True, 'Status': 'running', 'Health': {'Status': 'unhealthy'}}, 'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'}}, 'RestartCount': 0}
        self.ops.containers = Mock(return_value={'web': item})
        manifest = dict(self.manifest, recovery={'limit': 3, 'failure_window_seconds': 1, 'recover_unhealthy': False})
        state = {'desired_state': 'RUNNING', 'services': {'web': {'container_id': 'container', 'failure_since': 1}}}
        result = self.ops.probe(manifest, self.files, state)
        self.assertEqual(result['recovery_candidates'], [])
        self.assertFalse(result['recovery_policy']['recover_unhealthy'])
        self.assertEqual(result['services'][0]['failure_since'], 1)
        self.assertEqual(result['services'][0]['restart_policy'], 'unless-stopped')
        manifest['recovery']['recover_unhealthy'] = True
        self.assertEqual(self.ops.probe(manifest, self.files, state)['recovery_candidates'], ['web'])

    def test_deploy_requires_data_compatibility_confirmation(self):
        self.ops.bundle = Mock(return_value=(self.manifest, self.files))
        self.ops.compose = Mock()
        with self.assertRaisesRegex(agent.AgentError, 'STACK_DATA_COMPATIBILITY_CONFIRMATION'):
            self.ops.execute('stack_deploy', {})
        self.ops.compose.assert_not_called()

    def test_entrypoint_activation_keeps_original_configuration(self):
        directory = self.root / 'entrypoint'; directory.mkdir(mode=0o700)
        active = directory / 'compose.json'; active.write_text('original-config')
        released = self.root / 'released.json'; released.write_text('released-config')
        self.ops.policy['active_compose_paths'] = {'example': str(active)}
        self.ops.activate_entrypoint(self.manifest, released)
        self.assertTrue(active.is_symlink()); self.assertEqual(active.read_text(), 'released-config')
        self.assertEqual((self.a.root / 'entrypoint-history/task-example/previous-compose.json').read_text(), 'original-config')
        self.ops.activate_entrypoint(self.manifest, released)

    def test_entrypoint_is_unchanged_without_host_approval(self):
        original = self.root / 'compose.json'; original.write_text('original')
        self.ops.activate_entrypoint(self.manifest, original)
        self.assertFalse(original.is_symlink()); self.assertEqual(original.read_text(), 'original')

    def test_image_drift_alerts_without_restarting_unapproved_image(self):
        item = {'Id': 'container', 'Image': 'unexpected-content', 'Config': {'Image': 'changed-image'}, 'State': {'Running': True, 'Status': 'running'}, 'HostConfig': {'RestartPolicy': {'Name': 'no'}}, 'RestartCount': 0}
        self.ops.containers = Mock(return_value={'web': item})
        self.a.run = Mock(side_effect=lambda args, **kwargs: json.dumps([{'Id': 'expected-content'}]) if args[:2] == ['image', 'inspect'] else '')
        result = self.ops.probe(self.manifest, self.files, {'desired_state': 'RUNNING', 'package_ref': 'good', 'services': {'web': {'container_id': 'container', 'failure_since': 1}}})
        self.assertFalse(result['healthy']); self.assertTrue(result['services'][0]['image_drift']); self.assertEqual(result['recovery_candidates'], [])


if __name__ == '__main__':
    unittest.main()
