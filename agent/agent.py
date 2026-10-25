#!/usr/bin/env python3
"""Live Monitor protocol 1. Run through a forced-command SSH key; no listening port.

Only Docker argv built here can execute. Host-local policy is authoritative even if
the control plane is compromised. Secrets and Docker credentials stay on the host.
"""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile
import tempfile
import time
import base64
import urllib.request
import urllib.error
import urllib.parse


class AgentError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise AgentError("REGISTRY_REDIRECT_NOT_ALLOWED")


def check(condition, code):
    if not condition:
        raise AgentError(code)


def identifier(value):
    check(isinstance(value, str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}", value), "INVALID_IDENTIFIER")
    return value


def digest_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def docker_error_code(message):
    text = message.lower()
    for patterns, code in [(("no space left on device",), "HOST_DISK_FULL"),
                           (("port is already allocated", "address already in use"), "PORT_CONFLICT"),
                           (("unauthorized", "pull access denied", "authentication required"), "REGISTRY_AUTH_FAILED"),
                           (("permission denied", "operation not permitted"), "DOCKER_PERMISSION_DENIED"),
                           (("no such container",), "CONTAINER_NOT_FOUND"),
                           (("manifest unknown", "not found"), "IMAGE_OR_RESOURCE_NOT_FOUND")]:
        if any(pattern in text for pattern in patterns):
            return code
    return "DOCKER_FAILED"


def atomic_json(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


@contextlib.contextmanager
def lock(path):
    # POSIX agent only. Import here lets pure validation tests run on Windows too.
    import fcntl
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(path, "a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield


class Agent:
    def __init__(self, config):
        self.config = config
        self.environment = identifier(config["environment"])
        self.root = Path(config["state_root"]).resolve()
        self.backups = Path(config["backup_root"]).resolve()
        self.secrets = Path(config["secrets_root"]).resolve()
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.backups.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.redactions = []
        self.current_task = ""
        self.validation_containers = []

    def progress(self, value, message):
        print(json.dumps({"progress": value, "message": message}), flush=True)

    def run(self, args, input_data=None, output_file=None, timeout=120, include_stderr=False):
        check(isinstance(args, list) and all(isinstance(a, str) and "\x00" not in a for a in args), "INVALID_ARGV")
        binary = self.config.get("docker_binary", "/usr/bin/docker")
        environment = {"PATH": "/usr/bin:/bin", "HOME": str(self.root), "LANG": "C.UTF-8"}
        if self.config.get("docker_config"):
            environment["DOCKER_CONFIG"] = self.config["docker_config"]
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            try:
                process = subprocess.run([binary] + args, input=input_data, stdout=stdout,
                                         stderr=stderr, env=environment, timeout=timeout, check=False)
            except subprocess.TimeoutExpired:
                raise AgentError("DOCKER_TIMEOUT")
            if process.returncode:
                stderr.seek(0)
                code = docker_error_code(stderr.read(8192).decode("utf-8", errors="replace"))
                # No raw Docker output in API/logs: it can contain secrets. Record safe argv only.
                atomic_json(self.root / "diagnostics" / (self.current_task + ".json"),
                            {"code": code, "command": [binary] + self.safe_args(args),
                             "exit_code": process.returncode, "time": int(time.time() * 1000)})
                raise AgentError(code)
            stdout.seek(0)
            if output_file:
                with open(output_file, "xb") as target:
                    for chunk in iter(lambda: stdout.read(1024 * 1024), b""):
                        target.write(chunk)
                os.chmod(output_file, 0o600)
                return ""
            data = stdout.read(400001)
            if include_stderr:
                stderr.seek(0)
                data += stderr.read(400001 - min(len(data), 400001))
            check(len(data) <= 400000, "DOCKER_OUTPUT_LIMIT")
            result = data.decode("utf-8", errors="replace")
            for secret in self.redactions:
                result = result.replace(secret, "[REDACTED]")
            return result

    @staticmethod
    def safe_args(args):
        output, hidden = [], False
        for arg in args:
            if hidden:
                output.append("[REDACTED]")
                hidden = False
            else:
                output.append(arg)
                hidden = arg in ("-e", "--env", "--env-file")
        return output

    def image(self, value):
        if isinstance(value, str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value):
            catalog = self.root / "imports" / (value[7:] + ".json")
            check(catalog.is_file(), "LOCAL_IMAGE_NOT_IMPORTED_IN_ENVIRONMENT")
            return value
        check(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.:-]+/[A-Za-z0-9_./-]+@sha256:[a-f0-9]{64}", value), "IMMUTABLE_IMAGE_REQUIRED")
        check(value.split("/")[0] in self.config["allowed_registries"], "REGISTRY_NOT_ALLOWED")
        return value

    def image_catalog(self):
        images = []
        for path in sorted((self.root / "imports").glob("*.json"))[-100:]:
            item = json.loads(path.read_text())
            # Files are provenance, not a promise the image still exists in Docker.
            if self.run(["image", "ls", "--no-trunc", "--quiet", item["image"]]).strip():
                images.append(item)
        imported = {item["image"] for item in images}
        for line in self.run(["image", "ls", "--no-trunc", "--digests", "--format", "{{json .}}"]).splitlines()[:100]:
            item = json.loads(line)
            if item.get("ID") in imported:
                continue
            repository, digest = item.get("Repository", ""), item.get("Digest", "")
            if repository.split("/")[0] in self.config["allowed_registries"] and re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
                item["image"] = repository + "@" + digest
            images.append(item)
        return images[:100]

    def import_archive(self, payload):
        check(self.config.get("allow_image_import", False) is True, "IMAGE_IMPORT_NOT_APPROVED")
        size = payload.get("archive_bytes", 0)
        check(isinstance(size, int) and 0 < size <= self.config.get("max_image_archive_bytes", 4294967296), "ARCHIVE_SIZE_LIMIT")
        expected = payload.get("archive_sha256", "")
        check(re.fullmatch(r"[a-f0-9]{64}", expected), "ARCHIVE_CHECKSUM_REQUIRED")
        import shutil
        check(shutil.disk_usage(self.root).free > size + 268435456, "HOST_DISK_FULL")
        with tempfile.TemporaryDirectory(prefix="image-import-", dir=self.root) as folder:
            archive = Path(folder) / "image.archive"
            digest = hashlib.sha256()
            remaining = size
            self.progress(15, "RECEIVE_ARCHIVE")
            with open(archive, "xb") as target:
                while remaining:
                    block = sys.stdin.buffer.read(min(1024 * 1024, remaining))
                    check(bool(block), "INCOMPLETE_ARCHIVE")
                    target.write(block); digest.update(block); remaining -= len(block)
            check(digest.hexdigest() == expected, "ARCHIVE_CHECKSUM_MISMATCH")
            self.progress(35, "VALIDATE_ARCHIVE")
            # Inspect compressed Docker-save archives without extracting attacker paths.
            try:
                with tarfile.open(archive, "r:*") as saved:
                    members = {}; unpacked = 0
                    for member in saved:
                        name = member.name
                        check(not name.startswith("/") and ".." not in Path(name).parts and "\\" not in name and (member.isfile() or member.isdir()), "UNSAFE_ARCHIVE_MEMBER")
                        check(name not in members and len(members) < 100000, "ARCHIVE_MEMBER_LIMIT")
                        members[name] = member
                        unpacked += member.size
                        check(unpacked <= self.config.get("max_image_unpacked_bytes", 21474836480), "ARCHIVE_UNPACKED_LIMIT")
                    check(shutil.disk_usage(self.root).free > unpacked + 268435456, "HOST_DISK_FULL")
                    check("manifest.json" in members and 0 < members["manifest.json"].size <= 1048576, "DOCKER_SAVE_ARCHIVE_REQUIRED")
                    manifests = json.load(saved.extractfile(members["manifest.json"]))
                    check(isinstance(manifests, list) and 0 < len(manifests) <= 100, "ARCHIVE_IMAGE_LIMIT")
                    approved = []
                    for manifest in manifests:
                        config = members.get(manifest.get("Config"))
                        check(config is not None and config.isfile() and 0 < config.size <= 1048576, "INVALID_IMAGE_CONFIG")
                        config_bytes = saved.extractfile(config).read()
                        image_id = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
                        layers = manifest.get("Layers")
                        check(isinstance(layers, list) and all(layer in members and members[layer].isfile() for layer in layers), "INVALID_IMAGE_LAYERS")
                        metadata = json.loads(config_bytes).get("config") or {}
                        tags = manifest.get("RepoTags") or []
                        check(isinstance(tags, list) and all(isinstance(t, str) and len(t) <= 255 for t in tags), "INVALID_IMAGE_TAGS")
                        approved.append({"image": image_id, "tags": tags, "ports": list((metadata.get("ExposedPorts") or {}).keys()), "healthcheck": (metadata.get("Healthcheck") or {}).get("Test", []), "volumes": list((metadata.get("Volumes") or {}).keys()), "imported_at": int(time.time()*1000), "archive_sha256": expected})
            except (tarfile.TarError, ValueError, KeyError, TypeError):
                raise AgentError("INVALID_DOCKER_ARCHIVE")
            self.progress(55, "IMPORT_IMAGE")
            self.run(["image", "load", "--input", str(archive)], timeout=600)
            for item in approved:
                actual = json.loads(self.run(["image", "inspect", item["image"]]))[0]
                check(actual["Id"] == item["image"], "IMAGE_DIGEST_MISMATCH")
                atomic_json(self.root / "imports" / (item["image"][7:] + ".json"), item)
            return {"images": approved, "archive_sha256": expected, "imported": len(approved)}

    def name(self, app):
        return "lm-" + self.environment + "-" + identifier(app)

    def state(self, app):
        path = self.root / "applications" / (identifier(app) + ".json")
        return json.loads(path.read_text()) if path.exists() else {}

    def save_state(self, app, state):
        atomic_json(self.root / "applications" / (identifier(app) + ".json"), state)

    def inspect(self, name):
        value = json.loads(self.run(["container", "inspect", name]))[0]
        labels = value.get("Config", {}).get("Labels", {}) or {}
        check(labels.get("live-monitor.environment") == self.environment and labels.get("live-monitor.app"), "UNMANAGED_CONTAINER")
        return value

    def exists(self, name):
        return name in self.run(["container", "ls", "-a", "--format", "{{.Names}}", "--filter", "name=^/" + name + "$"]).splitlines()

    def validate(self, spec):
        app = identifier(spec["id"])
        check(spec["environment"] == self.environment, "ENVIRONMENT_MISMATCH")
        self.image(spec["image"])
        check(16 <= int(spec["memory_mb"]) <= self.config.get("max_memory_mb", 65536), "MEMORY_LIMIT")
        check(0 < float(spec["cpus"]) <= self.config.get("max_cpus", 32), "CPU_LIMIT")
        check(spec["restart_policy"] in ("no", "on-failure", "unless-stopped"), "RESTART_POLICY")
        check(1 <= int(spec.get("restart_limit", 3)) <= 20, "RESTART_LIMIT")
        check(spec.get("data_kind") in ("stateless", "volume", "postgres"), "DATA_KIND")
        check(not spec.get("user") or re.fullmatch(r"[a-zA-Z0-9_-]+(?::[a-zA-Z0-9_-]+)?", spec["user"]), "CONTAINER_USER")
        check(5 <= int(spec.get("health_deadline_seconds", 90)) <= 600, "HEALTH_DEADLINE")
        health = spec.get("health", {})
        argv = health.get("command")
        check(health.get("mode", "command") in ("command", "running"), "HEALTH_MODE")
        check(isinstance(argv, list) and len(argv) < 64 and all(isinstance(a, str) and len(a) < 2048 for a in argv) and (len(argv) > 0 or health.get("mode") == "running"), "HEALTH_ARGV")
        check(isinstance(spec.get("command", []), list) and len(spec.get("command", [])) < 64 and all(isinstance(a, str) and len(a) < 2048 for a in spec.get("command", [])), "CONTAINER_COMMAND_ARGV")
        check(1 <= int(health.get("interval_seconds", 10)) <= 300 and 1 <= int(health.get("timeout_seconds", 3)) <= 60, "HEALTH_TIMING")
        network = spec.get("network") or "bridge"
        identifier(network)
        check(network not in ("host", "none", "container"), "NETWORK_NOT_ALLOWED")
        used = set()
        for p in spec.get("ports", []):
            port = int(p["host"])
            check(1024 <= port <= 65535 and port not in used and 1 <= int(p["container"]) <= 65535, "INVALID_PORT")
            check(p["protocol"] in ("tcp", "udp"), "INVALID_PROTOCOL")
            used.add(port)
        for m in spec.get("mounts", []):
            check(m.get("type") in ("bind", "volume"), "MOUNT_TYPE")
            target = m["target"]
            check(target.startswith("/") and ".." not in target.split("/") and not any(target == p or target.startswith(p + "/") for p in ("/proc", "/sys", "/dev", "/run", "/var/run")), "UNSAFE_MOUNT_TARGET")
            check("," not in target and "," not in m["source"] and "docker.sock" not in target + m["source"], "UNSAFE_MOUNT")
            if m["type"] == "volume":
                identifier(m["source"])
                check(m["source"].startswith("lm-"), "VOLUME_NAMESPACE")
            else:
                path = Path(m["source"]).resolve(strict=True)
                allowed = [Path(p).resolve() for p in self.config.get("allowed_bind_roots", [])]
                check(m.get("read_only") is True and any(path == p or p in path.parents for p in allowed), "BIND_PATH_NOT_ALLOWED")
        for key, value in spec.get("environment_variables", {}).items():
            check(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) and not re.search(r"password|secret|token|credential|private_key", key, re.I), "SECRET_REFERENCE_REQUIRED")
            check(isinstance(value, str) and "\n" not in value and "\x00" not in value, "INVALID_ENV_VALUE")
        for key, ref in spec.get("secret_refs", {}).items():
            check(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key), "INVALID_ENV_KEY")
            identifier(ref)
            path = (self.secrets / ref).resolve(strict=True)
            check(self.secrets in path.parents and path.is_file(), "SECRET_PATH")
        return app

    def env_file(self, spec):
        values = dict(spec.get("environment_variables", {}))
        for key, ref in spec.get("secret_refs", {}).items():
            value = (self.secrets / ref).read_text().rstrip("\n")
            check("\n" not in value and "\x00" not in value, "MULTILINE_SECRET_NOT_SUPPORTED")
            self.redactions.append(value)
            values[key] = value
        path = self.root / ("env-" + self.current_task)
        with open(path, "x") as stream:
            os.chmod(path, 0o600)
            stream.write("\n".join(k + "=" + v for k, v in values.items()))
        return path

    def pull(self, image, credential_ref=""):
        self.image(image)
        if image.startswith("sha256:"):
            data = json.loads(self.run(["image", "inspect", image]))[0]
            check(data["Id"] == image, "IMAGE_DIGEST_MISMATCH")
            return {"image": image, "image_id": image}
        if credential_ref:
            identifier(credential_ref)
            path = (self.secrets / credential_ref).resolve(strict=True)
            check(self.secrets in path.parents and path.is_dir(), "DOCKER_CREDENTIAL_REFERENCE")
            self.config["docker_config"] = str(path)
        self.progress(20, "PULL_IMAGE")
        self.run(["image", "pull", image], timeout=300)
        data = json.loads(self.run(["image", "inspect", image]))[0]
        # Docker Hub may report canonical short repository names; content digest is immutable.
        check(any(d.split("@")[-1] == image.split("@")[-1] for d in (data.get("RepoDigests") or [])), "IMAGE_DIGEST_MISMATCH")
        return {"image": image, "image_id": data["Id"]}

    def registry_query(self, action, payload):
        registry = payload["registry"]
        check(registry in self.config["allowed_registries"], "REGISTRY_NOT_ALLOWED")
        check(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.:-]{1,253}", registry), "REGISTRY_ADDRESS")
        repository = payload.get("repository", "")
        if repository:
            check(re.fullmatch(r"[a-z0-9][a-z0-9_./-]{0,254}", repository) and ".." not in repository, "REPOSITORY_NAME")
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        headers = {"Accept": "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json"}
        credentials = ""
        ref = payload.get("credential_ref", "")
        if ref:
            identifier(ref)
            path = (self.secrets / ref / "config.json").resolve(strict=True)
            check(self.secrets in path.parents, "DOCKER_CREDENTIAL_REFERENCE")
            config = json.loads(path.read_text())
            credentials = config.get("auths", {}).get(registry, {}).get("auth", "")
        if credentials:
            headers["Authorization"] = "Basic " + credentials
        path = "/v2/_catalog?n=100"
        if repository:
            path = "/v2/" + repository + "/tags/list?n=100"
        if action == "resolve_image":
            tag = payload.get("tag", "")
            check(repository and re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag), "IMAGE_TAG_REQUIRED")
            path = "/v2/" + repository + "/manifests/" + tag
        base = "https://" + ("registry-1.docker.io" if registry == "docker.io" else registry)
        def fetch():
            return client.open(urllib.request.Request(base + path, headers=headers), timeout=10)
        try:
            try:
                response = fetch()
            except urllib.error.HTTPError as error:
                check(error.code == 401, "REGISTRY_METADATA_UNAVAILABLE")
                challenge = error.headers.get("WWW-Authenticate", "")
                check(challenge.startswith("Bearer "), "REGISTRY_AUTH_UNSUPPORTED")
                values = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
                realm = urllib.parse.urlsplit(values.get("realm", ""))
                allowed_auth = self.config.get("registry_auth_hosts", ["auth.docker.io"] if registry == "docker.io" else [registry])
                check(realm.scheme == "https" and realm.netloc in allowed_auth and not realm.username and not realm.fragment, "REGISTRY_AUTH_HOST_NOT_ALLOWED")
                parameters = urllib.parse.urlencode({"service": values.get("service", ""), "scope": values.get("scope", "")})
                auth_headers = {"Authorization": "Basic " + credentials} if credentials else {}
                token_response = client.open(urllib.request.Request(values["realm"] + ("&" if realm.query else "?") + parameters, headers=auth_headers), timeout=10)
                with token_response:
                    token_bytes = token_response.read(65537)
                check(len(token_bytes) <= 65536, "REGISTRY_TOKEN_LIMIT")
                token = json.loads(token_bytes)
                headers["Authorization"] = "Bearer " + (token.get("token") or token["access_token"])
                response = fetch()
            with response:
                body = response.read(400001)
                check(len(body) <= 400000, "REGISTRY_METADATA_LIMIT")
                if action == "resolve_image":
                    digest = response.headers.get("Docker-Content-Digest", "")
                    check(re.fullmatch(r"sha256:[a-f0-9]{64}", digest), "REGISTRY_DIGEST_MISSING")
                    check(hashlib.sha256(body).hexdigest() == digest[7:], "REGISTRY_MANIFEST_DIGEST_MISMATCH")
                    return {"image": registry + "/" + repository + "@" + digest, "tag": payload["tag"]}
                result = json.loads(body)
                return {"repository": repository, "repositories": (result.get("repositories") or [])[:100], "tags": (result.get("tags") or [])[:100], "page_limit": 100, "has_more": bool(response.headers.get("Link"))}
        except (urllib.error.URLError, ValueError, KeyError):
            raise AgentError("REGISTRY_METADATA_UNAVAILABLE")

    def create_args(self, spec, name, env):
        args = ["container", "create", "--name", name, "--label", "live-monitor.app=" + spec["id"],
                "--label", "live-monitor.environment=" + self.environment, "--label", "live-monitor.task=" + self.current_task,
                "--memory", str(int(spec["memory_mb"])) + "m", "--cpus", str(float(spec["cpus"])),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--network", spec.get("network") or "bridge",
                "--env-file", str(env)]
        restart = spec["restart_policy"]
        if spec.get("user"):
            args += ["--user", spec["user"]]
        if restart == "on-failure":
            restart += ":" + str(int(spec.get("restart_limit", 3)))
        args += ["--restart", restart]
        for p in spec.get("ports", []):
            # Binding interface is host policy, never user-provided arbitrary syntax.
            args += ["--publish", self.config.get("publish_address", "127.0.0.1") + ":" + str(int(p["host"])) + ":" + str(int(p["container"])) + "/" + p["protocol"]]
        for m in spec.get("mounts", []):
            args += ["--mount", "type=" + m["type"] + ",src=" + m["source"] + ",dst=" + m["target"] + (",readonly" if m.get("read_only") else "")]
        health = spec["health"]
        if health.get("mode") == "running":
            return args + ["--no-healthcheck", spec["image"]] + spec.get("command", [])
        args += ["--health-cmd", "exec " + " ".join(shlex.quote(a) for a in health["command"]),
                 "--health-interval", str(int(health.get("interval_seconds", 10))) + "s",
                 "--health-timeout", str(int(health.get("timeout_seconds", 3))) + "s", "--health-retries", "3", spec["image"]]
        return args + spec.get("command", [])

    def volumes(self, spec, create=True):
        for m in spec.get("mounts", []):
            if m["type"] != "volume":
                continue
            name = m["source"]
            found = name in self.run(["volume", "ls", "--format", "{{.Name}}", "--filter", "name=^" + name + "$"]).splitlines()
            if not found:
                check(create, "BACKUP_SOURCE_VOLUME_MISSING")
                self.run(["volume", "create", "--label", "live-monitor.app=" + spec["id"], "--label", "live-monitor.environment=" + self.environment, name])
            data = json.loads(self.run(["volume", "inspect", name]))[0]
            labels = data.get("Labels") or {}
            check(labels.get("live-monitor.app") == spec["id"] and labels.get("live-monitor.environment") == self.environment, "VOLUME_OWNERSHIP")

    def wait_healthy(self, name, seconds, running_only=False):
        deadline = time.monotonic() + seconds
        stable_since = time.monotonic()
        while time.monotonic() < deadline:
            state = self.inspect(name)["State"]
            if state.get("Health", {}).get("Status") == "healthy":
                return
            check(state.get("Running") is True, "CANDIDATE_EXITED")
            if running_only and time.monotonic() - stable_since >= min(5, seconds - 1):
                return
            time.sleep(1)
        raise AgentError("HEALTH_CHECK_FAILED")

    def deploy(self, payload):
        spec = payload["spec"]
        app = self.validate(spec)
        check(not self.maintenance(), "HOST_MAINTENANCE")
        name = self.name(app)
        self.pull(spec["image"], payload.get("credential_ref", ""))
        if payload.get("validate_only"):
            self.run(["network", "inspect", spec.get("network") or "bridge"])
            return {"validated": True, "image": spec["image"]}
        old = name + "-old-" + self.current_task[:8]
        had_old = self.exists(name)
        previous_running = False
        if had_old:
            previous_running = bool(self.inspect(name)["State"].get("Running"))
            # Stateful versions require explicit compatibility acknowledgment before touching old data.
            previous_kind = self.state(app).get("spec", {}).get("data_kind", "volume")
            check((spec["data_kind"] == "stateless" and previous_kind == "stateless") or payload.get("data_compatible") is True, "DATA_COMPATIBILITY_REQUIRED")
        self.volumes(spec)
        env = self.env_file(spec)
        created = False
        moved = False
        try:
            self.progress(45, "PREPARE_SWITCH")
            if had_old:
                self.run(["container", "stop", "--time", "30", name])
                self.run(["container", "rename", name, old])
                moved = True
            self.run(self.create_args(spec, name, env))
            created = True
            self.run(["container", "start", name])
            self.progress(70, "WAIT_HEALTHY")
            self.wait_healthy(name, int(spec.get("health_deadline_seconds", 90)), spec["health"].get("mode") == "running")
            state = self.state(app)
            state.update({"manual_stop": False, "recovery_attempts": 0, "failure_since": 0, "next_recovery_at": 0, "spec": spec})
            self.save_state(app, state)
            return {"container": name, "image": spec["image"], "previous_container": old if moved else None, "healthy": True}
        except Exception as original:
            rollback_failed = False
            if created:
                # Keep the failed candidate for inspection, never delete its volumes.
                try:
                    if self.exists(name):
                        self.run(["container", "stop", "--time", "10", name])
                        self.run(["container", "rename", name, name + "-failed-" + self.current_task[:8]])
                except AgentError:
                    rollback_failed = True
            if moved:
                try:
                    self.run(["container", "rename", old, name])
                    if previous_running:
                        self.run(["container", "start", name])
                except AgentError:
                    rollback_failed = True
            check(not rollback_failed, "ROLLBACK_REQUIRES_OPERATOR")
            raise original
        finally:
            env.unlink(missing_ok=True)

    def maintenance(self):
        path = self.root / "maintenance.json"
        return path.exists() and json.loads(path.read_text()).get("enabled") is True

    def snapshot(self):
        info = json.loads(self.run(["info", "--format", "{{json .}}"])); containers = []
        stats = self.run(["stats", "--no-stream", "--format", "{{json .}}"])
        resources = [json.loads(line) for line in stats.splitlines()][:100]
        resource_map = {r.get("Name"): r for r in resources}
        names = self.run(["container", "ls", "-a", "--format", "{{.Names}}", "--filter", "label=live-monitor.environment=" + self.environment]).splitlines()
        for name in names[:100]:
            # Superseded and failed candidates remain visible in Docker, not recovery targets.
            data = self.inspect(name); app = data["Config"]["Labels"]["live-monitor.app"]
            if name != self.name(app):
                continue
            state = data["State"]; local = self.state(app); health = state.get("Health", {}).get("Status", "none")
            process_only = local.get("spec", {}).get("health", {}).get("mode") == "running"
            healthy = bool(state.get("Running")) and (health == "healthy" or (process_only and health == "none"))
            usage = resource_map.get(name, {})
            cpu = float(str(usage.get("CPUPerc", "0%")).rstrip("%")) / max(0.001, float(local.get("spec", {}).get("cpus", 1)))
            memory = float(str(usage.get("MemPerc", "0%")).rstrip("%"))
            resource_abnormal = cpu > self.config.get("cpu_alert_percent", 95) or memory > self.config.get("memory_alert_percent", 90)
            now = int(time.time() * 1000)
            if healthy and not resource_abnormal:
                local["failure_since"] = 0
            elif not local.get("failure_since"):
                local["failure_since"] = now
            self.save_state(app, local)
            containers.append({"app_id": app, "name": name, "image": data["Config"]["Image"], "status": state["Status"],
                               "health": health, "healthy": healthy, "exit_code": state.get("ExitCode", 0),
                               "native_restarts": data.get("RestartCount", 0), "maintenance": self.maintenance(),
                               "resource_abnormal": resource_abnormal, "circuit_open": bool(local.get("circuit_open")),
                               **{k: local.get(k, False if k == "manual_stop" else 0) for k in ("manual_stop", "failure_since", "recovery_attempts", "next_recovery_at")}})
        return {"docker_version": info.get("ServerVersion"), "containers_running": info.get("ContainersRunning"),
                "cpu_count": info.get("NCPU"), "memory_bytes": info.get("MemTotal"), "containers": containers,
                "resources": [r for r in resources if r.get("Name") in {c["name"] for c in containers}], "observed_at": int(time.time() * 1000)}

    def operate(self, action, payload):
        app = identifier(payload["app_id"]); name = self.name(app); data = self.inspect(name)
        check(data["Config"]["Labels"]["live-monitor.app"] == app, "APP_OWNERSHIP")
        state = self.state(app)
        if action == "logs":
            for ref in payload.get("spec", {}).get("secret_refs", {}).values():
                identifier(ref)
                path = (self.secrets / ref).resolve(strict=True)
                check(self.secrets in path.parents, "SECRET_PATH")
                self.redactions.append(path.read_text().rstrip("\n"))
            text = self.run(["logs", "--tail", "100", "--timestamps", name], include_stderr=True)
            tail = text.encode("utf-8")[-16000:].decode("utf-8", errors="ignore")
            while len(json.dumps({"logs": tail}, ensure_ascii=False).encode("utf-8")) > 32000:
                tail = tail[len(tail) // 2:]
            return {"logs": tail}
        if action == "stop":
            state["manual_stop"] = True
            self.save_state(app, state)  # Persist intent before asking Docker to stop.
        else:
            check(not self.maintenance(), "HOST_MAINTENANCE")
        if action == "recover":
            spec = state.get("spec", {}); self.validate(spec)
            now = int(time.time() * 1000); limit = int(spec.get("restart_limit", 3))
            check(not state.get("manual_stop"), "MANUAL_STOP_SUPPRESSED")
            check(state.get("recovery_attempts", 0) < limit and data.get("RestartCount", 0) < limit, "RECOVERY_CIRCUIT_OPEN")
            check(now >= state.get("next_recovery_at", 0), "RECOVERY_BACKOFF")
            check(now - state.get("failure_since", now) >= int(spec.get("failure_window_seconds", 30)) * 1000, "FAILURE_DEBOUNCE")
            health = data["State"].get("Health", {}).get("Status")
            check((health == "unhealthy" and spec.get("recover_unhealthy") is True) or
                  (data["State"]["Status"] == "exited" and data["State"].get("ExitCode", 0) != 0 and data["HostConfig"]["RestartPolicy"]["Name"] == "no"), "NATIVE_RESTART_OWNS_EXIT")
            state["recovery_attempts"] = state.get("recovery_attempts", 0) + 1
            state["next_recovery_at"] = now + min(3600000, 30000 * 2 ** (state["recovery_attempts"] - 1))
            self.save_state(app, state)
            self.run(["container", "restart", "--time", "30", name])
            reason = "unhealthy" if data["State"].get("Running") and health == "unhealthy" else "exited"
            return {"attempt": state["recovery_attempts"], "reason": reason, "command": ["docker", "container", "restart", name], "started_at": now, "finished_at": int(time.time() * 1000)}
        if action == "circuit_break":
            spec = state.get("spec", {}); self.validate(spec)
            check(not state.get("manual_stop") and data.get("RestartCount", 0) >= int(spec.get("restart_limit", 3)), "NATIVE_LIMIT_NOT_REACHED")
            self.run(["update", "--restart", "no", name])
            state["circuit_open"] = True
            self.save_state(app, state)
            return {"circuit_open": True, "native_restarts": data.get("RestartCount", 0)}
        if action in ("start", "restart"):
            self.run(["container", action, name])
            state.update({"manual_stop": False, "recovery_attempts": 0, "next_recovery_at": 0, "failure_since": 0, "circuit_open": False})
            spec = state.get("spec", {})
            restart = spec.get("restart_policy", "no")
            if restart == "on-failure":
                restart += ":" + str(int(spec.get("restart_limit", 3)))
            self.run(["update", "--restart", restart, name])
            self.save_state(app, state)
        elif action == "stop":
            self.run(["container", "stop", "--time", "30", name])
        else:
            raise AgentError("UNKNOWN_OPERATION")
        return {"container": name, "status": self.inspect(name)["State"]["Status"], "manual_stop": state.get("manual_stop", False)}

    def helper(self, args, mounts):
        image = self.image(self.config["backup_helper_image"])
        command = ["run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL", "--cap-add", "DAC_OVERRIDE", "--cap-add", "CHOWN", "--cap-add", "FOWNER", "--memory", "512m", "--cpus", "1", "--security-opt", "no-new-privileges:true"]
        for mount in mounts:
            command += ["--mount", mount]
        return self.run(command + [image] + args, timeout=300)

    @staticmethod
    def archive_manifest(path):
        result = {}
        with tarfile.open(path, "r:gz") as archive:
            for item in archive:
                check(not item.name.startswith("/") and ".." not in Path(item.name).parts and (item.isdir() or item.isfile()), "UNSAFE_ARCHIVE_MEMBER")
                if item.isfile():
                    h = hashlib.sha256()
                    with archive.extractfile(item) as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b""):
                            h.update(block)
                    result[item.name] = {"sha256": h.hexdigest(), "uid": item.uid, "gid": item.gid, "mode": item.mode}
        return result

    def backup(self, payload):
        app = identifier(payload["app_id"])
        spec = self.state(app).get("spec", {})
        self.validate(spec)
        backup_id = identifier(payload["backup_id"])
        folder = self.backups / backup_id; folder.mkdir(mode=0o700)
        manifest = {"id": backup_id, "app_id": app, "environment": self.environment, "spec": spec, "files": [], "restore_tested": False}
        self.progress(25, "BACKUP_CONSISTENCY_CHECK")
        if spec["data_kind"] == "postgres":
            database = identifier(spec.get("postgres_database", "postgres")); user = identifier(spec.get("postgres_user", "postgres"))
            file = folder / "database.dump"
            self.run(["exec", "--user", "postgres", self.name(app), "pg_dump", "-U", user, "-d", database, "--format=custom", "--no-owner", "--no-acl"], output_file=file, timeout=600)
            manifest["files"].append({"name": file.name, "sha256": digest_file(file), "kind": "postgres"})
        elif spec["data_kind"] == "volume":
            mounts = [m for m in spec.get("mounts", []) if m["type"] == "volume"]
            check(bool(mounts), "NO_PERSISTENT_VOLUMES")
            for m in mounts:
                self.volumes(spec, create=False)
                check(not self.run(["ps", "-q", "--filter", "volume=" + m["source"]]).strip(), "COLD_BACKUP_REQUIRES_STOPPED_WRITERS")
                file = folder / (m["source"] + ".tar.gz")
                self.helper(["tar", "-czf", "/backup/" + file.name, "-C", "/source", "."],
                            ["type=volume,src=" + m["source"] + ",dst=/source,readonly", "type=bind,src=" + str(folder) + ",dst=/backup"])
                files = self.archive_manifest(file)
                manifest["files"].append({"name": file.name, "sha256": digest_file(file), "kind": "volume", "volume": m["source"], "content": files})
        atomic_json(folder / "manifest.json", manifest)
        self.verify_backup(folder, manifest)
        return {"backup_id": backup_id, "verified": True, "files": [{k: v for k, v in f.items() if k != "content"} for f in manifest["files"]], "configuration_saved": True}

    def verify_backup(self, folder, manifest):
        check(manifest["environment"] == self.environment, "BACKUP_ENVIRONMENT_MISMATCH")
        for item in manifest["files"]:
            path = (folder / item["name"]).resolve()
            check(folder == path.parent and digest_file(path) == item["sha256"], "BACKUP_CHECKSUM_FAILED")

    def restore(self, action, payload):
        app = identifier(payload["app_id"]); backup_id = identifier(payload["backup_id"])
        folder = self.backups / backup_id; manifest = json.loads((folder / "manifest.json").read_text())
        check(manifest["app_id"] == app, "BACKUP_APP_MISMATCH")
        self.verify_backup(folder, manifest)
        check(action == "restore_test" or manifest.get("restore_tested") is True, "RESTORE_TEST_REQUIRED")
        result = {"backup_id": backup_id, "targets": [], "original_data_preserved": True}
        for item in manifest["files"]:
            target = "lm-restore-" + self.current_task[:8] + "-" + str(len(result["targets"]))
            if item["kind"] == "volume":
                self.run(["volume", "create", "--label", "live-monitor.app=" + app, "--label", "live-monitor.environment=" + self.environment, target])
                self.helper(["tar", "-xzf", "/backup/" + item["name"], "-C", "/target"],
                            ["type=volume,src=" + target + ",dst=/target", "type=bind,src=" + str(folder) + ",dst=/backup,readonly"])
                verify_file = "verify-" + self.current_task + ".tar.gz"
                self.helper(["tar", "-czf", "/backup/" + verify_file, "-C", "/target", "."],
                            ["type=volume,src=" + target + ",dst=/target,readonly", "type=bind,src=" + str(folder) + ",dst=/backup"])
                check(self.archive_manifest(folder / verify_file) == item["content"], "RESTORE_CONTENT_MISMATCH")
                # Verification archives are retained; source backup/volumes are never removed.
                result["targets"].append({"volume": target, "source_volume": item["volume"], "verified": True})
            else:
                spec = manifest["spec"]; user = identifier(spec.get("postgres_user", "postgres"))
                container = self.name(app)
                if action == "restore_test":
                    container = target
                    self.run(["run", "-d", "--name", container, "--network", "none", "--user", "postgres", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--memory", "512m", "--label", "live-monitor.restore-test=true", "--env", "POSTGRES_HOST_AUTH_METHOD=trust", spec["image"]])
                    self.validation_containers.append(container)
                    deadline = time.monotonic() + 60
                    while True:
                        try:
                            self.run(["exec", "--user", "postgres", container, "pg_isready", "-U", "postgres"])
                            break
                        except AgentError:
                            check(time.monotonic() < deadline, "POSTGRES_VERIFY_STARTUP")
                            time.sleep(1)
                    user = "postgres"
                self.run(["exec", "--user", "postgres", container, "createdb", "-U", user, target])
                with open(folder / item["name"], "rb") as stream:
                    # pg_restore is the actual restore, not merely a pg_restore --list check.
                    data = stream.read(self.config.get("max_database_restore_bytes", 256 * 1024 * 1024) + 1)
                check(len(data) <= self.config.get("max_database_restore_bytes", 256 * 1024 * 1024), "DATABASE_RESTORE_LIMIT")
                self.run(["exec", "-i", "--user", "postgres", container, "pg_restore", "-U", user, "-d", target, "--exit-on-error", "--no-owner", "--no-acl"], input_data=data, timeout=600)
                self.run(["exec", "--user", "postgres", container, "psql", "-U", user, "-d", target, "-v", "ON_ERROR_STOP=1", "-c", "SELECT count(*) FROM pg_catalog.pg_class"])
                if action == "restore_test":
                    self.run(["stop", container])
                    self.validation_containers.remove(container)
                result["targets"].append({"database": target, "container": container, "verified": True})
        if action == "restore_test":
            manifest["restore_tested"] = True
            atomic_json(folder / "manifest.json", manifest)
        return result

    def execute(self, request):
        check(request.get("protocol") == 1 and request.get("environment") == self.environment, "PROTOCOL_OR_ENVIRONMENT")
        action = request.get("action"); payload = request.get("payload", {}); task = identifier(request["task_id"])
        self.current_task = task
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True).encode()).hexdigest()
        path = self.root / "tasks" / (task + ".json")
        if path.exists():
            previous = json.loads(path.read_text())
            check(previous["request_hash"] == fingerprint, "TASK_ID_REUSED")
            check(previous["status"] == "SUCCEEDED", "RECONCILE_PREVIOUS_TASK")
            return previous["result"]
        atomic_json(path, {"status": "RUNNING", "request_hash": fingerprint, "action": action, "started_at": int(time.time() * 1000)})
        try:
            self.progress(10, "AGENT_ACCEPTED")
            if action == "probe":
                data = self.snapshot()
            elif isinstance(action, str) and action.startswith("stack_"):
                from stack_ops import StackOperations
                data = StackOperations(self).execute(action, payload)
            elif action == "images":
                data = {"images": self.image_catalog()}
            elif action == "import_image":
                data = self.import_archive(payload)
            elif action == "pull":
                data = self.pull(payload["image"], payload.get("credential_ref", ""))
            elif action in ("registry_catalog", "resolve_image"):
                data = self.registry_query(action, payload)
            elif action in ("deploy", "validate", "rollback"):
                payload["validate_only"] = action == "validate"
                data = self.deploy(payload)
            elif action in ("start", "stop", "restart", "logs", "recover", "circuit_break"):
                data = self.operate(action, payload)
            elif action == "backup":
                data = self.backup(payload)
            elif action in ("restore", "restore_test"):
                data = self.restore(action, payload)
            elif action.startswith("maintenance_") and action in ("maintenance_on", "maintenance_off"):
                atomic_json(self.root / "maintenance.json", {"enabled": action == "maintenance_on"})
                data = {"maintenance": action == "maintenance_on"}
            elif action == "cleanup":
                image = self.image(payload["image"])
                # No prune or force: Docker refuses any image referenced by retained containers.
                self.run(["image", "rm", image])
                data = {"removed_image": image}
            else:
                raise AgentError("UNKNOWN_OPERATION")
            result = {"ok": True, "data": data}
            atomic_json(path, {"status": "SUCCEEDED", "request_hash": fingerprint, "result": result, "finished_at": int(time.time() * 1000)})
            return result
        except Exception as error:
            code = str(error) if isinstance(error, AgentError) else "AGENT_EXECUTION_FAILED"
            for container in self.validation_containers:
                try:
                    self.run(["stop", container], timeout=30)
                except AgentError:
                    pass
            atomic_json(path, {"status": "FAILED", "request_hash": fingerprint, "code": code, "finished_at": int(time.time() * 1000)})
            raise AgentError(code)


