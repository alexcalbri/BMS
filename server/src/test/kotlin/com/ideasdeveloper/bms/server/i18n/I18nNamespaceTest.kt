package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.extensions.i18n_ns_ext.ExtTexts
import com.ideasdeveloper.bms.modules.i18n_ns_alpha.AlphaTexts
import com.ideasdeveloper.bms.modules.i18n_ns_beta.BetaTexts
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleViewDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

/** Namespace-aware lookup (I18N.md §2): the caller's package catalog, then Core's, then any other. */
class I18nNamespaceTest {
    private val alpha = I18n.moduleNamespace("i18n_ns_alpha")
    private val beta = I18n.moduleNamespace("i18n_ns_beta")
    private val ext = I18n.extensionNamespace("i18n_ns_ext")
    private val shared = "Clave compartida de prueba ns"
    private val onlyGamma = "Solo en gamma de prueba ns"
    private val coreText = "La sesion no es valida."
    private val coreEnglish = "The session is not valid."

    init {
        I18n.register(alpha, "en", mapOf(shared to "High", coreText to "Alpha session text", "Opcion de prueba ns" to "Alpha option"))
        I18n.register(beta, "en", mapOf(shared to "New item"))
        I18n.register(ext, "en", mapOf(shared to "Extension meaning"))
        I18n.register("i18n_ns_gamma", "en", mapOf(onlyGamma to "Gamma text"))
    }

    @Test
    fun twoNamespacesKeepTheirOwnTranslationOfTheSameKey() = I18n.withLanguage("en") {
        assertEquals("High", AlphaTexts.direct(shared))
        assertEquals("New item", BetaTexts().direct(shared))
        assertEquals("New item", BetaTexts.fromCompanion(shared))
        assertEquals("Extension meaning", ExtTexts.direct(shared))
        assertEquals("High", AlphaTexts.translate(shared))
        // Spanish output is the source, whatever the namespace.
        I18n.withLanguage("es") { assertEquals(shared, AlphaTexts.direct(shared)) }
    }

    @Test
    fun fallbackIsOwnCatalogThenCoreThenAnyOther() = I18n.withLanguage("en") {
        // Own catalog wins over Core.
        assertEquals("Alpha session text", AlphaTexts.direct(coreText))
        // Not in the own catalog: Core.
        assertEquals(coreEnglish, BetaTexts().direct(coreText))
        assertEquals(coreEnglish, ExtTexts.direct(coreText))
        // Neither: any other catalog, as a last resort.
        assertEquals("Gamma text", BetaTexts().direct(onlyGamma))
        // None: the source.
        assertEquals("Sin entrada de prueba ns", BetaTexts().direct("Sin entrada de prueba ns"))
        // Core code (this test class) reads Core first.
        assertEquals(coreEnglish, tr(coreText))
        // Explicit namespace.
        assertEquals("High", I18n.translateIn(alpha, shared))
        assertEquals("New item", trIn(beta, shared))
        assertEquals(coreEnglish, I18n.translateIn("i18n_ns_unknown", coreText))
        assertEquals(shared, I18n.translateIn(alpha, shared, "es"))
    }

    @Test
    fun callerIsDetectedThroughHelpersLambdasThreadsAndCoroutines() = I18n.withLanguage("en") {
        assertEquals("High", AlphaTexts.throughHelper(shared))
        assertEquals("High", AlphaTexts.nestedLambdas(shared))
        assertEquals("High", AlphaTexts.inThread(shared))
        assertEquals("High", AlphaTexts.inCoroutine(shared))
        // An extension that calls its host module's helper gets the host module's text.
        assertEquals("High", ExtTexts.viaHost(shared))
    }

    @Test
    fun namespacesFollowThePackageOfTheClass() {
        assertEquals("compras_monday", I18n.namespaceOfClassName("com.ideasdeveloper.bms.modules.compras_monday.X"))
        assertEquals("extensions/compras_monday", I18n.namespaceOfClassName("com.ideasdeveloper.bms.extensions.compras_monday.ComprasMondayImport\$run\$1"))
        assertEquals("crm", I18n.namespaceOfClassName("com.ideasdeveloper.bms.modules.crm.sub.Thing"))
        assertEquals(I18n.CoreNamespace, I18n.namespaceOfClassName("com.ideasdeveloper.bms.server.modules.ModuleRegistry"))
        assertEquals(I18n.CoreNamespace, I18n.namespaceOfClassName("com.ideasdeveloper.bms.modulesx.Foo"))
        assertEquals(alpha, I18n.namespaceOf(AlphaTexts::class.java))
        assertEquals(ext, I18n.namespaceOf(ExtTexts::class.java))
        assertEquals(I18n.CoreNamespace, I18n.namespaceOf(I18nNamespaceTest::class.java))
        assertEquals(I18n.CoreNamespace, I18n.callerNamespace())
    }

    @Test
    fun localizedPayloadUsesTheNamespaceOfTheModuleThatCallsIt() = I18n.withLanguage("en") {
        val form = AlphaTexts.localizedForm(shared, "Opcion de prueba ns")
        assertEquals(listOf("High", "Alpha option"), form.fields.single().optionLabels)
        val field = com.ideasdeveloper.bms.ui.UiField("k", "K", type = "select", options = listOf(shared))
        assertEquals(listOf("New item"), field.localized(beta).optionLabels)
    }

    @Test
    fun definitionsAreTranslatedWithTheModuleCatalog() = I18n.withLanguage("en") {
        val definition = ModuleDefinition(
            id = "i18n_ns_alpha", displayName = shared, description = coreText,
            views = listOf(ModuleViewDefinition("home", shared, "/x", "list")),
        )
        val english = definition.localized()
        assertEquals("High", english.displayName)
        assertEquals("Alpha session text", english.description)
        assertEquals("High", english.views.single().title)
        assertEquals("New item", definition.copy(id = "i18n_ns_beta").localized().displayName)
    }
}
