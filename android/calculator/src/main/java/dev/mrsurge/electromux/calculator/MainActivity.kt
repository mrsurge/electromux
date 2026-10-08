package dev.mrsurge.electromux.calculator

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.LinearLayout
import android.widget.TextView
import com.cefrium.CefriumBrowser
import dev.mrsurge.electromux.host.ElectronMenuChrome
import dev.mrsurge.electromux.host.PackagedAssetServer
import dev.mrsurge.electromux.node.IElectronHost
import dev.mrsurge.electromux.node.INodeEvents
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Independent calculator consumer. Core APIs/menu presentation live in Electromux. */
class MainActivity : Activity() {
    private lateinit var browser: CefriumBrowser
    private lateinit var chrome: ElectronMenuChrome
    private lateinit var status: TextView
    private lateinit var assetServer: PackagedAssetServer
    private var host: IElectronHost? = null
    private var bound = false
    private var destroyed = false
    private var created = false
    private var loadingEffect: Int? = null
    private val commands = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private val acknowledgements = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private val observer = object : INodeEvents.Stub() {
        override fun event(raw: String) {
            val frame = JSONObject(raw)
            check(frame.getString("event") == "electron.effect")
            val data = frame.getJSONObject("data")
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                val id = data.getInt("effectId")
                try { apply(id, data.getJSONObject("effect")) }
                catch (error: Exception) { acknowledge(id, error.message ?: "Native operation failed") }
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            host = IElectronHost.Stub.asInterface(binder)
            host!!.subscribe(observer)
            command("electron.start", JSONObject())
        }
        override fun onServiceDisconnected(name: ComponentName) {
            host = null; status.text = "Node service disconnected; restart the app. No action replayed."
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manifest = JSONObject(assets.open("electron_app/electromux.json").bufferedReader().use { it.readText() })
        status = TextView(this).apply { text = "Starting embedded Electron application" }
        chrome = ElectronMenuChrome(this, manifest.getJSONObject("chrome")) { id ->
            command("electron.menu.select", JSONObject().put("itemId", id))
        }
        browser = CefriumBrowser.createWithSurface(this)
        assetServer = PackagedAssetServer(assets, "calculator")
        browser.setPinchToZoomEnabled(false)
        browser.setRequestInterceptor { url, _, _ ->
            !(url.startsWith(assetServer.origin + "/") || url.startsWith("data:") || url == "about:blank")
        }
        // Registers Cefrium's native callback target before navigation.
        browser.setOnLoadingStateChangedListener { _, _, _ -> }
        browser.setQueryHandler { _, raw, origin, callback ->
            if (origin != assetServer.origin + "/index.html" || browser.url != origin) {
                callback.failure(403, "Not the packaged main document"); true
            } else {
                try {
                    val signal = JSONObject(raw)
                    val method = signal.getString("method")
                    check(method in setOf("electromux.document.ready", "electromux.document.failed"))
                    val failure = if (method.endsWith("failed")) signal.optString("message", "Renderer failed").take(512).ifEmpty { "Renderer failed" } else null
                    runOnUiThread {
                        val pendingLoad = loadingEffect
                        loadingEffect = null
                        pendingLoad?.let { acknowledge(it, failure) }
                        if (failure != null) {
                            if (pendingLoad != null) { status.text = failure; status.visibility = android.view.View.VISIBLE }
                            android.util.Log.e("ElectromuxCalculator", failure)
                        }
                    }
                    callback.success("{}"); true
                } catch (_: Exception) { callback.failure(400, "Unknown document signal"); true }
            }
        }
        browser.setOnRenderProcessTerminatedListener { code, exitCode ->
            loadingEffect?.let { loadingEffect = null; acknowledge(it, "Renderer terminated ($code/$exitCode)") }
            status.text = "Renderer terminated ($code/$exitCode); restart the app"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chrome); addView(status)
            addView(browser.surfaceContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(layout)
        val intent = Intent(this, CalculatorService::class.java)
        startService(intent); bound = bindService(intent, connection, BIND_AUTO_CREATE)
        if (!bound) status.text = "Could not bind private Electron runtime"
    }
    private fun command(method: String, params: JSONObject) {
        val target = host ?: return
        try { commands.execute {
            val failure = try {
                val response = JSONObject(target.request(method, params.toString()))
                if (response.has("error")) response.getString("error") else null
            } catch (error: Exception) { error.message ?: "Electron request failed" }
            runOnUiThread { if (!destroyed) {
                status.text = failure ?: ""
                status.visibility = if (failure == null) android.view.View.GONE else android.view.View.VISIBLE
            } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { status.text = "Command busy; not replayed" }
    }
    private fun acknowledge(id: Int, error: String? = null) {
        val target = host ?: return
        acknowledgements.execute {
            try { target.acknowledge(id, error?.take(512) ?: "") }
            catch (failure: Exception) { runOnUiThread { if (!destroyed) status.text = failure.message } }
        }
    }
    private fun apply(id: Int, effect: JSONObject) {
        when (val kind = effect.getString("kind")) {
            "window.create" -> {
                check(!created && effect.getInt("windowId") == 1)
                val options = effect.getJSONObject("options")
                val web = options.optJSONObject("webPreferences")
                check(web?.optBoolean("nodeIntegration", false) != true)
                check(web?.optBoolean("nodeIntegrationInWorker", false) != true)
                if (web?.has("preload") == true) {
                    val root = File(noBackupFilesDir, "embedded-node-proof/electron_app").canonicalFile
                    check(File(web.getString("preload")).canonicalFile == File(root, "preload.js").canonicalFile) {
                        "Only the packaged declared preload is supported"
                    }
                }
                created = true; chrome.setWindowTitle(options.optString("title", "Electromux"))
            }
            "menu.set" -> chrome.setItems(effect.getJSONArray("items"))
            "theme" -> browser.setDarkMode(effect.getString("value") == "dark")
            "window.load" -> {
                check(created && effect.getInt("windowId") == 1 && loadingEffect == null)
                val root = File(noBackupFilesDir, "embedded-node-proof/electron_app").canonicalFile
                val value = effect.getString("value")
                val file = if (effect.getString("source") == "url") File(URI(value)) else File(root, value)
                check(file.canonicalFile == File(root, "index.html").canonicalFile) { "Only packaged main document is supported" }
                loadingEffect = id
                browser.loadUrl(assetServer.origin + "/index.html")
                return // Success ACK requires the exact document's preload-ready signal.
            }
            "webContents" -> when (effect.getString("action")) {
                "goBack" -> browser.goBack()
                "goForward" -> browser.goForward()
                "reload" -> browser.reload()
                else -> error("Unsupported webContents operation")
            }
            "role" -> when (effect.getString("role")) {
                "reload", "forceReload" -> browser.reload()
                "quit" -> android.os.Handler(mainLooper).postDelayed({ finish() }, 250)
                else -> error("Unsupported Electron role")
            }
            "window.close", "app.quit" -> android.os.Handler(mainLooper).postDelayed({ finish() }, 250)
            else -> error("Unsupported native effect: $kind")
        }
        acknowledge(id)
    }
    override fun onDestroy() {
        destroyed = true
        val target = host; host = null
        Thread { try { target?.close() } catch (_: Exception) { } }.start()
        commands.shutdownNow(); acknowledgements.shutdownNow()
        if (bound) unbindService(connection)
        if (::browser.isInitialized) browser.close()
        if (::assetServer.isInitialized) assetServer.close()
        super.onDestroy()
    }
}
