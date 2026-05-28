from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app import database


def service_payload(name: str) -> dict:
    return {
        "service_name": name,
        "service_type": "web",
        "cluster_name": "default",
        "host": None,
        "port": None,
        "url": f"http://example.com/{name}",
        "http_method": "GET",
        "expected_status_code": 200,
        "response_keyword": None,
        "check_timeout_seconds": 3,
        "redis_username": None,
        "redis_password": None,
        "redis_cluster_mode": False,
        "zookeeper_check_mode": "ruok",
        "zookeeper_check_command": "ruok",
        "zookeeper_expected_nodes": None,
        "check_interval": 60,
        "alert_config_id": None,
        "enabled": True,
    }


class DashboardActivityTest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.original_db_path = database.DATABASE_PATH
        database.DATABASE_PATH = Path(self.tmpdir.name) / "live_monitor_test.db"
        database.init_db()

    def tearDown(self):
        database.DATABASE_PATH = self.original_db_path
        self.tmpdir.cleanup()

    def test_recent_results_return_each_service_once(self):
        alpha = database.create_service(service_payload("alpha"))
        beta = database.create_service(service_payload("beta"))

        database.record_result(alpha["id"], "UP", 20, "first")
        latest_alpha = database.record_result(alpha["id"], "DOWN", 30, "latest")
        latest_beta = database.record_result(beta["id"], "UP", 10, "ok")

        rows = database.list_recent_results(limit=10)

        self.assertEqual({row["service_id"] for row in rows}, {alpha["id"], beta["id"]})
        self.assertIn(latest_alpha["id"], {row["id"] for row in rows})
        self.assertIn(latest_beta["id"], {row["id"] for row in rows})

    def test_recent_alerts_return_each_service_once(self):
        alpha = database.create_service(service_payload("alpha"))
        beta = database.create_service(service_payload("beta"))

        database.create_alert_record(alpha["id"], "DOWN", "old", "sent")
        latest_alpha = database.create_alert_record(alpha["id"], "DOWN", "latest", "sent")
        latest_beta = database.create_alert_record(beta["id"], "DOWN", "beta", "sent")

        rows = database.list_recent_alerts(limit=10)

        self.assertEqual({row["service_id"] for row in rows}, {alpha["id"], beta["id"]})
        self.assertIn(latest_alpha["id"], {row["id"] for row in rows})
        self.assertIn(latest_beta["id"], {row["id"] for row in rows})


if __name__ == "__main__":
    unittest.main()
