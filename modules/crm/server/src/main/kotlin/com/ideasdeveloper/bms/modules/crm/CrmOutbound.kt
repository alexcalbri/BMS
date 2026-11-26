package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.outbound.OutboundEvent
import com.ideasdeveloper.bms.server.outbound.OutboundEventKind
import com.ideasdeveloper.bms.server.outbound.OutboundFieldDef
import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundFile
import com.ideasdeveloper.bms.server.outbound.OutboundOrigin
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundQueue
import com.ideasdeveloper.bms.server.outbound.OutboundRecord
import com.ideasdeveloper.bms.server.outbound.OutboundRecordType
import com.ideasdeveloper.bms.server.outbound.OutboundSink
import com.ideasdeveloper.bms.server.outbound.OutboundSource
import com.ideasdeveloper.bms.server.outbound.OutboundValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Claves de los campos del lead que el CRM entrega a los servicios externos (OutboundFieldDef.key). */
object CrmOutboundKeys {
    const val Name = "name"
    const val Email = "email"
    const val Phone = "phone"
    const val Stage = "stage"
    const val Interest = "interest"
    const val Owners = "owners"
    const val FollowUp = "followUp"
    const val Source = "source"
    const val Language = "language"
    const val Message = "message"
    const val Details = "details"
    const val EstimatedValue = "estimatedValue"
    const val Stay = "stay"
    const val CreatedAt = "createdAt"
    const val CustomPrefix = "custom:"

    fun custom(fieldKey: String) = CustomPrefix + fieldKey
}

/**
 * Lo que el CRM expone de sus leads para enviarlos a un servicio externo (ver OutboundContract en el Core): tipo de
 * registro "lead", sus campos (los personalizados incluidos), el estado actual de un lead y sus archivos.
 * Solo lectura (salvo [linked], que guarda la clave externa); la logica pura va en funciones aparte para probarla.
 */
object CrmOutboundSource : OutboundSource {
    const val LeadType = "lead"
    private val log = LoggerFactory.getLogger(CrmOutboundSource::class.java)

    override val moduleId: String = CrmModule.id

    // ---- Campos (puro) ----

    /** Tipo de salida de un campo personalizado; null si no se envia (archivos y botones). */
    fun customType(field: CrmField): OutboundFieldType? = when (field.type) {
        CrmFieldType.Text -> OutboundFieldType.Text
        CrmFieldType.Textarea -> OutboundFieldType.LongText
        CrmFieldType.Number -> OutboundFieldType.Number
        CrmFieldType.Email -> OutboundFieldType.Email
        CrmFieldType.Phone -> OutboundFieldType.Phone
        CrmFieldType.Url -> OutboundFieldType.Url
        CrmFieldType.Date -> OutboundFieldType.Date
        CrmFieldType.Boolean -> OutboundFieldType.Boolean
        CrmFieldType.Dropdown -> if (field.multiple) OutboundFieldType.Options else OutboundFieldType.Status
        CrmFieldType.Files, CrmFieldType.Button -> null
    }

