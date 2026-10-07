package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.FrameCodec
import dev.mrsurge.electromux.host.FramedTransport
import dev.mrsurge.electromux.host.TransportFrame
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FramedTransportTest {
    private class Harness(timeout: Long = 1000, event: (TransportFrame.Event) -> Unit = {}) : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        private val client = Socket(listener.inetAddress, listener.localPort)
        val peer = listener.accept()
        val closed = CountDownLatch(1)
        val transport = FramedTransport(client.getInputStream(), client.getOutputStream(), {
            client.close(); closed.countDown()
        }, { raw ->
            if (raw.startsWith("event:")) TransportFrame.Event(raw.substringAfter(':'), raw)
            else TransportFrame.Reply(raw.substringAfter(':').toInt(), raw)
        }, setOf("state"), event, timeout)
        val io = Executors.newSingleThreadExecutor()
        fun send(raw: String) = FrameCodec.write(peer.getOutputStream(), raw)
        fun request(id: Int = 1) = io.submit<String> { transport.request(id, "request:$id") }
        fun receive() = FrameCodec.read(peer.getInputStream())
        override fun close() { transport.close(); peer.close(); listener.close(); io.shutdownNow() }
    }

    @Test fun interleavedEventsDoNotConsumeReply() {
        val event = CountDownLatch(2)
        Harness(event = { event.countDown() }).use { h ->
            val reply = h.request()
            assertEquals("request:1", h.receive())
            h.send("event:state"); h.send("reply:1"); h.send("event:state")
            assertEquals("reply:1", reply.get(2, TimeUnit.SECONDS))
            assertTrue(event.await(2, TimeUnit.SECONDS))
            val second = h.request(2)
            assertEquals("request:2", h.receive()); h.send("reply:2")
            assertEquals("reply:2", second.get(2, TimeUnit.SECONDS))
        }
    }

    @Test fun idleDoesNotTimeoutButPartialFrameDoes() {
        Harness(timeout = 80).use { h ->
            assertFalse(h.closed.await(150, TimeUnit.MILLISECONDS))
            h.peer.getOutputStream().write(0); h.peer.getOutputStream().flush()
            assertTrue(h.closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun requestTimeoutClosesWithoutReplay() {
        Harness(timeout = 80).use { h ->
            val reply = h.request(); assertEquals("request:1", h.receive())
            try { reply.get(2, TimeUnit.SECONDS); fail("accepted missing reply") } catch (_: java.util.concurrent.ExecutionException) { }
            assertTrue(h.closed.await(2, TimeUnit.SECONDS))
            assertEquals(-1, h.peer.getInputStream().read())
        }
    }

    @Test fun wrongCorrelationAndUndeclaredEventsFailClosed() {
        for (raw in listOf("reply:999", "event:other")) {
            Harness().use { h ->
                val reply = h.request(); h.receive(); h.send(raw)
                try { reply.get(2, TimeUnit.SECONDS); fail("accepted invalid frame") } catch (_: java.util.concurrent.ExecutionException) { }
                assertTrue(h.closed.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun replyThenEofPreservesCompletedResult() {
        Harness().use { h ->
            val reply = h.request(); h.receive(); h.send("reply:1"); h.peer.shutdownOutput()
            assertEquals("reply:1", reply.get(2, TimeUnit.SECONDS))
            assertTrue(h.closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun closeWakesPendingRequest() {
        Harness().use { h ->
            val reply = h.request(); h.receive(); h.transport.close()
            try { reply.get(2, TimeUnit.SECONDS); fail("accepted closed request") } catch (_: java.util.concurrent.ExecutionException) { }
        }
    }

    @Test fun blockedEventConsumerHasBoundedQueue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        Harness(event = {
            entered.countDown()
            try { release.await(); delivered.incrementAndGet() } finally { finished.countDown() }
        }).use { h ->
            try {
                h.send("event:state"); assertTrue(entered.await(2, TimeUnit.SECONDS))
                repeat(17) { h.send("event:state") }
                assertTrue(h.closed.await(2, TimeUnit.SECONDS))
            } finally { release.countDown() }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            // The already-running callback may finish or be interrupted. No
            // queued callbacks may start; close cannot undo an active observer.
            assertTrue(delivered.get() <= 1)
        }
    }
}
