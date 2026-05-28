from __future__ import annotations

import os
import socket
import sys
import threading
import unittest


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app.services.redis_monitor import check_redis


class RedisMonitorTest(unittest.TestCase):
    def test_pong_is_up(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]

            def serve_once():
                conn, _ = server.accept()
                with conn:
                    conn.recv(128)
                    conn.sendall(b"+PONG\r\n")

            thread = threading.Thread(target=serve_once, daemon=True)
            thread.start()
            result = check_redis("127.0.0.1", port)
            thread.join(timeout=2)

        self.assertEqual(result["status"], "UP")

    def test_auth_then_pong_is_up(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]
            received = []

            def serve_once():
                conn, _ = server.accept()
                with conn:
                    received.append(conn.recv(256))
                    conn.sendall(b"+OK\r\n")
                    received.append(conn.recv(256))
                    conn.sendall(b"+PONG\r\n")

            thread = threading.Thread(target=serve_once, daemon=True)
            thread.start()
            result = check_redis("127.0.0.1", port, username="default", password="secret")
            thread.join(timeout=2)

        self.assertEqual(result["status"], "UP")
        self.assertIn(b"AUTH", received[0])
        self.assertIn(b"default", received[0])
        self.assertIn(b"secret", received[0])

    def test_cluster_mode_checks_cluster_state(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]

            def serve_once():
                conn, _ = server.accept()
                with conn:
                    conn.recv(128)
                    conn.sendall(b"+PONG\r\n")
                    conn.recv(256)
                    conn.sendall(b"$16\r\ncluster_state:ok\r\n")

            thread = threading.Thread(target=serve_once, daemon=True)
            thread.start()
            result = check_redis("127.0.0.1", port, cluster_mode=True)
            thread.join(timeout=2)

        self.assertEqual(result["status"], "UP")


if __name__ == "__main__":
    unittest.main()
