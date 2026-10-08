package dev.mrsurge.electromux.node

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

/** Separate opt-in service contract; no changes to accepted INodeHost/TE2 callers. */
abstract class EmbeddedElectronService : Service() {
    protected abstract val consumer: EmbeddedConsumerSpec
    private val lane = ReentrantLock()
    private var runtime: EmbeddedNodeRuntime? = null
    private var observer: INodeEvents? = null
    private var closed = false
    private fun authorize() { check(Binder.getCallingUid() == Process.myUid()) { "Private Electron caller rejected" } }
    private val binder = object : IElectronHost.Stub() {
        override fun subscribe(value: INodeEvents) {
            authorize(); synchronized(this@EmbeddedElectronService) {
                check(!closed && observer == null) { "Electron renderer owner already attached" }; observer = value
            }
        }
        override fun request(method: String, parameters: String): String {
            authorize()
            return nodeRequestReply {
                require(method in consumer.methods && parameters.toByteArray().size <= 65536)
                check(lane.tryLock()) { "Electron command lane busy" }
                try {
                    val engine = synchronized(this@EmbeddedElectronService) {
                        check(!closed && observer != null)
                        check(android.app.Application.getProcessName() != packageName)
                        runtime ?: EmbeddedNodeRuntime(applicationContext, false, consumer) { frame ->
                            val target = synchronized(this@EmbeddedElectronService) { observer }
                            checkNotNull(target) { "Electron renderer detached" }.event(frame)
                        }.also { runtime = it }
                    }
                    engine.request(method, JSONObject(parameters))
                } finally { lane.unlock() }
            }
        }
        override fun acknowledge(effectId: Int, error: String?) {
            authorize()
            // This must not acquire the command lane that is awaiting this ACK.
            val engine = synchronized(this@EmbeddedElectronService) { checkNotNull(runtime) }
            engine.acknowledgeEffect(effectId, error?.takeIf { it.isNotEmpty() })
        }
        override fun close() {
            authorize(); shutdown(); stopSelf()
        }
    }
    private fun shutdown() = synchronized(this) {
        if (!closed) { closed = true; observer = null; runtime?.close(); runtime = null }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        shutdown(); super.onDestroy()
        // Embedded Node cannot be initialized twice. This is our private process,
        // never the application UI, Termux, or a TE2 framework process.
        if (android.app.Application.getProcessName() != packageName) Process.killProcess(Process.myPid())
    }
}
