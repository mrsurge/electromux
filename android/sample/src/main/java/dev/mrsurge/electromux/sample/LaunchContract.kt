package dev.mrsurge.electromux.sample

/** Only native consumer configuration constructs this value; never deserialize it from a page. */
data class LaunchSpec(val executable: String, val arguments: List<String>, val cwd: String) {
    init {
        require(executable.startsWith("/data/data/com.termux/files/usr/bin/"))
        require(!executable.contains("/../") && !executable.contains('\u0000'))
        require(cwd.startsWith("/data/data/com.termux/files/"))
        require(!cwd.contains("/../") && !cwd.contains('\u0000'))
        require(arguments.all { !it.contains('\u0000') })
        require(arguments.sumOf { it.toByteArray(Charsets.UTF_8).size } < 16 * 1024)
    }
}

data class IdentityStatus(
    val installed: Boolean,
    val sameUid: Boolean,
    val sameSignature: Boolean,
    val serviceAvailable: Boolean,
) {
    val canLaunch: Boolean get() = installed && sameUid && sameSignature && serviceAvailable
    fun summary(): String = when {
        !installed -> "Termux is not installed or is not visible."
        !sameSignature -> "Sample and Termux signing certificates do not match."
        !sameUid -> "Sample and Termux do not share an installed UID."
        !serviceAvailable -> "Termux execution service is unavailable."
        else -> "Identity checks passed. Device execution remains to be verified."
    }
}
