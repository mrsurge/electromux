package dev.mrsurge.electromux.sample

import android.content.Context
import dev.mrsurge.electromux.host.TermuxHelperLauncher
import dev.mrsurge.electromux.host.LaunchSpec
import dev.mrsurge.electromux.host.IdentityStatus

/** Constants verified against Termux 8629e632; not public RUN_COMMAND. */
class TermuxLaunchAdapter(private val context: Context) {
    private val launcher = TermuxHelperLauncher(context.applicationContext)
    fun inspect(): IdentityStatus = launcher.inspect()

    /** Must be called from an explicit visible native user action, not page input. */
    fun launch(spec: LaunchSpec) {
        check(inspect().canLaunch) { "Termux identity/service verification failed" }
        val completion = DiagnosticCompletion.prepare(context)
        try {
            launcher.launch(spec, completion)
        } catch (error: RuntimeException) {
            DiagnosticCompletion.dispatchFailed()
            throw error
        }
    }

}
