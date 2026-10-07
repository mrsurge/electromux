package dev.mrsurge.electromux.host

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/** Native-owned explicit TermuxService dispatch, independent of any consumer. */
class TermuxHelperLauncher(private val context: Context) {
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

    fun launch(spec: LaunchSpec, completion: PendingIntent? = null) {
        check(inspect().canLaunch) { "Termux identity/service verification failed" }
        val intent = Intent("com.termux.service_execute").apply {
            component = this@TermuxHelperLauncher.component
            data = Uri.Builder().scheme("com.termux.file").path(spec.executable).build()
            putExtra("com.termux.execute.arguments", spec.arguments.toTypedArray())
            putExtra("com.termux.execute.cwd", spec.cwd)
            putExtra("com.termux.execute.runner", "app-shell")
            putExtra("com.termux.execute.background", true)
            if (completion != null) putExtra("pendingIntent", completion)
        }
        context.startForegroundService(intent)
    }
}