def main():
    os.umask(0o077)
    try:
        path = Path("/etc/live-monitor-agent.json")
        check(path.stat().st_uid == 0 and path.stat().st_mode & 0o022 == 0, "INSECURE_AGENT_CONFIG")
        config = json.loads(path.read_text())
        # JSON line followed by raw archive bytes for imports; legacy JSON ends at EOF.
        data = sys.stdin.buffer.readline(65537)
        check(len(data) <= 65536, "REQUEST_TOO_LARGE")
        request = json.loads(data)
        selected = request.get("environment")
        if selected != config.get("environment"):
            profiles = config.get("profiles", {})
            check(selected in profiles, "ENVIRONMENT_NOT_APPROVED")
            config = {**config, **profiles[selected], "environment": selected}
        agent = Agent(config)
        import signal
        def deadline(signum, frame):
            raise AgentError("AGENT_DEADLINE")
        signal.signal(signal.SIGALRM, deadline)
        signal.alarm(min(1100, max(1, int(config.get("deadline_seconds", 1100)))))
        with lock(agent.root / "agent.lock"):
            result = agent.execute(request)
        print(json.dumps(result), flush=True)
    except Exception as error:
        code = str(error) if isinstance(error, AgentError) else "AGENT_EXECUTION_FAILED"
        print(json.dumps({"ok": False, "code": code}), flush=True)
        sys.exit(1)


if __name__ == "__main__":
    sys.modules.setdefault("agent", sys.modules[__name__])
    main()
