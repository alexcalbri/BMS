package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmExtensionIds
import com.ideasdeveloper.bms.modules.crm.CrmExtensions
import com.ideasdeveloper.bms.modules.crm.CrmOutboundSource
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.outbound.OutboundRecordType
import com.ideasdeveloper.bms.server.outbound.monday.MondayApiException
import com.ideasdeveloper.bms.server.outbound.monday.MondayBoardInfo
import com.ideasdeveloper.bms.server.outbound.monday.MondayBoardSchema
import com.ideasdeveloper.bms.server.outbound.monday.MondayBoards
import com.ideasdeveloper.bms.server.outbound.monday.MondayConfigException
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundUi
import com.ideasdeveloper.bms.server.outbound.monday.OutboundQueueView
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection

/**
 * Pantalla "Envio a monday" de la extension (seccion del CRM con el permiso de configuracion): configuracion por
 * tablero del tipo de registro "lead" y estado de la cola de envios. Un fallo de monday se muestra como aviso y nunca
 * rompe la pantalla. Sin tildes, como el resto del CRM.
 */
object MondayOutboundScreens {
    const val Section = "monday_outbound"
    private const val Route = CrmScreens.UiRoute
    private const val ExtensionId = CrmExtensionIds.MondayImport
    private const val FailedShown = 20
    private const val SentShown = 15
    private const val PendingShown = 50
    private val queue = OutboundQueueView(MondayConnectionService)
    private val log = org.slf4j.LoggerFactory.getLogger(MondayOutboundScreens::class.java)

    private fun request(op: String, params: Map<String, String>): UiRequest = UiRequest("$Route/$Section", params = params + ("op" to op))

    private fun warning(text: String) = UiText(text, tone = UiTone.Warning)

    /** Los tipos de registro del CRM que se pueden enviar (hoy solo el lead). */
    private fun types(tenant: Connection): List<OutboundRecordType> =
        CrmOutboundSource.recordTypes(tenant).filter { it.id == CrmOutboundSource.LeadType }

    // ---- Pantalla ----

