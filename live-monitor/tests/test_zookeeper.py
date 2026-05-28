from __future__ import annotations

import os
import socket
import sys
import threading
import unittest


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app.services.zookeeper_monitor import check_zookeeper


class ZooKeeperMonitorTest(unittest.TestCase):
    def test_port_mode_is_up_when_tcp_connects(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]

            def serve_once():
                conn, _ = server.accept()
                conn.close()

            thread = threading.Thread(target=serve_once, daemon=True)
            thread.start()
            result = check_zookeeper("127.0.0.1", port, check_mode="port")
            thread.join(timeout=2)

        self.assertEqual(result["status"], "UP")
        self.assertEqual(result["message"], "port open")

    def test_custom_four_letter_command_accepts_response(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]
            received = []

            def serve_once():
                conn, _ = server.accept()
                with conn:
                    received.append(conn.recv(128))
                    conn.sendall(b"Mode: follower\n")

            thread = threading.Thread(target=serve_once, daemon=True)
            thread.start()
            result = check_zookeeper("127.0.0.1", port, check_command="stat")
            thread.join(timeout=2)

        self.assertEqual(result["status"], "UP")
        self.assertEqual(received[0], b"stat")


if __name__ == "__main__":
    unittest.main()
