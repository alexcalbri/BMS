package com.ideasdeveloper.bms.server.outbound

import com.ideasdeveloper.bms.server.extensions.ExtensionPermissions
import java.sql.Connection

/**
 * Conexion con un servicio externo compartida por las extensiones de una empresa (tabla integration_connections, tenant
 * V009). Sirve para que quien instala varias extensiones de monday (una por modulo) escriba el token una sola vez:
 * cada extension usa primero su propio token y, si no tiene, el compartido.
 *
 * Solo guarda el valor YA SELLADO (enc:v1:..., con la llave de datos de la empresa): sellar y abrir lo hace la extension
 * con su host, igual que su propio token. Solo debe leerla una extension que declare el permiso
 * [ExtensionConnectionPermission] del servicio en su manifiesto (lo revisa el validador de paquetes al instalarla, y
 * la pantalla de instalacion lo muestra). El control es declarativo, como el resto de permisos de extension: declarar
 * "connections.monday" equivale a poder usar el token de monday de toda la empresa. Los servicios validos son una lista
 * cerrada ([ExtensionPermissions.SharedConnectionServices]). Compartirla es una decision explicita en la pantalla de
 * la extension ("usar este token en todas las extensiones de monday").
 */
object IntegrationConnections {
    const val Table = "integration_connections"
    private val servicePattern = Regex("^[a-z][a-z0-9_]{0,62}$")

    /** Permiso de extension para leer y escribir la conexion compartida de [service], p. ej. "connections.monday". */
    fun permission(service: String): String = "connections.${requireService(service)}"

    /** El valor sellado de la conexion compartida, o null si no hay. */
    fun sealed(tenant: Connection, service: String): String? =
        tenant.prepareStatement("SELECT secret FROM $Table WHERE service = ?").use { query ->
            query.setString(1, requireService(service))
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1)?.takeIf { it.isNotBlank() } else null }
        }

    /** Guarda (upsert) el valor ya sellado; null o vacio quita la conexion compartida. */
    fun save(tenant: Connection, service: String, sealed: String?, updatedBy: String?) {
        val id = requireService(service)
        if (sealed.isNullOrBlank()) {
            tenant.prepareStatement("DELETE FROM $Table WHERE service = ?").use { delete ->
                delete.setString(1, id)
                delete.executeUpdate()
            }
            return
        }
        tenant.prepareStatement(
            """
            INSERT INTO $Table (service, secret, updated_by) VALUES (?, ?, ?)
            ON CONFLICT (service) DO UPDATE SET secret = EXCLUDED.secret, updated_by = EXCLUDED.updated_by, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setString(1, id)
            upsert.setString(2, sealed)
            upsert.setString(3, updatedBy?.take(200))
            upsert.executeUpdate()
        }
    }

    private fun requireService(service: String): String {
        require(servicePattern.matches(service) && service in ExtensionPermissions.SharedConnectionServices) { "Servicio invalido." }
        return service
    }
}

/** Permiso de extension de la conexion compartida con monday. */
const val ExtensionConnectionPermission = "connections.monday"
