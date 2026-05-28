from __future__ import annotations

import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, unquote_to_bytes, urlparse


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend")))

from app.utils.alerting import _md5_upper, _send_sms, _split_sms_mobiles


class SmsGatewayHandler(BaseHTTPRequestHandler):
    request_path = ""

    def do_GET(self):
        SmsGatewayHandler.request_path = self.path
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"0_1:13800000000")

    def log_message(self, format, *args):
        return


class SmsAlertingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), SmsGatewayHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}/smsSendServlet.htm"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=2)
        cls.server.server_close()

    def test_sms_gateway_uses_sms_task_request_shape(self):
        status = _send_sms(
            {
                "sms_api_url": self.base_url,
                "sms_username": "xxzx",
                "sms_password_is_md5": True,
                "sms_password_md5": "751CB3F4AA17C36186F4856C8982BF27",
                "sms_rstype": "text",
                "sms_ext_code": "99",
            },
            "13800000000",
            "服务异常",
        )

        parsed = urlparse(SmsGatewayHandler.request_path)
        params = parse_qs(parsed.query)
        raw_pairs = dict(part.split("=", 1) for part in parsed.query.split("&"))

        self.assertEqual(status, "success")
        self.assertEqual(parsed.path, "/smsSendServlet.htm")
        self.assertEqual(params["command"], ["sendMD5"])
        self.assertEqual(params["username"], ["xxzx"])
        self.assertEqual(params["pwd"], ["751CB3F4AA17C36186F4856C8982BF27"])
        self.assertEqual(params["mobiles"], ["13800000000"])
        self.assertEqual(params["rstype"], ["text"])
        self.assertEqual(params["extCode"], ["99"])
        self.assertEqual(unquote_to_bytes(raw_pairs["content"]).decode("gbk"), "服务异常")

    def test_sms_gateway_supports_multiple_mobiles(self):
        status = _send_sms(
            {
                "sms_api_url": self.base_url,
                "sms_username": "xxzx",
                "sms_password_is_md5": True,
                "sms_password_md5": "751CB3F4AA17C36186F4856C8982BF27",
                "sms_rstype": "text",
            },
            "13800000000, 13900000000；13800000000\n13700000000",
            "alert",
        )

        parsed = urlparse(SmsGatewayHandler.request_path)
        params = parse_qs(parsed.query)

        self.assertEqual(status, "success")
        self.assertEqual(params["mobiles"], ["13800000000,13900000000,13700000000"])

    def test_split_sms_mobiles_accepts_common_separators_and_dedupes(self):
        self.assertEqual(
            _split_sms_mobiles("13800000000,13900000000；13800000000\n13700000000"),
            ["13800000000", "13900000000", "13700000000"],
        )

    def test_plain_sms_password_is_md5_encoded_for_gateway(self):
        self.assertEqual(_md5_upper("secret"), "5EBE2294ECD0E0F08EAB7690D2A6EE69")


if __name__ == "__main__":
    unittest.main()
