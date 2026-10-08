package dev.mrsurge.electromux.nodeproof

import android.app.Service
import android.content.Intent
import dev.mrsurge.electromux.node.EmbeddedNodeRuntime
import dev.mrsurge.electromux.node.ProcessRuntimeSlot
import android.content.Context

private object ProofRuntimeProcess {
    private val slot = ProcessRuntimeSlot<EmbeddedNodeRuntime>()
    @Volatile var event = "No event yet"
    fun acquire(context: Context) = slot.acquire {
        EmbeddedNodeRuntime(context.applicationContext) { event = it }
    }
}

/** Binder confines sample control to the APK, not an HTTP/debug listener. */
class NodeProofService : Service() {
    private val binder = object : INodeProof.Stub() {
        override fun request(method: String): String = proofReply {
            ProofRuntimeProcess.acquire(applicationContext).request(method)
        }
        override fun lastEvent(): String = ProofRuntimeProcess.event
    }
    override fun onBind(intent: Intent) = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    // Service recreation reconnects to the process-owned engine. Android process
    // death releases it; do not close/restart Node in onDestroy().
}
