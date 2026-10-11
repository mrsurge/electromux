package dev.mrsurge.electromux.node

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class NodeRequestReplyTest {
    @Test fun checkedTransportFailureBecomesExplicitErrorWithoutRetry() {
        var calls = 0
        val reply = nodeRequestReply { calls++; throw TimeoutException("Node unavailable") }
        assertEquals("Node unavailable", JSONObject(reply).getString("error"))
        assertEquals(1, calls)
    }
    @Test fun errorDetailsAreBoundedAndSuccessIsUnchanged() {
        assertEquals(2048, JSONObject(nodeRequestReply { throw IllegalStateException("x".repeat(65536)) })
            .getString("error").length)
        val raw = "{\"id\":1,\"result\":{}}"
        assertEquals(raw, nodeRequestReply { raw })
    }
}
