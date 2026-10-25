"""Sealed, host-approved Compose releases. The UI supplies references, never YAML or shell."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request


class StackOperations:
    def __init__(self, agent):
        self.a = agent
        self.policy = agent.config.get('stack_policy', {})

    def check(self, value, code):
        from agent import check
        check(value, code)

    def document(self, path):
        path = Path(path)
        self.check(not path.is_symlink() and path.is_file(), 'STACK_FILE_MISSING_OR_SYMLINK')
        st = path.stat()
        self.check(st.st_uid == 0 and st.st_mode & 0o022 == 0, 'STACK_FILE_NOT_ROOT_OWNED')
        parent = path.parent.stat()
        self.check(parent.st_uid == 0 and parent.st_mode & 0o022 == 0, 'STACK_DIRECTORY_NOT_ROOT_OWNED')
        return path.read_bytes()

    def bundle(self, payload):
        reference = payload.get('package_ref', '')
        path = self.policy.get('packages', {}).get(reference)
        self.check(path, 'STACK_PACKAGE_NOT_APPROVED')
        raw = self.document(path)
        self.check(hashlib.sha256(raw).hexdigest() == payload.get('package_sha256'), 'STACK_PACKAGE_DIGEST_MISMATCH')
        manifest = json.loads(raw)
        self.check(manifest.get('schema') == 1 and manifest.get('environment') == self.a.environment
                   and manifest.get('stack_id') == payload.get('stack_id'), 'STACK_ENVIRONMENT_OR_ID')
        self.check(manifest.get('project') in self.policy.get('projects', []), 'STACK_PROJECT_NOT_APPROVED')
        for record in manifest.get('assets', []):
            asset = Path(record['file'])
            parent = asset.parent.stat()
            self.check(not asset.is_symlink() and parent.st_uid == 0 and parent.st_mode & 0o022 == 0, 'STACK_ASSET_DIRECTORY_UNSAFE')
            from agent import digest_file
            self.check(digest_file(asset) == record['sha256'], 'STACK_ASSET_DIGEST_MISMATCH')
        for reference in manifest.get('post_deploy_hooks', []):
            hook = self.policy.get('hooks', {}).get(reference)
            self.check(hook and hashlib.sha256(self.document(hook['file'])).hexdigest() == hook['sha256'], 'STACK_HOOK_NOT_APPROVED')
        files = {}
        for key in ['compose', 'rollback_compose'] + (['dependency_compose'] if manifest.get('dependency_compose') else []):
            record = manifest[key]
            file = Path(path).parent / record['file']
            self.check(file.resolve().parent == Path(path).resolve().parent, 'STACK_FILE_PATH')
            data = self.document(file)
            self.check(hashlib.sha256(data).hexdigest() == record['sha256'], 'STACK_COMPOSE_DIGEST_MISMATCH')
            config = json.loads(data)
            self.validate_compose(config)
            from agent import atomic_json
            rendered = self.a.root / 'stack-rendered' / (payload['package_sha256'] + '-' + key + '.json')
            atomic_json(rendered, config)
            files[key] = (rendered, config)
        return manifest, files

    def validate_compose(self, config):
        services = config.get('services', {})
        self.check(0 < len(services) <= 32, 'STACK_SERVICE_LIMIT')
        allowed = {'name', 'services', 'networks', 'volumes'}
        self.check(set(config) <= allowed, 'STACK_COMPOSE_TOP_LEVEL')
        for name, service in services.items():
            self.check(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', name), 'STACK_SERVICE_NAME')
            self.a.image(service.get('image', ''))
            self.check(not any(service.get(k) for k in ['build', 'privileged', 'pid', 'ipc', 'devices', 'cap_add', 'develop', 'extends', 'volumes_from']), 'STACK_PRIVILEGE_FORBIDDEN')
            mode = service.get('network_mode', '')
            self.check(not mode or mode == 'bridge' or (mode.startswith('service:') and mode[8:] in services), 'STACK_NETWORK_NAMESPACE')
            self.check(service.get('restart', 'no').split(':')[0] in ['no', 'on-failure', 'unless-stopped', 'always'], 'STACK_RESTART_POLICY')
            self.check(float(service.get('cpus', 0)) > 0 and service.get('mem_limit'), 'STACK_RESOURCE_LIMITS')
            for mount in service.get('volumes', []):
                self.check(isinstance(mount, dict) and mount.get('type') in ['bind', 'volume'], 'STACK_TYPED_MOUNT_REQUIRED')
                target = mount.get('target', '')
                self.check(target.startswith('/') and '..' not in target.split('/'), 'STACK_MOUNT_TARGET')
                if mount['type'] == 'bind':
                    source = Path(mount['source']).resolve()
                    if 'docker.sock' in str(source) + target:
                        self.check(name in self.policy.get('docker_socket_services', []) and str(source) == '/run/docker.sock'
                                   and target == '/var/run/docker.sock', 'STACK_DOCKER_SOCKET_DENIED')
                    else:
                        roots = self.policy.get('read_only_bind_roots' if mount.get('read_only') else 'writable_bind_roots', [])
                        self.check(any(source == Path(p).resolve() or Path(p).resolve() in source.parents for p in roots), 'STACK_BIND_OUTSIDE_POLICY')
                    self.check(source.exists(), 'STACK_BIND_SOURCE_MISSING')
                    mount.setdefault('bind', {})['create_host_path'] = False
            self.check(not service.get('container_name') or service['container_name'] in self.policy.get('container_names', []), 'STACK_CONTAINER_NAME_DENIED')

    def state_path(self, stack):
        self.check(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}', stack or ''), 'STACK_ID')
        return self.a.root / 'stacks' / (stack + '.json')

    def state(self, stack):
        p = self.state_path(stack)
        return json.loads(p.read_text()) if p.exists() else {'desired_state': 'STOPPED', 'services': {}}

    def save(self, stack, state):
        from agent import atomic_json
        atomic_json(self.state_path(stack), state)

    def compose(self, manifest, file, *arguments):
        return self.a.run(['compose', '--project-name', manifest['project'], '-f', str(file), *arguments], timeout=900)

    def approved_inspect(self, identity):
        # The old single-container inspector deliberately rejects unlabelled legacy workloads.
        # Stack ownership is independently bound to the sealed project/dependency manifest.
        return json.loads(self.a.run(['container', 'inspect', identity]))[0]

    def containers(self, manifest):
        ids = self.a.run(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + manifest['project']]).split()
        result = {}
        for identity in ids:
            item = self.approved_inspect(identity)
            self.check(item['Config'].get('Labels', {}).get('com.docker.compose.project') == manifest['project'], 'STACK_PROJECT_OWNERSHIP')
            name = item['Config'].get('Labels', {}).get('com.docker.compose.service')
            self.check(not name or name not in result, 'STACK_DUPLICATE_SERVICE_CONTAINER')
            if name:
                result[name] = item
        return result

    def health(self, probe):
        try:
            if probe['kind'] == 'tcp':
                with socket.create_connection((probe['host'], int(probe['port'])), timeout=3):
                    return True
            self.check(probe['kind'] == 'http', 'STACK_HEALTH_KIND')
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            try:
                with client.open(probe['url'], timeout=3) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            return status in probe.get('statuses', [200])
        except (OSError, ValueError):
            return False

    def service_health(self, probe, container):
        if probe['kind'] != 'container_tcp':
            return self.health(probe)
        networks = container.get('NetworkSettings', {}).get('Networks', {})
        selected = networks.get(probe.get('network')) if probe.get('network') else next(iter(networks.values()), None)
        return bool(selected and selected.get('IPAddress') and self.health({'kind': 'tcp', 'host': selected['IPAddress'], 'port': probe['port']}))

    def probe(self, manifest, files, state):
        containers = self.containers(manifest)
        resources = {}
        if containers:
            for line in self.a.run(['stats', '--no-stream', '--format', '{{json .}}', *[c['Id'] for c in containers.values()]]).splitlines():
                usage = json.loads(line)
                resources[usage['ID']] = usage
        now = int(time.time() * 1000)
        services, candidates, circuits = [], [], []
        for name in files['compose'][1]['services']:
            item = containers.get(name)
            runtime = state.setdefault('services', {}).setdefault(name, {})
            if item and runtime.get('container_id') != item['Id']:
                runtime.update(container_id=item['Id'], recovery_attempts=0, circuit_open=False, failure_since=0, next_recovery_at=0)
            running = bool(item and item['State']['Running'])
            expected = files['compose'][1]['services'][name]['image']
            identity_matches = True
            if item and state.get('package_ref') and item['Config']['Image'] != expected:
                try:
                    identity_matches = item['Image'] == json.loads(self.a.run(['image', 'inspect', expected]))[0]['Id']
                except Exception:
                    identity_matches = False
            healthy = running and identity_matches and item['State'].get('Health', {}).get('Status') != 'unhealthy'
            healthy = healthy and all(self.service_health(p, item) for p in manifest.get('readiness', {}).get(name, []))
            stopped = state.get('desired_state') != 'RUNNING' or runtime.get('manual_stop', False)
            if healthy:
                runtime['failure_since'] = 0
            elif not runtime.get('failure_since'):
                runtime['failure_since'] = now
            limit = int(manifest.get('recovery', {}).get('limit', 3))
            native_count = item.get('RestartCount', 0) if item else 0
            if identity_matches and not stopped and not healthy and now - runtime['failure_since'] >= int(manifest.get('recovery', {}).get('failure_window_seconds', 30)) * 1000:
                if runtime.get('circuit_open') or runtime.get('recovery_attempts', 0) >= limit:
                    circuits.append(name)
                elif now >= runtime.get('next_recovery_at', 0) and item:
                    policy = item['HostConfig']['RestartPolicy']['Name']
                    if native_count >= limit or (running and manifest.get('recovery', {}).get('recover_unhealthy', True)) or (policy == 'no' and item['State'].get('ExitCode', 0) != 0):
                        candidates.append(name)
            services.append({'service': name, 'container_id': item['Id'] if item else '',
                'image': item['Config']['Image'] if item else files['compose'][1]['services'][name]['image'], 'status': item['State']['Status'] if item else 'missing',
                'healthy': healthy, 'manual_stop': stopped, 'restart_count': native_count,
                'expected_image': expected, 'image_drift': not identity_matches,
                'recovery_attempts': runtime.get('recovery_attempts', 0), 'circuit_open': name in circuits,
                'failure_since': runtime.get('failure_since', 0), 'next_recovery_at': runtime.get('next_recovery_at', 0),
                'restart_policy': item['HostConfig']['RestartPolicy']['Name'] if item else '',
                'resources': next((v for k, v in resources.items() if item and item['Id'].startswith(k)), {})})
        dependencies = []
        for dependency in manifest.get('external_dependencies', []):
            item = self.approved_inspect(dependency['container']) if self.a.exists(dependency['container']) else None
            dependencies.append({'name': dependency['name'], 'container': dependency['container'],
                'container_id': item['Id'] if item else '',
                'image': dependency['image'], 'running': bool(item and item['State']['Running']),
                'healthy': bool(item and item['State']['Running'] and all(self.health(p) for p in dependency.get('readiness', []))),
                'management': 'existing-data-preserved', 'bootstrap_available': 'dependency_compose' in files})
        for endpoint in manifest.get('external_endpoints', []):
            healthy = all(self.health(p) for p in endpoint.get('readiness', []))
            dependencies.append({'name': endpoint['name'], 'container_id': 'external:' + endpoint['name'],
                'image': 'external-service', 'running': healthy, 'healthy': healthy, 'management': 'external-service-preserved'})
        self.save(manifest['stack_id'], state)
        return {'services': services, 'dependencies': dependencies, 'recovery_candidates': candidates,
                'recovery_policy': dict(manifest.get('recovery', {}), recover_unhealthy=manifest.get('recovery', {}).get('recover_unhealthy', True), controller='native-restart-with-platform-health-recovery'),
                'circuits': circuits, 'desired_state': state['desired_state'],
                'healthy': all(s['healthy'] or s['manual_stop'] for s in services) and all(d['healthy'] for d in dependencies)}

    def wait_healthy(self, manifest, files, state):
        deadline = time.monotonic() + int(manifest.get('deadline_seconds', 240))
        stable = None
        while time.monotonic() < deadline:
            result = self.probe(manifest, files, state)
            stable = (stable or time.monotonic()) if result['healthy'] else None
            if stable is not None and time.monotonic() - stable >= int(manifest.get('stable_seconds', 20)):
                return result
            time.sleep(2)
        self.check(False, 'STACK_READINESS_FAILED')

    def hooks(self, manifest):
        for reference in manifest.get('post_deploy_hooks', []):
            hook = self.policy.get('hooks', {}).get(reference)
            self.check(hook and hashlib.sha256(self.document(hook['file'])).hexdigest() == hook['sha256'], 'STACK_HOOK_NOT_APPROVED')
            process = subprocess.run([hook.get('interpreter', '/bin/bash'), hook['file']], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
            self.check(process.returncode == 0, 'STACK_HOOK_FAILED')

    def activate_entrypoint(self, manifest, file):
        configured = self.policy.get('active_compose_paths', {}).get(manifest['project'])
        if not configured:
            return
        destination = Path(configured)
        parent = destination.parent.stat()
        self.check(parent.st_uid == 0 and parent.st_mode & 0o022 == 0 and not destination.is_dir(), 'STACK_ENTRYPOINT_NOT_APPROVED')
        if destination.is_symlink() and destination.resolve() == file.resolve():
            return
        backup = self.a.root / 'entrypoint-history' / self.a.current_task
        backup.mkdir(mode=0o700, parents=True, exist_ok=False)
        if destination.exists():
            shutil.copy2(destination, backup / 'previous-compose.json')
        temporary = destination.with_name('.live-monitor-' + self.a.current_task)
        self.check(not temporary.exists() and not temporary.is_symlink(), 'STACK_ENTRYPOINT_TEMP_EXISTS')
        os.symlink(file.resolve(), temporary)
        os.replace(temporary, destination)

    def preflight(self, manifest):
        for record in manifest.get('idle_queries', []):
            self.check(record['container'] in self.policy.get('backup_containers', []) and record['query'].lstrip().upper().startswith('SELECT ') and ';' not in record['query'], 'STACK_IDLE_QUERY_DENIED')
            result = self.a.run(['exec', '--user', record.get('user', 'postgres'), record['container'], 'psql', '-At', '-v', 'ON_ERROR_STOP=1', '-d', record['database'], '-c', record['query']])
            self.check(result.strip() == str(record.get('expected', 0)), 'STACK_ACTIVE_WORKLOAD_MAINTENANCE_DEFERRED')
        for record in manifest.get('idle_probes', []):
            headers = {}
            for name, source in record.get('headers_files', {}).items():
                approved = any(asset['file'] == source['file'] for asset in manifest.get('assets', []))
                value = (Path(source['file']).read_bytes() if approved else self.document(source['file'])).decode().strip()
                headers[name] = source.get('prefix', '') + value
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with client.open(urllib.request.Request(record['url'], headers=headers), timeout=10) as response:
                value = json.loads(response.read(65536))
            for key in record['path']:
                value = value[key]
            self.check(value == record.get('expected', 0), 'STACK_ACTIVE_WORKLOAD_MAINTENANCE_DEFERRED')

    def logs(self, manifest, files, payload):
        name = payload.get('service')
        self.check(name in files['compose'][1]['services'], 'STACK_UNKNOWN_SERVICE')
        def secrets(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if re.search(r'password|secret|token|credential|private.key', key, re.I) and isinstance(item, str) and item:
                        self.a.redactions.append(item)
                    secrets(item)
            elif isinstance(value, str):
                try:
                    parsed = json.loads(value)
                    if isinstance(parsed, (dict, list)):
                        secrets(parsed)
                except ValueError:
                    pass
            elif isinstance(value, list):
                for item in value:
                    secrets(item)
        for service in files['compose'][1]['services'].values():
            secrets(service.get('environment', {}))
            for mount in service.get('volumes', []):
                path = Path(mount['source']) if mount.get('type') == 'bind' else None
                if path and path.is_file() and path.stat().st_size < 65536:
                    if re.search(r'secret|credential|token|\.key$', mount['target'], re.I):
                        secret = path.read_text().strip()
                        if secret:
                            self.a.redactions.append(secret)
        item = self.containers(manifest).get(name)
        self.check(item, 'STACK_CONTAINER_MISSING')
        text = self.a.run(['logs', '--tail', '100', '--timestamps', item['Id']], include_stderr=True).encode()[-16000:].decode(errors='ignore')
        while len(json.dumps({'logs': text}).encode()) > 32000:
            text = text[len(text) // 8 + 1:]
        return {'logs': text, 'service': name}

    def backup(self, manifest, files):
        from agent import digest_file
        folder = self.a.backups / ('stack-' + self.a.current_task)
        folder.mkdir(mode=0o700, exist_ok=False)
        self.a.progress(30, 'STACK_BACKUP_STARTED')
        shutil.copyfile(files['rollback_compose'][0], folder / 'rollback-compose.json')
        for name, record in enumerate(manifest.get('postgres_backups', [])):
            self.check(record['container'] in self.policy.get('backup_containers', []), 'STACK_BACKUP_CONTAINER_DENIED')
            database = record['database']
            self.check(re.fullmatch(r'[a-zA-Z0-9_]{1,63}', database), 'STACK_DATABASE_NAME')
            file = folder / ('database-' + str(name) + '.dump')
            self.a.run(['exec', '--user', record.get('user', 'postgres'), record['container'], 'pg_dump', '-Fc', '-d', database], output_file=file, timeout=600)
            self.a.run(['exec', '-i', '--user', record.get('user', 'postgres'), record['container'], 'pg_restore', '--list'], input_data=file.read_bytes(), timeout=120)
        for index, path in enumerate(manifest.get('backup_paths', [])):
            source = Path(path).resolve()
            self.check(str(source) in self.policy.get('backup_paths', []), 'STACK_BACKUP_PATH_DENIED')
            process = subprocess.run(['/bin/cp', '--reflink=auto', '-a', '--', str(source), str(folder / ('files-' + str(index)))], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
            self.check(process.returncode == 0, 'STACK_DATA_BACKUP_FAILED')
        hashes = {str(p.relative_to(folder)): digest_file(p) for p in folder.rglob('*') if p.is_file() and not p.is_symlink()}
        # Copy preserves original files; verify the complete copied data, not a sample.
        for index, path in enumerate(manifest.get('backup_paths', [])):
            source = Path(path).resolve()
            copied = folder / ('files-' + str(index))
            for original in source.rglob('*') if source.is_dir() else [source]:
                if original.is_file() and not original.is_symlink():
                    target = copied / original.relative_to(source) if source.is_dir() else copied
                    self.check(digest_file(original) == hashes[str(target.relative_to(folder))], 'STACK_BACKUP_CHECKSUM_MISMATCH')
        (folder / 'checksums.json').write_text(json.dumps(hashes))
        return {'backup_id': folder.name, 'verified_files': len(hashes), 'original_data_preserved': True}

    def execute(self, action, payload):
        manifest, files = self.bundle(payload)
        stack = manifest['stack_id']
        state = self.state(stack)
        if state.get('active_compose') == 'rollback_compose' and action not in ['stack_plan', 'stack_deploy', 'stack_rollback']:
            files = dict(files, compose=files['rollback_compose'])
        if action == 'stack_plan':
            self.compose(manifest, files['compose'][0], 'config', '--quiet')
            return {'project': manifest['project'], 'services': [{'service': n, 'image': s['image']} for n, s in files['compose'][1]['services'].items()],
                    'external_dependencies': [d['name'] for d in manifest.get('external_dependencies', []) + manifest.get('external_endpoints', [])],
                    'backup_required': True, 'package_sha256': payload['package_sha256']}
        if action == 'stack_probe':
            return self.probe(manifest, files, state)
        if action == 'stack_logs':
            return self.logs(manifest, files, payload)
        self.check(not self.a.maintenance() or action != 'stack_recover', 'HOST_MAINTENANCE')
        if action in ['stack_stop', 'stack_service_stop']:
            if action == 'stack_stop':
                state['desired_state'] = 'STOPPED'
                for name in files['compose'][1]['services']:
                    state.setdefault('services', {}).setdefault(name, {})['manual_stop'] = True
            else:
                name = payload.get('service', '')
                self.check(name in files['compose'][1]['services'], 'STACK_UNKNOWN_SERVICE')
                state.setdefault('services', {}).setdefault(name, {})['manual_stop'] = True
            self.save(stack, state)
            self.compose(manifest, files['compose'][0], 'stop', *([payload['service']] if action == 'stack_service_stop' else []))
        elif action in ['stack_start', 'stack_service_start', 'stack_service_restart']:
            names = list(files['compose'][1]['services']) if action == 'stack_start' else [payload['service']]
            for name in names:
                self.check(name in files['compose'][1]['services'], 'STACK_UNKNOWN_SERVICE')
                state.setdefault('services', {})[name] = {'manual_stop': False}
            state['desired_state'] = 'RUNNING'
            self.save(stack, state)
            self.compose(manifest, files['compose'][0], 'restart' if action == 'stack_service_restart' else 'start', *names)
            followers = [n for n, s in files['compose'][1]['services'].items() if s.get('network_mode', '') in ['service:' + name for name in names] and n not in names]
            if followers:
                self.compose(manifest, files['compose'][0], 'up', '-d', '--no-deps', '--pull', 'never', '--force-recreate', *followers)
            self.hooks(manifest)
            if state.get('package_ref'):
                self.activate_entrypoint(manifest, files['compose'][0])
        elif action in ['stack_deploy', 'stack_rollback', 'stack_backup']:
            self.check(payload.get('data_compatible') is True, 'STACK_DATA_COMPATIBILITY_CONFIRMATION')
            if state.get('package_ref') != payload.get('package_ref') and manifest.get('baseline_containers'):
                current = self.containers(manifest)
                for name, expected in manifest['baseline_containers'].items():
                    self.check(name in current and current[name]['Id'] == expected['container_id'] and current[name]['Image'] == expected['image_id'], 'STACK_BASELINE_CHANGED_REVIEW_REQUIRED')
            for dependency in manifest.get('external_dependencies', []):
                if not self.a.exists(dependency['container']):
                    self.check(action == 'stack_deploy' and 'dependency_compose' in files and dependency.get('bootstrap') is True, 'STACK_EXTERNAL_DEPENDENCY_MISSING')
                    project = manifest.get('dependency_project')
                    self.check(project in self.policy.get('projects', []), 'STACK_DEPENDENCY_PROJECT_DENIED')
                    self.a.pull(dependency['image'], manifest.get('credential_ref', ''))
                    self.compose(dict(manifest, project=project), files['dependency_compose'][0], 'up', '-d', '--no-build', '--pull', 'never', '--no-deps', dependency['service'])
                expected_image = json.loads(self.a.run(['image', 'inspect', dependency['image']]))[0]['Id']
                self.check(self.approved_inspect(dependency['container'])['Image'] == expected_image, 'STACK_EXTERNAL_IMAGE_DRIFT')
                self.check(self.approved_inspect(dependency['container'])['State']['Running'], 'STACK_EXTERNAL_DEPENDENCY_STOPPED')
                deadline = time.monotonic() + int(manifest.get('dependency_deadline_seconds', 120))
                while not all(self.health(p) for p in dependency.get('readiness', [])) and time.monotonic() < deadline:
                    time.sleep(2)
                self.check(all(self.health(p) for p in dependency.get('readiness', [])), 'STACK_DEPENDENCY_NOT_READY')
            for alias in manifest.get('image_aliases', []):
                self.check(alias['alias'] in self.policy.get('image_aliases', []), 'STACK_IMAGE_ALIAS_DENIED')
                self.a.pull(alias['image'], manifest.get('credential_ref', ''))
                self.a.run(['image', 'tag', alias['image'], alias['alias']])
            for service in files['compose'][1]['services'].values():
                self.a.pull(service['image'], manifest.get('credential_ref', ''))
            self.preflight(manifest)
            previous_running = {n: bool(i['State']['Running']) for n, i in self.containers(manifest).items()}
            was_running = any(previous_running.values())
            previous_state = copy.deepcopy(state)
            self.a.progress(20, 'STACK_QUIESCING')
            self.compose(manifest, files['compose'][0], 'stop')
            applied = False
            try:
                backup = self.backup(manifest, files)
                if action == 'stack_backup':
                    if was_running:
                        self.compose(manifest, files['rollback_compose'][0], 'start', *[n for n, running in previous_running.items() if running])
                        self.hooks(manifest)
                    return backup
                self.a.progress(60, 'STACK_RECREATING')
                selected = files['rollback_compose'][0] if action == 'stack_rollback' else files['compose'][0]
                applied = True
                self.compose(manifest, selected, 'up', '-d', '--no-build', '--pull', 'never', '--force-recreate')
                self.hooks(manifest)
                state = {'desired_state': 'RUNNING', 'services': {}, 'package_ref': payload['package_ref'], 'package_sha256': payload['package_sha256'],
                         'active_compose': 'rollback_compose' if action == 'stack_rollback' else 'compose'}
                self.save(stack, state)
                self.a.progress(85, 'STACK_VERIFYING')
                verification_files = dict(files, compose=files['rollback_compose']) if action == 'stack_rollback' else files
                result = self.wait_healthy(manifest, verification_files, state)
                self.activate_entrypoint(manifest, selected)
                result['backup'] = backup
                result['project'] = manifest['project']
                return result
            except Exception:
                self.a.progress(80, 'STACK_ROLLING_BACK')
                if applied:
                    self.compose(manifest, files['rollback_compose'][0], 'up', '-d', '--no-build', '--pull', 'never', '--force-recreate')
                elif was_running:
                    self.compose(manifest, files['rollback_compose'][0], 'start', *[n for n, running in previous_running.items() if running])
                self.hooks(manifest)
                if not was_running:
                    self.compose(manifest, files['rollback_compose'][0], 'stop')
                elif applied:
                    stopped = [n for n, running in previous_running.items() if not running]
                    if stopped:
                        self.compose(manifest, files['rollback_compose'][0], 'stop', *stopped)
                        for name in stopped:
                            previous_state.setdefault('services', {}).setdefault(name, {})['manual_stop'] = True
                    verification = dict(copy.deepcopy(previous_state), desired_state='RUNNING')
                    self.wait_healthy(manifest, {'compose': files['rollback_compose'], 'rollback_compose': files['rollback_compose']}, verification)
                self.save(stack, previous_state)
                if was_running and applied:
                    self.activate_entrypoint(manifest, files['rollback_compose'][0])
                self.check(False, 'STACK_DEPLOY_FAILED_PREVIOUS_CONFIGURATION_RESTORED')
        elif action == 'stack_recover':
            self.check(state.get('desired_state') == 'RUNNING', 'STACK_RECOVERY_MANUALLY_STOPPED')
            current = self.probe(manifest, files, state)
            for name in current['recovery_candidates']:
                runtime = state['services'][name]
                item = self.containers(manifest)[name]
                limit = int(manifest.get('recovery', {}).get('limit', 3))
                if item.get('RestartCount', 0) >= limit:
                    self.a.run(['update', '--restart', 'no', item['Id']])
                    runtime['circuit_open'] = True
                    self.save(stack, state)
                    continue
                runtime['recovery_attempts'] = runtime.get('recovery_attempts', 0) + 1
                runtime['next_recovery_at'] = int(time.time() * 1000) + min(3600000, 30000 * 2 ** (runtime['recovery_attempts'] - 1))
                self.save(stack, state)
                self.a.run(['restart', '--time', '30', item['Id']], timeout=120)
                followers = [n for n, s in files['compose'][1]['services'].items() if s.get('network_mode') == 'service:' + name]
                if followers:
                    self.compose(manifest, files['compose'][0], 'up', '-d', '--no-deps', '--pull', 'never', '--force-recreate', *followers)
                self.hooks(manifest)
        else:
            self.check(False, 'STACK_UNKNOWN_OPERATION')
        return self.probe(manifest, files, state)
