package dev.mrsurge.electromux.node

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

/** APK-declared private service, in a dedicated process. No entry selection via Intents. */
abstract class EmbeddedNodeService : Service() {
    protected abstract val consumer: EmbeddedConsumerSpec
    protected open val termuxLane: Boolean = false
    protected open fun validateEnvironment() = Unit

    companion object {
        private val runtime = ProcessRuntimeSlot<EmbeddedNodeRuntime>()
        private val observers = RemoteCallbackList<INodeEvents>()
        private val lane = ReentrantLock()
        private var declaration: List<Any>? = null
        private fun publish(frame: String) {
            require(frame.toByteArray(Charsets.UTF_8).size <= 65536)
            synchronized(observers) {
                val count = observers.beginBroadcast()
                try {
                    for (index in 0 until count) {
                        try { observers.getBroadcastItem(index).event(frame) }
                        catch (_: android.os.RemoteException) { /* Binder owns dead observer removal. */ }
                    }
                } finally { observers.finishBroadcast() }
            }
        }
    }

    private fun authorize() {
        check(Binder.getCallingUid() == Process.myUid()) { "Private Node caller rejected" }
    }
    private val binder = object : INodeHost.Stub() {
        override fun request(method: String, parameters: String): String {
            authorize()
            // Future/transport failures may be checked Java exceptions. Never let
            // them escape Binder's RuntimeException-only transaction boundary.
            return nodeRequestReply { dispatch(method, parameters) }
        }
        private fun dispatch(method: String, parameters: String): String {
            require(method in consumer.methods)
            require(parameters.toByteArray(Charsets.UTF_8).size <= 65536)
            check(lane.tryLock()) { "Node request lane busy" }
            try {
                check(android.app.Application.getProcessName() != packageName) {
                    "Embedded Node must run in a dedicated service process"
                }
                validateEnvironment()
                val key = listOf(consumer.entryAsset, consumer.methods, consumer.events, termuxLane)
                synchronized(runtime) {
                    check(declaration == null || declaration == key) { "Node consumer cannot be retargeted" }
                    declaration = key
                }
                return runtime.acquire {
                    EmbeddedNodeRuntime(applicationContext, termuxLane, consumer, ::publish)
                }.request(method, JSONObject(parameters))
            } finally { lane.unlock() }
        }
        override fun subscribe(observer: INodeEvents) {
            authorize()
            synchronized(observers) {
                check(observers.registeredCallbackCount < 16) { "Node observer limit reached" }
                check(observers.register(observer))
            }
        }
        override fun unsubscribe(observer: INodeEvents) {
            authorize()
            synchronized(observers) { observers.unregister(observer) }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    // Runtime and event hub belong to the process, not a particular Service/Activity.
}
