from __future__ import annotations

import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app.services.web_monitor import check_web


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/ok":
            self.send_response(200)
        elif self.path == "/created":
            self.send_response(201)
        else:
            self.send_response(500)
        self.end_headers()
        self.wfile.write(b"done")

    def do_HEAD(self):
        self.send_response(204)
        self.end_headers()

    def log_message(self, format, *args):
        return


class WebMonitorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def test_200_is_up(self):
        result = check_web(f"{self.base_url}/ok")
        self.assertEqual(result["status"], "UP")

    def test_500_is_down(self):
        result = check_web(f"{self.base_url}/fail")
        self.assertEqual(result["status"], "DOWN")

    def test_expected_status_code_is_respected(self):
        result = check_web(f"{self.base_url}/created", expected_status_code=201)
        self.assertEqual(result["status"], "UP")

    def test_keyword_must_match(self):
        result = check_web(f"{self.base_url}/ok", response_keyword="missing")
        self.assertEqual(result["status"], "DOWN")

    def test_head_method(self):
        result = check_web(f"{self.base_url}/ok", method="HEAD")
        self.assertEqual(result["status"], "UP")


if __name__ == "__main__":
    unittest.main()
