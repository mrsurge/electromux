from __future__ import annotations

import sys

from .protocol import read_frame, write_frame


def main() -> None:
    write_frame(sys.stdout.buffer, {"version": 1, "event": "ready"})
    while (request := read_frame(sys.stdin.buffer)) is not None:
        identifier = request.get("id")
        if request.get("method") == "ping":
            write_frame(sys.stdout.buffer, {
                "id": identifier,
                "result": {"pong": request.get("value"),
                           "events": [{"event": "sample.updated", "id": identifier}]},
            })
        else:
            write_frame(sys.stdout.buffer, {"id": identifier, "error": "unknown method"})


if __name__ == "__main__":
    main()
