from __future__ import annotations

import argparse
import hmac
import json
import os
from pathlib import Path
import signal
import selectors
import socket
import subprocess
import sys
import time
import threading
import queue
import errno
import fcntl
import stat
import uuid
from contextlib import contextmanager
from collections.abc import Callable

from .protocol import ProtocolError, read_frame, write_frame


class DeadlinePipe:
    """Bounded blocking pipe operations; one deadline covers a complete frame."""
    def __init__(self, fd: int, timeout: float = 5.0) -> None:
        self.fd = fd
        self.deadline = time.monotonic() + timeout

    def _wait(self, event: int) -> None:
        with selectors.DefaultSelector() as selector:
            selector.register(self.fd, event)
            if not selector.select(max(0, self.deadline - time.monotonic())):
                raise ProtocolError("backend I/O deadline exceeded")

    def read(self, size: int) -> bytes:
        self._wait(selectors.EVENT_READ)
        return os.read(self.fd, size)

    def write(self, data: bytes) -> None:
        offset = 0
        while offset < len(data):
            self._wait(selectors.EVENT_WRITE)
            offset += os.write(self.fd, data[offset:offset + 4096])

    def flush(self) -> None:
        pass


class Session:
    def __init__(self, argv: list[str], *, cwd: str | None = None,
                 env: dict[str, str] | None = None, stop_timeout: float = 2.0) -> None:
        self.argv = argv
        self.cwd = cwd
        self.env = env
        self.stop_timeout = stop_timeout
        self.child: subprocess.Popen[bytes] | None = None
        self.events: Callable[[dict[str, object]], None] | None = None
        self._condition = threading.Condition()
        self._request_lock = threading.Lock()
        self._reader: threading.Thread | None = None
        self._expected: int | None = None
        self._response: dict[str, object] | None = None
        self._error: str | None = None

    def _read_output(self, child: subprocess.Popen[bytes]) -> None:
        assert child.stdout is not None
        try:
            while True:
                # Idle is unbounded; once a frame starts, completion is bounded.
                first = os.read(child.stdout.fileno(), 1)
                if not first:
                    raise ProtocolError("backend output closed")
                pipe = DeadlinePipe(child.stdout.fileno())
                class StartedFrame:
                    def read(self, size: int) -> bytes:
                        nonlocal first
                        if first:
                            value, first = first, b""
                            return value
                        return pipe.read(size)
                frame = read_frame(StartedFrame())
                assert frame is not None
                if "event" in frame:
                    event = frame.get("event")
                    if ("id" in frame or not isinstance(event, str)
                            or not event or len(event) > 128):
                        raise ProtocolError("invalid backend event")
                    observer = self.events
                    if observer is not None:
                        observer(frame)
                else:
                    with self._condition:
                        if (type(frame.get("id")) is not int
                                or frame.get("id") != self._expected
                                or self._response is not None):
                            raise ProtocolError("backend correlation failure")
                        self._response = frame
                        self._condition.notify_all()
        except (ProtocolError, OSError) as exc:
            with self._condition:
                self._error = str(exc)
                self._condition.notify_all()

    def status(self) -> dict[str, object]:
        live = self.child is not None and self.child.poll() is None
        return {"state": "ready" if live else "stopped",
                "pid": self.child.pid if live and self.child else None,
                "helperInstanceId": HELPER_INSTANCE_ID}

    def start(self) -> dict[str, object]:
        if self.status()["state"] == "ready":
            return self.status()
        self.stop()
        self.child = subprocess.Popen(self.argv, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, start_new_session=True,
                                      cwd=self.cwd, env=self.env, bufsize=0)
        assert self.child.stdout is not None
        try:
            if read_frame(DeadlinePipe(self.child.stdout.fileno())) != {"version": 1, "event": "ready"}:
                raise ProtocolError("backend did not report ready")
        except (ProtocolError, OSError):
            self.stop()
            raise
        self._error = None
        self._reader = threading.Thread(target=self._read_output, args=(self.child,), daemon=True)
        self._reader.start()
        return self.status()

    def request(self, payload: object) -> dict[str, object]:
        if not isinstance(payload, dict) or type(payload.get("id")) is not int:
            raise ProtocolError("payload requires an integer id")
        if self.status()["state"] != "ready" or self.child is None:
            raise ProtocolError("backend is stopped")
        assert self.child.stdin is not None and self.child.stdout is not None
        with self._request_lock:
            try:
                with self._condition:
                    self._expected = payload["id"]
                    self._response = None
                write_frame(DeadlinePipe(self.child.stdin.fileno()), payload)
                with self._condition:
                    if not self._condition.wait_for(
                        lambda: self._response is not None or self._error is not None, timeout=5,
                    ):
                        raise ProtocolError("backend response deadline exceeded")
                    if self._response is None and self._error is not None:
                        raise ProtocolError(self._error)
                    assert self._response is not None
                    response = self._response
                    self._expected = None
                    self._response = None
                return response
            except (ProtocolError, OSError):
                self.stop()
                raise

    def stop(self) -> dict[str, object]:
        child, self.child = self.child, None
        if child is not None:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=self.stop_timeout)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=2)
            if self._reader:
                self._reader.join(timeout=6)
                self._reader = None
            if child.stdin:
                child.stdin.close()
            if child.stdout:
                child.stdout.close()
        return self.status()


