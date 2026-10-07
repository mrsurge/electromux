package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.LaunchSpec
import dev.mrsurge.electromux.host.IdentityStatus

import org.junit.Assert.*
import org.junit.Test

class LaunchContractTest {
    @Test fun configuredArgumentsRemainAnArray() {
        val spec = LaunchSpec("/data/data/com.termux/files/usr/bin/python", listOf("-c", "print('a b')"),
            "/data/data/com.termux/files/home")
        assertEquals(listOf("-c", "print('a b')"), spec.arguments)
    }

    @Test fun rejectsOutsidePathsAndInvalidArguments() {
        for (executable in listOf("python", "/system/bin/sh", "/data/data/com.termux/files/usr/bin/../sh")) {
            try {
                LaunchSpec(executable, emptyList(), "/data/data/com.termux/files/home")
                fail("accepted forbidden executable")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            LaunchSpec("/data/data/com.termux/files/usr/bin/python", listOf("\u0000"),
                "/data/data/com.termux/files/home")
            fail("accepted NUL")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun identityRequiresEveryFact() {
        assertTrue(IdentityStatus(true, true, true, true).canLaunch)
        for (status in listOf(IdentityStatus(false, true, true, true),
            IdentityStatus(true, false, true, true), IdentityStatus(true, true, false, true),
            IdentityStatus(true, true, true, false))) {
            assertFalse(status.canLaunch)
        }
    }
}
