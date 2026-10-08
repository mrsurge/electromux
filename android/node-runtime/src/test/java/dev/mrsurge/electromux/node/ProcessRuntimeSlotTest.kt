package dev.mrsurge.electromux.node

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessRuntimeSlotTest {
    @Test fun recreatedConsumersRetainExactEngine() {
        val slot = ProcessRuntimeSlot<Any>()
        val engine = Any()
        assertSame(engine, slot.acquire { engine })
        repeat(20) { assertSame(engine, slot.acquire { error("Must not restart") }) }
    }
    @Test fun concurrentConsumersInitializeOnce() {
        val slot = ProcessRuntimeSlot<Any>()
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..20).map { pool.submit<Any> { slot.acquire { calls.incrementAndGet(); Any() } } }
            val engine = futures.first().get(2, TimeUnit.SECONDS)
            futures.forEach { assertSame(engine, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun failedStartupIsNotRetriedWithinSameProcess() {
        val slot = ProcessRuntimeSlot<Any>()
        val failure = IllegalStateException("Native initialization failed")
        val calls = AtomicInteger()
        repeat(2) {
            try { slot.acquire { calls.incrementAndGet(); throw failure }; fail("Failure expected") }
            catch (error: IllegalStateException) { assertSame(failure, error) }
        }
        assertEquals(1, calls.get())
    }
}
