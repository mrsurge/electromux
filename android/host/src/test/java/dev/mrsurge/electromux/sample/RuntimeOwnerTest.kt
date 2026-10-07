package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.RuntimeOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

class RuntimeOwnerTest {
    @Test fun rendererDetachRetainsOwnerAndReplacementReceivesOnlyNewEvents() {
        var released = 0
        val owner = RuntimeOwner<Int> { released++ }
        val old = mutableListOf<Int>()
        val first = owner.subscribe { old.add(it) }
        owner.emit(1)
        first.close()
        val next = mutableListOf<Int>()
        owner.subscribe { next.add(it) }
        owner.emit(2)
        assertEquals(listOf(1), old)
        assertEquals(listOf(2), next)
        assertEquals(0, released)
        owner.close(); owner.close()
        assertEquals(1, released)
        owner.emit(3)
        assertEquals(listOf(2), next)
    }

    @Test fun observerFailureIsLocalAndSubscriptionCountIsBounded() {
        val owner = RuntimeOwner<Int> { }
        var failures = 0
        var good = 0
        owner.subscribe { failures++; throw IllegalStateException("slow renderer") }
        owner.subscribe { good++ }
        owner.emit(1); owner.emit(2)
        assertEquals(1, failures); assertEquals(2, good)
        repeat(15) { owner.subscribe { } }
        try { owner.subscribe { }; fail("unbounded observers") } catch (_: IllegalStateException) { }
        owner.close()
    }

    @Test fun boundedLaneRejectsSaturationAndCloseRejectsNewRequests() {
        val owner = RuntimeOwner<Int> { }
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        owner.execute { started.countDown(); block.await() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        repeat(8) { owner.execute { } }
        try { owner.execute { }; fail("unbounded queue") } catch (_: RejectedExecutionException) { }
        block.countDown()
        owner.close()
        try { owner.execute { }; fail("closed lane") } catch (_: IllegalStateException) { }
    }
}
