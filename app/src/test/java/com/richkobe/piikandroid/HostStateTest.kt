package com.richkobe.piikandroid

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HostStateTest {
    private val host: () -> Unit = {}
    private val external: (String) -> Unit = {}

    @After
    fun removeListeners() {
        repeat(3) {
            HostState.removeListener(host)
            HostState.removeExternalListener(external)
        }
    }

    @Test
    fun repeatedHostSubscriptionIsRemovedOnce() {
        repeat(3) { HostState.addListener(host) }
        assertEquals(1, listeners("listeners").count { it === host })
        HostState.removeListener(host)
        assertFalse(listeners("listeners").any { it === host })
    }

    @Test
    fun repeatedExternalSubscriptionIsRemovedOnce() {
        repeat(3) { HostState.addExternalListener(external) }
        assertEquals(1, listeners("externalListeners").count { it === external })
        HostState.removeExternalListener(external)
        assertFalse(listeners("externalListeners").any { it === external })
    }

    private fun listeners(name: String): Collection<*> =
        HostState::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as Collection<*>
}
