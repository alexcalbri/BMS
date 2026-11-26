package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection

/**
 * Dynamic translation of what the staff of a company writes (I18N.md §8): stage names, shown translated as labels, and the
 * notes of a lead, shown with their translation under the original. Core does the work ([com.ideasdeveloper.bms.server.i18n.TextTranslation]);
 * this object only calls it safely: no Core access (tests), another language or any failure shows the originals.
 *
 * Never pass customer data here (names, emails, phones, custom field values, imported content, customer emails).
 */
object CrmTexts {
    /** The names as they read in the viewer's language (same size and order); one call for all of them. */
    fun labels(tenant: Connection, companyCode: String, names: List<String>): List<String> {
        if (names.isEmpty() || I18n.language() == I18n.Source && sourceIsCompanyLanguage(companyCode)) return names
        val access = CrmCustomerEvents.access ?: return names
        return runCatching { access.textTranslation.translate(companyCode, tenant, names) }.getOrDefault(names).takeIf { it.size == names.size } ?: names
    }

    /** The translation to show under each note (null where there is none); one call for all the notes of a screen. */
    fun notes(tenant: Connection, companyCode: String, notes: List<String>): List<String?> {
        val none = notes.map { null }
        if (notes.isEmpty()) return none
        val access = CrmCustomerEvents.access ?: return none
        return runCatching { access.textTranslation.noteTranslations(companyCode, tenant, notes) }.getOrDefault(none).takeIf { it.size == notes.size } ?: none
    }

    /**
     * The entries of a lead's history whose text may be translated: notes written by staff inside BMS (an author user). Imported notes
     * (CSV, monday, forms: no user) are customer or third-party content and are never sent (I18N.md §8).
     */
    fun staffNotes(activities: List<CrmActivity>): List<CrmActivity> =
        activities.filter { it.kind == "note" && it.userId != null && it.body.isNotBlank() }

    /** Languages a manual translation of a name can be written in: the supported ones except the company's own. */
    fun otherLanguages(companyCode: String): List<String> {
        val own = companyLanguage(companyCode)
        return I18n.Languages.filter { it != own }
    }

    /** Name of a language in the viewer's own language, for the labels of the editors. */
    fun languageName(language: String): String = when (language) {
        "en" -> tr("ingles")
        "es" -> tr("espanol")
        else -> language
    }

    /** Manual translations of [names] into [language] (name -> translation), for an editor. */
    fun manual(tenant: Connection, names: List<String>, language: String): Map<String, String> {
        val access = CrmCustomerEvents.access ?: return emptyMap()
        return runCatching { access.textTranslation.manualTranslations(tenant, names, language) }.getOrDefault(emptyMap())
    }

    /** Stores (or, when [translated] is blank, removes) the manual translation of [name] into [language]. */
    fun saveManual(tenant: Connection, name: String, language: String, translated: String) {
        val access = CrmCustomerEvents.access ?: return
        runCatching {
            if (translated.isBlank()) access.textTranslation.removeManual(tenant, name, language)
            else access.textTranslation.setManual(tenant, name, language, translated)
        }
    }

    private fun companyLanguage(companyCode: String): String =
        runCatching { CrmCustomerEvents.access?.companyLanguage(companyCode) }.getOrNull() ?: I18n.Source

    private fun sourceIsCompanyLanguage(companyCode: String) = companyLanguage(companyCode) == I18n.Source
}
