package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The automation form as plain values: the rule is edited in a form with a fixed number of condition and action "slots"
 * (a slot with no kind is ignored). Reading and writing it needs no database, so it is tested without one.
 */
object CrmAutomationForm {
    const val None = "(ninguna)"
    val WhenBefore = msg("Antes de la fecha")
    val WhenOn = msg("El dia de la fecha")
    val WhenAfter = msg("Despues de la fecha")

    /** Slots shown for a rule: its own plus one to add, at least [MinSlots]. The agents can save more than the form shows. */
    const val MinSlots = 3
    const val MaxSlots = 10

    fun slots(count: Int) = (count + 1).coerceIn(MinSlots, MaxSlots)

    private fun text(values: Map<String, String>, key: String) = values[key].orEmpty().trim()

    private fun names(values: Map<String, String>, key: String) = UiFieldValues.options(values[key].orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }

    private fun wholeNumber(raw: String, what: String, default: Int): Int =
        raw.ifEmpty { default.toString() }.toIntOrNull() ?: throw CrmValidationException(tr("{0} debe ser un numero entero.", what))

    /** Reads the form. Throws [CrmValidationException] (Spanish) for anything the person must fix; ids are checked afterwards by [CrmAutomationRules.validate]. */
    fun parse(values: Map<String, String>, refs: CrmAutomationRefs, id: UUID, position: Int): CrmAutomation {
        val type = CrmTriggerType.byLabel(text(values, "triggerType")) ?: throw CrmValidationException(tr("Elige el disparador de la automatizacion."))
        val trigger = when (type) {
            CrmTriggerType.StageChanged -> CrmTrigger(type, stageId = refs.stage(text(values, "triggerStage")) ?: throw CrmValidationException(tr("Elige la etapa del disparador.")))
            CrmTriggerType.FieldChanged -> CrmTrigger(type, field = refs.field(text(values, "changedField")) ?: throw CrmValidationException(tr("Elige el campo del disparador.")))
            CrmTriggerType.Date -> {
                val days = wholeNumber(text(values, "dateDays"), tr("Los dias"), 0)
                val offset = when (text(values, "dateWhen")) {
                    WhenBefore -> -days
                    WhenAfter -> days
                    else -> 0
                }
                CrmTrigger(
                    type,
                    field = refs.field(text(values, "dateField")) ?: throw CrmValidationException(tr("Elige el campo de fecha del disparador.")),
                    offsetDays = offset,
                    hour = wholeNumber(text(values, "dateHour"), tr("La hora"), 9),
                )
            }
            else -> CrmTrigger(type)
        }
        val conditions = (1..MaxSlots).mapNotNull { slot -> condition(values, refs, slot) }
        val actions = (1..MaxSlots).mapNotNull { slot -> action(values, refs, slot) }
        val warnHours = text(values, "warnHours").ifEmpty { "0" }.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: throw CrmValidationException(tr("El aviso anticipado son horas: un numero de 0 en adelante."))
        return CrmAutomation(
            id = id,
            name = text(values, "name"),
            enabled = values["enabled"] == "true",
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            warnBeforeMinutes = if (type == CrmTriggerType.Date) (warnHours * 60).roundToInt() else 0,
            notifyUserIds = names(values, "notifyUsers").map { refs.user(it) ?: throw CrmValidationException(tr("El usuario {0} ya no tiene acceso al CRM.", it)) },
            position = position,
        ).normalized()
    }

