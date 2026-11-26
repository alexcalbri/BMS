package com.ideasdeveloper.bms.modules.empresa

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmpresaI18nTest {
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(EmpresaServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("empresa", EmpresaServerModule.definition)

    @Test
    fun definitionIsTranslated() {
        val definition = EmpresaServerModule.definition
        assertEquals("Empresa", definition.localized().displayName)
        I18n.withLanguage("en") {
            val english = definition.localized()
            assertEquals("Company", english.displayName)
            assertEquals("Company administration, identity and settings of each company.", english.description)
            assertEquals("empresa", english.id)
        }
    }
}
