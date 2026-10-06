import io
import struct
import unittest

from electromux.protocol import MAX_FRAME, ProtocolError, read_frame, write_frame


class Fragmented(io.BytesIO):
    def read(self, size: int = -1) -> bytes:
        return super().read(min(size, 1))


class ProtocolTests(unittest.TestCase):
    def test_roundtrip_fragmented(self) -> None:
        stream = io.BytesIO()
        write_frame(stream, {"id": 1, "value": "hello 🌍"})
        self.assertEqual(read_frame(Fragmented(stream.getvalue())),
                         {"id": 1, "value": "hello 🌍"})

    def test_clean_eof(self) -> None:
        self.assertIsNone(read_frame(io.BytesIO()))

    def test_invalid_frames(self) -> None:
        for raw in (b"\0", struct.pack(">I", 4) + b"{}",
                    struct.pack(">I", MAX_FRAME + 1), struct.pack(">I", 0),
                    struct.pack(">I", 1) + b"\xff", struct.pack(">I", 2) + b"[]"):
            with self.subTest(raw=raw), self.assertRaises(ProtocolError):
                read_frame(io.BytesIO(raw))

    def test_large_output_rejected(self) -> None:
        with self.assertRaises(ProtocolError):
            write_frame(io.BytesIO(), {"value": "x" * MAX_FRAME})
