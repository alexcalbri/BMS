package com.ideasdeveloper.bms.modules.crm

import kotlin.test.Test
import kotlin.test.assertEquals

class CrmDownloadsTest {
    @Test
    fun onlyASafeListOfTypesIsServedAsStored() {
        assertEquals("application/pdf", CrmDownloads.safeType("application/pdf"))
        assertEquals("image/png", CrmDownloads.safeType("IMAGE/PNG; charset=binary"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", CrmDownloads.safeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        // What can run code in the browser, or is made up, never keeps its type.
        listOf("text/html", "image/svg+xml", "application/xhtml+xml", "text/javascript", "application/x-msdownload", "", "x/<script>", "application/vnd.openxmlformats-officedocument.").forEach {
            assertEquals("application/octet-stream", CrmDownloads.safeType(it), it)
        }
        assertEquals("application/octet-stream", CrmDownloads.safeType(null))
        assertEquals("application/octet-stream", CrmDownloads.contentType("text/html").toString())
    }
}
