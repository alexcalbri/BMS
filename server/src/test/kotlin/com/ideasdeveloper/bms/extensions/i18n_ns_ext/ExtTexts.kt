package com.ideasdeveloper.bms.extensions.i18n_ns_ext

import com.ideasdeveloper.bms.modules.i18n_ns_alpha.AlphaTexts
import com.ideasdeveloper.bms.server.i18n.tr

/** Code of a demo extension "i18n_ns_ext" (hosted by i18n_ns_alpha) for I18nNamespaceTest. */
object ExtTexts {
    fun direct(source: String): String = tr(source)

    // The host module's helper still translates as the host module.
    fun viaHost(source: String): String = AlphaTexts.direct(source)
}
