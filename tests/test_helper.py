import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from electromux.protocol import read_frame, write_frame


class HelperTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        root.chmod(0o700)
        self.path = root / "host.sock"
        self.token = secrets.token_hex(32)
        token_file = root / "token"
        token_file.write_text(self.token)
        token_file.chmod(0o600)
        self.process = subprocess.Popen([
            sys.executable, "-m", "electromux.helper", "--socket", str(self.path),
            "--session", "sample", "--token-file", str(token_file)],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        for _ in range(100):
            if self.path.exists():
                break
            if self.process.poll() is not None:
                self.fail("helper failed before binding")
            time.sleep(0.01)
        self.assertTrue(self.path.exists())

    def tearDown(self) -> None:
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()
            if self.process.stderr:
                self.process.stderr.close()
            self.temp.cleanup()

    def connect(self, token: str | None = None):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.settimeout(3)
        client.connect(str(self.path))
        stream = client.makefile("rwb", buffering=0)
        write_frame(stream, {"id": 1, "method": "hello", "version": 1,
                             "session": "sample", "token": token or self.token})
        response = read_frame(stream)
        return client, stream, response

    def call(self, stream, method: str, **fields):
        write_frame(stream, {"id": 2, "method": method, **fields})
        result = read_frame(stream)
        self.assertEqual(result["id"], 2)
        return result

    def test_lifecycle_and_reconnect(self) -> None:
        client, stream, hello = self.connect()
        with client, stream:
            self.assertIn("result", hello)
            first = self.call(stream, "start")["result"]
            self.assertEqual(first["state"], "ready")
            self.assertEqual(self.call(stream, "start")["result"]["pid"], first["pid"])
            reply = self.call(stream, "request", payload={"id": 10, "method": "ping", "value": "sample"})
            self.assertEqual(reply["result"]["result"]["pong"], "sample")
            self.assertEqual(reply["result"]["result"]["events"][0]["id"], 10)
            self.call(stream, "detach")
        client, stream, _ = self.connect()
        with client, stream:
            self.assertEqual(self.call(stream, "status")["result"]["pid"], first["pid"])
            self.assertEqual(self.call(stream, "stop")["result"]["state"], "stopped")
            with self.assertRaises(ProcessLookupError):
                os.kill(first["pid"], 0)

    def test_invalid_handshake_and_method(self) -> None:
        client, stream, response = self.connect("wrong")
        with client, stream:
            self.assertEqual(response["error"], "invalid handshake")
        client, stream, _ = self.connect()
        with client, stream:
            self.assertIn("error", self.call(stream, "arbitrary.execute"))
            self.assertIn("error", self.call(stream, "request", payload={"id": 1}))

    def test_socket_permissions(self) -> None:
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_helper_exit_reaps_owned_child(self) -> None:
        client, stream, _ = self.connect()
        with client, stream:
            pid = self.call(stream, "start")["result"]["pid"]
        self.process.terminate()
        self.assertEqual(self.process.wait(timeout=5), 0)
        self.assertFalse(self.path.exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
