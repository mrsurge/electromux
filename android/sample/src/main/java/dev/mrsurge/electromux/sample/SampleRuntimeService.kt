package dev.mrsurge.electromux.sample

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.mrsurge.electromux.host.RuntimeOwner
import dev.mrsurge.electromux.host.TermuxHelperClient
import org.json.JSONObject

data class SampleRuntimeEvent(val name: String, val frame: JSONObject)

/** Private, non-sticky owner. Android may stop it; destruction only detaches. */
class SampleRuntimeService : Service() {
    lateinit var runtime: RuntimeOwner<SampleRuntimeEvent>
        private set
    lateinit var protocol: dev.mrsurge.electromux.host.ConsumerPageProtocol
        private set
    private lateinit var client: TermuxHelperClient
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): SampleRuntimeService {
            check(Binder.getCallingUid() == Process.myUid()) { "Local binding required" }
            return this@SampleRuntimeService
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeOwner { client.close() }
        client = TermuxHelperClient(applicationContext, SampleConsumer.helperInstall, SampleConsumer.descriptor.events) { name, frame ->
            runtime.emit(SampleRuntimeEvent(name, frame))
        }
        protocol = SampleConsumer.protocol(client)
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onDestroy() {
        runtime.close()
        super.onDestroy()
    }
}
