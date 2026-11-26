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

/*
 * Option labels and default empty texts are translated with the catalog of the package that asks
 * (I18N.md §2): [namespace] null means the namespace of the calling class (I18n.callerNamespace), so a
 * module's `localizedPayload(result)` uses that module's catalog first.
 */

/** The screen with option labels and default empty texts in the current language. */
fun UiScreen.localized(namespace: String? = null): UiScreen {
    if (I18n.language() == I18n.Source) return this
    val ns = namespace ?: I18n.callerNamespace()
    return copy(body = body.map { localizeNode(it, ns) })
}

/** The piece (and its children) with option labels and default empty texts in the current language. */
fun UiNode.localized(namespace: String? = null): UiNode {
    if (I18n.language() == I18n.Source) return this
    return localizeNode(this, namespace ?: I18n.callerNamespace())
}

/** Suggestions carry no options nor default texts: returned as they are (for uniform response helpers). */
fun UiSuggestions.localized(@Suppress("UNUSED_PARAMETER") namespace: String? = null): UiSuggestions = this

/** The field with [UiField.optionLabels] filled (when it has none) with the translation of each option. */
fun UiField.localized(namespace: String? = null): UiField {
    if (I18n.language() == I18n.Source || options.isEmpty() || optionLabels != null) return this
    return localizeField(this, namespace ?: I18n.callerNamespace())
}

/**
 * [UiScreen.localized], [UiNode.localized] or [UiSuggestions.localized] by the runtime type of
 * [payload], also inside lists; any other value is returned unchanged. For generic response helpers.
 */
@Suppress("UNCHECKED_CAST")
fun <T> localizedPayload(payload: T, namespace: String? = null): T {
    if (I18n.language() == I18n.Source) return payload
    return localizeAny(payload, namespace ?: I18n.callerNamespace()) as T
}

/**
 * [localizedPayload] with the catalog of the package that declares [owner]'s class: for a host module
 * that shows nodes an extension built (`localizedFor(source, source.screen(...))`), so the extension's
 * select options and default texts are translated as the extension translates them, not as the host.
 */
fun <T> localizedFor(owner: Any, payload: T): T {
    if (I18n.language() == I18n.Source) return payload
    return localizedPayload(payload, I18n.namespaceOf(owner.javaClass))
}

private fun localizeAny(payload: Any?, namespace: String): Any? = when (payload) {
    is UiScreen -> payload.copy(body = payload.body.map { localizeNode(it, namespace) })
    is UiNode -> localizeNode(payload, namespace)
    is List<*> -> payload.map { localizeAny(it, namespace) }
    else -> payload
}

private fun localizeField(field: UiField, namespace: String): UiField {
    if (field.options.isEmpty() || field.optionLabels != null) return field
    val labels = field.options.map { I18n.translateIn(namespace, it) }
    return if (labels == field.options) field else field.copy(optionLabels = labels)
}

private fun localizeNode(node: UiNode, namespace: String): UiNode {
    val children = { nodes: List<UiNode> -> nodes.map { localizeNode(it, namespace) } }
    return when (node) {
        is UiColumn -> node.copy(children = children(node.children))
        is UiRow -> node.copy(children = children(node.children))
        is UiGrid -> node.copy(children = children(node.children))
        is UiCard -> node.copy(children = children(node.children), actions = children(node.actions))
        is UiForm -> node.copy(fields = node.fields.map { localizeField(it, namespace) })
        is UiTable -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoData, namespace))
        is UiList -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoData, namespace))
        is UiTimeline -> node.copy(emptyText = defaultText(node.emptyText, DefaultNoActivity, namespace))
        is UiBoard -> node.copy(
            emptyText = defaultText(node.emptyText, DefaultNoItems, namespace),
            columns = node.columns.map { column -> column.copy(footer = column.footer?.let { localizeNode(it, namespace) as UiButton }) },
        )
        else -> node
    }
}

// Default empty texts of the contract (ModuleUi.kt); a module's own empty text is translated by the module.
private val DefaultNoData = msg("Sin datos.")
private val DefaultNoItems = msg("Sin elementos.")
private val DefaultNoActivity = msg("Sin actividad.")

private fun defaultText(text: String, default: String, namespace: String): String =
    if (text == default) I18n.translateIn(namespace, default) else text

// ---- Metadata (I18N.md §6): definitions stay in Spanish and are translated when they are sent ----

/** The definition with its texts (name, description, views, fields, actions, permissions) in the current language, from the module's catalog. */
fun ModuleDefinition.localized(): ModuleDefinition {
    if (I18n.language() == I18n.Source) return this
    val ns = I18n.moduleNamespace(id)
    return copy(
        displayName = I18n.translateIn(ns, displayName),
        description = I18n.translateIn(ns, description),
        views = views.map { it.localized(ns) },
        permissions = permissions.map { it.localized(ns) },
    )
}

fun ModuleViewDefinition.localized(namespace: String): ModuleViewDefinition = copy(
    title = I18n.translateIn(namespace, title),
    fields = fields.map { it.localized(namespace) },
    actions = actions.map { it.localized(namespace) },
)

/** Labels are translated; select options are values and stay as they are. */
fun ModuleFieldDefinition.localized(namespace: String): ModuleFieldDefinition =
    copy(label = I18n.translateIn(namespace, label), help = I18n.translateIn(namespace, help), section = I18n.translateIn(namespace, section))

fun ModuleActionDefinition.localized(namespace: String): ModuleActionDefinition = copy(title = I18n.translateIn(namespace, title))

fun ModulePermissionDefinition.localized(namespace: String): ModulePermissionDefinition =
    copy(title = I18n.translateIn(namespace, title), description = I18n.translateIn(namespace, description))

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
