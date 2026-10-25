"""Signed, declarative offline installation. No package-provided host commands."""
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
from agent import check, identifier, digest_file, atomic_json, AgentError


def safe_relative(value):
    check(isinstance(value, str) and value and not value.startswith('/') and '\\' not in value and '..' not in PurePosixPath(value).parts, 'INSTALL_UNSAFE_PATH')
    return value


def installed_policy(agent, stack_id):
    if not isinstance(stack_id,str) or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}',stack_id):return {}
    file = agent.root / 'installations' / stack_id / 'policy.json'
    if not file.exists():
        return {}
    check(not file.is_symlink() and file.stat().st_uid == 0 and file.stat().st_mode & 0o022 == 0, 'INSTALL_POLICY_PERMISSIONS')
    result = json.loads(file.read_text())
    check(result.get('installation_id') == stack_id, 'INSTALL_POLICY_ID')
    return result['policy']


class Installer:
    def __init__(self, agent):
        self.a = agent
        self.policy = agent.config.get('install_policy', {})
        self.bundles = agent.root / 'install-bundles'
        self.root = agent.root / 'installations'

    def execute(self, action, payload):
        check(self.policy.get('enabled') is True, 'INSTALL_NOT_ENABLED_ON_HOST')
        if action == 'install_upload':
            return self.upload(payload)
        install_id = identifier(payload['installation_id'])
        if action == 'install_plan':
            return self.plan(install_id, payload)
        if action == 'install_apply':
            return self.apply(install_id, payload)
        raise AgentError('INSTALL_UNKNOWN_OPERATION')

    def read_bundle(self, bundle_id):
        check(re.fullmatch(r'[a-f0-9]{64}', bundle_id or ''), 'INSTALL_BUNDLE_ID')
        folder = self.bundles / bundle_id
        raw = (folder / 'installer.json').read_bytes()
        check(hashlib.sha256(raw).hexdigest() == bundle_id, 'INSTALL_BUNDLE_CHANGED')
        doc = json.loads(raw)
        self.validate_manifest(doc)
        for name, checksum in doc['files'].items():
            file = folder / safe_relative(name)
            check(not file.is_symlink() and file.is_file() and digest_file(file) == checksum, 'INSTALL_ASSET_CHANGED')
        return folder, doc

    def validate_manifest(self, doc):
        check(doc.get('format') == 'live-monitor.install.v1', 'INSTALL_PACKAGE_FORMAT')
        identifier(doc.get('product', ''))
        check(doc['product'] in self.policy.get('products', []), 'INSTALL_PRODUCT_NOT_APPROVED')
        check(isinstance(doc.get('version'), str) and 0 < len(doc['version']) <= 80, 'INSTALL_VERSION')
        check(set(doc.get('capabilities', [])) <= set(self.policy.get('capabilities', [])), 'INSTALL_CAPABILITY_NOT_APPROVED')
        check(isinstance(doc.get('files'), dict) and len(doc['files']) <= 10000, 'INSTALL_FILE_LIMIT')
        for name, checksum in doc['files'].items():
            safe_relative(name)
            check(name not in ('installer.json', 'installer.sig') and re.fullmatch(r'[a-f0-9]{64}', checksum or ''), 'INSTALL_FILE_CHECKSUM')
        check(isinstance(doc.get('images'), list) and 0 < len(doc['images']) <= 32, 'INSTALL_IMAGE_LIMIT')
        for image in doc['images']:
            check(image['file'] in doc['files'] and isinstance(image['ids'], list) and image['ids'], 'INSTALL_IMAGE_ARCHIVE')
            check(all(re.fullmatch(r'sha256:[a-f0-9]{64}', value) for value in image['ids']), 'INSTALL_IMAGE_ID')
        parameters = doc.get('parameters', {})
        check(isinstance(parameters, dict) and len(parameters) <= 64 and len(json.dumps(parameters).encode()) < 12000, 'INSTALL_PARAMETERS_LIMIT')
        for name, definition in parameters.items():
            check(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', name), 'INSTALL_PARAMETER_NAME')
            check(name not in ('INSTALL_ROOT','PROJECT','PUBLIC_HOST','PUBLIC_BIND','HOST_MAC_FILE'), 'INSTALL_RESERVED_PARAMETER')
            check(definition.get('type') in ('text', 'integer', 'secret', 'generated'), 'INSTALL_PARAMETER_TYPE')
            check(definition['type'] not in ('secret', 'generated') or 'default' not in definition, 'INSTALL_SECRET_DEFAULT_FORBIDDEN')
        compose = doc.get('compose', {})
        check(isinstance(compose, dict) and 0 < len(compose.get('services', {})) <= 32, 'INSTALL_SERVICES')

    def upload(self, payload):
        check(self.a.config.get('allow_image_import') is True, 'IMAGE_IMPORT_NOT_APPROVED')
        size = payload.get('archive_bytes', 0)
        check(isinstance(size, int) and 0 < size <= self.policy.get('max_archive_bytes', 34359738368), 'INSTALL_ARCHIVE_SIZE')
        expected = payload.get('archive_sha256', '')
        check(re.fullmatch(r'[a-f0-9]{64}', expected), 'INSTALL_ARCHIVE_CHECKSUM')
        check(shutil.disk_usage(self.a.root).free > size + 268435456, 'HOST_DISK_FULL')
        self.bundles.mkdir(parents=True, mode=0o700, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='upload-', dir=self.bundles) as staging:
            staging = Path(staging); archive = staging / 'bundle.tar'
            remaining = size; digest = hashlib.sha256()
            self.a.progress(15, 'INSTALL_RECEIVING')
            with archive.open('xb') as output:
                while remaining:
                    chunk = sys.stdin.buffer.read(min(1048576, remaining))
                    check(bool(chunk), 'INSTALL_UPLOAD_INCOMPLETE')
                    output.write(chunk); digest.update(chunk); remaining -= len(chunk)
            check(digest.hexdigest() == expected, 'INSTALL_ARCHIVE_CHECKSUM_MISMATCH')
            content = staging / 'content'; content.mkdir(mode=0o700)
            self.a.progress(35, 'INSTALL_VERIFYING_SIGNATURE')
            with tarfile.open(archive, 'r:*') as package:
                members = {}; total = 0
                for member in package:
                    safe_relative(member.name)
                    check(member.isfile() or member.isdir(), 'INSTALL_LINKS_FORBIDDEN')
                    check(member.name not in members and len(members) < 10002, 'INSTALL_ARCHIVE_MEMBERS')
                    total += member.size
                    check(total <= self.policy.get('max_unpacked_bytes', 68719476736), 'INSTALL_UNPACKED_LIMIT')
                    members[member.name] = member
                check('installer.json' in members and 0 < members['installer.json'].size <= 262144, 'INSTALL_MANIFEST_REQUIRED')
                check('installer.sig' in members and 0 < members['installer.sig'].size <= 8192, 'INSTALL_SIGNATURE_REQUIRED')
                raw = package.extractfile(members['installer.json']).read()
                signature = package.extractfile(members['installer.sig']).read()
                (staging / 'signed.json').write_bytes(raw); (staging / 'signature').write_bytes(signature)
                verified = False
                for key in self.policy.get('trusted_public_keys', []):
                    key = Path(key)
                    check(key.is_file() and not key.is_symlink() and key.stat().st_uid == 0 and key.stat().st_mode & 0o022 == 0, 'INSTALL_TRUST_KEY_PERMISSIONS')
                    result = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(key), '-signature', str(staging / 'signature'), str(staging / 'signed.json')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                    if result.returncode == 0:
                        verified = True; break
                check(verified, 'INSTALL_SIGNATURE_NOT_TRUSTED')
                doc = json.loads(raw); self.validate_manifest(doc)
                check({name for name, member in members.items() if member.isfile()} == set(doc['files']) | {'installer.json', 'installer.sig'}, 'INSTALL_UNDECLARED_FILES')
                check(shutil.disk_usage(self.a.root).free > total + 268435456, 'HOST_DISK_FULL')
                for name, checksum in doc['files'].items():
                    check(name in members and members[name].isfile(), 'INSTALL_ASSET_MISSING')
                    target = content / name; target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with package.extractfile(members[name]) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output, 1048576)
                    target.chmod(0o600)
                    check(digest_file(target) == checksum, 'INSTALL_ASSET_CHECKSUM')
                (content / 'installer.json').write_bytes(raw)
                bundle_id = hashlib.sha256(raw).hexdigest(); target = self.bundles / bundle_id
                if not target.exists():
                    os.rename(content, target)
            self.a.progress(90, 'INSTALL_PACKAGE_READY')
        return self.metadata(doc, bundle_id, payload['installation_id'])

    def metadata(self, doc, bundle_id, installation_id):
        return {'installation_id': identifier(installation_id), 'bundle_id': bundle_id, 'product': doc['product'], 'name': doc.get('name', doc['product']), 'version': doc['version'], 'parameters': doc.get('parameters', {}), 'requirements': doc.get('requirements', {}), 'services': list(doc['compose']['services']), 'capabilities': doc.get('capabilities', []), 'status': 'UPLOADED'}

    def values(self, doc, supplied):
        check(isinstance(supplied, dict) and set(supplied) <= set(doc.get('parameters', {})), 'INSTALL_UNKNOWN_PARAMETER')
        result = {}
        for name, spec in doc.get('parameters', {}).items():
            value = supplied.get(name, spec.get('default', ''))
            if spec['type'] == 'integer':
                check(isinstance(value, int) and not isinstance(value, bool) and spec.get('min', 1) <= value <= spec.get('max', 65535), 'INSTALL_INTEGER_PARAMETER')
            else:
                check(isinstance(value, str) and len(value) <= spec.get('max_length', 8192 if spec['type'] == 'secret' else 1024) and '\x00' not in value and '\r' not in value, 'INSTALL_TEXT_PARAMETER')
                check(not spec.get('required', True) or bool(value), 'INSTALL_REQUIRED_PARAMETER')
            result[name] = value
            if spec['type'] in ('secret', 'generated') and value:
                self.a.redactions.append(str(value))
        return result

    def intrinsic_values(self, install_id, folder, doc, public_host):
        check(isinstance(public_host, str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,253}', public_host), 'INSTALL_PUBLIC_HOST')
        result = {'INSTALL_ROOT': str(folder), 'PROJECT': 'lm-' + self.a.environment.lower() + '-' + install_id.lower(), 'PUBLIC_HOST': public_host, 'PUBLIC_BIND': self.a.config.get('publish_address', '127.0.0.1')}
        if 'host_identity' in doc.get('capabilities', []):
            routes = Path('/proc/net/route').read_text().splitlines()[1:]
            interface = next((row.split()[0] for row in routes if row.split()[1] == '00000000' and row.split()[0] != 'lo'), '')
            check(re.fullmatch(r'[A-Za-z0-9_.-]+', interface), 'INSTALL_HOST_IDENTITY_UNAVAILABLE')
            file = Path('/sys/class/net') / interface / 'address'
            check(file.is_file(), 'INSTALL_HOST_IDENTITY_UNAVAILABLE'); result['HOST_MAC_FILE'] = str(file)
        if doc.get('network_allocations'):
            networks = json.loads(self.a.run(['network', 'inspect', *self.a.run(['network', 'ls', '-q']).split()]))
            used = [ipaddress.ip_network(config['Subnet']) for network in networks for config in (network.get('IPAM', {}).get('Config') or []) if config.get('Subnet')]
            for line in Path('/proc/net/route').read_text().splitlines()[1:]:
                route=line.split()
                if len(route)>7 and route[7]!='00000000':
                    address=socket.inet_ntoa(int(route[1],16).to_bytes(4,'little'));mask=socket.inet_ntoa(int(route[7],16).to_bytes(4,'little'))
                    used.append(ipaddress.ip_network(address+'/'+mask,strict=False))
            for name, spec in doc['network_allocations'].items():
                check(re.fullmatch(r'[A-Z][A-Z0-9_]{0,31}', name), 'INSTALL_NETWORK_NAME')
                chosen = next((candidate for pool in ('10.192.0.0/10','172.16.0.0/12','192.168.0.0/16') for candidate in ipaddress.ip_network(pool).subnets(new_prefix=24) if not any(candidate.overlaps(existing) for existing in used if candidate.version == existing.version)), None)
                check(chosen is not None, 'INSTALL_NO_FREE_SUBNET'); used.append(chosen)
                result[name + '_SUBNET'] = str(chosen)
                for offset in spec.get('offsets', []):
                    check(isinstance(offset, int) and 1 <= offset <= 254, 'INSTALL_NETWORK_HOST_OFFSET'); result[name + '_IP_' + str(offset)] = str(chosen[offset])
        return result

    def render(self, value, context):
        if isinstance(value, str):
            def replace(match):
                check(match[1] in context, 'INSTALL_UNDEFINED_PLACEHOLDER')
                return str(context[match[1]])
            return re.sub(r'\$\{([A-Za-z][A-Za-z0-9_]*)\}', replace, value)
        if isinstance(value, list): return [self.render(item, context) for item in value]
        if isinstance(value, dict): return {key: self.render(item, context) for key, item in value.items()}
        return value

    def plan(self, install_id, payload):
        folder, doc = self.read_bundle(payload['bundle_id'])
        parameters = self.values(doc, payload.get('parameters', {}))
        destination = self.root / install_id
        check(not (destination / 'installed.json').exists(), 'INSTALL_ALREADY_INSTALLED')
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        context = {**parameters, **self.intrinsic_values(install_id, destination, doc, payload['public_host'])}
        assets = []
        for asset in doc.get('assets', []):
            check(asset['source'] in doc['files'], 'INSTALL_TEMPLATE_MISSING')
            source = folder / asset['source']; target = destination / safe_relative(asset['target'])
            check(PurePosixPath(asset['target']).parts[0] in ('config','init','secrets','assets'), 'INSTALL_RESERVED_ASSET_PATH')
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if asset.get('template', False):
                check(source.stat().st_size <= 1048576, 'INSTALL_TEMPLATE_SIZE'); target.write_text(self.render(source.read_text(), context))
            else: shutil.copyfile(source, target)
            target.chmod(int(asset.get('mode', '0644'), 8) & 0o666)
            uid, gid = asset.get('uid', 0), asset.get('gid', 0)
            check(isinstance(uid, int) and 0 <= uid <= 65535 and isinstance(gid, int) and 0 <= gid <= 65535, 'INSTALL_ASSET_OWNER')
            os.chown(target, uid, gid)
            assets.append({'file': str(target), 'sha256': digest_file(target)})
        compose = self.render(copy.deepcopy(doc['compose']), context)
        self.validate_compose(compose, destination, doc)
        atomic_json(destination/'candidate-compose.json',compose)
        self.a.run(['compose','--project-name',context['PROJECT'],'-f',str(destination/'candidate-compose.json'),'config','--quiet'])
        checks = self.preflight(compose, doc, context['PROJECT'])
        plan_id = hashlib.sha256(json.dumps({'compose': compose, 'assets': assets, 'bundle': payload['bundle_id'], 'context': context}, sort_keys=True).encode()).hexdigest()
        atomic_json(destination / 'plan.json', {'id': plan_id, 'bundle_id': payload['bundle_id'], 'installation_id': install_id, 'compose': compose, 'assets': assets, 'context': context, 'created_at': int(time.time()), 'checks': checks})
        public_parameters={name:value for name,value in parameters.items() if doc['parameters'][name]['type'] not in ('secret','generated')}
        return {'installation_id': install_id, 'plan_id': plan_id, 'ready': all(item['passed'] for item in checks), 'checks': checks, 'services': list(compose['services']), 'version': doc['version'], 'install_directory': str(destination), 'entrypoints': self.entrypoints(doc, context), 'configuration':public_parameters}

    def validate_compose(self, compose, destination, doc):
        check(set(compose) <= {'services', 'networks', 'volumes'}, 'INSTALL_COMPOSE_TOP_LEVEL')
        allowed_fields = {'image','restart','stop_grace_period','environment','ports','volumes','networks','network_mode','depends_on','healthcheck','command','entrypoint','user','working_dir','read_only','tmpfs','security_opt','cap_drop','pids_limit','mem_limit','memswap_limit','cpus','ulimits'}
        images = {image for record in doc['images'] for image in record['ids']}
        for name, service in compose['services'].items():
            identifier(name)
            check(set(service) <= allowed_fields and service.get('image') in images, 'INSTALL_UNSAFE_SERVICE')
            check(service.get('restart','no') in ('no','on-failure','on-failure:3','unless-stopped'), 'INSTALL_RESTART_POLICY')
            check(0 < float(service.get('cpus', 0)) <= self.policy.get('max_service_cpus',self.a.config.get('max_cpus',32)) and service.get('mem_limit'), 'INSTALL_RESOURCE_LIMITS')
            check(16777216<=self.memory_bytes(service['mem_limit'])<=self.policy.get('max_service_memory_mb',self.a.config.get('max_memory_mb',65536))*1048576, 'INSTALL_MEMORY_POLICY')
            check(set(service.get('cap_drop', [])) <= {'ALL'} and service.get('security_opt', []) in ([], ['no-new-privileges:true']), 'INSTALL_SECURITY_OPTIONS')
            service['security_opt'] = ['no-new-privileges:true']
            mode = service.get('network_mode','')
            check(not mode or mode.startswith('service:') and mode[8:] in compose['services'], 'INSTALL_NETWORK_MODE')
            for mount in service.get('volumes', []):
                check(isinstance(mount, dict) and mount.get('type') in ('bind','volume'), 'INSTALL_TYPED_MOUNT')
                target = mount.get('target',''); check(target.startswith('/') and '..' not in PurePosixPath(target).parts and not target.startswith(('/proc/','/sys/','/dev/')), 'INSTALL_MOUNT_TARGET')
                if mount['type'] == 'bind':
                    source = Path(mount['source'])
                    if str(source) == '/run/docker.sock' and target == '/var/run/docker.sock':
                        check('docker_socket' in doc.get('capabilities', []), 'INSTALL_DOCKER_SOCKET_DENIED')
                    elif str(source).startswith('/sys/class/net/'):
                        check('host_identity' in doc.get('capabilities', []) and re.fullmatch('/sys/class/net/[A-Za-z0-9_.-]+/address', str(source)) and mount.get('read_only') is True, 'INSTALL_HOST_IDENTITY_PATH')
                    else:
                        check(destination.resolve() in source.resolve().parents and not source.is_symlink(), 'INSTALL_BIND_PATH')
                        if not source.exists():
                            check(not mount.get('read_only'), 'INSTALL_ASSET_NOT_FOUND'); source.mkdir(parents=True, mode=0o700)
                            # Image users must be able to write their own newly created data directory.
                            source.chmod(0o777)
                    mount['bind'] = {'create_host_path': False}
                else:
                    check(mount['source'] in compose.get('volumes', {}), 'INSTALL_UNKNOWN_VOLUME')
            check(not any('docker.sock' in str(m.get('source',''))+m.get('target','') for m in service.get('volumes', []) if str(m.get('source')) != '/run/docker.sock'), 'INSTALL_SOCKET_ALIAS_DENIED')
        for volume in compose.get('volumes', {}).values(): check(not volume, 'INSTALL_EXTERNAL_VOLUMES_FORBIDDEN')
        for network in compose.get('networks', {}).values(): check(set(network or {}) <= {'internal','ipam'}, 'INSTALL_EXTERNAL_NETWORK_FORBIDDEN')

    def published_ports(self, compose):
        ports = []
        for service in compose['services'].values():
            for port in service.get('ports', []):
                check(isinstance(port, dict) and set(port) <= {'target','published','host_ip','protocol'}, 'INSTALL_TYPED_PORT')
                host_port = int(port['published']); target = int(port['target']); protocol = port.get('protocol','tcp')
                address = port.get('host_ip', self.a.config.get('publish_address','127.0.0.1'))
                check(address in (self.a.config.get('publish_address','127.0.0.1'), '127.0.0.1') and 1024 <= host_port <= 65535 and 1 <= target <= 65535 and protocol in ('tcp','udp'), 'INSTALL_PORT_POLICY')
                check((host_port, protocol) not in [(p[1],p[2]) for p in ports], 'INSTALL_DUPLICATE_PORT')
                ports.append((address, host_port, protocol))
        return ports

    def memory_bytes(self, value):
        match=re.fullmatch(r'([0-9]+(?:\.[0-9]+)?)([bkmg]?)',str(value).lower())
        check(match is not None,'INSTALL_MEMORY_FORMAT')
        return int(float(match[1])*{'':1,'b':1,'k':1024,'m':1048576,'g':1073741824}[match[2]])

    def preflight(self, compose, doc, project):
        self.a.progress(45, 'INSTALL_CHECKING_ENVIRONMENT')
        info = json.loads(self.a.run(['info','--format','{{json .}}']))
        self.a.run(['compose','version','--short'])
        required = doc.get('requirements', {})
        memory_info={line.split(':')[0]:int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.split(':')[0] in ('MemAvailable','MemFree')}
        available_memory=memory_info.get('MemAvailable',memory_info.get('MemFree',0))
        checks = [{'code':'DOCKER_READY','passed':True},
                  {'code':'MEMORY','passed':int(info.get('MemTotal',0)) >= int(required.get('memory_mb',256))*1048576},
                  {'code':'CPU','passed':int(info.get('NCPU',0)) >= int(required.get('cpus',1))},
                  {'code':'DISK','passed':shutil.disk_usage(self.a.root).free >= int(required.get('disk_mb',256))*1048576},
                  {'code':'ARCHITECTURE','passed':not required.get('architecture') or info.get('Architecture') in ({'amd64':'x86_64','arm64':'aarch64'}.get(required['architecture']),required['architecture'])}]
        checks.append({'code':'AVAILABLE_MEMORY','passed':available_memory>=sum(self.memory_bytes(service['mem_limit']) for service in compose['services'].values())})
        checks.append({'code':'PROJECT_AVAILABLE','passed':not self.a.run(['ps','-aq','--filter','label=com.docker.compose.project='+project]).strip()})
        occupied=set()
        for line in self.a.run(['container','ls','--format','{{json .Ports}}']).splitlines():
            occupied.update((int(port),protocol) for port,protocol in re.findall(r':([0-9]+)->[0-9]+/(tcp|udp)',json.loads(line)))
        for address, port, protocol in self.published_ports(compose):
            sock = socket.socket(socket.AF_INET,socket.SOCK_STREAM if protocol=='tcp' else socket.SOCK_DGRAM)
            try: sock.bind((address,port)); passed=True
            except OSError: passed=False
            finally: sock.close()
            checks.append({'code':'PORT','port':port,'passed':passed and (port,protocol) not in occupied})
        return checks

    def entrypoints(self, doc, context):
        values = self.render(doc.get('entrypoints',[]),context)
        for item in values: check(re.fullmatch(r'https?://[A-Za-z0-9_.:\[\]-]+(?:/[A-Za-z0-9_./#?=&%-]*)?',item.get('url','')), 'INSTALL_ENTRYPOINT_URL')
        return values

    def apply(self, install_id, payload):
        destination = self.root / install_id
        plan = json.loads((destination / 'plan.json').read_text())
        check(plan['id'] == payload.get('plan_id'), 'INSTALL_PLAN_EXPIRED_OR_CHANGED')
        folder, doc = self.read_bundle(plan['bundle_id'])
        if (destination / 'installed.json').exists():
            completed=json.loads((destination/'installed.json').read_text())
            check(completed['plan_id']==plan['id'], 'INSTALL_ALREADY_INSTALLED')
            from stack_ops import StackOperations
            engine=StackOperations(self.a)
            package_hash=digest_file(destination/'manifest.json')
            manifest,files=engine.bundle({'stack_id':install_id,'package_ref':install_id,'package_sha256':package_hash})
            snapshot=engine.probe(manifest,files,engine.state(install_id))
            return {'installation_id':install_id,'package_ref':install_id,'package_sha256':package_hash,'project':manifest['project'],'healthy':snapshot['healthy'],'entrypoints':self.entrypoints(doc,plan['context']),'snapshot':snapshot}
        check(not self.a.maintenance(), 'HOST_MAINTENANCE')
        check(time.time()-plan['created_at'] <= 1800, 'INSTALL_PLAN_EXPIRED_OR_CHANGED')
        for name, spec in doc.get('parameters',{}).items():
            if spec['type'] in ('secret','generated') and plan['context'].get(name): self.a.redactions.append(str(plan['context'][name]))
        check(all(item['passed'] for item in self.preflight(plan['compose'],doc,plan['context']['PROJECT'])), 'INSTALL_PREFLIGHT_FAILED')
        for record in plan['assets']: check(digest_file(record['file'])==record['sha256'], 'INSTALL_PREPARED_ASSET_CHANGED')
        self.a.progress(50,'INSTALL_IMPORTING_IMAGES')
        for index, record in enumerate(doc['images']):
            archive = folder / record['file']
            original_progress=self.a.progress
            original_limit=self.a.config.get('max_image_archive_bytes')
            original_unpacked=self.a.config.get('max_image_unpacked_bytes')
            self.a.config['max_image_archive_bytes']=self.policy.get('max_archive_bytes',34359738368)
            self.a.config['max_image_unpacked_bytes']=self.policy.get('max_unpacked_bytes',68719476736)
            self.a.progress=lambda value,message:original_progress(50+int((index+value/100)*14/len(doc['images'])),'INSTALL_IMPORTING_IMAGES')
            try:
                with archive.open('rb') as source:
                    imported = self.a.import_archive({'archive_bytes':archive.stat().st_size,'archive_sha256':doc['files'][record['file']]},source)
            finally:
                self.a.progress=original_progress
                for key,value in [('max_image_archive_bytes',original_limit),('max_image_unpacked_bytes',original_unpacked)]:
                    if value is None:self.a.config.pop(key,None)
                    else:self.a.config[key]=value
            check({image['image'] for image in imported['images']}==set(record['ids']), 'INSTALL_IMAGE_ID_MISMATCH')
        project = plan['context']['PROJECT']; compose = plan['compose']
        # Never take ownership of a pre-existing project, including stopped containers.
        check(not self.a.run(['ps','-aq','--filter','label=com.docker.compose.project='+project]).strip(), 'INSTALL_PROJECT_ALREADY_EXISTS')
        atomic_json(destination/'compose.json',compose)
        from stack_ops import StackOperations
        refs = {'file':'compose.json','sha256':digest_file(destination/'compose.json')}
        manifest = {'schema':1,'environment':self.a.environment,'stack_id':install_id,'project':project,'compose':refs,'rollback_compose':refs,'assets':plan['assets'], 'readiness':self.render(doc.get('readiness',{}),plan['context']), 'recovery':doc.get('recovery',{'limit':3,'failure_window_seconds':30}), 'deadline_seconds':min(600,int(doc.get('deadline_seconds',300))), 'stable_seconds':min(30,int(doc.get('stable_seconds',5)))}
        atomic_json(destination/'manifest.json',manifest)
        policy = {'packages':{install_id:str(destination/'manifest.json')},'projects':[project], 'writable_bind_roots':[str(destination)],'read_only_bind_roots':[str(destination)]+([plan['context']['HOST_MAC_FILE']] if 'HOST_MAC_FILE' in plan['context'] else []),'docker_socket_services':[name for name,service in compose['services'].items() if any(m.get('source')=='/run/docker.sock' for m in service.get('volumes',[]))]}
        atomic_json(destination/'policy.json',{'installation_id':install_id,'policy':policy})
        engine = StackOperations(self.a); engine.policy = policy
        rendered = destination/'compose.json'
        engine.validate_compose(compose)
        files = {'compose':(rendered,compose),'rollback_compose':(rendered,compose)}
        self.a.progress(65,'INSTALL_STARTING_DEPENDENCIES')
        try:
            engine.compose(manifest,rendered,'config','--quiet')
            engine.compose(manifest,rendered,'up','-d','--no-build','--pull','never')
            state={'desired_state':'RUNNING','services':{},'package_ref':install_id,'package_sha256':digest_file(destination/'manifest.json'),'active_compose':'compose'}
            engine.save(install_id,state)
            self.a.progress(85,'INSTALL_VERIFYING_HEALTH')
            snapshot=engine.wait_healthy(manifest,files,state)
            result={'installation_id':install_id,'package_ref':install_id,'package_sha256':state['package_sha256'],'project':project,'healthy':True,'entrypoints':self.entrypoints(doc,plan['context']),'snapshot':snapshot}
            atomic_json(destination/'installed.json',{'version':doc['version'],'plan_id':plan['id'],'completed_at':int(time.time())})
            return result
        except Exception as error:
            self.a.progress(90,'INSTALL_STOPPING_FAILED_APPLICATION')
            details={'reason':str(error) if isinstance(error,AgentError) else 'INSTALL_EXECUTION_FAILED','failed_services':[]}
            try:
                current=engine.containers(manifest)
                details['failed_services']=[name for name in compose['services'] if name not in current or not current[name]['State'].get('Running') or current[name]['State'].get('Health',{}).get('Status') in ('starting','unhealthy')]
            except Exception:pass
            atomic_json(destination/'diagnostics.json',details)
            try:engine.compose(manifest,rendered,'stop')
            except Exception:raise AgentError('INSTALL_FAILED_STOP_REQUIRES_OPERATOR',details)
            state=engine.state(install_id);state['desired_state']='STOPPED';engine.save(install_id,state)
            raise AgentError('INSTALL_FAILED_DATA_RETAINED_CHECK_HOST_DIAGNOSTICS',details)
