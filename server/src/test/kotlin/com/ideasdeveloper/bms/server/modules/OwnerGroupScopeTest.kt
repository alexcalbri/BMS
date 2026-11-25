package com.ideasdeveloper.bms.server.modules

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerGroupScopeTest {
    private fun owner(email: String?) = OwnerGroupScope.Owner(UUID.randomUUID(), email)

    // Two companies of a group belong to the same person only when both business owners have the same email.
    @Test
    fun sameOwnerNeedsTheSameEmailInBothCompanies() {
        assertTrue(OwnerGroupScope.sameOwner(owner("ana@empresa.com"), owner("ana@empresa.com")))
        assertTrue(OwnerGroupScope.sameOwner(owner(" Ana@Empresa.com "), owner("ana@empresa.com")))
        assertFalse(OwnerGroupScope.sameOwner(owner("ana@empresa.com"), owner("beto@empresa.com")))
        // A missing email never matches, not even another missing one.
        assertFalse(OwnerGroupScope.sameOwner(owner(null), owner(null)))
        assertFalse(OwnerGroupScope.sameOwner(owner(""), owner("")))
        assertFalse(OwnerGroupScope.sameOwner(owner("  "), owner("  ")))
        assertFalse(OwnerGroupScope.sameOwner(owner("ana@empresa.com"), owner(null)))
        assertFalse(OwnerGroupScope.sameOwner(owner(null), owner("ana@empresa.com")))
    }
}
