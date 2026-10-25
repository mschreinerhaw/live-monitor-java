import gzip
import hashlib
import io
import json
import tarfile
from pathlib import Path
from unittest.mock import patch
import unittest
import test_agent as fixtures
import agent


class ImageImportTests(unittest.TestCase):
    def setUp(self):
        fixtures.AgentTests.setUp(self)
        self.a.config["allow_image_import"] = True
        self.config_bytes = json.dumps({"config": {"ExposedPorts": {"8080/tcp": {}}, "Volumes": {"/data": {}}}}).encode()
        self.image = "sha256:" + hashlib.sha256(self.config_bytes).hexdigest()

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, extra=None, compressed=False):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as saved:
            files = {"manifest.json": json.dumps([{"Config": "config.json", "RepoTags": ["demo:1"], "Layers": []}]).encode(), "config.json": self.config_bytes}
            for name, data in files.items():
                member = tarfile.TarInfo(name); member.size = len(data)
                saved.addfile(member, io.BytesIO(data))
            if extra:
                saved.addfile(extra, io.BytesIO(b""))
        return gzip.compress(output.getvalue()) if compressed else output.getvalue()

    def import_data(self, data, checksum=None):
        def docker(args, **kwargs):
            if args[:2] == ["image", "inspect"]:
                return json.dumps([{"Id": self.image}])
            return ""
        with patch.object(agent.sys, "stdin", type("Input", (), {"buffer": io.BytesIO(data)})()), patch.object(self.a, "run", side_effect=docker) as run:
            result = self.a.import_archive({"archive_bytes": len(data), "archive_sha256": checksum or hashlib.sha256(data).hexdigest()})
        return result, run

    def test_tar_and_gzip_import_pin_content_identity(self):
        for compressed in [False, True]:
            result, run = self.import_data(self.archive(compressed=compressed))
            self.assertEqual(self.image, result["images"][0]["image"])
            self.assertEqual(["8080/tcp"], result["images"][0]["ports"])
            self.assertEqual(self.image, self.a.image(self.image))
            self.assertTrue(any(call.args[0][:2] == ["image", "load"] for call in run.call_args_list))
            self.assertFalse(list(self.a.root.glob("image-import-*")))

    def test_checksum_mismatch_rejected_before_docker(self):
        with self.assertRaisesRegex(agent.AgentError, "CHECKSUM_MISMATCH"):
            self.import_data(self.archive(), "0" * 64)

    def test_path_escape_and_symlink_rejected(self):
        for member in [tarfile.TarInfo("../outside"), tarfile.TarInfo("/etc/passwd")]:
            with self.assertRaisesRegex(agent.AgentError, "UNSAFE_ARCHIVE"):
                self.import_data(self.archive(extra=member))
        link = tarfile.TarInfo("link"); link.type = tarfile.SYMTYPE; link.linkname = "/etc"
        with self.assertRaisesRegex(agent.AgentError, "UNSAFE_ARCHIVE"):
            self.import_data(self.archive(extra=link))

    def test_unpacked_limit_and_truncated_upload(self):
        self.a.config["max_image_unpacked_bytes"] = 1
        with self.assertRaisesRegex(agent.AgentError, "UNPACKED_LIMIT"):
            self.import_data(self.archive(compressed=True))
        self.a.config.pop("max_image_unpacked_bytes")
        with patch.object(agent.sys, "stdin", type("Input", (), {"buffer": io.BytesIO(b"")})()):
            with self.assertRaisesRegex(agent.AgentError, "INCOMPLETE_ARCHIVE"):
                self.a.import_archive({"archive_bytes": 100, "archive_sha256": "0" * 64})

    def test_import_requires_host_approval_and_environment_provenance(self):
        self.a.config["allow_image_import"] = False
        with self.assertRaisesRegex(agent.AgentError, "NOT_APPROVED"):
            self.import_data(self.archive())
        with self.assertRaisesRegex(agent.AgentError, "NOT_IMPORTED_IN_ENVIRONMENT"):
            self.a.pull(self.image)

    def test_local_deployment_never_pulls_registry(self):
        self.import_data(self.archive())
        with patch.object(self.a, "run", return_value=json.dumps([{"Id": self.image}])) as run:
            self.assertEqual(self.image, self.a.pull(self.image)["image_id"])
            self.assertEqual(["image", "inspect", self.image], run.call_args.args[0])

    def test_process_monitor_is_explicit_and_no_fake_health_command(self):
        self.spec["health"] = {"mode": "running", "command": []}
        self.assertEqual("app", self.a.validate(self.spec))
        args = self.a.create_args(self.spec, "app", Path("env"))
        self.assertIn("--no-healthcheck", args)
        self.assertNotIn("--health-cmd", args)

    def test_single_container_monitoring_includes_its_own_resources_and_identity(self):
        name = self.a.name("app")
        def docker(args, **kwargs):
            if args[0] == "info": return "{}"
            if args[0] == "stats": return json.dumps({"Name": name, "CPUPerc": "1%", "MemPerc": "2%", "MemUsage": "3MiB / 64MiB"})
            return name
        inspected = {"Id": "container-identity", "Config": {"Image": self.spec["image"], "Labels": {"live-monitor.app": "app"}}, "State": {"Status": "running", "Running": True, "Health": {"Status": "healthy"}}}
        with patch.object(self.a, "run", side_effect=docker), patch.object(self.a, "inspect", return_value=inspected):
            row = self.a.snapshot()["containers"][0]
            self.assertEqual("container-identity", row["container_id"])
            self.assertEqual("1%", row["resources"]["CPUPerc"])
            self.assertEqual("3MiB / 64MiB", row["resources"]["MemUsage"])


if __name__ == "__main__":
    import unittest
    unittest.main()