    private fun condition(values: Map<String, String>, refs: CrmAutomationRefs, slot: Int): CrmCondition? {
        val label = text(values, "cond${slot}_kind")
        if (label.isEmpty() || label == None) return null
        val kind = CrmConditionKind.byLabel(label) ?: throw CrmValidationException(tr("Condicion {0}: elige una condicion de la lista.", slot))
        return when (kind) {
            CrmConditionKind.StageIn -> CrmCondition(kind, stageIds = names(values, "cond${slot}_stages").map { refs.stage(it) ?: throw CrmValidationException(tr("Condicion {0}: la etapa {1} ya no existe.", slot, it)) })
            CrmConditionKind.InterestIn -> {
                val chosen = names(values, "cond${slot}_interests")
                CrmCondition(
                    kind,
                    interestIds = chosen.filter { it != CrmForms.NoInterest }.map { refs.interest(it) ?: throw CrmValidationException(tr("Condicion {0}: el interes {1} ya no existe.", slot, it)) },
                    noInterest = CrmForms.NoInterest in chosen,
                )
            }
            CrmConditionKind.FieldEquals, CrmConditionKind.FieldNotEquals, CrmConditionKind.FieldEmpty, CrmConditionKind.FieldFilled -> CrmCondition(
                kind,
                field = refs.field(text(values, "cond${slot}_field")) ?: throw CrmValidationException(tr("Condicion {0}: elige el campo.", slot)),
                value = if (kind.usesValue) text(values, "cond${slot}_value") else "",
            )
            else -> CrmCondition(kind)
        }
    }

    private fun action(values: Map<String, String>, refs: CrmAutomationRefs, slot: Int): CrmAction? {
        val label = text(values, "act${slot}_kind")
        if (label.isEmpty() || label == None) return null
        val kind = CrmActionKind.byLabel(label) ?: throw CrmValidationException(tr("Accion {0}: elige una accion de la lista.", slot))
        return when (kind) {
            CrmActionKind.SendTemplate -> CrmAction(kind, templateId = refs.template(text(values, "act${slot}_template")) ?: throw CrmValidationException(tr("Accion {0}: elige la plantilla.", slot)))
            CrmActionKind.SetStage -> CrmAction(kind, stageId = refs.stage(text(values, "act${slot}_stage")) ?: throw CrmValidationException(tr("Accion {0}: elige la etapa.", slot)))
            CrmActionKind.SetField -> CrmAction(
                kind,
                field = refs.field(text(values, "act${slot}_field")) ?: throw CrmValidationException(tr("Accion {0}: elige el campo.", slot)),
                value = text(values, "act${slot}_value"),
            )
            CrmActionKind.Notify -> CrmAction(
                kind,
                who = CrmNotifyWho.byLabel(text(values, "act${slot}_who")) ?: CrmNotifyWho.Assigned,
                userIds = names(values, "act${slot}_users").map { refs.user(it) ?: throw CrmValidationException(tr("Accion {0}: el usuario {1} ya no tiene acceso al CRM.", slot, it)) },
                title = text(values, "act${slot}_title"),
                text = text(values, "act${slot}_text"),
            )
            else -> CrmAction(kind)
        }
    }