class ConnectionWriter:
    """One bounded writer for replies/events; a slow client is disconnected."""
    def __init__(self, connection: socket.socket, stream: object) -> None:
        self.connection = connection
        self.stream = stream
        self.frames: queue.Queue[dict[str, object] | None] = queue.Queue(maxsize=16)
        self.closed = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _disconnect(self) -> None:
        self.closed.set()
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def send(self, frame: dict[str, object]) -> None:
        if self.closed.is_set():
            raise ProtocolError("client writer closed")
        try:
            self.frames.put_nowait(frame)
        except queue.Full:
            self._disconnect()
            raise ProtocolError("client output queue exceeded")

    def event(self, frame: dict[str, object]) -> None:
        try:
            self.send(frame)
        except ProtocolError:
            pass  # Disconnect the slow client, not the owned backend.

    def _run(self) -> None:
        try:
            while (frame := self.frames.get()) is not None:
                write_frame(self.stream, frame)
        except (ProtocolError, OSError):
            self._disconnect()

    def close(self) -> None:
        try:
            self.frames.put_nowait(None)
        except queue.Full:
            self._disconnect()
        self.thread.join(timeout=1)
        if self.thread.is_alive():
            self._disconnect()
            self.thread.join(timeout=1)
        self.closed.set()


def declared_backend(path: Path) -> Session:
    """Host-owned private declaration, never a command accepted from a page."""
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise ValueError("backend declaration must be a private regular file")
    if path.stat().st_size > 65536:
        raise ValueError("backend declaration exceeds limit")
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or set(value) - {"argv", "cwd", "env", "stopTimeout"}:
        raise ValueError("invalid backend declaration")
    argv, cwd, overrides = value.get("argv"), value.get("cwd"), value.get("env", {})
    if (not isinstance(argv, list) or not 1 <= len(argv) <= 128
            or any(not isinstance(item, str) or "\0" in item or len(item) > 16384 for item in argv)
            or not Path(argv[0]).is_absolute()):
        raise ValueError("backend argv requires an absolute executable")
    if not isinstance(cwd, str) or not Path(cwd).is_absolute() or not Path(cwd).is_dir():
        raise ValueError("backend cwd requires an existing absolute directory")
    if (not isinstance(overrides, dict) or len(overrides) > 64
            or any(not isinstance(k, str) or not k or "=" in k or "\0" in k
                   or not isinstance(v, str) or "\0" in v or len(v) > 16384
                   for k, v in overrides.items())):
        raise ValueError("invalid backend environment")
    timeout = value.get("stopTimeout", 2)
    if type(timeout) not in (int, float) or not 0 < timeout <= 30:
        raise ValueError("invalid backend stop deadline")
    return Session(argv, cwd=cwd, env={**os.environ, **overrides}, stop_timeout=timeout)


HELPER_INSTANCE_ID = uuid.uuid4().hex


