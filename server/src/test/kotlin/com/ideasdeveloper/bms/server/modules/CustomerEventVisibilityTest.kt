package com.ideasdeveloper.bms.server.modules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomerEventVisibilityTest {
    private fun visible(
        id: String,
        installed: Boolean = false,
        extensionModule: String? = null,
        enabled: Set<String> = emptySet(),
        admin: Boolean = false,
        permission: Boolean = false,
    ) = customerEventVisibility(id, installed, extensionModule, enabled, admin) { permission }


    @Test
    fun clientesIsAlwaysVisible() = assertTrue(visible("clientes"))

    @Test
    fun installedModuleNeedsEnabledAndPermissionOrAdmin() {
        assertFalse(visible("crm", installed = true, permission = true))
        assertFalse(visible("crm", installed = true, enabled = setOf("crm")))
        assertTrue(visible("crm", installed = true, enabled = setOf("crm"), permission = true))
        assertTrue(visible("crm", installed = true, enabled = setOf("crm"), admin = true))
    }

    @Test
    fun extensionFollowsItsModuleBeingEnabled() {
        assertTrue(visible("ext_a", extensionModule = "crm", enabled = setOf("crm"), permission = true))
        assertTrue(visible("ext_a", extensionModule = "crm", enabled = setOf("crm"), admin = true))
        assertFalse(visible("ext_a", extensionModule = "crm", enabled = setOf("crm")))
        assertFalse(visible("ext_a", extensionModule = "crm", admin = true, permission = true))
    }

    @Test
    fun uninstalledOrUnknownIdsAreForAdminsOnly() {
        assertFalse(visible("hotel"))
        assertFalse(visible("hotel", permission = true, enabled = setOf("hotel")))
        assertTrue(visible("hotel", admin = true))
    }
}
