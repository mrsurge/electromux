package dev.mrsurge.electromux.host

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONArray

data class HelperSession(val root: File, val packageRoot: File, val id: String, val token: String,
                         val backendConfig: File? = null) {
    val socketPath: String get() = File(root, "host.sock").path
    fun arguments() =
        listOf("-B", "-m", "electromux.helper", "--socket", socketPath, "--session", id,
            "--token-file", File(root, "token").path) +
            (backendConfig?.let { listOf("--backend-config", it.path) } ?: emptyList())
}

/** Native-declared consumer paths only. No pip/global install or page-provided paths. */
class HelperProvisioner(private val context: Context, private val spec: HelperInstallSpec) {
    private val termuxRoot = File("/data/data/com.termux/files")
    private val base = File(spec.basePath)
    private val prefs = context.getSharedPreferences(spec.preferencesName, Context.MODE_PRIVATE)

    fun prepare(): HelperSession {
        privateDirectory(base)
        val names = spec.files.keys.sorted()
        var total = 0L
        val contents = names.associateWith { context.assets.open(spec.files.getValue(it)).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024)
                total += count
                require(total <= 32 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } }
        require(contents.values.sumOf { it.size.toLong() } <= 32 * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        names.forEach { digest.update(it.toByteArray()); digest.update(0.toByte()); digest.update(contents.getValue(it)) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val packageRoot = File(base, "p/$hash")
        names.forEach { name ->
            val file = File(packageRoot, name)
            privateDirectory(checkNotNull(file.parentFile))
            checkManagedPath(file)
            if (file.exists()) {
                check(file.isFile && file.length() == contents.getValue(name).size.toLong())
                check(file.readBytes().contentEquals(contents.getValue(name)))
            }
            else publish(file, contents.getValue(name))
            Os.chmod(file.path, 0x180)
        }
        var id = prefs.getString("id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(12)
            check(prefs.edit().putString("id", id).commit())
        }
        require(id.matches(Regex("[a-f0-9]{12}")))
        val root = File(base, "s/$id")
        privateDirectory(root)
        val tokenFile = File(root, "token")
        checkManagedPath(tokenFile)
        if (!tokenFile.exists()) {
            check(!attempted()) { "Lost session credentials; refusing uncertain relaunch" }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            publish(tokenFile, bytes.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8))
        }
        Os.chmod(tokenFile.path, 0x180)
        check(tokenFile.isFile && tokenFile.length() == 64L)
        val token = tokenFile.readText()
        require(token.matches(Regex("[a-f0-9]{64}")))
        val config = spec.backend?.let { backend ->
            val value = JSONObject().put("argv", JSONArray(listOf(backend.executable,
                File(packageRoot, backend.entrypoint).path) + backend.arguments))
                .put("cwd", packageRoot.path).put("env", JSONObject(backend.environment))
                .put("stopTimeout", backend.stopTimeout).toString().toByteArray(Charsets.UTF_8)
            require(value.size <= 65536)
            val configHash = MessageDigest.getInstance("SHA-256").digest(value)
                .joinToString("") { "%02x".format(it) }
            File(packageRoot, "backend-$configHash.json").also { file ->
                checkManagedPath(file)
                if (file.exists()) {
                    check(file.isFile && file.length() == value.size.toLong())
                    check(file.readBytes().contentEquals(value))
                } else publish(file, value)
                Os.chmod(file.path, 0x180)
            }
        }
        return HelperSession(root, packageRoot, id, token, config)
    }

    fun attempted(): Boolean = prefs.getBoolean("attempted", false)
    fun markAttempted(value: Boolean) { check(prefs.edit().putBoolean("attempted", value).commit()) }
    private fun publish(file: File, bytes: ByteArray) {
        val temporary = File.createTempFile(".seed-", ".part", file.parentFile)
        try {
            Os.chmod(temporary.path, 0x180)
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            check(!file.exists()) { "Managed file appeared during publication" }
            check(temporary.renameTo(file)) { "Managed file publication failed" }
        } finally { temporary.delete() }
    }
    private fun privateDirectory(file: File) {
        check(file.absolutePath == base.absolutePath || file.absolutePath.startsWith(base.absolutePath + "/"))
        checkManagedPath(file)
        check(file.isDirectory || file.mkdirs())
        Os.chmod(file.path, 0x1c0)
    }
    private fun checkManagedPath(file: File) {
        check(file.absolutePath.startsWith(termuxRoot.absolutePath + "/"))
        // Android may alias /data/data to /data/user/0. Trust that fixed platform
        // anchor only; any link inside the managed consumer subtree still fails.
        val relative = file.absolutePath.removePrefix(termuxRoot.absolutePath + "/")
        check(file.canonicalFile == File(termuxRoot.canonicalFile, relative).absoluteFile) {
            "Unexpected managed path link"
        }
    }
}
