package dev.mrsurge.electromux.sample

import org.junit.Assert.*
import org.junit.Test

class DiagnosticStateTest {
    @Test fun completionIsCorrelatedAndOneShot() {
        val state = DiagnosticState()
        state.begin("first")
        assertFalse(state.finish("other", "spoof"))
        assertEquals("first", state.pendingId)
        assertTrue(state.finish("first", "exit=0"))
        assertFalse(state.finish("first", "duplicate"))
        state.begin("second")
        assertFalse(state.finish("first", "late"))
        assertEquals("second", state.pendingId)
    }

    @Test fun cannotReplacePendingAndTimeoutCannotCompleteNextRequest() {
        val state = DiagnosticState()
        state.begin("first")
        try { state.begin("second"); fail("replaced pending request") }
        catch (_: IllegalStateException) { }
        assertTrue(state.finish("first", "timeout"))
        state.begin("second")
        assertFalse(state.finish("first", "late completion"))
    }

    @Test fun outputIsBoundedAndMissingExitIsNotSuccess() {
        val result = DiagnosticResult(null, null, "x".repeat(5000), "", "").summary()
        assertTrue(result.contains("exit=missing"))
        assertTrue(result.contains("[truncated]"))
        assertTrue(result.length < 4300)
        assertTrue(DiagnosticResult(0, 0, "Python 3.14.6", "", "").summary().contains("exit=0, error=0"))
    }
}
