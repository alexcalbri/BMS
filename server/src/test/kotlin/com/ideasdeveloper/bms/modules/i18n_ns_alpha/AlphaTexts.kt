package com.ideasdeveloper.bms.modules.i18n_ns_alpha

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Code of a demo module "i18n_ns_alpha" for I18nNamespaceTest: every tr here resolves to that module. */
object AlphaTexts {
    fun direct(source: String): String = tr(source)

    // A helper in the same package that translates on behalf of other code of the package.
    private fun label(source: String) = tr(source)

    fun throughHelper(source: String): String = label(source)

    fun nestedLambdas(source: String): String =
        listOf(source).map { text -> run { lazy { sequenceOf(text).map { tr(it) }.first() }.value } }.single()

    fun inThread(source: String): String {
        var result = ""
        val language = I18n.language()
        val thread = Thread { result = I18n.withLanguage(language) { tr(source) } }
        thread.start()
        thread.join()
        return result
    }

    fun inCoroutine(source: String): String = runBlocking(I18n.contextElement(I18n.language())) {
        withContext(Dispatchers.Default) { tr(source) }
    }

    fun translate(source: String): String = I18n.translate(source)

    fun localizedForm(vararg options: String): UiForm =
        localizedPayload(UiForm("f", listOf(UiField("k", "K", type = "select", options = options.toList()))))
}