    /** Form values of [automation] (null: a new rule). */
    fun values(automation: CrmAutomation?, refs: CrmAutomationRefs): Map<String, String> {
        val values = mutableMapOf(
            "name" to automation?.name.orEmpty(),
            "enabled" to (automation?.enabled ?: false).toString(),
            "triggerType" to (automation?.trigger?.type ?: CrmTriggerType.LeadCreated).label,
            "dateWhen" to WhenBefore,
            "dateDays" to "1",
            "dateHour" to "9",
            "warnHours" to "0",
        )
        if (automation == null) return values
        val trigger = automation.trigger
        when (trigger.type) {
            CrmTriggerType.StageChanged -> values["triggerStage"] = refs.stageName(trigger.stageId)
            CrmTriggerType.FieldChanged -> values["changedField"] = refs.fieldLabel(trigger.field)
            CrmTriggerType.Date -> {
                values["dateField"] = refs.fieldLabel(trigger.field)
                values["dateWhen"] = when {
                    trigger.offsetDays < 0 -> WhenBefore
                    trigger.offsetDays > 0 -> WhenAfter
                    else -> WhenOn
                }
                values["dateDays"] = kotlin.math.abs(trigger.offsetDays).toString()
                values["dateHour"] = trigger.hour.toString()
                values["warnHours"] = hours(automation.warnBeforeMinutes)
            }
            else -> Unit
        }
        values["notifyUsers"] = UiFieldValues.options(automation.notifyUserIds.mapNotNull { id -> refs.users.firstOrNull { it.id == id }?.name })
        automation.conditions.forEachIndexed { index, condition ->
            val slot = index + 1
            values["cond${slot}_kind"] = condition.kind.label
            when (condition.kind) {
                CrmConditionKind.StageIn -> values["cond${slot}_stages"] = UiFieldValues.options(condition.stageIds.map { refs.stageName(it) })
                CrmConditionKind.InterestIn -> values["cond${slot}_interests"] = UiFieldValues.options(
                    condition.interestIds.mapNotNull { id -> refs.interests.firstOrNull { it.id == id }?.name } + listOfNotNull(CrmForms.NoInterest.takeIf { condition.noInterest }),
                )
                else -> if (condition.kind.usesField) {
                    values["cond${slot}_field"] = refs.fieldLabel(condition.field)
                    values["cond${slot}_value"] = condition.value
                }
            }
        }
        automation.actions.forEachIndexed { index, action ->
            val slot = index + 1
            values["act${slot}_kind"] = action.kind.label
            when (action.kind) {
                CrmActionKind.SendTemplate -> values["act${slot}_template"] = refs.templates.firstOrNull { it.id == action.templateId }?.name.orEmpty()
                CrmActionKind.SetStage -> values["act${slot}_stage"] = refs.stageName(action.stageId)
                CrmActionKind.SetField -> {
                    values["act${slot}_field"] = refs.fieldLabel(action.field)
                    values["act${slot}_value"] = action.value
                }
                CrmActionKind.Notify -> {
                    values["act${slot}_who"] = action.who.label
                    values["act${slot}_users"] = UiFieldValues.options(action.userIds.mapNotNull { id -> refs.users.firstOrNull { it.id == id }?.name })
                    values["act${slot}_title"] = action.title
                    values["act${slot}_text"] = action.text
                }
                else -> Unit
            }
        }
        return values
    }

    /** Minutes as hours for the form: 2880 -> "48", 90 -> "1.5". */
    fun hours(minutes: Int): String = if (minutes % 60 == 0) (minutes / 60).toString() else (minutes / 60.0).toString()
}