    fun screen(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> = try {
        build(context, submitted)
    } catch (failure: Exception) {
        // Nada de lo que falle aqui rompe la pantalla: se muestra un aviso y el detalle va al log.
        log.warn("Fallo la pantalla de envio a monday.", failure)
        listOf(warning(tr("No se pudo mostrar la pantalla de envio a monday ({0}).", failure.javaClass.simpleName)))
    }

    private fun build(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> {
        val tenant = context.tenant
        CrmExtensions.missingNodes(tenant, ExtensionId)?.let { return it }
        val token = MondayOutbound.token(tenant)
        val nodes = mutableListOf<UiNode>()
        if (token == null) {
            nodes += warning(tr("Falta el token de monday: guardalo en Importar (puedes compartirlo con las demas extensiones de monday) y vuelve aqui."))
        }
        var boards: List<MondayBoardInfo> = emptyList()
        var boardsFailed = false
        if (token != null) {
            try {
                boards = MondayBoards.listBoards(MondayOutbound.api, token)
            } catch (failure: MondayApiException) {
                boardsFailed = true
                nodes += warning(tr("No se pudieron leer los tableros de monday: {0}", failure.message.orEmpty()))
            }
        }
        types(tenant).forEach { type ->
            val config = MondayOutbound.config.get(tenant, type.id)
            val boardId = MondayOutboundUi.optionId(submitted?.get("board")) ?: config?.boardId
            var schema: MondayBoardSchema? = null
            if (token != null && !boardsFailed && boardId != null) {
                try {
                    schema = MondayBoards.boardSchema(MondayOutbound.api, token, boardId)
                    if (schema == null) nodes += warning(tr("monday no devolvio el tablero {0}: revisa que exista y que el token tenga acceso.", boardId))
                } catch (failure: MondayApiException) {
                    nodes += warning(tr("No se pudieron leer las columnas del tablero: {0}", failure.message.orEmpty()))
                }
            }
            nodes += MondayOutboundUi.configCard(type, config, boards, schema, ::request, submitted.orEmpty())
        }
        nodes += MondayOutboundUi.statusNodes(
            queue.summary(tenant),
            queue.events(tenant, setOf("failed"), FailedShown),
            queue.events(tenant, setOf("pending"), PendingShown),
            queue.events(tenant, setOf("sent"), SentShown),
            ::request,
        )
        return nodes
    }

    // ---- Acciones ----

    private fun failureText(op: String, kind: String) = when (op) {
        "save" -> tr("No se pudo guardar la configuracion ({0}).", kind)
        "suggest" -> tr("No se pudo sugerir columnas ({0}).", kind)
        "retry", "retryAll" -> tr("No se pudo reintentar el envio ({0}).", kind)
        "discard" -> tr("No se pudo descartar el envio ({0}).", kind)
        else -> tr("No se pudo completar la accion ({0}).", kind)
    }

    fun action(context: CrmScreens.Context, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val tenant = context.tenant
        val keep = params - setOf("op", "id", "recordType")
        fun done(tone: String, text: String, submitted: Map<String, String>? = null) =
            CrmExtensionActionResult(Section, UiNotice(text, tone), keep, submitted)

        if (!context.permissions.has(CrmScreens.Settings)) return done(UiTone.Warning, tr("No tienes permiso para configurar el envio a monday."))
        CrmExtensions.inactive(tenant, ExtensionId)?.let { return done(UiTone.Warning, it) }
        val id = params["id"]?.toLongOrNull()
        return try {
            when (op) {
                "save", "suggest" -> {
                    val type = types(tenant).firstOrNull { it.id == params["recordType"] } ?: return done(UiTone.Warning, tr("Ese tipo de registro no existe."))
                    val boardId = MondayOutboundUi.optionId(values["board"])
                    var schema: MondayBoardSchema? = null
                    if (boardId != null) {
                        val token = MondayOutbound.token(tenant) ?: return done(UiTone.Warning, tr("Falta el token de monday: guardalo en Importar."))
                        schema = MondayBoards.boardSchema(MondayOutbound.api, token, boardId)
                            ?: return done(UiTone.Warning, tr("monday no devolvio el tablero {0}: revisa que exista y que el token tenga acceso.", boardId))
                    }
                    if (op == "suggest") {
                        if (schema == null) return done(UiTone.Warning, tr("Elige un tablero antes de pedir sugerencias."))
                        val suggested = MondayOutboundUi.suggest(type, schema, CrmOutboundSource.importMapping(tenant, type.id, "monday"))
                        if (suggested.isEmpty()) return done(UiTone.Info, tr("No hay una importacion de monday con la que sugerir columnas."), values)
                        // Solo se vuelve a dibujar con lo sugerido: no se guarda hasta que el usuario pulse Guardar.
                        done(UiTone.Info, tr("Se sugirieron {0} columna(s). Revisalas y pulsa Guardar.", suggested.size), values + suggested)
                    } else {
                        val result = MondayOutboundUi.applyConfigForm(values, type, schema)
                        MondayAudit.acting(context.permissions.activityActor) { MondayOutbound.config.save(tenant, result.config) }
                        if (result.warnings.isEmpty()) done(UiTone.Success, tr("Configuracion guardada."))
                        else done(UiTone.Warning, tr("Configuracion guardada.") + " " + result.warnings.joinToString(" "))
                    }
                }
                "retry" -> if (id != null && queue.retry(tenant, id)) done(UiTone.Success, tr("El envio se reintentara en el proximo ciclo.")) else done(UiTone.Warning, tr("Ese envio ya no esta fallido."))
                "retryAll" -> done(UiTone.Success, tr("Se reintentaran {0} envio(s) fallido(s).", queue.retryFailed(tenant)))
                "discard" -> if (id != null && queue.discard(tenant, id)) done(UiTone.Success, tr("Envio descartado.")) else done(UiTone.Warning, tr("Ese envio ya no esta fallido."))
                else -> done(UiTone.Warning, tr("Esa accion no existe."))
            }
        } catch (failure: MondayConfigException) {
            done(UiTone.Warning, failure.message.orEmpty())
        } catch (failure: MondayApiException) {
            done(UiTone.Warning, tr("monday respondio con un error: {0}", failure.message.orEmpty()))
        } catch (failure: Exception) {
            log.warn("Fallo la accion {} del envio a monday.", op, failure)
            done(UiTone.Danger, failureText(op, failure.javaClass.simpleName))
        }
    }
}
