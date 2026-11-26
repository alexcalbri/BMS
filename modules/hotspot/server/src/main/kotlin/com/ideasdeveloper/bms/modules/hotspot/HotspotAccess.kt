package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection
import java.util.UUID

/**
 * "Accesos WiFi" and "Integraciones" sections. Every user sees their own WiFi code; the business
 * owner (and server owners in the server company) see every user's code; server owners also choose
 * which users of the server company may join user-code networks of other companies.
 */
class HotspotAccess(private val access: ModuleAccessService) {
    private val separator = " · "

    fun isServerCompanyOwner(permissions: ModulePermissions) =
        permissions.serverOwner && permissions.companyCode == ServerWorkspace.Code

    // ---- Accesos WiFi ----

    fun accessBody(tenant: Connection, permissions: ModulePermissions): List<UiNode> {
        val usersEnabled = HotspotIntegrations.isEnabled(tenant, HotspotIntegrations.Users)
        val own = permissions.userId?.let { userId ->
            if (permissions.serverOwner) {
                access.centralConnection().use { central -> HotspotUserCodes.codeFor(tenant, userId, true, central) }
            } else {
                HotspotUserCodes.codeFor(tenant, userId, false)
            }
        }
        val mine = UiCard(
            title = tr("Mi acceso WiFi"),
            children = listOfNotNull(
                own?.let { UiMetric(tr("Tu codigo de usuario"), it, tr("Escribelo en las redes con acceso \"Codigo de usuario\".")) }
                    ?: UiText(tr("No se pudo identificar tu usuario."), UiTextStyle.Caption),
                if (usersEnabled) null else UiStatus(tr("El acceso con codigo de usuario aun no esta activo en esta empresa (Integraciones > Usuarios)."), UiTone.Warning),
            ),
        )
        if (!permissions.businessOwner && !permissions.serverOwner) return listOf(mine)

        val users = if (permissions.serverOwner) {
            access.centralConnection().use { central -> HotspotUserCodes.users(tenant, central) }
        } else {
            HotspotUserCodes.users(tenant, null)
        }
        val table = UiCard(
            title = tr("Codigos de los usuarios"),
            children = listOf(
                UiTable(
                    listOf(UiTableColumn("user", tr("Usuario")), UiTableColumn("code", tr("Codigo")), UiTableColumn("kind", tr("Tipo")), UiTableColumn("state", tr("Estado"))),
                    users.map {
                        mapOf(
                            "user" to if (it.displayName.isNullOrBlank()) it.username else "${it.name} (${it.username})",
                            "code" to it.code,
                            "kind" to if (it.serverOwner) tr("Dueno del servidor") else tr("Usuario"),
                            "state" to if (it.active) tr("Activo") else tr("Inactivo"),
                        )
                    },
                    tr("Aun no hay usuarios."),
                ),
                UiGrid(
                    minItemWidth = 260,
                    children = users.map { user ->
                        UiCard(
                            title = user.displayName?.takeIf { it.isNotBlank() } ?: user.username,
                            children = listOf(
                                UiText(user.code, UiTextStyle.Subtitle),
                                UiStatus(
                                    if (user.wifiAccessEnabled) tr("Acceso activo") else tr("Acceso revocado"),
                                    if (user.wifiAccessEnabled) UiTone.Success else UiTone.Warning,
                                ),
                                UiButton(
                                    if (user.wifiAccessEnabled) tr("Revocar acceso") else tr("Restaurar acceso"),
                                    UiRequest(
                                        "${HotspotScreens.UiRoute}/access",
                                        params = mapOf("usercode" to if (user.wifiAccessEnabled) "revoke" else "restore", "id" to user.id.toString()),
                                        confirm = if (user.wifiAccessEnabled) tr("Revocar el acceso WiFi de este usuario y desconectar sus dispositivos?") else null,
                                    ),
                                    style = if (user.wifiAccessEnabled) "danger" else "primary",
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        if (!isServerCompanyOwner(permissions)) return listOf(mine, table)
        return listOf(mine, table) + grantsBody(tenant, users)
    }

    fun userCodeAction(tenant: Connection, step: String, rawId: String?, permissions: ModulePermissions): Pair<String, String> {
        if (!permissions.businessOwner && !permissions.serverOwner) return tr("No tienes permiso para cambiar accesos.") to UiTone.Danger
        val userId = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return tr("El usuario no es valido.") to UiTone.Danger
        val user = if (permissions.serverOwner) {
            access.centralConnection().use { central -> HotspotUserCodes.users(tenant, central) }
        } else {
            HotspotUserCodes.users(tenant, null)
        }.firstOrNull { it.id == userId } ?: return tr("Ese usuario no existe.") to UiTone.Warning
        val enabled = when (step) {
            "revoke" -> false
            "restore" -> true
            else -> return tr("Accion desconocida.") to UiTone.Danger
        }
        if (!HotspotUserCodes.setAccessEnabled(tenant, userId, enabled)) return tr("No se pudo actualizar el acceso.") to UiTone.Danger
        if (!enabled) {
            val settings = HotspotSettingsRepository.load(tenant)
            val now = java.time.Instant.now()
            HotspotCodeSessions.revokeUserSessions(tenant, userId, permissions.companyCode, now)
            HotspotCodeSessions.expire(tenant, settings, HotspotIntegrations.controller(tenant), now)
            revokeContinuations(userId, permissions.companyCode, now)
        }
        return (if (enabled) tr("Acceso WiFi restaurado.") else tr("Acceso WiFi revocado.")) to UiTone.Success
    }

    // Sessions this user's code continued on the shared networks of linked companies end too.
    private fun revokeContinuations(userId: UUID, userCompany: String, now: java.time.Instant) {
        val linked = access.centralConnection().use { central -> HotspotRoaming.linkedCompanies(central, userCompany) } - userCompany
        linked.forEach { company ->
            access.tenantConnectionForCompany(company)?.use { other ->
                runCatching {
                    if (HotspotCodeSessions.hasOpenUserSessions(other, userId, userCompany)) {
                        HotspotCodeSessions.revokeUserSessions(other, userId, userCompany, now)
                        HotspotCodeSessions.expire(other, HotspotSettingsRepository.load(other), HotspotIntegrations.controller(other), now)
                    }
                }
            }
        }
    }

    private fun grantsBody(tenant: Connection, users: List<HotspotUser>): List<UiNode> {
        val names = users.associate { it.id to userLabel(it) }
        val grants = HotspotUserCodes.grants(tenant)
        val networks = otherCompanyNetworks()
        val list = UiCard(
            title = tr("Acceso a redes de otras empresas"),
            children = listOf(
                UiText(
                    tr("Usuarios de la empresa del servidor que pueden conectarse con su codigo a redes \"Codigo de usuario\" de otras empresas. Solo los duenos del servidor lo cambian."),
                    UiTextStyle.Caption,
                ),
                if (grants.isEmpty()) UiText(tr("Aun no hay accesos."), UiTextStyle.Caption) else UiGrid(
                    minItemWidth = 260,
                    children = grants.map { grant ->
                        UiCard(
                            title = "${grant.companyCode}$separator${grant.ssid}",
                            children = listOf(
                                UiText(names[grant.userId] ?: tr("Usuario eliminado"), UiTextStyle.Caption),
                                UiButton(
                                    tr("Quitar"),
                                    UiRequest("${HotspotScreens.UiRoute}/access", params = mapOf("grant" to "delete", "id" to grant.id.toString()), confirm = tr("Quitar este acceso?")),
                                    style = "danger",
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        val form = UiCard(
            title = tr("Dar acceso"),
            children = listOf(
                if (networks.isEmpty()) {
                    UiText(tr("Ninguna otra empresa tiene redes con acceso \"Codigo de usuario\"."), UiTextStyle.Caption)
                } else {
                    UiForm(
                        id = "grant",
                        fields = listOf(
                            UiField("network", tr("Red"), "dropdown", required = true, options = networks),
                            UiField("user", tr("Usuario"), "dropdown", required = true, options = users.filter { it.active }.map(::userLabel)),
                        ),
                        submit = UiButton(tr("Dar acceso"), UiRequest("${HotspotScreens.UiRoute}/access", params = mapOf("grant" to "add"), formId = "grant")),
                    )
                },
            ),
        )
        return listOf(list, form)
    }

    /** Adds or removes a grant; returns the message to show. */
    fun grantAction(tenant: Connection, step: String, id: String?, values: Map<String, String>): Pair<String, String> {
        return when (step) {
            "add" -> {
                val (companyCode, ssid) = values["network"].orEmpty().split(separator, limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                    ?: return tr("Elige la red.") to UiTone.Danger
                if ("$companyCode$separator$ssid" !in otherCompanyNetworks()) return tr("Esa red ya no tiene acceso por codigo de usuario.") to UiTone.Danger
                val user = access.centralConnection().use { central -> HotspotUserCodes.users(tenant, central) }
                    .firstOrNull { it.active && userLabel(it) == values["user"] }
                    ?: return tr("Elige el usuario.") to UiTone.Danger
                HotspotUserCodes.addGrant(tenant, companyCode, ssid, user.id)
                tr("{0} puede conectarse a {1} de {2}.", user.name, ssid, companyCode) to UiTone.Success
            }
            "delete" -> {
                val grantId = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (grantId != null && HotspotUserCodes.deleteGrant(tenant, grantId)) tr("Acceso quitado.") to UiTone.Success
                else tr("Ese acceso ya no existe.") to UiTone.Warning
            }
            else -> tr("Accion desconocida.") to UiTone.Danger
        }
    }

    // Full name and username, so two people with the same name can be told apart.
    internal fun userLabel(user: HotspotUser): String {
        val base = if (user.displayName.isNullOrBlank()) user.username else "${user.name} (${user.username})"
        return if (user.serverOwner) "$base · dueno del servidor" else base
    }

    // "COMPANY · SSID" of every user-code network of the other active companies.
    internal fun otherCompanyNetworks(): List<String> {
        val companies = access.centralConnection().use { central ->
            central.prepareStatement("SELECT code FROM companies WHERE is_active AND NOT is_server_workspace ORDER BY code").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        return companies.flatMap { code ->
            val tenant = access.tenantConnectionForCompany(code) ?: return@flatMap emptyList()
            tenant.use { connection ->
                // Companies without the hotspot have no networks table.
                runCatching {
                    connection.prepareStatement("SELECT ssid FROM hotspot_networks WHERE access_mode = ? ORDER BY ssid").use { query ->
                        query.setString(1, HotspotNetworks.UserCode)
                        query.executeQuery().use { rows -> buildList { while (rows.next()) add("$code$separator${rows.getString(1)}") } }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    // ---- Integraciones ----

    fun integrationsBody(tenant: Connection): List<UiNode> {
        val enabled = HotspotIntegrations.enabled(tenant)
        val catalog = HotspotIntegrations.catalog(tenant)
        fun card(integration: HotspotIntegration): UiNode {
            val on = integration.id in enabled
            return UiCard(
                title = tr(integration.title),
                children = listOf(
                    UiText(tr(integration.description), UiTextStyle.Caption),
                    UiStatus(if (on) tr("Activa") else tr("Inactiva"), if (on) UiTone.Success else UiTone.Neutral),
                    UiButton(
                        if (on) tr("Desactivar") else tr("Activar"),
                        UiRequest(
                            "${HotspotScreens.UiRoute}/integrations",
                            params = mapOf("integration" to integration.id, "enabled" to (!on).toString()),
                            confirm = if (on && integration.kind == HotspotIntegrations.Network) tr("Sin controlador de red el portal deja de autorizar visitantes. Continuar?") else null,
                        ),
                        style = if (on) "secondary" else "primary",
                    ),
                ),
            )
        }
        return listOf(
            UiCard(
                title = tr("Controlador de red"),
                children = listOf(
                    UiText(tr("Ecosistema de red donde el portal autoriza a los visitantes. Solo uno puede estar activo."), UiTextStyle.Caption),
                    UiGrid(minItemWidth = 260, children = catalog.filter { it.kind == HotspotIntegrations.Network }.map(::card)),
                ),
            ),
            UiCard(
                title = tr("Modulos"),
                children = listOf(
                    UiText(
                        tr("Conecta el hotspot con otros modulos. Los modulos que dan acceso a sus clientes (hotel, membresia...) aparecen aqui al instalarse. Solo el dueno de la empresa o del servidor las cambia."),
                        UiTextStyle.Caption,
                    ),
                    UiGrid(minItemWidth = 260, children = catalog.filter { it.kind == HotspotIntegrations.Module }.map(::card)),
                ),
            ),
        )
    }
}