@contextmanager
def endpoint_lease(path: Path, recover_stale: bool = False):
    """Never replace a live endpoint; serialize helper ownership through process death."""
    if not path.parent.is_dir() or path.parent.stat().st_mode & 0o077:
        raise RuntimeError("endpoint requires an existing private 0700 directory")
    fd = os.open(str(path) + ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if path.exists() or path.is_symlink():
            if not recover_stale or not stat.S_ISSOCK(path.lstat().st_mode):
                raise RuntimeError("endpoint already exists; refusing replacement")
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.settimeout(1)
                result = probe.connect_ex(str(path))
            if result not in (errno.ECONNREFUSED, errno.ENOENT):
                raise RuntimeError("endpoint may be live; refusing replacement")
            path.unlink(missing_ok=True)
        yield
    finally:
        os.close(fd)


def serve(path: Path, session_id: str, token: str, backend: Path | None = None,
          recover_stale: bool = False) -> None:
    with endpoint_lease(path, recover_stale):
        _serve_locked(path, session_id, token, backend)


def _serve_locked(path: Path, session_id: str, token: str, backend: Path | None = None) -> None:
    if path.exists() or path.is_symlink():
        raise RuntimeError("endpoint already exists; refusing replacement")
    if not path.parent.is_dir() or path.parent.stat().st_mode & 0o077:
        raise RuntimeError("endpoint requires an existing private 0700 directory")
    session = declared_backend(backend) if backend else Session([sys.executable, "-m", "electromux.sample_backend"])
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    bound = False
    def interrupted(_signum: int, _frame: object) -> None:
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        listener.bind(str(path))
        bound = True
        os.chmod(path, 0o600)
        listener.listen(1)
        while True:
            connection, _ = listener.accept()
            connection.settimeout(30)
            with connection, connection.makefile("rwb", buffering=0) as stream:
                writer = None
                try:
                    hello = read_frame(stream)
                    if hello is None:
                        continue
                    supplied = hello.get("token")
                    if (hello.get("method") != "hello" or hello.get("version") != 1
                            or hello.get("session") != session_id
                            or not isinstance(supplied, str)
                            or type(hello.get("id")) is not int
                            or not hmac.compare_digest(supplied.encode(), token.encode())):
                        write_frame(stream, {"id": hello.get("id"), "error": "invalid handshake"})
                        continue
                    write_frame(stream, {"id": hello.get("id"), "result": {"version": 1}})
                    writer = ConnectionWriter(connection, stream)
                    if hello.get("events") is True:
                        session.events = writer.event
                    while (request := read_frame(stream)) is not None:
                        identifier = request.get("id")
                        if type(identifier) is not int:
                            raise ProtocolError("request id must be an integer")
                        method = request.get("method")
                        try:
                            if method == "status":
                                result = session.status()
                            elif method == "start":
                                result = session.start()
                            elif method == "stop":
                                result = session.stop()
                            elif method == "request":
                                result = session.request(request.get("payload"))
                            elif method == "detach":
                                writer.send({"id": identifier, "result": {"detached": True}})
                                break
                            elif method == "shutdown":
                                writer.send({"id": identifier, "result": session.stop()})
                                return
                            else:
                                raise ProtocolError("unknown method")
                            writer.send({"id": identifier, "result": result})
                        except (ProtocolError, BrokenPipeError) as exc:
                            writer.send({"id": identifier, "error": str(exc)})
                except (ProtocolError, OSError):
                    pass
                finally:
                    session.events = None
                    if writer is not None:
                        writer.close()
    except KeyboardInterrupt:
        pass
    finally:
        session.stop()
        listener.close()
        if bound:
            path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", type=Path, required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--backend-config", type=Path)
    parser.add_argument("--recover-stale", action="store_true")
    args = parser.parse_args()
    info = args.token_file.stat()
    if info.st_mode & 0o077:
        parser.error("token file must be private")
    token = args.token_file.read_text().strip()
    if len(token) < 32:
        parser.error("token must contain at least 32 characters")
    serve(args.socket, args.session, token, args.backend_config, args.recover_stale)


if __name__ == "__main__":
    main()
