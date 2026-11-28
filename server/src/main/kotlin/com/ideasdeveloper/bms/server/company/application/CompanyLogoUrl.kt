package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import java.net.URI
import java.net.URISyntaxException

/**
 * The rule for the logo URL of a company: every client of that company loads it, so it is an outbound channel (a beacon
 * that tells whoever runs the URL who opens the app and from where). https only, at most [MaxLength] characters, a host,
 * no whitespace or control characters and no credentials in the URL. Checked when a logo is created or CHANGED (never on a
 * value already stored: an older logo that no longer meets it keeps working until somebody changes it).
 */
object CompanyLogoUrl {
    const val MaxLength = 300

    /** Why [url] (already trimmed, not blank) is refused, or null when it is fine. */
    fun problem(url: String): String? {
        if (url.length > MaxLength) return tr("La URL del logo no puede tener mas de {0} caracteres.", MaxLength)
        if (url.any { it.isWhitespace() || it.isISOControl() }) return tr("La URL del logo no es valida.")
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return tr("La URL del logo no es valida.")
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return tr("La URL del logo debe empezar por https://.")
        // userInfo is null for a malformed authority too, so the raw text is checked as well.
        if (uri.userInfo != null || uri.rawAuthority?.contains('@') == true) return tr("La URL del logo no puede llevar usuario ni contrasena.")
        if (uri.host.isNullOrBlank()) return tr("La URL del logo no es valida.")
        return null
    }

    /** [candidate] trimmed, refused with a [CompanyProvisioningException] when it is a new value (not [current]) that breaks the rule. */
    fun checked(candidate: String?, current: String? = null): String? {
        val logo = candidate?.trim()?.takeIf { it.isNotBlank() }
        if (logo != null && logo != current?.trim()) problem(logo)?.let { throw CompanyProvisioningException(it) }
        return logo
    }
}
