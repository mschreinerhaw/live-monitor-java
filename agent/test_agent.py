import copy
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import hashlib
import urllib.error
from unittest.mock import patch
import agent


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.secrets = self.root / "secrets"
        self.secrets.mkdir()
        self.a = agent.Agent({"environment": "test", "state_root": str(self.root / "state"),
                              "backup_root": str(self.root / "backup"), "secrets_root": str(self.secrets),
                              "allowed_registries": ["docker.io"], "allowed_bind_roots": []})
        self.spec = {"id": "app", "environment": "test", "image": "docker.io/library/nginx@sha256:" + "a" * 64,
                     "cpus": 1, "memory_mb": 64, "restart_policy": "on-failure", "restart_limit": 3,
                     "data_kind": "stateless", "health": {"command": ["true"]}}

    def tearDown(self):
        self.temp.cleanup()

    def test_valid_spec(self):
        self.assertEqual("app", self.a.validate(self.spec))

    def test_mutable_tag_rejected(self):
        self.spec["image"] = "docker.io/library/nginx:latest"
        with self.assertRaisesRegex(agent.AgentError, "IMMUTABLE"):
            self.a.validate(self.spec)

    def test_unapproved_registry_rejected(self):
        self.spec["image"] = self.spec["image"].replace("docker.io", "evil.test")
        with self.assertRaisesRegex(agent.AgentError, "REGISTRY"):
            self.a.validate(self.spec)

    def test_host_namespace_and_socket_mount_rejected(self):
        self.spec["network"] = "host"
        with self.assertRaisesRegex(agent.AgentError, "NETWORK"):
            self.a.validate(self.spec)
        self.spec.pop("network")
        self.spec["mounts"] = [{"type": "bind", "source": "/var/run/docker.sock", "target": "/var/run/docker.sock", "read_only": True}]
        with self.assertRaises(agent.AgentError):
            self.a.validate(self.spec)

    def test_secret_symlink_escape_rejected(self):
        path = self.root / "outside"
        path.write_text("secret")
        try:
            (self.secrets / "escaped").symlink_to(path)
        except OSError:
            self.skipTest("symlink creation requires OS privilege")
        self.spec["secret_refs"] = {"PASSWORD": "escaped"}
        with self.assertRaisesRegex(agent.AgentError, "SECRET_PATH"):
            self.a.validate(self.spec)

    def test_plain_secret_environment_rejected(self):
        self.spec["environment_variables"] = {"ACCESS_TOKEN": "private"}
        with self.assertRaisesRegex(agent.AgentError, "SECRET_REFERENCE"):
            self.a.validate(self.spec)

    def test_secret_environment_file_removed_by_deployment_finally(self):
        (self.secrets / "database").write_text("private-password")
        self.spec["secret_refs"] = {"DATABASE_PASSWORD": "database"}
        self.a.current_task = "test-task"
        path = self.a.env_file(self.spec)
        self.assertIn("DATABASE_PASSWORD=private-password", path.read_text())
        self.assertNotIn("private-password", json.dumps(self.spec))
        self.assertIn("private-password", self.a.redactions)
        path.unlink()
        self.a.pull = lambda *args: {"image": self.spec["image"]}
        self.a.exists = lambda name: False
        commands = []
        self.a.run = lambda args, **kwargs: commands.append(args) or ""
        def failed_health(*args):
            raise agent.AgentError("HEALTH_CHECK_FAILED")
        self.a.wait_healthy = failed_health
        with self.assertRaisesRegex(agent.AgentError, "HEALTH_CHECK_FAILED"):
            self.a.deploy({"spec": self.spec})
        self.assertFalse(path.exists(), "Failed deployment must remove its temporary secret environment file")
        self.assertNotIn("private-password", json.dumps(commands))

    def test_container_argv_is_not_host_shell(self):
        self.spec["command"] = ["echo", "$(touch /host-owned)"]
        args = self.a.create_args(self.spec, "lm-test-app", self.root / "env")
        self.assertEqual(["echo", "$(touch /host-owned)"], args[-2:])
        self.assertIn("ALL", args)
        self.assertNotIn("--privileged", args)

    def test_manual_stop_written_before_docker_stop(self):
        self.a.inspect = lambda _: {"Config": {"Labels": {"live-monitor.app": "app"}}, "State": {"Status": "exited"}}
        def run(args):
            self.assertTrue(self.a.state("app")["manual_stop"])
            return ""
        self.a.run = run
        self.a.operate("stop", {"app_id": "app"})

    def test_manual_stop_survives_restart_and_blocks_recovery(self):
        self.a.save_state("app", {"manual_stop": True, "spec": self.spec})
        self.a.inspect = lambda _: {"Config": {"Labels": {"live-monitor.app": "app"}}, "State": {"Status": "exited"}}
        with self.assertRaisesRegex(agent.AgentError, "MANUAL_STOP"):
            self.a.operate("recover", {"app_id": "app", "spec": self.spec})

    def test_recovery_limit_checked_before_command(self):
        self.a.save_state("app", {"manual_stop": False, "recovery_attempts": 3, "spec": self.spec})
        self.a.inspect = lambda _: {"Config": {"Labels": {"live-monitor.app": "app"}}, "State": {"Status": "exited"}}
        with self.assertRaisesRegex(agent.AgentError, "CIRCUIT_OPEN"):
            self.a.operate("recover", {"app_id": "app", "spec": self.spec})

    def test_atomic_task_idempotency_and_conflicting_reuse(self):
        request = {"protocol": 1, "environment": "test", "task_id": "unique-task", "action": "probe", "payload": {}}
        self.a.snapshot = lambda: {"containers": []}
        result = self.a.execute(request)
        self.a.snapshot = lambda: self.fail("Successful operation must not run twice")
        self.assertEqual(result, self.a.execute(request))
        request["action"] = "images"
        with self.assertRaisesRegex(agent.AgentError, "TASK_ID_REUSED"):
            self.a.execute(request)

    def test_failed_task_never_blindly_reruns(self):
        request = {"protocol": 1, "environment": "test", "task_id": "failed-task", "action": "unknown", "payload": {}}
        with self.assertRaisesRegex(agent.AgentError, "UNKNOWN"):
            self.a.execute(request)
        with self.assertRaisesRegex(agent.AgentError, "RECONCILE_PREVIOUS"):
            self.a.execute(request)

    def test_backup_checksum_detects_corruption(self):
        folder = self.root / "backup"
        file = folder / "data.dump"
        file.write_bytes(b"valid")
        manifest = {"environment": "test", "files": [{"name": "data.dump", "sha256": agent.digest_file(file)}]}
        self.a.verify_backup(folder, manifest)
        file.write_bytes(b"corrupt")
        with self.assertRaisesRegex(agent.AgentError, "CHECKSUM"):
            self.a.verify_backup(folder, manifest)

    def test_restore_archive_rejects_traversal_and_links(self):
        for name, kind in [("../../outside", tarfile.REGTYPE), ("link", tarfile.SYMTYPE)]:
            path = self.root / "unsafe.tar.gz"
            with tarfile.open(path, "w:gz") as output:
                item = tarfile.TarInfo(name)
                item.type = kind
                output.addfile(item, io.BytesIO())
            with self.assertRaisesRegex(agent.AgentError, "UNSAFE_ARCHIVE"):
                self.a.archive_manifest(path)

    def test_cross_environment_request_fails_before_execution(self):
        with self.assertRaisesRegex(agent.AgentError, "ENVIRONMENT"):
            self.a.execute({"protocol": 1, "environment": "prod", "task_id": "id", "action": "probe"})

    def test_maintenance_marker_is_persistent(self):
        self.a.execute({"protocol": 1, "environment": "test", "task_id": "maintain", "action": "maintenance_on", "payload": {}})
        self.assertTrue(self.a.maintenance())
    def test_docker_diagnostics_expose_categories_not_raw_credentials(self):
        self.assertEqual("REGISTRY_AUTH_FAILED", agent.docker_error_code("unauthorized password=do-not-log"))
        self.assertEqual("PORT_CONFLICT", agent.docker_error_code("address already in use"))
        self.assertEqual("HOST_DISK_FULL", agent.docker_error_code("no space left on device"))
    def test_registry_manifest_resolution_verifies_content_digest(self):
        body = b'{"schemaVersion":2}'
        digest = "sha256:" + hashlib.sha256(body).hexdigest()
        class Response(io.BytesIO):
            headers = {"Docker-Content-Digest": digest}
        with patch.object(agent.urllib.request, "build_opener") as build:
            build.return_value.open.return_value = Response(body)
            result = self.a.registry_query("resolve_image", {"registry": "docker.io", "repository": "library/example", "tag": "version"})
            self.assertEqual("docker.io/library/example@" + digest, result["image"])

    def test_registry_cannot_redirect_credentials_to_another_auth_host(self):
        with patch.object(agent.urllib.request, "build_opener") as build:
            build.return_value.open.side_effect = urllib.error.HTTPError("https://registry", 401, "Unauthorized", {"WWW-Authenticate": 'Bearer realm="https://evil.test/token",service="test"'}, None)
            with self.assertRaisesRegex(agent.AgentError, "AUTH_HOST_NOT_ALLOWED"):
                self.a.registry_query("registry_catalog", {"registry": "docker.io", "repository": "library/example"})
            self.assertEqual(1, build.return_value.open.call_count)

    def test_failed_restore_stops_verification_container_without_removing_data(self):
        stopped = []
        def restore(action, payload):
            self.a.validation_containers.append("isolated-verifier")
            raise agent.AgentError("RESTORE_CONTENT_MISMATCH")
        self.a.restore = restore
        self.a.run = lambda args, **kw: stopped.append(args)
        with self.assertRaisesRegex(agent.AgentError, "RESTORE_CONTENT_MISMATCH"):
            self.a.execute({"protocol": 1, "environment": "test", "task_id": "restore-failed", "action": "restore_test", "payload": {}})
        self.assertEqual([["stop", "isolated-verifier"]], stopped)
    def test_exit_recovery_does_not_report_stale_healthy_check_as_its_cause(self):
        self.spec["restart_policy"] = "no"
        self.a.save_state("app", {"spec": self.spec, "manual_stop": False, "failure_since": 1})
        self.a.inspect = lambda name: {"Config": {"Labels": {"live-monitor.app": "app"}}, "State": {"Running": False, "Status": "exited", "ExitCode": 137, "Health": {"Status": "healthy"}}, "HostConfig": {"RestartPolicy": {"Name": "no"}}}
        self.a.run = lambda args: ""
        self.assertEqual("exited", self.a.operate("recover", {"app_id": "app", "spec": self.spec})["reason"])
    def test_explicit_start_resets_fault_debounce_window(self):
        self.a.save_state("app", {"spec": self.spec, "manual_stop": True, "failure_since": 1})
        self.a.inspect = lambda name: {"Config": {"Labels": {"live-monitor.app": "app"}}, "State": {"Status": "running"}}
        self.a.run = lambda args: ""
        self.a.operate("start", {"app_id": "app"})
        self.assertEqual(0, self.a.state("app")["failure_since"])


if __name__ == "__main__":
    unittest.main()
