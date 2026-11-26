package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensions
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmImportKind
import com.ideasdeveloper.bms.modules.crm.CrmImportTarget
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection
import java.util.UUID

/**
 * Operaciones de la importacion que comparten las pantallas y las herramientas MCP. Quien llama comprueba antes que el
 * usuario tiene crm.settings. Los errores de negocio salen como [InmobiliariaMondayException] con un mensaje para mostrar.
 */
object InmobiliariaMondayService {
    /** Por que no se puede importar (Inmobiliaria no esta activa o no ofrece el contrato de importacion); null si se puede. */
    fun unavailableReason(tenant: Connection): String? {
        CrmHost.extensionInactiveReason(tenant, InmobiliariaMondayServerExtension.ExtensionId)?.let { return it }
        if (!CrmHost.extensionEnabled(tenant, InmobiliariaMondayServerExtension.TargetId)) {
            return tr("Para importar necesitas la extension {0} activa en esta empresa (Empresa > Extensiones).", CrmExtensions.displayName(InmobiliariaMondayServerExtension.TargetId))
        }
        if (InmobiliariaMondayRunner.target(tenant) == null) {
            return tr("La extension Inmobiliaria instalada no admite importaciones; actualizala desde Empresa > Extensiones.")
        }
        return null
    }

    /** El destino listo para importar o el error que explica por que no. */
    fun requireTarget(tenant: Connection): CrmImportTarget {
        unavailableReason(tenant)?.let { throw InmobiliariaMondayException(it) }
        return InmobiliariaMondayRunner.target(tenant) ?: throw InmobiliariaMondayException(tr("La extension Inmobiliaria no esta disponible."))
    }

    fun kind(tenant: Connection, kindId: String): CrmImportKind =
        InmobiliariaMondayRunner.kindOf(requireTarget(tenant), kindId)
            ?: throw InmobiliariaMondayException(tr("La extension Inmobiliaria no importa registros de tipo '{0}'.", kindId.take(40)))

    /** Guarda el token; guardarlo exige la extension activa, borrarlo (null) siempre se puede. */
    fun saveToken(tenant: Connection, token: String?) {
        if (token != null) CrmHost.extensionInactiveReason(tenant, InmobiliariaMondayServerExtension.ExtensionId)?.let { throw InmobiliariaMondayException(it) }
        InmobiliariaMondaySettingsStore.saveToken(tenant, token)
    }

    fun token(tenant: Connection): String? = InmobiliariaMondaySettingsStore.loadToken(tenant)

    /** Boards de monday con el token guardado. */
    fun boards(tenant: Connection): List<MondayBoard> {
        val token = token(tenant) ?: throw InmobiliariaMondayException(tr("Guarda primero el token de la API de monday."))
        return InmobiliariaMondayClient.boards(token)
    }

    /**
     * Pone en cola la lectura de un tablero (la hace el trabajo en segundo plano). [mapping] (columna de monday -> campo)
     * es opcional: sin el, al terminar la lectura queda el sugerido por el titulo de cada columna.
     */
    fun start(tenant: Connection, actor: CrmActor, boardId: String, boardName: String, kindId: String, newListings: Boolean, mapping: Map<String, String>): UUID {
        val importKind = kind(tenant, kindId)
        if (token(tenant) == null) throw InmobiliariaMondayException(tr("Guarda primero el token de la API de monday."))
        if (!InmobiliariaMondayClient.validBoardId(boardId)) throw InmobiliariaMondayException(tr("Elige un tablero."))
        if (InmobiliariaMondayStore.busy(tenant)) throw InmobiliariaMondayException(tr(InmobiliariaMondayStore.AlreadyActive))
        // Las columnas aun no se conocen: aqui solo se comprueba que los campos existan y no se repitan.
        val clean = MondayMapping.checkMapping(importKind, mapping.keys.map { MondayColumn("", it, "") }, mapping)
        return InmobiliariaMondayStore.create(tenant, boardId, boardName, kindId, newListings && kindId == MondayMapping.KindProperty, clean, actor.userId, actor.name)
    }

    fun job(tenant: Connection, id: UUID): MondayJob = InmobiliariaMondayStore.find(tenant, id) ?: throw InmobiliariaMondayException(tr("Esa importacion ya no existe."))

    /** Guarda el mapeo de una importacion lista (o leyendo, para el caso de agentes que lo envian al aplicar). */
    fun saveMapping(tenant: Connection, id: UUID, mapping: Map<String, String>): MondayJob {
        val job = job(tenant, id)
        if (job.state != MondayJobState.Ready) throw InmobiliariaMondayException(tr("El mapeo solo se cambia cuando la lectura termino y antes de aplicar."))
        val clean = MondayMapping.checkMapping(kind(tenant, job.kind), job.columns, mapping)
        InmobiliariaMondayStore.setMapping(tenant, id, clean)
        return job.copy(mapping = clean)
    }

    /**
     * Empieza a aplicar: el trabajo en segundo plano la sigue de a 100 filas, como [actor] (quien pulso Aplicar; se le vuelve a
     * comprobar el permiso en cada corrida). Exige el mapeo completo en los campos obligatorios y, para transacciones, que las
     * propiedades ya esten importadas.
     */
    fun apply(tenant: Connection, actor: CrmActor, id: UUID) {
        val job = job(tenant, id)
        if (job.state != MondayJobState.Ready) throw InmobiliariaMondayException(tr("La importacion no esta lista para aplicar (estado: {0}).", job.state))
        val missing = MondayMapping.missingRequired(kind(tenant, job.kind), job.mapping)
        if (missing.isNotEmpty()) throw InmobiliariaMondayException(tr("Falta mapear: {0}.", missing.joinToString { tr(it) }))
        if (job.kind == MondayMapping.KindDeal && !propertiesImported(tenant)) throw InmobiliariaMondayException(tr(PropertiesFirst))
        if (!InmobiliariaMondayStore.startApplying(tenant, id, actor.userId, actor.name)) {
            throw InmobiliariaMondayException(tr("La importacion cambio de estado; revisa la pantalla."))
        }
    }

    /** Las transacciones se importan despues de las propiedades: una importacion de propiedades terminada o propiedades ya venidas de monday. */
    private fun propertiesImported(tenant: Connection): Boolean =
        InmobiliariaMondayStore.hasDoneJob(tenant, MondayMapping.KindProperty) || InmobiliariaMondayStore.hasImportedProperties(tenant)

    val PropertiesFirst = msg("Importa primero las propiedades.")

    /** Cancela: leyendo o lista se descarta con sus filas; aplicando se detiene y queda lista para aplicar lo pendiente. */
    fun cancel(tenant: Connection, id: UUID): String {
        val job = job(tenant, id)
        return when (job.state) {
            MondayJobState.Applying -> {
                InmobiliariaMondayStore.transition(tenant, id, MondayJobState.Applying, MondayJobState.Ready)
                tr("Se detuvo la aplicacion; lo ya aplicado se conserva y puedes seguir con lo pendiente.")
            }
            else -> {
                InmobiliariaMondayStore.delete(tenant, id)
                tr("Importacion descartada.")
            }
        }
    }

    /** Descarta una importacion terminada o fallida (los datos ya importados quedan en Inmobiliaria). */
    fun discard(tenant: Connection, id: UUID) {
        val job = job(tenant, id)
        if (job.state == MondayJobState.Applying) throw InmobiliariaMondayException(tr("Cancela primero la aplicacion."))
        InmobiliariaMondayStore.delete(tenant, id)
    }
}
