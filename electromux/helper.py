from __future__ import annotations

import argparse
import hmac
import os
from pathlib import Path
import signal
import selectors
import socket
import subprocess
import sys
import time

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
    def __init__(self, argv: list[str]) -> None:
        self.argv = argv
        self.child: subprocess.Popen[bytes] | None = None

    def status(self) -> dict[str, object]:
        live = self.child is not None and self.child.poll() is None
        return {"state": "ready" if live else "stopped",
                "pid": self.child.pid if live and self.child else None}

    def start(self) -> dict[str, object]:
        if self.status()["state"] == "ready":
            return self.status()
        self.stop()
        self.child = subprocess.Popen(self.argv, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, start_new_session=True)
        assert self.child.stdout is not None
        try:
            if read_frame(DeadlinePipe(self.child.stdout.fileno())) != {"version": 1, "event": "ready"}:
                raise ProtocolError("backend did not report ready")
        except (ProtocolError, OSError):
            self.stop()
            raise
        return self.status()

    def request(self, payload: object) -> dict[str, object]:
        if not isinstance(payload, dict):
            raise ProtocolError("payload must be an object")
        if self.status()["state"] != "ready" or self.child is None:
            raise ProtocolError("backend is stopped")
        assert self.child.stdin is not None and self.child.stdout is not None
        try:
            write_frame(DeadlinePipe(self.child.stdin.fileno()), payload)
            response = read_frame(DeadlinePipe(self.child.stdout.fileno()))
            if response is None or response.get("id") != payload.get("id"):
                raise ProtocolError("backend correlation failure")
        except (ProtocolError, OSError):
            self.stop()
            raise
        return response

    def stop(self) -> dict[str, object]:
        child, self.child = self.child, None
        if child is not None:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=2)
            if child.stdin:
                child.stdin.close()
            if child.stdout:
                child.stdout.close()
        return self.status()


def serve(path: Path, session_id: str, token: str) -> None:
    if path.exists() or path.is_symlink():
        raise RuntimeError("endpoint already exists; refusing replacement")
    if not path.parent.is_dir() or path.parent.stat().st_mode & 0o077:
        raise RuntimeError("endpoint requires an existing private 0700 directory")
    session = Session([sys.executable, "-m", "electromux.sample_backend"])
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
                                write_frame(stream, {"id": identifier, "result": {"detached": True}})
                                break
                            elif method == "shutdown":
                                write_frame(stream, {"id": identifier, "result": session.stop()})
                                return
                            else:
                                raise ProtocolError("unknown method")
                            write_frame(stream, {"id": identifier, "result": result})
                        except (ProtocolError, BrokenPipeError) as exc:
                            write_frame(stream, {"id": identifier, "error": str(exc)})
                except (ProtocolError, OSError):
                    pass
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
    args = parser.parse_args()
    info = args.token_file.stat()
    if info.st_mode & 0o077:
        parser.error("token file must be private")
    token = args.token_file.read_text().strip()
    if len(token) < 32:
        parser.error("token must contain at least 32 characters")
    serve(args.socket, args.session, token)


if __name__ == "__main__":
    main()
