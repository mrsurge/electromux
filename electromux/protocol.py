from __future__ import annotations

import json
import struct
from typing import BinaryIO

MAX_FRAME = 64 * 1024


class ProtocolError(ValueError):
    pass


def _read_exact(stream: BinaryIO, size: int) -> bytes:
    parts = bytearray()
    while len(parts) < size:
        chunk = stream.read(size - len(parts))
        if not chunk:
            raise ProtocolError("truncated frame")
        parts.extend(chunk)
    return bytes(parts)


def read_frame(stream: BinaryIO) -> dict[str, object] | None:
    first = stream.read(1)
    if not first:
        return None
    size = struct.unpack(">I", first + _read_exact(stream, 3))[0]
    if not 0 < size <= MAX_FRAME:
        raise ProtocolError("invalid frame length")
    try:
        value = json.loads(_read_exact(stream, size))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("invalid JSON") from exc
    if not isinstance(value, dict):
        raise ProtocolError("frame must be an object")
    return value


def write_frame(stream: BinaryIO, value: dict[str, object]) -> None:
    raw = json.dumps(value, separators=(",", ":"), allow_nan=False).encode()
    if not 0 < len(raw) <= MAX_FRAME:
        raise ProtocolError("invalid frame length")
    framed = struct.pack(">I", len(raw)) + raw
    offset = 0
    while offset < len(framed):
        count = stream.write(framed[offset:])
        if count is None:  # DeadlinePipe writes the complete supplied buffer.
            break
        if count <= 0:
            raise ProtocolError("frame write made no progress")
        offset += count
    stream.flush()
