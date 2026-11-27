package com.ideasdeveloper.bms.modules.hotspot

import kotlin.test.Test
import kotlin.test.assertFalse

class HotspotMcpToolsTest {
    @Test
    fun myWifiCodeIsNotReadOnlyBecauseItCreatesTheCodeOnFirstUse() {
        val tool = HotspotMcpTools.tools.single { it.name == "my_wifi_code" }
        assertFalse(tool.readOnly)
    }

    @Test
    fun portalFormsDecodeLikeABrowserSendsThem() {
        val form = formOf("name=Ana+Mar%C3%ADa&email=ana%40example.com&terms=si&empty=", 200)
        kotlin.test.assertEquals("Ana María", form["name"])
        kotlin.test.assertEquals("ana@example.com", form["email"])
        kotlin.test.assertEquals("", form["empty"])
    }
}