/** The "Automatizaciones" tab of Configuracion: the list of rules, the rule form and the recent runs. */
object CrmAutomationScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** [message] to show after saving; [warning] when the notice carries a warning (duplicate email, for example). */
    data class Saved(val message: String, val warning: Boolean)

    fun refsOf(context: CrmScreens.Context): CrmAutomationRefs = CrmAutomationRefs(
        stages = context.stages,
        interests = context.interests,
        templates = CrmMail.templates(context.tenant),
        users = context.users,
        fields = context.fields,
        cloudbedsReady = CrmModuleExtensions.hasAction("cloudbeds_reserve") && CrmModuleExtensions.cloudbedsActive(context.tenant),
    )

    private fun welcomeActive(context: CrmScreens.Context): Boolean = CrmModuleExtensions.cloudbedsActive(context.tenant) && CrmHost.cloudbedsSettings(context.tenant).welcomeEnabled

    private fun stayKeys(context: CrmScreens.Context): List<String> =
        CrmStay.effective(context.stay, context.fields).let { listOf(it.startKey, it.endKey).filter { key -> key.isNotEmpty() } }

    // ---- Saving ----

    fun save(context: CrmScreens.Context, id: UUID?, values: Map<String, String>): Saved {
        val tenant = context.tenant
        val refs = refsOf(context)
        val existing = CrmAutomationStore.all(tenant)
        val current = id?.let { wanted -> existing.firstOrNull { it.id == wanted } }
        if (id != null && current == null) throw CrmValidationException(tr("Esa automatizacion ya no existe."))
        val automation = CrmAutomationForm.parse(values, refs, current?.id ?: UUID.randomUUID(), current?.position ?: CrmAutomationStore.nextPosition(tenant))
        CrmAutomationRules.validate(automation, refs, existing)
        CrmAutomationStore.save(tenant, automation, exists = current != null)
        return saved(context, automation, tr("Automatizacion {0} guardada.", automation.name))
    }

    /** Turns a rule on or off. Turning on checks it again: what it points to may be gone. */
    fun setEnabled(context: CrmScreens.Context, automation: CrmAutomation, enabled: Boolean): Saved {
        val changed = automation.copy(enabled = enabled)
        if (enabled) CrmAutomationRules.validate(changed, refsOf(context), CrmAutomationStore.all(context.tenant))
        if (!CrmAutomationStore.setEnabled(context.tenant, automation.id, enabled)) throw CrmValidationException(tr("Esa automatizacion ya no existe."))
        return saved(context, changed, tr("Automatizacion {0} {1}.", automation.name, if (enabled) "activada" else "apagada"))
    }

    private fun saved(context: CrmScreens.Context, automation: CrmAutomation, message: String): Saved {
        val duplicate = CrmAutomationRules.duplicatesCloudbedsWelcome(automation, stayKeys(context), welcomeActive(context))
        val warnings = listOfNotNull(
            tr("{0} Puedes apagar la bienvenida de Cloudbeds en esta pantalla.", tr(CrmAutomationRules.DuplicateWelcomeWarning)).takeIf { duplicate },
            CrmAutomationRules.messageReplyNotice(automation),
        )
        return if (warnings.isEmpty()) Saved(message, false) else Saved("$message ${warnings.joinToString(" ")}", true)
    }

    // ---- Screen ----

    fun tab(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val tenant = context.tenant
        val refs = refsOf(context)
        val automations = CrmAutomationStore.all(tenant)
        val editing = params["automation"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> automations.firstOrNull { it.id == id } }
        val runsOf = params["runs"]?.takeIf { it.isNotEmpty() }
        val runsAutomation = runsOf?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val recent = CrmAutomationStore.recent(tenant, runsAutomation, if (runsOf != null) 100 else 30)
        val byAutomation = CrmAutomationStore.recent(tenant, null, 200).groupBy { it.automationId }
        val zone = CrmZone.of(tenant)
        val duplicates = automations.any { CrmAutomationRules.duplicatesCloudbedsWelcome(it, stayKeys(context), welcomeActive(context)) }
        return listOfNotNull(
            UiText(tr("Cada regla tiene un disparador, condiciones opcionales y acciones en orden. Quedan registradas en Ejecuciones recientes y en la actividad del lead."), UiTextStyle.Caption),
            if (duplicates) UiCard(
                listOf(
                    UiText(tr(CrmAutomationRules.DuplicateWelcomeWarning), tone = UiTone.Warning),
                    UiButton(
                        tr("Apagar la bienvenida de Cloudbeds"),
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "welcomeOff"), confirm = tr("Cloudbeds dejara de enviar su correo de bienvenida. Continuar?")),
                        style = "secondary",
                    ),
                ),
                title = tr("Correos duplicados"),
            ) else null,
            listCard(automations, refs, byAutomation),
            formCard(context, refs, editing, submitted),
            runsCard(recent, runsOf != null, runsAutomation?.let { id -> automations.firstOrNull { it.id == id }?.name }, zone),
        )
    }

    private fun listCard(automations: List<CrmAutomation>, refs: CrmAutomationRefs, runs: Map<UUID, List<CrmRunRow>>): UiNode {
        val rows: List<UiNode> = automations.map { automation ->
            val last = runs[automation.id].orEmpty().take(3).joinToString(", ") { CrmAutomationStore.statusLabel(it.status) }
            val summary = CrmAutomationSummary.trigger(automation.trigger, refs) + ": " + automation.actions.joinToString(", ") { CrmAutomationSummary.action(it, refs) }
            UiRow(
                listOf(
                    UiText("${automation.name} - $summary"),
                    if (automation.enabled) UiStatus(tr("Activa"), UiTone.Success) else UiStatus(tr("Apagada"), UiTone.Neutral),
                    UiText(if (last.isEmpty()) tr("Sin ejecuciones") else tr("Ultimas: {0}", last), UiTextStyle.Caption),
                    UiButton(
                        if (automation.enabled) tr("Apagar") else tr("Activar"),
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "toggle", "automation" to automation.id.toString(), "enabled" to (!automation.enabled).toString())),
                        style = "secondary",
                    ),
                    UiButton(tr("Editar"), UiNavigate("automations", mapOf("automation" to automation.id.toString())), style = "secondary"),
                    UiButton(tr("Ejecuciones"), UiNavigate("automations", mapOf("runs" to automation.id.toString())), style = "secondary"),
                    UiButton(
                        tr("Eliminar"),
                        UiRequest(UiRoute + "/automations", params = mapOf("op" to "delete", "automation" to automation.id.toString()), confirm = tr("Eliminar la automatizacion {0} y su registro de ejecuciones?", automation.name)),
                        style = "danger",
                    ),
                ),
            )
        }
        return UiCard(rows.ifEmpty { listOf(UiText(tr("Aun no hay automatizaciones."), tone = UiTone.Neutral)) }, title = tr("Automatizaciones"))
    }

    private fun formCard(context: CrmScreens.Context, refs: CrmAutomationRefs, editing: CrmAutomation?, submitted: Map<String, String>?): UiNode {
        val values = CrmAutomationForm.values(editing, refs) + submitted.orEmpty()
        val conditionSlots = CrmAutomationForm.slots(editing?.conditions?.size ?: 0)
        val actionSlots = CrmAutomationForm.slots(editing?.actions?.size ?: 0)
        val stageNames = refs.stages.map { it.name }
        val stageLabels = context.optionLabels(stageNames)
        val userNames = refs.users.map { it.name }
        val valueFields = refs.fields.filter { it.type != CrmFieldType.Files && it.type != CrmFieldType.Button }
        val dateFields = listOf(CrmAutomationRefs.FollowUpLabel) + refs.fields.filter { it.type == CrmFieldType.Date }.map { it.label }
        val settableFields = listOf(CrmAutomationRefs.FollowUpLabel) + valueFields.map { it.label }
        val cloudbeds = refs.cloudbedsReady
        val actionKinds = CrmActionKind.entries.filter { cloudbeds || !it.isCloudbeds || editing?.actions?.any { action -> action.kind == it } == true }

        fun on(key: String, vararg labels: String) = UiFieldCondition(key, labels.toList())
        val none = CrmAutomationForm.None
        val trigger = listOf(
            UiField("name", tr("Nombre"), required = true),
            UiField("enabled", tr("Activa"), type = "boolean", help = tr("Una regla apagada no se ejecuta. Las de fecha solo corren para las fechas de hoy en adelante.")),
            UiField("triggerType", tr("Disparador"), type = "dropdown", required = true, options = CrmTriggerType.entries.map { it.label }, help = tr("Manual: solo se ejecuta desde un boton del lead (campo de tipo Boton).")),
            UiField("triggerStage", tr("Etapa"), type = "dropdown", options = stageNames, optionLabels = stageLabels, visibleWhen = on("triggerType", CrmTriggerType.StageChanged.label)),
            UiField("changedField", tr("Campo que cambia"), type = "dropdown", options = listOf(CrmAutomationRefs.FollowUpLabel) + valueFields.map { it.label }, visibleWhen = on("triggerType", CrmTriggerType.FieldChanged.label)),
            UiField("dateField", tr("Fecha"), type = "dropdown", options = dateFields, visibleWhen = on("triggerType", CrmTriggerType.Date.label), help = tr("Un campo de fecha de la ficha (ingreso, salida...) o la fecha de seguimiento.")),
            UiField("dateWhen", tr("Cuando"), type = "dropdown", options = listOf(CrmAutomationForm.WhenBefore, CrmAutomationForm.WhenOn, CrmAutomationForm.WhenAfter), visibleWhen = on("triggerType", CrmTriggerType.Date.label)),
            UiField("dateDays", tr("Dias (0 a 60)"), type = "number", visibleWhen = on("dateWhen", CrmAutomationForm.WhenBefore, CrmAutomationForm.WhenAfter)),
            UiField("dateHour", tr("Hora del dia (0 a 23, zona horaria de la empresa)"), type = "number", visibleWhen = on("triggerType", CrmTriggerType.Date.label)),
        )
        val notices = listOf(
            UiField(
                "warnHours", tr("Avisar con anticipacion (horas; 0 no avisa)"), type = "number", section = tr("Datos que faltan"), visibleWhen = on("triggerType", CrmTriggerType.Date.label),
                help = tr("Reglas de fecha: con esta anticipacion se revisa si al lead le falta algo para la ejecucion (48 = 2 dias antes) y se avisa que falta."),
            ),
            UiField(
                "notifyUsers", tr("Avisar a"), type = "dropdown", multiple = true, options = userNames, section = tr("Datos que faltan"),
                help = tr("Quienes reciben el aviso de datos que faltan. Vacio: los asignados del lead."),
            ),
        )
        val conditions = (1..conditionSlots).flatMap { slot ->
            val section = tr("Condicion {0}", slot)
            val kind = "cond${slot}_kind"
            listOf(
                UiField(kind, tr("Condicion"), type = "dropdown", options = listOf(none) + CrmConditionKind.entries.map { it.label }, section = section, help = if (slot == 1) tr("Todas las condiciones deben cumplirse. Sin condiciones, siempre corre.") else ""),
                UiField("cond${slot}_stages", tr("Etapas"), type = "dropdown", multiple = true, options = stageNames, optionLabels = stageLabels, section = section, visibleWhen = on(kind, CrmConditionKind.StageIn.label)),
                UiField(
                    "cond${slot}_interests", tr("Intereses"), type = "dropdown", multiple = true, options = listOf(CrmForms.NoInterest) + refs.interests.map { it.name }, section = section,
                    visibleWhen = on(kind, CrmConditionKind.InterestIn.label),
                ),
                UiField(
                    "cond${slot}_field", tr("Campo"), type = "dropdown", options = valueFields.map { it.label }, section = section,
                    visibleWhen = on(kind, CrmConditionKind.FieldEquals.label, CrmConditionKind.FieldNotEquals.label, CrmConditionKind.FieldEmpty.label, CrmConditionKind.FieldFilled.label),
                ),
                UiField(
                    "cond${slot}_value", tr("Valor"), section = section, visibleWhen = on(kind, CrmConditionKind.FieldEquals.label, CrmConditionKind.FieldNotEquals.label),
                    help = tr("En campos Si / No escribe Si o No."),
                ),
            )
        }
        val actions = (1..actionSlots).flatMap { slot ->
            val section = tr("Accion {0}", slot)
            val kind = "act${slot}_kind"
            listOf(
                UiField(kind, tr("Accion"), type = "dropdown", options = listOf(none) + actionKinds.map { it.label }, section = section, help = if (slot == 1) tr("Se ejecutan en orden y se detienen en la primera que falle. Cloudbeds aparece con la integracion activa.") else ""),
                UiField("act${slot}_template", tr("Plantilla"), type = "dropdown", options = refs.templates.map { it.name }, section = section, visibleWhen = on(kind, CrmActionKind.SendTemplate.label), help = tr("Se envia al correo del lead (traducida si la plantilla lo pide).")),
                UiField("act${slot}_stage", tr("Etapa"), type = "dropdown", options = stageNames, optionLabels = stageLabels, section = section, visibleWhen = on(kind, CrmActionKind.SetStage.label)),
                UiField("act${slot}_field", tr("Campo"), type = "dropdown", options = settableFields, section = section, visibleWhen = on(kind, CrmActionKind.SetField.label)),
                UiField(
                    "act${slot}_value", tr("Valor"), section = section, visibleWhen = on(kind, CrmActionKind.SetField.label),
                    help = tr("Vacio borra el campo. Fechas AAAA-MM-DD; Si / No como true o false; listas con una opcion exacta."),
                ),
                UiField("act${slot}_who", tr("Avisar a"), type = "dropdown", options = CrmNotifyWho.entries.map { it.label }, section = section, visibleWhen = on(kind, CrmActionKind.Notify.label)),
                UiField("act${slot}_users", tr("Usuarios"), type = "dropdown", multiple = true, options = userNames, section = section, visibleWhen = on("act${slot}_who", CrmNotifyWho.Users.label)),
                UiField("act${slot}_title", tr("Titulo del aviso"), section = section, visibleWhen = on(kind, CrmActionKind.Notify.label)),
                UiField("act${slot}_text", tr("Texto del aviso"), type = "textarea", section = section, visibleWhen = on(kind, CrmActionKind.Notify.label), help = tr("Llega a la app y al correo de cada usuario.")),
            )
        }
        val open = buildList {
            if (values["warnHours"].orEmpty() != "0" || values["notifyUsers"].orEmpty().isNotEmpty()) add(tr("Datos que faltan"))
            (1..conditionSlots).forEach { slot -> if (values["cond${slot}_kind"].orEmpty().let { it.isNotEmpty() && it != none }) add(tr("Condicion {0}", slot)) }
            (1..actionSlots).forEach { slot -> if (slot == 1 || values["act${slot}_kind"].orEmpty().let { it.isNotEmpty() && it != none }) add(tr("Accion {0}", slot)) }
        }
        val saveParams = mapOf("op" to "save") + listOfNotNull(editing?.let { "automation" to it.id.toString() })
        return UiCard(
            listOf(
                UiForm(
                    "automation", trigger + notices + conditions + actions, values,
                    submit = UiButton(if (editing == null) tr("Crear automatizacion") else tr("Guardar automatizacion"), UiRequest(UiRoute + "/automations", params = saveParams, formId = "automation")),
                    minFieldWidth = 220,
                    collapsibleSections = true,
                    openSections = open,
                ),
            ),
            title = if (editing == null) tr("Nueva automatizacion") else tr("Editar {0}", editing.name),
            actions = listOfNotNull(editing?.let { UiButton(tr("Nueva"), UiNavigate("automations"), style = "secondary") }),
        )
    }

    private fun runsCard(runs: List<CrmRunRow>, asked: Boolean, automationName: String?, zone: java.time.ZoneId): UiNode {
        val items = runs.map { run ->
            UiListItem(
                title = "${run.automationName} - ${run.leadName}",
                subtitle = run.detail,
                trailing = timeFormat.format(run.createdAt.atZone(zone)),
                badges = listOf(UiBadge(CrmAutomationStore.statusLabel(run.status), tone(run.status))),
                action = UiNavigate("lead", mapOf("id" to run.leadId.toString())),
            )
        }
        return UiCard(
            listOf<UiNode>(UiList(items, emptyText = tr("Aun no hay ejecuciones."))) +
                listOfNotNull(UiButton(tr("Ver todas"), UiNavigate("automations", mapOf("runs" to "all")), style = "secondary").takeIf { automationName != null }),
            title = if (automationName != null) tr("Ejecuciones recientes: {0}", automationName) else tr("Ejecuciones recientes"),
            collapsible = true,
            expanded = asked,
        )
    }

    private fun tone(status: String) = when (status) {
        CrmRunOutcome.Done -> UiTone.Success
        CrmRunOutcome.Failed -> UiTone.Danger
        CrmRunOutcome.MissingData, CrmRunOutcome.Warned -> UiTone.Warning
        else -> UiTone.Neutral
    }
}