    /** Los campos del lead: los del CRM y, despues, los personalizados de la empresa. */
    fun leadFields(stages: List<CrmStage>, interests: List<CrmInterest>, custom: List<CrmField>): List<OutboundFieldDef> = buildList {
        add(OutboundFieldDef(CrmOutboundKeys.Name, "Nombre", OutboundFieldType.Text))
        add(OutboundFieldDef(CrmOutboundKeys.Email, "Correo", OutboundFieldType.Email))
        add(OutboundFieldDef(CrmOutboundKeys.Phone, "Telefono", OutboundFieldType.Phone))
        add(OutboundFieldDef(CrmOutboundKeys.Stage, "Etapa", OutboundFieldType.Status, stages.map { it.name }))
        add(OutboundFieldDef(CrmOutboundKeys.Interest, "Interes", OutboundFieldType.Status, interests.map { it.name }))
        add(OutboundFieldDef(CrmOutboundKeys.Owners, "Asignados", OutboundFieldType.Person))
        add(OutboundFieldDef(CrmOutboundKeys.FollowUp, "Seguimiento", OutboundFieldType.Date))
        add(OutboundFieldDef(CrmOutboundKeys.Source, "Origen", OutboundFieldType.Text))
        add(OutboundFieldDef(CrmOutboundKeys.Language, "Idioma", OutboundFieldType.Text))
        add(OutboundFieldDef(CrmOutboundKeys.Message, "Mensaje", OutboundFieldType.LongText))
        add(OutboundFieldDef(CrmOutboundKeys.Details, "Detalles", OutboundFieldType.LongText))
        add(OutboundFieldDef(CrmOutboundKeys.EstimatedValue, "Valor estimado", OutboundFieldType.Number))
        add(OutboundFieldDef(CrmOutboundKeys.Stay, "Estadia", OutboundFieldType.Timeline))
        add(OutboundFieldDef(CrmOutboundKeys.CreatedAt, "Creado", OutboundFieldType.Date))
        custom.forEach { field ->
            val type = customType(field) ?: return@forEach
            add(OutboundFieldDef(CrmOutboundKeys.custom(field.key), field.label, type, if (field.type == CrmFieldType.Dropdown) field.options else emptyList()))
        }
    }

