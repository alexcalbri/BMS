package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.modules.ModuleActionDefinition
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleFieldDefinition
import com.ideasdeveloper.bms.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.bms.server.modules.ModuleViewDefinition
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiSuggestions
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTimeline

/*
 * Response-time localization of server-driven UI (I18N.md §5). Labels are translated explicitly with
 * tr where modules create them; localized() only does what a module cannot do by itself without
 * repeating code: it fills UiField.optionLabels with the translation of each option (values stay in
 * the source language and are what the app submits) and translates the default empty texts of the
 * contract. With the source language it returns the payload unchanged.
 *
 * Call it in the module's response helper right before UiJson.encodeToString(...):
 *
 *     respondText(UiJson.encodeToString(serializer, localizedPayload(result)), ContentType.Application.Json)
 */

/** The screen with option labels and default empty texts in the current language. */
fun UiScreen.localized(): UiScreen {
    if (I18n.language() == I18n.Source) return this
    return copy(body = body.map { it.localized() })
}

/** The piece (and its children) with option labels and default empty texts in the current language. */
fun UiNode.localized(): UiNode {
    if (I18n.language() == I18n.Source) return this
    return localizeNode(this)
}

/** Suggestions carry no options nor default texts: returned as they are (for uniform response helpers). */
fun UiSuggestions.localized(): UiSuggestions = this

/** The field with [UiField.optionLabels] filled (when it has none) with the translation of each option. */
fun UiField.localized(): UiField {
    if (I18n.language() == I18n.Source || options.isEmpty() || optionLabels != null) return this
    val labels = options.map { I18n.translate(it) }
    return if (labels == options) this else copy(optionLabels = labels)
}

/**
 * [UiScreen.localized], [UiNode.localized] or [UiSuggestions.localized] by the runtime type of
 * [payload], also inside lists; any other value is returned unchanged. For generic response helpers.
 */
@Suppress("UNCHECKED_CAST")
fun <T> localizedPayload(payload: T): T {
    if (I18n.language() == I18n.Source) return payload
    return when (payload) {
        is UiScreen -> payload.localized() as T
        is UiNode -> payload.localized() as T
        is UiSuggestions -> payload.localized() as T
        is List<*> -> payload.map { localizedPayload(it) } as T
        else -> payload
    }
}

private fun localizeNode(node: UiNode): UiNode = when (node) {
    is UiColumn -> node.copy(children = node.children.map(::localizeNode))
    is UiRow -> node.copy(children = node.children.map(::localizeNode))
    is UiGrid -> node.copy(children = node.children.map(::localizeNode))
    is UiCard -> node.copy(children = node.children.map(::localizeNode), actions = node.actions.map(::localizeNode))
    is UiForm -> node.copy(fields = node.fields.map { it.localized() })
    is UiTable -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoData))
    is UiList -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoData))
    is UiTimeline -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoActivity))
    is UiBoard -> node.copy(
        emptyText = defaultText(node.emptyText, DefaultNoItems),
        columns = node.columns.map { column -> column.copy(footer = column.footer?.let { localizeNode(it) as UiButton }) },
    )
    else -> node
}

// Default empty texts of the contract (ModuleUi.kt); a module's own empty text is translated by the module.
private val DefaultNoData = msg("Sin datos.")
private val DefaultNoItems = msg("Sin elementos.")
private val DefaultNoActivity = msg("Sin actividad.")

private fun defaultText(text: String, default: String): String = if (text == default) tr(default) else text

// ---- Metadata (I18N.md §6): definitions stay in Spanish and are translated when they are sent ----

/** The definition with its texts (name, description, views, fields, actions, permissions) in the current language. */
fun ModuleDefinition.localized(): ModuleDefinition {
    if (I18n.language() == I18n.Source) return this
    return copy(
        displayName = tr(displayName),
        description = tr(description),
        views = views.map { it.localized() },
        permissions = permissions.map { it.localized() },
    )
}

fun ModuleViewDefinition.localized(): ModuleViewDefinition = copy(
    title = tr(title),
    fields = fields.map { it.localized() },
    actions = actions.map { it.localized() },
)

/** Labels are translated; select options are values and stay as they are. */
fun ModuleFieldDefinition.localized(): ModuleFieldDefinition = copy(label = tr(label), help = tr(help), section = tr(section))

fun ModuleActionDefinition.localized(): ModuleActionDefinition = copy(title = tr(title))

fun ModulePermissionDefinition.localized(): ModulePermissionDefinition = copy(title = tr(title), description = tr(description))

/**
 * Every text of [definition] that Core translates at response time, for a package's catalog check
 * (I18nCatalogCheck): its name, description, view titles, field labels/help/sections, action titles
 * and permission titles/descriptions. Blank texts are left out.
 */
fun definitionTexts(definition: ModuleDefinition): Set<String> = buildSet {
    add(definition.displayName)
    add(definition.description)
    definition.views.forEach { view ->
        add(view.title)
        view.fields.forEach { field -> add(field.label); add(field.help); add(field.section) }
        view.actions.forEach { add(it.title) }
    }
    definition.permissions.forEach { add(it.title); add(it.description) }
}.filter { it.isNotBlank() }.toSet()
