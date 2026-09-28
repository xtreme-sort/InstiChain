import unittest
import base64
import tempfile
from pathlib import Path
from unittest.mock import patch

import httpx

from app.config import Settings
from app.preflight import check_drive, check_smtp
from app.bootstrap import main as bootstrap_main
from app.authority import load_signing_key
from cryptography.hazmat.primitives import serialization


class PreflightTests(unittest.TestCase):
    def test_advisor_key_is_encrypted_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "advisor.pem"
            with patch("sys.argv", ["bootstrap", "advisor-keygen", "--output", str(path)]), \
                    patch("app.bootstrap.getpass.getpass", return_value="test-only-passphrase"), \
                    patch("builtins.print") as output:
                bootstrap_main()
                encrypted = path.read_bytes()
                with self.assertRaises(TypeError):
                    serialization.load_pem_private_key(encrypted, password=None)
                key = serialization.load_pem_private_key(encrypted, password=b"test-only-passphrase")
                public_key = base64.b64encode(key.public_key().public_bytes_raw()).decode()
                self.assertIn(public_key, output.call_args.args[0])
                self.assertEqual(path.stat().st_mode & 0o077, 0)
                with self.assertRaises(SystemExit):
                    bootstrap_main()
                self.assertEqual(path.read_bytes(), encrypted)

    def test_admin_key_is_owner_only_and_loadable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "admin.pem"
            with patch("sys.argv", ["bootstrap", "keygen", "--output", str(path)]), patch("builtins.print"):
                bootstrap_main()
            self.assertEqual(len(load_signing_key(path).public_key().public_bytes_raw()), 32)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                load_signing_key(path)

    def test_smtp_without_recipient_never_sends_mail(self):
        with patch("app.preflight.smtp_connection") as connect:
            smtp = connect.return_value.__enter__.return_value
            smtp.noop.return_value = (250, b"ok")
            self.assertIn("No message", check_smtp(Settings(_env_file=None)))
            smtp.send_message.assert_not_called()

    def test_explicit_delivery_check(self):
        with patch("app.preflight.deliver_message") as send:
            self.assertIn("Confirm receipt", check_smtp(Settings(_env_file=None), "person@iitm.ac.in"))
            message = send.call_args.args[0]
            self.assertEqual(message["To"], "person@iitm.ac.in")

    def test_drive_missing_credentials(self):
        with self.assertRaisesRegex(ValueError, "not configured"):
            check_drive(Settings(_env_file=None, drive_test_access_token=None))

    def test_drive_account_and_selected_file(self):
        requests = []
        def handler(request):
            requests.append(request)
            return httpx.Response(200, json={"user": {"permissionId": "test"}} if request.url.path.endswith("about")
                                  else {"id": "file123", "trashed": False, "capabilities": {"canDownload": True}})
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            result = check_drive(Settings(_env_file=None, drive_test_access_token="test-token", drive_test_file_id="file123"), client)
        self.assertIn("metadata access confirmed", result)
        self.assertEqual(len(requests), 2)
        self.assertTrue(all(request.method == "GET" for request in requests))
        self.assertTrue(all(request.headers["Authorization"] == "Bearer test-token" for request in requests))
        self.assertTrue(all("test-token" not in str(request.url) for request in requests))

    def test_drive_provider_errors_do_not_expose_response(self):
        for code in [401, 403, 404]:
            with self.subTest(code=code), httpx.Client(transport=httpx.MockTransport(
                    lambda request: httpx.Response(code, text="sensitive-provider-body"))) as client:
                with self.assertRaises(ValueError) as caught:
                    check_drive(Settings(_env_file=None, drive_test_access_token="secret"), client)
                self.assertIn(str(code), str(caught.exception))
                self.assertNotIn("sensitive", str(caught.exception))
