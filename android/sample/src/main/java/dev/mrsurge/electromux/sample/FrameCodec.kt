package dev.mrsurge.electromux.sample

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object FrameCodec {
    const val MAX_FRAME = 65536
    fun write(stream: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME)
        DataOutputStream(stream).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    fun read(stream: InputStream): String {
        val input = DataInputStream(stream)
        val size = input.readInt()
        require(size in 1..MAX_FRAME)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
