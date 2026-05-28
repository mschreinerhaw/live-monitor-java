from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app import database


def service_payload(name: str, alert_group_id: int | None = None) -> dict:
    return {
        "service_name": name,
        "service_type": "web",
        "cluster_name": "prod",
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
        "alert_group_id": alert_group_id,
        "enabled": True,
    }


class AlertCenterModelTest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.original_db_path = database.DATABASE_PATH
        database.DATABASE_PATH = Path(self.tmpdir.name) / "live_monitor_test.db"
        database.init_db()

    def tearDown(self):
        database.DATABASE_PATH = self.original_db_path
        self.tmpdir.cleanup()

    def test_service_binds_to_alert_group_with_policies_and_channels(self):
        policies = database.list_alert_policies()
        down_policy = next(policy for policy in policies if policy["trigger_type"] == "consecutive_down")
        channel = database.create_alert_channel(
            {
                "channel_name": "生产邮件",
                "channel_type": "email",
                "alert_email": "ops@example.com",
                "enabled": True,
            }
        )
        group = database.create_alert_group(
            {
                "group_name": "生产环境",
                "description": "核心服务",
                "enabled": True,
                "policy_ids": [down_policy["id"]],
                "channel_ids": [channel["id"]],
            }
        )

        service = database.create_service(service_payload("redis-prod", alert_group_id=group["id"]))
        loaded = database.get_service(service["id"], include_secrets=True)

        self.assertEqual(loaded["alert_group_id"], group["id"])
        self.assertEqual(loaded["alert_group_name"], "生产环境")
        self.assertEqual(loaded["alert_policies"][0]["trigger_type"], "consecutive_down")
        self.assertEqual(loaded["alert_channels"][0]["alert_email"], "ops@example.com")


if __name__ == "__main__":
    unittest.main()
