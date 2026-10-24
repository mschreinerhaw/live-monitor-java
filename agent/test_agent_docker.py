"""REAL Docker acceptance. Only run against a dedicated local test daemon, never production.

The runner and Docker daemon must share /tmp/live-monitor-ops-acceptance so helper
containers see the same backup files. Artifacts are retained for inspection.
"""
import copy
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid
import agent


@unittest.skipUnless(os.environ.get("LIVE_MONITOR_AGENT_DOCKER_ACCEPTANCE") == "1", "Explicit local Docker acceptance opt-in required")
class DockerAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suffix = uuid.uuid4().hex[:8]
        cls.app_id = "acceptance-" + cls.suffix
        cls.root = Path("/tmp/live-monitor-ops-acceptance") / cls.suffix
        cls.root.mkdir(parents=True, mode=0o700)
        cls.image = os.environ["LIVE_MONITOR_TEST_IMAGE"]
        cls.a = agent.Agent({"environment": "acceptance", "state_root": str(cls.root / "state"),
                             "backup_root": str(cls.root / "backup"), "secrets_root": str(cls.root / "secrets"),
                             "docker_binary": "/usr/bin/docker", "allowed_registries": ["docker.io"],
                             "allowed_bind_roots": [], "backup_helper_image": os.environ["LIVE_MONITOR_TEST_HELPER_IMAGE"]})
        cls.volume = "lm-acceptance-" + cls.suffix
        cls.spec = {"id": cls.app_id, "environment": "acceptance", "image": cls.image,
                    "cpus": 0.5, "memory_mb": 64, "restart_policy": "no", "restart_limit": 2,
                    "command": ["python", "-c", "import os,http.server; p='/tmp/force-unhealthy'; os.path.exists(p) and os.unlink(p); http.server.HTTPServer(('0.0.0.0',8080),http.server.SimpleHTTPRequestHandler).serve_forever()"],
                    "health": {"command": ["python", "-c", "import os,urllib.request; assert not os.path.exists('/tmp/force-unhealthy'); urllib.request.urlopen('http://127.0.0.1:8080/')"], "interval_seconds": 1, "timeout_seconds": 1},
                    "health_deadline_seconds": 15, "data_kind": "volume", "mounts": [{"type": "volume", "source": cls.volume, "target": "/data", "read_only": False}],
                    "failure_window_seconds": 1, "recover_unhealthy": True}
        cls.proofs = {}

    def call(self, action, payload, task=None):
        return self.a.execute({"protocol": 1, "environment": "acceptance", "task_id": task or str(uuid.uuid4()), "action": action, "payload": payload})["data"]

    def test_full_lifecycle_and_real_restore(self):
        started = time.time()
        self.assertTrue(self.call("validate", {"spec": self.spec})["validated"])
        deployed = self.call("deploy", {"spec": self.spec})
        self.assertTrue(deployed["healthy"])
        name = self.a.name(self.app_id)
        self.a.run(["exec", name, "sh", "-c", "printf 'verified-persistent-data' > /data/proof.txt"])
        self.a.helper(["sh", "-c", "printf 'restricted-file' > /data/private.txt; chown 1000:1000 /data/private.txt; chmod 600 /data/private.txt"],
                      ["type=volume,src=" + self.volume + ",dst=/data"])
        before = self.call("probe", {})
        self.assertTrue(before["containers"][0]["healthy"])
        self.call("stop", {"app_id": self.app_id})
        self.assertTrue(self.a.state(self.app_id)["manual_stop"])
        with self.assertRaisesRegex(agent.AgentError, "MANUAL_STOP"):
            self.call("recover", {"app_id": self.app_id, "spec": self.spec})
        backup_id = str(uuid.uuid4())
        backup = self.call("backup", {"app_id": self.app_id, "spec": self.spec, "backup_id": backup_id})
        self.assertTrue(backup["verified"])
        restored = self.call("restore_test", {"app_id": self.app_id, "backup_id": backup_id})
        self.assertTrue(restored["targets"][0]["verified"])
        self.assertNotEqual(self.volume, restored["targets"][0]["volume"])
        recovered = self.call("restore", {"app_id": self.app_id, "backup_id": backup_id})
        self.assertTrue(recovered["original_data_preserved"])
        self.call("start", {"app_id": self.app_id})
        self.a.wait_healthy(name, 15)
        self.assertFalse(self.a.state(self.app_id)["manual_stop"])
        # A failed candidate must restore the original running container, retaining both volumes.
        bad = copy.deepcopy(self.spec)
        bad["health"]["command"] = ["false"]
        bad["health_deadline_seconds"] = 5
        with self.assertRaisesRegex(agent.AgentError, "HEALTH_CHECK_FAILED"):
            self.call("deploy", {"spec": bad, "data_compatible": True})
        self.a.wait_healthy(name, 15)
        self.assertEqual(self.spec["image"], self.a.inspect(name)["Config"]["Image"])
        self.assertEqual("verified-persistent-data", self.a.run(["exec", name, "cat", "/data/proof.txt"]))
        # The process stays running while health checks fail; a restart clears the fault at startup.
        self.a.run(["exec", name, "touch", "/tmp/force-unhealthy"])
        deadline = time.monotonic() + 15
        while self.a.inspect(name)["State"]["Health"]["Status"] != "unhealthy":
            self.assertLess(time.monotonic(), deadline)
            time.sleep(1)
        self.assertTrue(self.a.inspect(name)["State"]["Running"])
        state = self.a.state(self.app_id); state["failure_since"] = int(time.time() * 1000) - 5000; self.a.save_state(self.app_id, state)
        self.call("recover", {"app_id": self.app_id, "spec": self.spec})
        self.a.wait_healthy(name, 15)
        self.call("start", {"app_id": self.app_id})  # Explicit operator action resets the incident budget.
        # Simulate abnormal exit, debounce, and actual bounded recovery through the agent.
        self.a.run(["kill", name])
        state = self.a.state(self.app_id)
        state["failure_since"] = int(time.time() * 1000) - 5000
        self.a.save_state(self.app_id, state)
        restart = self.call("recover", {"app_id": self.app_id, "spec": self.spec})
        self.assertEqual(1, restart["attempt"])
        self.a.wait_healthy(name, 15)
        state = self.a.state(self.app_id)
        state["recovery_attempts"] = self.spec["restart_limit"]
        state["next_recovery_at"] = 0
        self.a.save_state(self.app_id, state)
        self.a.run(["kill", name])
        with self.assertRaisesRegex(agent.AgentError, "CIRCUIT_OPEN"):
            self.call("recover", {"app_id": self.app_id, "spec": self.spec})
        self.call("stop", {"app_id": self.app_id})
        self.proofs.update({"image": self.image, "app_id": self.app_id, "volume": self.volume, "backup_id": backup_id,
                            "deployment": deployed, "backup": backup, "restore_test": restored, "restore": recovered,
                            "abnormal_exit_recovered": True, "failed_upgrade_restored_old_container": True,
                            "manual_stop_respected": True, "retry_limit_enforced": True, "running_but_unhealthy_recovered": True, "seconds": round(time.time() - started, 2)})
        agent.atomic_json(self.root / "acceptance.json", self.proofs)
        print("ACCEPTANCE_EVIDENCE=" + str(self.root / "acceptance.json"), flush=True)

    @unittest.skipUnless(os.environ.get("LIVE_MONITOR_TEST_POSTGRES_IMAGE"), "PostgreSQL digest must be supplied")
    def test_postgres_dump_actual_restore_and_original_preserved(self):
        spec = copy.deepcopy(self.spec)
        spec.update({"id": "pg-" + self.suffix, "image": os.environ["LIVE_MONITOR_TEST_POSTGRES_IMAGE"], "command": [], "user": "postgres",
                     "memory_mb": 256, "data_kind": "postgres", "postgres_database": "postgres", "postgres_user": "postgres",
                     "secret_refs": {"POSTGRES_PASSWORD": "postgres-password"}, "health_deadline_seconds": 60,
                     "health": {"command": ["pg_isready", "-U", "postgres"], "interval_seconds": 1, "timeout_seconds": 1},
                     "mounts": [{"type": "volume", "source": "lm-postgres-" + self.suffix, "target": "/var/lib/postgresql/data", "read_only": False}]})
        self.a.secrets.mkdir(mode=0o700, exist_ok=True)
        (self.a.secrets / "postgres-password").write_text(uuid.uuid4().hex)
        self.call("deploy", {"spec": spec})
        name = self.a.name(spec["id"])
        try:
            self.a.run(["exec", "--user", "postgres", name, "psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "CREATE TABLE acceptance_proof(value text); INSERT INTO acceptance_proof VALUES ('real-restored-value');"])
            backup_id = str(uuid.uuid4())
            backup = self.call("backup", {"app_id": spec["id"], "spec": spec, "backup_id": backup_id})
            tested = self.call("restore_test", {"app_id": spec["id"], "backup_id": backup_id})
            restored = self.call("restore", {"app_id": spec["id"], "backup_id": backup_id})
            target = restored["targets"][0]["database"]
            values = []
            for database in ("postgres", target):
                values.append(self.a.run(["exec", "--user", "postgres", name, "psql", "-U", "postgres", "-d", database, "-tAc", "SELECT value FROM acceptance_proof"]).strip())
            self.assertEqual(["real-restored-value", "real-restored-value"], values)
            self.assertTrue(tested["targets"][0]["verified"])
            agent.atomic_json(self.root / "postgres-acceptance.json", {"backup": backup, "restore_test": tested, "restore": restored, "original_and_restored_values_match": True})
        finally:
            self.call("stop", {"app_id": spec["id"]})


if __name__ == "__main__":
    unittest.main()
