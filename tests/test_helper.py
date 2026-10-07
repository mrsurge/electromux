import os
import json
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import threading

from electromux.protocol import read_frame, write_frame
from electromux.protocol import ProtocolError
from electromux.helper import DeadlinePipe, declared_backend, Session, ConnectionWriter


EVENT_BACKEND = """
import sys, time
from electromux.protocol import read_frame, write_frame
write_frame(sys.stdout.buffer, {"version": 1, "event": "ready"})
while (request := read_frame(sys.stdin.buffer)) is not None:
    write_frame(sys.stdout.buffer, {"event": "state", "data": "before"})
    write_frame(sys.stdout.buffer, {"id": request["id"], "result": "reply"})
    time.sleep(.02)
    write_frame(sys.stdout.buffer, {"event": "state", "data": "after"})
"""


class EventTests(unittest.TestCase):
    def test_interleaved_events_and_correlated_replies(self) -> None:
        session = Session([sys.executable, "-c", EVENT_BACKEND])
        events = []
        after = threading.Event()
        def observe(frame):
            events.append(frame)
            if frame["data"] == "after":
                after.set()
        session.events = observe
        try:
            session.start()
            self.assertEqual(session.request({"id": 1})["result"], "reply")
            self.assertTrue(after.wait(2))
            self.assertEqual([x["data"] for x in events], ["before", "after"])
            session.events = None  # Detach does not retain an event history.
            self.assertEqual(session.request({"id": 2})["result"], "reply")
            time.sleep(.05)
            self.assertEqual(len(events), 2)
            session.events = observe
            self.assertEqual(session.request({"id": 3})["id"], 3)
        finally:
            session.stop()
        self.assertIsNone(session._reader)

    def test_reply_then_immediate_eof_keeps_mutation_result(self) -> None:
        code = EVENT_BACKEND.replace('    time.sleep(.02)', '    sys.exit(0)')
        session = Session([sys.executable, "-c", code])
        try:
            session.start()
            self.assertEqual(session.request({"id": 4})["result"], "reply")
        finally:
            session.stop()

    def test_event_with_request_id_is_protocol_failure(self) -> None:
        code = EVENT_BACKEND.replace('{"event": "state", "data": "before"}',
                                    '{"event": "state", "id": 1}')
        session = Session([sys.executable, "-c", code])
        try:
            session.start()
            with self.assertRaisesRegex(ProtocolError, "invalid backend event"):
                session.request({"id": 1})
            self.assertEqual(session.status()["state"], "stopped")
        finally:
            session.stop()

    def test_slow_client_has_bounded_queue_and_disconnects(self) -> None:
        left, right = socket.socketpair()
        left.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        with left, right, left.makefile("wb", buffering=0) as stream:
            writer = ConnectionWriter(left, stream)
            try:
                for _ in range(100):
                    writer.event({"event": "state", "data": "x" * 60000})
                self.assertTrue(writer.closed.wait(2))
                self.assertLessEqual(writer.frames.qsize(), 16)
            finally:
                writer.close()
            self.assertFalse(writer.thread.is_alive())


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

    def connect(self, token: str | None = None, *, events: bool = False):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.settimeout(3)
        client.connect(str(self.path))
        stream = client.makefile("rwb", buffering=0)
        write_frame(stream, {"id": 1, "method": "hello", "version": 1,
                             "session": "sample", "token": token or self.token, "events": events})
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

    def test_sample_unsolicited_event_is_a_separate_authenticated_frame(self) -> None:
        client, stream, _ = self.connect(events=True)
        with client, stream:
            self.call(stream, "start")
            write_frame(stream, {"id": 2, "method": "request",
                                 "payload": {"id": 10, "method": "ping", "value": "events"}})
            frames = [read_frame(stream), read_frame(stream)]
            reply = next(frame for frame in frames if frame.get("id") == 2)
            event = next(frame for frame in frames if frame.get("event") == "sample.state")
            self.assertEqual(reply["result"]["result"]["pong"], "events")
            self.assertEqual(event["data"], {"id": 10, "state": "ready"})
            self.assertNotIn("id", event)

    def test_authenticated_shutdown_reaps_helper_and_backend(self) -> None:
        client, stream, _ = self.connect()
        with client, stream:
            pid = self.call(stream, "start")["result"]["pid"]
            self.assertEqual(self.call(stream, "shutdown")["result"]["state"], "stopped")
        self.assertEqual(self.process.wait(timeout=5), 0)
        self.assertFalse(self.path.exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_pipe_deadline_does_not_wait_forever(self) -> None:
        reader, writer = os.pipe()
        try:
            with self.assertRaises(ProtocolError):
                DeadlinePipe(reader, 0.02).read(1)
        finally:
            os.close(reader)
            os.close(writer)

    def test_helper_exit_reaps_owned_child(self) -> None:
        client, stream, _ = self.connect()
        with client, stream:
            pid = self.call(stream, "start")["result"]["pid"]
        self.process.terminate()
        self.assertEqual(self.process.wait(timeout=5), 0)
        self.assertFalse(self.path.exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_native_declared_backend_and_validation(self) -> None:
        path = Path(self.temp.name) / "backend.json"
        path.write_text(json.dumps({"argv": [sys.executable, "-m", "electromux.sample_backend"],
                                   "cwd": str(Path.cwd()), "env": {"SAMPLE": "declared"},
                                   "stopTimeout": 20}))
        path.chmod(0o600)
        backend = declared_backend(path)
        try:
            self.assertEqual(backend.start()["state"], "ready")
            self.assertEqual(backend.env["SAMPLE"], "declared")
            self.assertEqual(backend.stop_timeout, 20)
        finally:
            backend.stop()
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            declared_backend(path)
        path.chmod(0o600)
        path.write_text(json.dumps({"argv": ["relative"], "cwd": str(Path.cwd())}))
        with self.assertRaises(ValueError):
            declared_backend(path)

    def test_authenticated_event_stream_and_request_only_reconnect(self) -> None:
        self.process.terminate()
        self.process.wait(timeout=5)
        self.process.stderr.close()
        root = Path(self.temp.name)
        declaration = root / "events.json"
        declaration.write_text(json.dumps({"argv": [sys.executable, "-c", EVENT_BACKEND],
                                           "cwd": str(Path.cwd())}))
        declaration.chmod(0o600)
        self.process = subprocess.Popen([
            sys.executable, "-m", "electromux.helper", "--socket", str(self.path),
            "--session", "sample", "--token-file", str(root / "token"),
            "--backend-config", str(declaration)], stderr=subprocess.PIPE)
        for _ in range(100):
            if self.path.exists():
                break
            time.sleep(.01)
        client, stream, _ = self.connect(events=True)
        with client, stream:
            pid = self.call(stream, "start")["result"]["pid"]
            write_frame(stream, {"id": 9, "method": "request", "payload": {"id": 42}})
            frames = [read_frame(stream) for _ in range(3)]
            self.assertEqual([x["data"] for x in frames if "event" in x], ["before", "after"])
            reply = next(x for x in frames if x.get("id") == 9)
            self.assertEqual(reply["result"]["id"], 42)
            self.call(stream, "detach")
        client, stream, _ = self.connect()  # Events require an authenticated opt-in.
        with client, stream:
            self.assertEqual(self.call(stream, "status")["result"]["pid"], pid)
            self.assertEqual(self.call(stream, "request", payload={"id": 43})["result"]["result"], "reply")
            time.sleep(.05)
            self.assertEqual(self.call(stream, "status")["result"]["pid"], pid)
