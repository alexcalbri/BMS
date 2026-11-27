package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.server.outbound.IntegrationConnections
import com.ideasdeveloper.bms.server.outbound.monday.JdbcMondayOutboundConfigStore
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundSink
import java.sql.Connection
import java.sql.SQLException

/** Tabla de la configuracion de envio de la extension (migrations/V002__outbound.sql). */
const val MondayOutboundTable = "ext_monday__outbound"

/** Servicio de la conexion compartida con monday (IntegrationConnections). */
const val MondayConnectionService = "monday"

/**
 * Envio de los leads del CRM a monday: la parte de la extension de [MondayOutboundSink] (que ya sabe entregar altas,
 * cambios, notas y archivos). Solo aporta de donde sale el token: el propio de la extension y, si no hay, la conexion
 * compartida de la empresa. El token nunca se muestra ni se registra.
 */
object MondayOutbound : MondayOutboundSink(
    sinkId = MondayConnectionService,
    moduleId = "crm",
    config = AuditedMondayConfigStore(JdbcMondayOutboundConfigStore(MondayOutboundTable)),
) {
    private val log = org.slf4j.LoggerFactory.getLogger(MondayOutbound::class.java)

    /** Primero el token propio de la extension; si no hay (o no se puede leer), el de la conexion compartida. */
    override fun token(tenant: Connection): String? = resolveToken(
        own = { MondaySettingsStore.loadToken(CrmHost, tenant) },
        shared = { sharedToken(tenant) },
    )

    /** El token de la conexion compartida ya abierto, o null si no hay o es ilegible (no debe enviarse a monday). */
    fun sharedToken(tenant: Connection): String? = try {
        CrmHost.openSecret(tenant, IntegrationConnections.sealed(tenant, MondayConnectionService))
            ?.takeIf { it.isNotBlank() && !CrmHost.isSecretUnreadable(it) }
    } catch (failure: SQLException) {
        // Sin la tabla compartida (empresa sin migrar) simplemente no hay conexion compartida.
        log.warn("No se pudo leer la conexion compartida de monday ({}).", failure.javaClass.simpleName)
        null
    }

    /** Si hay una conexion compartida guardada (sin abrirla). */
    fun hasSharedConnection(tenant: Connection): Boolean = try {
        IntegrationConnections.sealed(tenant, MondayConnectionService) != null
    } catch (failure: SQLException) {
        log.warn("No se pudo consultar la conexion compartida de monday ({}).", failure.javaClass.simpleName)
        false
    }

    /** La regla pura de eleccion: el token propio gana; el compartido solo se lee si no hay propio. */
    fun resolveToken(own: () -> String?, shared: () -> String?): String? =
        own()?.takeIf { it.isNotBlank() } ?: shared()?.takeIf { it.isNotBlank() }
}