    /** El valor guardado de un campo personalizado, en el tipo de salida del campo; null si esta vacio o no es valido. */
    fun customValue(field: CrmField, stored: JsonElement?): OutboundValue? {
        val type = customType(field) ?: return null
        val items = (stored as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        val text = items?.joinToString(", ") ?: (stored as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return when (type) {
            OutboundFieldType.Options -> (items ?: listOfNotNull(text.ifEmpty { null })).takeIf { it.isNotEmpty() }?.let { OutboundValue.Options(it) }
            OutboundFieldType.Boolean -> (stored as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { v -> v == "true" || v == "si" } }?.let { OutboundValue.Bool(it) }
            OutboundFieldType.Number -> text.toBigDecimalOrNull()?.let { OutboundValue.Number(it) }
            OutboundFieldType.Date -> runCatching { LocalDate.parse(text.take(10)) }.getOrNull()?.takeIf { text.isNotEmpty() }?.let { OutboundValue.Date(it) }
            else -> text.takeIf { it.isNotEmpty() }?.let { OutboundValue.Text(it) }
        }
    }

    /** El lead como registro de salida (puro: recibe ya leidos los nombres de etapa e interes y los responsables). */
    fun recordOf(
        lead: CrmLead,
        stageName: String?,
        interestName: String?,
        owners: List<OutboundPerson>,
        custom: List<CrmField>,
        importKey: String? = null,
    ): OutboundRecord {
        val values = linkedMapOf<String, OutboundValue>()
        fun text(key: String, value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { values[key] = OutboundValue.Text(it) }
        }
        text(CrmOutboundKeys.Name, lead.name)
        text(CrmOutboundKeys.Email, lead.email)
        text(CrmOutboundKeys.Phone, lead.phone)
        text(CrmOutboundKeys.Stage, stageName)
        text(CrmOutboundKeys.Interest, interestName)
        if (owners.isNotEmpty()) values[CrmOutboundKeys.Owners] = OutboundValue.Persons(owners)
        lead.followUpOn?.let { values[CrmOutboundKeys.FollowUp] = OutboundValue.Date(it) }
        text(CrmOutboundKeys.Source, lead.source)
        text(CrmOutboundKeys.Language, lead.language)
        text(CrmOutboundKeys.Message, lead.message)
        text(CrmOutboundKeys.Details, lead.details)
        lead.estimatedValue?.let { values[CrmOutboundKeys.EstimatedValue] = OutboundValue.Number(it) }
        if (lead.targetStart != null || lead.targetEnd != null) values[CrmOutboundKeys.Stay] = OutboundValue.Range(lead.targetStart, lead.targetEnd)
        values[CrmOutboundKeys.CreatedAt] = OutboundValue.Date(lead.createdAt.atOffset(ZoneOffset.UTC).toLocalDate())
        custom.forEach { field ->
            customValue(field, lead.customFields[field.key])?.let { values[CrmOutboundKeys.custom(field.key)] = it }
        }
        val title = lead.name.trim().ifEmpty { lead.email.trim().ifEmpty { lead.phone.trim().ifEmpty { "Lead" } } }
        return OutboundRecord(LeadType, lead.id.toString(), title, values, importKey)
    }

    // ---- Mapeo de la importacion (puro) ----

    /**
     * Destinos de una importacion del CRM -> clave del campo de salida. Los destinos que no son un campo del lead
     * (nota, campos del cliente, ignorar) no aparecen. Las fechas de estadia (ingreso, salida o rango) van a [CrmOutboundKeys.Stay].
     */
    fun destinationKey(destination: String): String? = when {
        destination == "name" -> CrmOutboundKeys.Name
        destination == "email" -> CrmOutboundKeys.Email
        destination == "phone" -> CrmOutboundKeys.Phone
        destination == "stage" -> CrmOutboundKeys.Stage
        destination == "interest" -> CrmOutboundKeys.Interest
        destination == "owner" -> CrmOutboundKeys.Owners
        destination == "followUpOn" -> CrmOutboundKeys.FollowUp
        destination == "source" -> CrmOutboundKeys.Source
        destination == "language" -> CrmOutboundKeys.Language
        destination == "message" -> CrmOutboundKeys.Message
        destination == "details" -> CrmOutboundKeys.Details
        destination == "estimatedValue" -> CrmOutboundKeys.EstimatedValue
        destination == "targetStart" || destination == "targetEnd" || destination == "dates" -> CrmOutboundKeys.Stay
        destination == "createdAt" -> CrmOutboundKeys.CreatedAt
        destination.startsWith(CrmImportTargets.CrmPrefix) && destination.length > CrmImportTargets.CrmPrefix.length ->
            CrmOutboundKeys.custom(destination.removePrefix(CrmImportTargets.CrmPrefix))
        else -> null
    }

    /** Columna de la fuente -> clave del campo de salida, a partir del mapeo de importacion (columna -> destino). */
    fun invertMapping(columns: Map<String, String>): Map<String, String> =
        columns.mapNotNull { (column, destination) -> destinationKey(destination)?.let { column to it } }.toMap()

    // ---- OutboundSource ----

    override fun recordTypes(tenant: Connection): List<OutboundRecordType> =
        listOf(OutboundRecordType(LeadType, "Lead", leadFields(CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmFields.all(tenant))))

    override fun record(tenant: Connection, recordType: String, recordId: String): OutboundRecord? {
        if (recordType != LeadType) return null
        val id = runCatching { UUID.fromString(recordId) }.getOrNull() ?: return null
        // Los archivados tambien se leen: CrmRepository.lead no filtra por archivado y ya trae import_key.
        val lead = CrmRepository.lead(tenant, id) ?: return null
        val stageName = CrmRepository.stages(tenant).firstOrNull { it.id == lead.stageId }?.name
        val interestName = lead.interestId?.let { wanted -> CrmRepository.interests(tenant).firstOrNull { it.id == wanted }?.name }
        val owners = CrmOutbound.resolvePeople(tenant, lead.ownerUserIds)
        return recordOf(lead, stageName, interestName, owners, CrmFields.all(tenant), lead.importKey)
    }

    override fun file(tenant: Connection, files: ModuleFiles, recordType: String, recordId: String, fileId: String): OutboundFile? {
        if (recordType != LeadType) return null
        val lead = runCatching { UUID.fromString(recordId) }.getOrNull() ?: return null
        val id = runCatching { UUID.fromString(fileId) }.getOrNull() ?: return null
        // Solo un adjunto activo de ese lead: CrmAttachments.file ya descarta los quitados.
        val stored = CrmAttachments.file(tenant, files, id)?.takeIf { it.leadId == lead } ?: return null
        return OutboundFile(stored.ref.name, stored.contentType, stored.ref.size) {
            stored.open() ?: throw FileNotFoundException("El archivo ${stored.ref.name} ya no esta en el almacenamiento.")
        }
    }

    override fun linked(tenant: Connection, recordType: String, recordId: String, importKey: String) {
        if (recordType != LeadType) return
        val id = runCatching { UUID.fromString(recordId) }.getOrNull() ?: return
        val key = importKey.take(200)
        // Una sola sentencia: no pisa una clave que el lead ya tenga y no choca con el indice unico de import_key
        // (si otra fila ya tiene la clave no afecta nada y el enlace de la cola sigue valiendo).
        val updated = tenant.prepareStatement(
            """
            UPDATE crm_leads SET import_key = ?
            WHERE id = CAST(? AS uuid) AND import_key IS NULL
              AND NOT EXISTS (SELECT 1 FROM crm_leads o WHERE o.import_key = ? AND o.id <> CAST(? AS uuid))
            """.trimIndent()
        ).use { update ->
            update.setString(1, key)
            update.setString(2, id.toString())
            update.setString(3, key)
            update.setString(4, id.toString())
            update.executeUpdate()
        }
        if (updated == 0) log.info("No se guardo la clave de importacion {} en el lead {}: el lead ya tiene una o la clave es de otro lead.", key, id)
    }

    override fun importMapping(tenant: Connection, recordType: String, importSource: String): Map<String, String> {
        if (recordType != LeadType) return emptyMap()
        val job = CrmImport.jobQuery(tenant, "WHERE source = ? AND status = 'done' ORDER BY created_at DESC LIMIT 1") { it.setString(1, importSource) }.firstOrNull()
            ?: return emptyMap()
        return invertMapping(job.mapping.columns)
    }
}

/**
 * Avisos de salida del CRM: se llaman DESPUES de guardar (como [CrmAutomationEvents]) y nunca lanzan. Sin extensiones
 * que reciban avisos no hacen nada (ni una consulta).
 */
object CrmOutbound {
    private val log = LoggerFactory.getLogger(CrmOutbound::class.java)

    fun created(tenant: Connection, leadId: UUID, origin: String = OutboundOrigin.User, sinks: List<OutboundSink>? = null) {
        publish(tenant, leadId, OutboundEventKind.Created, sinks) { OutboundEvent.created(CrmModule.id, CrmOutboundSource.LeadType, leadId.toString(), origin) }
    }

    /** Un cambio con las claves de los campos que cambiaron; sin claves no avisa. */
    fun updated(tenant: Connection, leadId: UUID, changed: Collection<String>, origin: String = OutboundOrigin.User, sinks: List<OutboundSink>? = null) {
        if (changed.isEmpty()) return
        publish(tenant, leadId, OutboundEventKind.Updated, sinks) { OutboundEvent.updated(CrmModule.id, CrmOutboundSource.LeadType, leadId.toString(), changed, origin) }
    }

    /** Hay extensiones que reciben avisos: sin ellas no vale la pena ni leer el lead para compararlo. */
    fun active(): Boolean = CrmModuleExtensions.outboundSinks().isNotEmpty()

    /**
     * Una fila que actualizo un lead existente: avisa solo lo que de verdad cambio. [before] es el lead leido antes de aplicar
     * la fila; una fila re-importada que dejo todo igual no avisa. (Las altas usan [created].)
     */
    fun imported(tenant: Connection, leadId: UUID, before: CrmLead, fields: List<CrmField>, origin: String, sinks: List<OutboundSink>) {
        if (sinks.isEmpty()) return
        val after = CrmRepository.lead(tenant, leadId) ?: return
        updated(tenant, leadId, diffKeys(before, after, fields), origin, sinks)
    }

    /** Una nota con su autor y a quienes menciona (nombre y correo de cada uno). */
    fun note(tenant: Connection, leadId: UUID, noteId: UUID, text: String, author: CrmActor, mentions: Collection<UUID>, origin: String = OutboundOrigin.User) {
        publish(tenant, leadId, OutboundEventKind.Note, null, savepoint = true) {
            val ids = (mentions + listOfNotNull(author.userId)).distinct()
            val found = people(tenant, ids)
            val writer = author.userId?.let { found[it] } ?: OutboundPerson(author.userId?.toString(), author.name)
            OutboundEvent.note(
                CrmModule.id, CrmOutboundSource.LeadType, leadId.toString(), noteId.toString(), text, writer,
                mentions.distinct().filter { it != author.userId }.map { personOrPlaceholder(it, found) }, origin,
            )
        }
    }

    /** Un archivo enlazado al lead. */
    fun file(tenant: Connection, leadId: UUID, added: CrmAttachments.Added, origin: String = OutboundOrigin.User) {
        publish(tenant, leadId, OutboundEventKind.File, null) { OutboundEvent.file(CrmModule.id, CrmOutboundSource.LeadType, leadId.toString(), added.id.toString(), added.name, added.size, origin) }
    }

    /** Un aviso por cada archivo nuevo de [change] (los quitados no se avisan). */
    fun files(tenant: Connection, leadId: UUID, changes: Collection<CrmAttachments.Change>, origin: String = OutboundOrigin.User) {
        changes.flatMap { it.addedFiles }.forEach { file(tenant, leadId, it, origin) }
    }

    /**
     * Nombre y correo de los usuarios (el nombre es el de perfil, o el usuario si no tiene). Se lee de application_users
     * porque quien envia no tiene el permiso del CRM de los usuarios que figuran como responsables o mencionados.
     * Los ids que no estan en application_users no aparecen en el resultado (ver [resolvePeople]).
     */
    fun people(tenant: Connection, ids: Collection<UUID>): Map<UUID, OutboundPerson> {
        if (ids.isEmpty()) return emptyMap()
        val array = tenant.createArrayOf("uuid", ids.distinct().toTypedArray())
        try {
            return tenant.prepareStatement(
                "SELECT id, COALESCE(NULLIF(display_name, ''), postgres_role), COALESCE(email, '') FROM application_users WHERE id = ANY(?)"
            ).use { query ->
                query.setArray(1, array)
                query.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            val id = rows.getObject(1, UUID::class.java)
                            put(id, OutboundPerson(id.toString(), rows.getString(2), rows.getString(3).ifBlank { null }))
                        }
                    }
                }
            }
        } finally {
            runCatching { array.free() }
        }
    }

    /** Una persona por cada id, en el mismo orden: la que no esta en application_users queda con un nombre generico y sin correo. */
    fun resolvePeople(tenant: Connection, ids: Collection<UUID>): List<OutboundPerson> {
        val found = people(tenant, ids)
        return ids.distinct().map { personOrPlaceholder(it, found) }
    }

    /** La persona encontrada, o una sin correo ("Usuario <8 primeros del id>") para que el servicio avise que no tiene usuario alla. Pura. */
    fun personOrPlaceholder(id: UUID, found: Map<UUID, OutboundPerson>): OutboundPerson =
        found[id] ?: OutboundPerson(id.toString(), "Usuario ${id.toString().take(8)}", null)

    /**
     * Arma y publica el aviso sin que nada pueda romper lo que el usuario acaba de guardar. Leer a las personas tambien
     * puede fallar: dentro de una transaccion ajena (autoCommit apagado) se hace en un savepoint para que ese fallo no
     * la deje abortada. Un fallo solo va al log (leadId, tipo de aviso y la excepcion; nunca el texto de una nota).
     */
    private fun publish(tenant: Connection, leadId: UUID, kind: OutboundEventKind, given: List<OutboundSink>?, savepoint: Boolean = false, build: () -> OutboundEvent) {
        try {
            val sinks = given ?: CrmModuleExtensions.outboundSinks()
            if (sinks.isEmpty()) return
            // Solo armar una nota lee la base (personas): created, updated y file son puros y no abren savepoint.
            val event = if (savepoint) guarded(tenant) { build() } else build()
            OutboundQueue.publish(tenant, sinks, event)
        } catch (failure: Exception) {
            log.warn("No se pudo avisar el cambio del lead {} ({}) a los servicios externos: {}: {}", leadId, kind.code, failure.javaClass.simpleName, failure.message)
        }
    }

    private fun <T> guarded(tenant: Connection, block: () -> T): T {
        if (tenant.autoCommit) return block()
        val savepoint = tenant.setSavepoint()
        try {
            return block().also { runCatching { tenant.releaseSavepoint(savepoint) } }
        } catch (failure: Exception) {
            runCatching { tenant.rollback(savepoint) }
            throw failure
        }
    }

    /** Claves que cambiaron entre dos lecturas del mismo lead (la importacion no sabe cuales toco): vacio si quedo igual. Pura. */
    fun diffKeys(before: CrmLead, after: CrmLead, fields: List<CrmField>): List<String> {
        val input = CrmLeadInput(
            name = after.name, email = after.email, phone = after.phone, stageId = after.stageId, interestId = after.interestId,
            ownerUserIds = after.ownerUserIds, source = after.source, language = after.language, message = after.message, details = after.details,
            targetStart = after.targetStart, targetEnd = after.targetEnd, estimatedValue = after.estimatedValue, followUpOn = after.followUpOn,
            // Lo que ya no esta en el lead se borro: null lo quita al fusionar.
            custom = (before.customFields.keys + after.customFields.keys).associateWith { after.customFields[it] },
        )
        return changedKeys(before, input, fields)
    }

    // ---- Claves que cambiaron (puro) ----

    /**
     * Claves de salida ([CrmOutboundKeys]) de los campos que cambia guardar [input] sobre [lead]. [ownersBefore] es la lista
     * con la que se dibujo el formulario (por defecto, la actual del lead). Con [CrmLeadInput.ownerUserIds] nulo los
     * responsables no cambian.
     */
    fun changedKeys(lead: CrmLead, input: CrmLeadInput, fields: List<CrmField>, ownersBefore: List<UUID>? = null): List<String> = buildList {
        if (lead.name != input.name) add(CrmOutboundKeys.Name)
        if (lead.email != input.email) add(CrmOutboundKeys.Email)
        if (lead.phone != input.phone) add(CrmOutboundKeys.Phone)
        if (lead.stageId != input.stageId) add(CrmOutboundKeys.Stage)
        if (lead.interestId != input.interestId) add(CrmOutboundKeys.Interest)
        input.ownerUserIds?.let { wanted -> if ((ownersBefore ?: lead.ownerUserIds).toSet() != wanted.toSet()) add(CrmOutboundKeys.Owners) }
        if (lead.followUpOn != input.followUpOn) add(CrmOutboundKeys.FollowUp)
        if (lead.source != input.source) add(CrmOutboundKeys.Source)
        if (lead.language != input.language) add(CrmOutboundKeys.Language)
        if (lead.message != input.message) add(CrmOutboundKeys.Message)
        if (lead.details != input.details) add(CrmOutboundKeys.Details)
        if (!sameNumber(lead.estimatedValue, input.estimatedValue)) add(CrmOutboundKeys.EstimatedValue)
        if (lead.targetStart != input.targetStart || lead.targetEnd != input.targetEnd) add(CrmOutboundKeys.Stay)
        val after = CrmFields.merge(lead.customFields, input.custom)
        fields.filter { CrmOutboundSource.customType(it) != null && CrmFields.display(lead.customFields[it.key]) != CrmFields.display(after[it.key]) }
            .forEach { add(CrmOutboundKeys.custom(it.key)) }
    }

    private fun sameNumber(before: BigDecimal?, after: BigDecimal?): Boolean =
        if (before == null || after == null) before == after else before.compareTo(after) == 0
}
