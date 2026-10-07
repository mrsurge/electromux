package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.FrameCodec

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameCodecTest {
    @Test fun utf8RoundTrip() {
        val output = ByteArrayOutputStream()
        FrameCodec.write(output, "é")
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0xc3.toByte(), 0xa9.toByte()), output.toByteArray())
        assertEquals("é", FrameCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun invalidFramesFail() {
        for (bytes in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(-1, -1, -1, -1),
            byteArrayOf(0, 1, 0, 1), byteArrayOf(0, 0, 0, 2, 65), byteArrayOf(0, 0, 0, 1, 0xff.toByte()))) {
            try { FrameCodec.read(ByteArrayInputStream(bytes)); fail("Invalid frame accepted") }
            catch (_: Exception) { }
        }
    }

}
