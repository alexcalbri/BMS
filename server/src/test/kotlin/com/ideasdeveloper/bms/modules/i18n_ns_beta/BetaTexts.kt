package com.ideasdeveloper.bms.modules.i18n_ns_beta

import com.ideasdeveloper.bms.server.i18n.tr

/** Code of a demo module "i18n_ns_beta" for I18nNamespaceTest. */
class BetaTexts {
    fun direct(source: String): String = tr(source)

    companion object {
        fun fromCompanion(source: String): String = tr(source)
    }
}
