package dev.mrsurge.electromux.nodeproof

import android.app.Service
import android.content.Intent
import dev.mrsurge.electromux.node.EmbeddedNodeRuntime
import dev.mrsurge.electromux.node.ProcessRuntimeSlot
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

private object ProofRuntimeProcess {
    private val slot = ProcessRuntimeSlot<EmbeddedNodeRuntime>()
    @Volatile var event = "No event yet"
    fun acquire(context: Context) = slot.acquire {
        if (BuildConfig.TERMUX_PROOF) {
            val manager = context.packageManager
            @Suppress("DEPRECATION")
            val termux = manager.getApplicationInfo("com.termux", 0)
            check(termux.uid == Process.myUid()) { "Termux shared UID mismatch" }
            check(manager.checkSignatures(context.packageName, "com.termux") == PackageManager.SIGNATURE_MATCH) {
                "Termux signing mismatch"
            }
        }
        EmbeddedNodeRuntime(context.applicationContext, BuildConfig.TERMUX_PROOF) { event = it }
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
