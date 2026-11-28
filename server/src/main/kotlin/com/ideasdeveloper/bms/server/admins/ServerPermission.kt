package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.ServerPermissionIds
import com.ideasdeveloper.bms.ServerPermissionInfo
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr

/**
 * Server permissions an owner can grant to a server administrator (SERVER_ADMINS.md §3). Deny by
 * default: a route an administrator may use names one of these in [ServerRoutePolicies]; anything
 * else is owner only. No permission manages administrators or touches server_owners.
 */
enum class ServerPermission(
    val id: String,
    val group: String,
    private val title: String,
    private val description: String,
    val highRisk: Boolean,
    val scoped: Boolean,
) {
    CompaniesView(ServerPermissionIds.CompaniesView, "companies", msg("Ver empresas"), msg("Ver la lista de empresas: codigo, nombre, estado y modulos."), false, true),
    CompaniesCreate(ServerPermissionIds.CompaniesCreate, "companies", msg("Crear empresas"), msg("Crear empresas y duplicar las empresas de su alcance. Las empresas que crea pasan a su alcance."), false, true),
    CompaniesEdit(ServerPermissionIds.CompaniesEdit, "companies", msg("Editar empresas"), msg("Editar el perfil de la empresa y ver su business owner y cambiar su nombre visible. Cambiar el usuario, el correo o la contrasena del business owner necesita tambien el permiso de cambiar su contrasena."), false, true),
    CompaniesStatus(ServerPermissionIds.CompaniesStatus, "companies", msg("Activar y desactivar empresas"), msg("Activar o desactivar una empresa."), false, true),
    CompaniesModules(ServerPermissionIds.CompaniesModules, "companies", msg("Modulos de las empresas"), msg("Activar o desactivar modulos en una empresa."), false, true),
    CompaniesExtensions(ServerPermissionIds.CompaniesExtensions, "companies", msg("Extensiones de las empresas"), msg("Instalar, configurar, activar y desinstalar extensiones dentro de una empresa."), false, true),
    CompaniesOwnerPassword(ServerPermissionIds.CompaniesOwnerPassword, "companies", msg("Cambiar la contrasena del business owner"), msg("Poner una contrasena nueva al business owner y cambiar su usuario o su correo (con el que se recupera la contrasena). Riesgo alto: equivale a tomar el control de la empresa."), true, true),
    CompaniesEnter(ServerPermissionIds.CompaniesEnter, "companies", msg("Entrar a empresas"), msg("Entrar a una empresa desde el selector y trabajar en ella como su business owner. Riesgo alto: acceso completo a los datos de la empresa."), true, true),
    ModulesView(ServerPermissionIds.ModulesView, "modules", msg("Ver modulos del servidor"), msg("Ver los modulos instalados y disponibles y su estado."), false, false),
    ModulesInstall(ServerPermissionIds.ModulesInstall, "modules", msg("Instalar modulos"), msg("Instalar, actualizar y revisar modulos del catalogo configurado."), false, false),
    ModulesRemove(ServerPermissionIds.ModulesRemove, "modules", msg("Quitar modulos"), msg("Quitar un modulo del servidor."), false, false),
    ModulesAuthorizeUnsigned(ServerPermissionIds.ModulesAuthorizeUnsigned, "modules", msg("Autorizar modulos sin firma"), msg("Autorizar un paquete de modulo sin firma. Riesgo alto: ejecuta codigo en el servidor."), true, false),
    ExtensionsInstall(ServerPermissionIds.ExtensionsInstall, "extensions", msg("Instalar extensiones"), msg("Ver el catalogo e instalar, actualizar, quitar y revisar paquetes de extensiones."), false, false),
    ExtensionsAuthorizeUnsigned(ServerPermissionIds.ExtensionsAuthorizeUnsigned, "extensions", msg("Autorizar extensiones sin firma"), msg("Autorizar un paquete de extension sin firma. Riesgo alto: ejecuta codigo en el servidor."), true, false),
    CoreUpdate(ServerPermissionIds.CoreUpdate, "server", msg("Actualizar el servidor"), msg("Pedir una actualizacion del Core y ver su estado. Riesgo alto: instala y ejecuta en el servidor el codigo publicado."), true, false),
    LogsView(ServerPermissionIds.LogsView, "server", msg("Ver errores del servidor"), msg("Ver los errores recientes del servidor."), false, false),
    AuditView(ServerPermissionIds.AuditView, "server", msg("Ver el registro de auditoria"), msg("Ver el registro de auditoria: las filas de sus empresas y las propias; las del servidor solo con todas las empresas."), false, true),
    Settings(ServerPermissionIds.Settings, "server", msg("Ajustes del servidor"), msg("Cambiar el nombre del servidor."), false, false),
    Workspace(ServerPermissionIds.Workspace, "server", msg("Espacio del servidor"), msg("Crear el espacio del servidor, activar sus modulos y administrar su perfil, correo y usuarios. Riesgo alto: permite crear cuentas de personal del espacio con cualquier permiso de sus modulos (se desactivan si el administrador se desactiva, se borra o pierde este permiso)."), true, false),
    UsersTwoFactorReset(ServerPermissionIds.UsersTwoFactorReset, "companies", msg("Desactivar la verificacion en dos pasos de usuarios"), msg("Desactivar la verificacion en dos pasos de un usuario de una empresa de su alcance (tambien de su business owner) que perdio su autenticador y sus codigos. Riesgo alto: quien conozca la contrasena de esa cuenta podra entrar solo con ella."), true, true),
    AssistantManage(ServerPermissionIds.AssistantManage, "server", msg("Asistente de IA"), msg("Activar el asistente de IA, poner la clave de API del servidor, elegir el modelo y los limites y permitirlo a las empresas de su alcance. Riesgo alto: los datos que el asistente consulte se envian a Anthropic."), true, true),
    AssistantUse(ServerPermissionIds.AssistantUse, "server", msg("Usar el asistente de IA"), msg("Usar el asistente de IA en el espacio del servidor (consultar la auditoria) y dentro de las empresas a las que puede entrar, con los permisos de esa sesion. Riesgo alto: la auditoria del servidor y la actividad de las empresas de su alcance, con nombres y correos de quienes actuaron, se envian al proveedor de IA. El dueno del servidor siempre puede."), true, true);

    fun info(): ServerPermissionInfo = ServerPermissionInfo(id, tr(GroupTitles.getValue(group)), tr(title), tr(description), if (highRisk) "high" else "normal", scoped)

    companion object {
        // Headings of the permission editor, shown as they come (already translated).
        private val GroupTitles = mapOf(
            "companies" to msg("Empresas"),
            "modules" to msg("Modulos"),
            "extensions" to msg("Extensiones"),
            "server" to msg("Servidor"),
        )

        private val byId = entries.associateBy { it.id }

        /** The permission with [id], or null for an unknown id (stored values that are not known are ignored). */
        fun of(id: String): ServerPermission? = byId[id]
    }
}
