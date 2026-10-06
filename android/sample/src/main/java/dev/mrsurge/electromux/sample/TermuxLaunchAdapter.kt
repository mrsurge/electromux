package dev.mrsurge.electromux.sample

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/** Constants verified against Termux 8629e632; not public RUN_COMMAND. */
class TermuxLaunchAdapter(private val context: Context) {
    private val component = ComponentName("com.termux", "com.termux.app.TermuxService")

    @Suppress("DEPRECATION")
    fun inspect(): IdentityStatus {
        val pm = context.packageManager
        return try {
            val app = pm.getApplicationInfo("com.termux", 0)
            val service = try {
                val info = pm.getServiceInfo(component, 0)
                info.enabled && info.applicationInfo.enabled
            } catch (_: PackageManager.NameNotFoundException) { false }
            IdentityStatus(true, app.uid == Process.myUid(),
                pm.checkSignatures(context.packageName, "com.termux") == PackageManager.SIGNATURE_MATCH,
                service)
        } catch (_: PackageManager.NameNotFoundException) {
            IdentityStatus(false, false, false, false)
        }
    }

    internal fun executionIntent(spec: LaunchSpec): Intent = Intent("com.termux.service_execute").apply {
        component = this@TermuxLaunchAdapter.component
        data = Uri.Builder().scheme("com.termux.file").path(spec.executable).build()
        putExtra("com.termux.execute.arguments", spec.arguments.toTypedArray())
        putExtra("com.termux.execute.cwd", spec.cwd)
        putExtra("com.termux.execute.runner", "app-shell")
        putExtra("com.termux.execute.background", true)
    }

    /** Must be called from an explicit visible native user action, not page input. */
    fun launch(spec: LaunchSpec) {
        check(inspect().canLaunch) { "Termux identity/service verification failed" }
        context.startForegroundService(executionIntent(spec))
    }
}
