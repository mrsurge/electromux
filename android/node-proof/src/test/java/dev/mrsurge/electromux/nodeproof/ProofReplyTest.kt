package dev.mrsurge.electromux.nodeproof

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class ProofReplyTest {
    @Test fun successPassesThroughUnchanged() {
        val reply = "{\"id\":1,\"result\":{}}"
        assertEquals(reply, proofReply { reply })
    }
    @Test fun checkedRuntimeFailureReturnsErrorDtoInsteadOfBinderNull() {
        val reply = JSONObject(proofReply {
            throw ExecutionException(IllegalStateException("Node exited; process restart required"))
        })
        assertEquals("runtime_failure", reply.getString("kind"))
        assertEquals("Node exited; process restart required", reply.getString("error"))
    }
}
