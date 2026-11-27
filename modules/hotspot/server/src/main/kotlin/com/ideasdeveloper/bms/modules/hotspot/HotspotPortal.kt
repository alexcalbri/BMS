package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.BasePath
import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.FormValues
import com.ideasdeveloper.bms.modules.hotspot.HotspotPortalPages.GuestContext
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CustomerSql
import com.ideasdeveloper.bms.server.modules.MailException
import com.ideasdeveloper.bms.server.modules.MailSender
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.NotificationText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * Public captive portal served on each company's configured domain.
 *
 * Flow: UniFi redirects the guest to /guest/s/default/?id=<mac>&ssid=<network>. On networks with
 * form access (and SSIDs not configured) the guest registers, receives a one-time code by email and
 * enters it; on networks with customer-code access the guest types the code shown in Clientes. The
 * backend then authorizes the MAC through UniFi, only after UniFi confirms the device is on the SSID
 * of the redirect (the redirect is not signed, so a guest could edit it to pick another method). An
 * email can connect again only after the reconnection interval (the network's, or the company's).
 */
class HotspotPortal(private val access: ModuleAccessService) {
    private val log = LoggerFactory.getLogger(HotspotPortal::class.java)
    private val random = SecureRandom()
    private val macPattern = HotspotGuestCodes.MacPattern
    private val emailPattern = Regex("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}$")
    private val phonePattern = Regex("^[+0-9][0-9 ()-]{5,19}$")
    private val maxCodeAttempts = 5
    // A new code is not emailed while the previous one for the same address is younger than this.
    // Wrong customer codes: per device within the window, and for the whole company per minute.
    private val maxCodeFailuresPerDevice = 5
    private val codeFailureWindow = Duration.ofMinutes(15)
    private val maxCodeFailuresPerMinute = 30
    // The device is on another network than the one the portal link names (edited link or stale tab).
    private fun wrongNetworkText() =
        tr("Este acceso no corresponde a la red WiFi a la que estas conectado. Cierra esta pagina y abre el navegador de nuevo desde la red WiFi.")

    private class PortalTarget(
        val companyCode: String,
        val connection: Connection,
        val settings: HotspotSettings,
        val controller: NetworkController,
    )

    fun install(route: Route) {
        route.get(BasePath) { call.showForm() }
        route.get("$BasePath/") { call.showForm() }
        route.post("$BasePath/register") { call.register() }
        route.post("$BasePath/verify") { call.verify() }
        route.post("$BasePath/code") { call.customerCode() }
    }

    private suspend fun ApplicationCall.showForm() {
        val guest = guestContext(request.queryParameters)
        val html = withPortal { target ->
            roamingWelcome(target, guest)?.let { return@withPortal it }
            val network = codeNetwork(target, guest)
            if (network != null) {
                HotspotPortalPages.accessCode(target.settings, guest, byUser = network.byUserCode)
            } else {
                HotspotPortalPages.form(target.settings, guest)
            }
        } ?: return
        respondHtml(html)
    }

    // Limite por IP de origen (la MAC y el correo los controla el cliente, la IP no tanto). Dos capas:
    // 1) un pre-filtro en memoria, barato y con tope duro, ANTES de abrir la base de la empresa (cubre tambien
    //    dominios que no existen y evita que un atacante con muchas IPs infle la tabla);
    // 2) el contador compartido y persistente en la base de la empresa (hotspot_ip_throttle, V012), por ventana fija.
    private val ipHits = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()
    private val ipWindowMillis = Duration.ofMinutes(10).toMillis()
    // Holgados a proposito: un hotel o local comparte una sola IP publica (NAT) entre muchos huespedes.
    private val maxRegisterPerIp = 60
    private val maxCodePerIp = 120
    private val maxTrackedIps = 50_000
    private val lastIpCleanup = java.util.concurrent.atomic.AtomicLong(0)
    // Ultima limpieza de la tabla por empresa (cada empresa tiene su base).
    private val lastTableCleanup = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // IPv6: se agrupa por /64 (un cliente controla todo su /64); IPv4 queda igual.
    private fun ipGroup(ip: String): String = HotspotGuestCodes.ipGroup(ip)

    // Misma regla que el resto del servidor: detras de nginx (127.0.0.1) la IP real viene en X-Real-IP.
    private fun ApplicationCall.clientIp(): String {
        val remote = request.local.remoteAddress
        return if (remote == "127.0.0.1") request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() }?.take(64) ?: remote else remote
    }

    // True si esta IP ya agoto sus intentos de [action] en la ventana; si no, cuenta este.
    // Pre-filtro en memoria (ventana deslizante, tope duro). Se llama antes de abrir la base de la empresa.
    private fun overMemoryIpLimit(ip: String, action: String, max: Int): Boolean {
        val now = System.currentTimeMillis()
        // Limpieza de entradas vencidas como mucho una vez por minuto.
        if (now - lastIpCleanup.get() > 60_000 && lastIpCleanup.getAndSet(now) != now) {
            ipHits.entries.removeIf { (_, hits) -> synchronized(hits) { hits.peekLast()?.let { now - it > ipWindowMillis } ?: true } }
        }
        val key = "$action|${ipGroup(ip)}"
        // Tope duro: con el mapa lleno (ataque con muchas IPs) las IPs nuevas se rechazan en vez de crecer la memoria.
        if (ipHits.size >= maxTrackedIps && !ipHits.containsKey(key)) return true
        val hits = ipHits.computeIfAbsent(key) { java.util.ArrayDeque() }
        synchronized(hits) {
            while (hits.isNotEmpty() && now - hits.peekFirst() > ipWindowMillis) hits.pollFirst()
            if (hits.size >= max) return true
            hits.addLast(now)
            return false
        }
    }

    // Contador persistente de la empresa; fuente compartida entre reinicios y servidores.
    private fun overIpLimit(target: PortalTarget, ip: String, action: String, max: Int, windowMillis: Long = ipWindowMillis): Boolean {
        val connection = target.connection
        val now = System.currentTimeMillis()
        // Limpieza de ventanas vencidas de ESTA empresa como mucho una vez por minuto; nunca debe tumbar la peticion.
        if (now - (lastTableCleanup[target.companyCode] ?: 0L) > 60_000) {
            lastTableCleanup[target.companyCode] = now
            runCatching {
                connection.prepareStatement("DELETE FROM hotspot_ip_throttle WHERE window_start < ?").use { delete ->
                    delete.setTimestamp(1, Timestamp(now - maxOf(ipWindowMillis, Duration.ofHours(1).toMillis())))
                    delete.executeUpdate()
                }
            }.onFailure { log.warn("No se pudo limpiar hotspot_ip_throttle de {}", target.companyCode, it) }
        }
        return HotspotGuestCodes.hitLimit(connection, ipGroup(ip), action, max, windowMillis, now)
    }

    // Staff with the permission to change the settings get one in-app notice per hour when the ceiling of new customers is reached.
    private fun notifyCeilingReached(target: PortalTarget, ceiling: Int) {
        runCatching {
            if (overIpLimit(target, "*", "ceiling_notice", 1, Duration.ofHours(1).toMillis())) return
            val connection = target.connection
            val recipients = connection.prepareStatement("SELECT id FROM application_users WHERE is_active").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }.filter { id ->
                access.userAccess(connection, target.companyCode, id, serverOwner = false)?.permissions(HotspotModule.id)?.has(HotspotScreens.SettingsManage) == true
            }
            access.notificationsFor(HotspotModule.id).notifyEach(connection, recipients) {
                NotificationText(
                    tr("Limite de registros nuevos del WiFi"),
                    tr("El formulario del portal llego a {0} clientes nuevos en una hora y rechaza direcciones nuevas hasta la hora siguiente. Si no es normal, revisa los registros; el limite se cambia en la configuracion.", ceiling),
                )
            }
        }.onFailure { log.warn("Hotspot {}: no se pudo avisar que se alcanzo el limite de clientes nuevos ({}).", target.companyCode, it.javaClass.simpleName) }
    }

    private fun tooManyRequestsHtml() =
        HotspotPortalPages.message(null, tr("Demasiados intentos"), tr("Espera unos minutos y vuelve a intentarlo."), retry = false)

    private suspend fun ApplicationCall.register() {
        val ip = clientIp()
        if (overMemoryIpLimit(ip, "register", maxRegisterPerIp)) return respondHtml(tooManyRequestsHtml(), HttpStatusCode.TooManyRequests)
        val form = HotspotGuestCodes.readForm(this) ?: return
        val guest = guestContext(form)
        val values = FormValues(
            name = form["name"].orEmpty().trim(),
            email = form["email"].orEmpty().trim().lowercase(),
            phone = form["phone"].orEmpty().trim(),
            birthday = form["birthday"].orEmpty().trim(),
        )
        var limited = false
        val html = withPortal { target ->
            if (overIpLimit(target, ip, "register", maxRegisterPerIp)) {
                limited = true
                tooManyRequestsHtml()
            } else {
                register(target, guest, values, form["terms"] == "si", ip)
            }
        } ?: return
        respondHtml(html, if (limited) HttpStatusCode.TooManyRequests else HttpStatusCode.OK)
    }

    private suspend fun ApplicationCall.verify() {
        val form = HotspotGuestCodes.readForm(this) ?: return
        val email = form["email"].orEmpty().trim().lowercase()
        val code = form["code"].orEmpty().filter { it.isDigit() }
        val guest = guestContext(form)
        val ip = clientIp()
        if (overMemoryIpLimit(ip, "verify", maxCodePerIp)) return respondHtml(tooManyRequestsHtml(), HttpStatusCode.TooManyRequests)
        val html = withPortal { target ->
            if (email.length > 120 || !emailPattern.matches(email)) {
                HotspotPortalPages.message(target.settings, tr("Solicitud invalida"), tr("Vuelve a empezar el registro."))
            } else {
                verify(target, email, code, guest, ip)
            }
        } ?: return
        respondHtml(html)
    }

    private suspend fun ApplicationCall.customerCode() {
        val ip = clientIp()
        if (overMemoryIpLimit(ip, "code", maxCodePerIp)) return respondHtml(tooManyRequestsHtml(), HttpStatusCode.TooManyRequests)
        val form = HotspotGuestCodes.readForm(this) ?: return
        val guest = guestContext(form)
        val code = HotspotNetworks.normalizeCustomerCode(form["code"].orEmpty().take(40))
        var limited = false
        val html = withPortal { target ->
            if (overIpLimit(target, ip, "code", maxCodePerIp)) {
                limited = true
                tooManyRequestsHtml()
            } else {
                accessByCode(target, guest, code)
            }
        } ?: return
        respondHtml(html, if (limited) HttpStatusCode.TooManyRequests else HttpStatusCode.OK)
    }

    private fun register(target: PortalTarget, guest: GuestContext, values: FormValues, acceptedTerms: Boolean, ip: String): String {
        val network = HotspotNetworks.findBySsid(target.connection, guest.ssid)
        // A network that currently asks for an enabled code integration cannot be entered through the form.
        codeNetwork(target, guest)?.let { return HotspotPortalPages.accessCode(it.applyTo(target.settings), guest, byUser = it.byUserCode) }
        val settings = network?.applyTo(target.settings) ?: target.settings
        val connection = target.connection
        fun retry(message: String) = HotspotPortalPages.form(settings, guest, values, message)

        if (!macPattern.matches(guest.mac)) return deviceNotIdentified(settings)
        if (values.name.isBlank() || values.name.length > 60) return retry(tr("Escribe tu nombre."))
        if (values.email.length > 120 || !emailPattern.matches(values.email)) return retry(tr("Escribe un correo valido."))
        if (!phonePattern.matches(values.phone)) return retry(tr("Escribe un numero de celular valido."))
        val birthday = values.birthday.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { date -> date.isBefore(LocalDate.now()) && date.year > 1900 }
                ?: return retry(tr("La fecha de cumpleanos no es valida."))
        }
        if (!acceptedTerms) return retry(tr("Debes aceptar los terminos para continuar."))

        val smtp = HotspotMail.smtp(connection, access.serverMail, access.secrets)
            ?: return HotspotPortalPages.message(settings, tr("Portal no disponible"), tr("El envio de codigos no esta configurado. Avisa al personal del local."))

        // An address that already has a usable code (a running session, or one just sent) gets the SAME page as a first
        // submission and no new email: nothing tells the person whether the address has a session, the page carries no
        // identifier of the code, and a second device of the guest types the code it received on the first one.
        if (HotspotGuestCodes.hasLiveCode(connection, values.email)) {
            return HotspotPortalPages.code(settings, values.email, guest.continueUrl, null, guest)
        }

        minutesUntilReconnect(connection, values.email, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining)
        }

        // Per device and per destination address, so nobody can fill a mailbox that is not theirs.
        if (HotspotGuestCodes.overSendLimit(connection, values.email, guest.mac)) {
            return retry(tr("Ya se enviaron varios codigos. Espera un rato y vuelve a intentarlo."))
        }

        // The guest is a customer from the moment the form is sent (the emailed code is the customer's); an address that
        // already is one is reused. New customers per origin address are limited, since nothing is proven yet.
        val customerId = HotspotGuestCodes.existingCustomer(connection, values.email) ?: run {
            if (overIpLimit(target, ip, "customer", HotspotGuestCodes.MaxNewCustomersPerIpHour, Duration.ofHours(1).toMillis())) {
                return retry(tr("Demasiados registros nuevos desde esta red. Espera un rato y vuelve a intentarlo."))
            }
            // The whole company: only NEW addresses are refused (existing customers never reach this), and staff are told once an hour.
            if (overIpLimit(target, "*", "new_customers", settings.newCustomersPerHour, Duration.ofHours(1).toMillis())) {
                notifyCeilingReached(target, settings.newCustomersPerHour)
                return HotspotPortalPages.message(settings, tr("Intentalo mas tarde"), tr("Estamos recibiendo muchos registros en este momento. Intentalo mas tarde."))
            }
            findOrCreateCustomer(connection, values)
        }

        val code = (0 until 6).joinToString("") { random.nextInt(10).toString() }
        val codeId = UUID.randomUUID()
        HotspotGuestCodes.insertCode(
            connection, UUID.randomUUID(), codeId, customerId, values, birthday, guest, codeHash(codeId, code),
            Instant.now().plus(Duration.ofMinutes(settings.codeTtlMinutes.toLong())),
        )

        try {
            MailSender.sendText(
                settings = smtp,
                to = values.email,
                subject = settings.emailSubject,
                body = tr("Hola {0},\n\nTu codigo de acceso a {1} es: {2}\n\nVence en {3} minutos si no lo usas. Puedes usarlo en hasta 2 dispositivos, y la sesion termina al mismo tiempo en los dos.\nSi no lo solicitaste, ignora este correo.", values.name, settings.networkName, code, settings.codeTtlMinutes),
            )
        } catch (failure: MailException) {
            log.warn("Hotspot {}: no se pudo enviar el codigo a {}: {}", target.companyCode, values.email, failure.cause?.message ?: failure.message)
            connection.prepareStatement("DELETE FROM hotspot_codes WHERE id = ?").use { delete ->
                delete.setObject(1, codeId)
                delete.executeUpdate()
            }
            return retry(tr("No pudimos enviar el codigo a tu correo. Verificalo e intenta de nuevo."))
        }
        return HotspotPortalPages.code(settings, values.email, guest.continueUrl, null, guest)
    }

    private fun verify(target: PortalTarget, email: String, code: String, guest: GuestContext, ip: String): String {
        val connection = target.connection
        val continueUrl = guest.continueUrl
        val origin = ipGroup(ip)
        fun codePage(settings: HotspotSettings, message: String) = HotspotPortalPages.code(settings, email, continueUrl, message, guest)
        // Wrong codes are counted per (address, origin) and per origin: a third party cannot use up the tries of the guest.
        if (HotspotGuestCodes.tooManyWrongCodes(connection, origin, email)) {
            return HotspotPortalPages.message(target.settings, tr("Demasiados intentos"), tr("Espera unos minutos y vuelve a intentarlo."))
        }
        // The code is looked up from the address and the code typed, never from an id of the page.
        val codeId = HotspotGuestCodes.candidates(connection, email)
            .firstOrNull { code.length == 6 && MessageDigest.isEqual(codeHash(it.id, code).toByteArray(), it.hash.toByteArray()) }?.id
        if (codeId == null) {
            HotspotGuestCodes.recordWrongCode(connection, origin, email)
            return codePage(target.settings, tr("Codigo incorrecto. Revisalo e intenta de nuevo."))
        }
        val pending = connection.prepareStatement(
            """
            SELECT registrations.id AS registration_id, registrations.customer_id, registrations.name, registrations.email, registrations.phone,
                   registrations.mac, registrations.ssid
            FROM hotspot_codes codes
            JOIN hotspot_registrations registrations ON registrations.id = codes.registration_id
            WHERE codes.id = ?
            """.trimIndent()
        ).use { query ->
            query.setObject(1, codeId)
            query.executeQuery().use { rows ->
                if (!rows.next()) null else PendingCode(
                    registrationId = rows.getObject("registration_id", UUID::class.java),
                    customerId = rows.getObject("customer_id", UUID::class.java),
                    name = rows.getString("name"),
                    email = rows.getString("email"),
                    phone = rows.getString("phone").orEmpty(),
                    mac = rows.getString("mac"),
                    ssid = rows.getString("ssid").orEmpty(),
                )
            }
        } ?: return HotspotPortalPages.message(target.settings, tr("Codigo no encontrado"), tr("Vuelve a registrarte para recibir un codigo nuevo."))
        // Times of the network the guest registered from, when it is configured.
        val settings = HotspotNetworks.findBySsid(connection, pending.ssid)?.applyTo(target.settings) ?: target.settings
        // The device that enters the code is the one whose page this is (the first one is the one that asked for it).
        val mac = guest.mac.takeIf { macPattern.matches(it) } ?: pending.mac
        fun again(message: String) = codePage(settings, message)
        // The code is right, so the address is proven: the customer of the form becomes "email verified" (an already verified one is untouched).
        pending.customerId?.let { HotspotActivity.verifyGuestEmail(connection, it, pending.email) }

        // One session per code: the first device starts it, the next one (2 at most) ends at the same moment. The wait for
        // the address to come back is asked only when the session starts; it is kept in the database with the device slots.
        val activation = HotspotGuestCodes.activate(connection, codeId, mac, settings.accessMinutes) {
            minutesUntilReconnect(connection, pending.email, settings.reconnectMinutes)
        }
        val minutes = when (activation) {
            is HotspotGuestCodes.Activation.Started -> activation.minutes
            is HotspotGuestCodes.Activation.Joined -> activation.minutes
            is HotspotGuestCodes.Activation.ReconnectWait -> return HotspotPortalPages.wait(settings, activation.minutes)
            is HotspotGuestCodes.Activation.Over -> return minutesUntilReconnect(connection, pending.email, settings.reconnectMinutes)
                ?.let { HotspotPortalPages.wait(settings, it, byCode = true) }
                ?: HotspotPortalPages.message(settings, tr("Codigo vencido"), tr("Vuelve a registrarte para recibir un codigo nuevo."))
            HotspotGuestCodes.Activation.Expired -> return HotspotPortalPages.message(settings, tr("Codigo vencido"), tr("Vuelve a registrarte para recibir un codigo nuevo."))
            HotspotGuestCodes.Activation.Used -> return HotspotPortalPages.message(settings, tr("Codigo ya utilizado"), tr("Vuelve a registrarte para recibir un codigo nuevo."))
            HotspotGuestCodes.Activation.DeviceLimit -> return HotspotPortalPages.message(settings, tr("Limite de dispositivos"), tr("Este codigo ya se esta usando en 2 dispositivos."))
        }
        val joined = activation is HotspotGuestCodes.Activation.Joined

        // The access of this device is a registration of the same customer (the first device keeps the one of the form).
        val registrationId = if (mac == pending.mac) pending.registrationId else UUID.randomUUID().also { id ->
            insertRegistration(
                connection, id, Owner.Customer(pending.customerId), FormValues(name = pending.name, email = pending.email, phone = pending.phone),
                birthday = null, guest = guest.copy(mac = mac, ssid = guest.ssid.ifBlank { pending.ssid }), accessMode = HotspotNetworks.Form,
            )
        }
        val result = target.controller.authorizeGuest(settings.copy(accessMinutes = minutes), mac, guest.ssid.ifBlank { pending.ssid })
        recordAccess(connection, registrationId, Owner.Customer(pending.customerId), pending.email, mac, result, minutes, joined = joined)
        if (result.wrongNetwork) {
            log.warn("Hotspot {}: red distinta para {}: {}", target.companyCode, mac, result.message)
            HotspotGuestCodes.release(connection, codeId, mac)
            return HotspotPortalPages.message(settings, tr("Red WiFi distinta"), wrongNetworkText())
        }
        if (!result.authorized) {
            log.warn("Hotspot {}: UniFi rechazo {}: {}", target.companyCode, mac, result.message)
            // Give the slot (and the code, when it was the first device) back so the guest can retry once the problem is fixed.
            HotspotGuestCodes.release(connection, codeId, mac)
            return again(tr("No pudimos activar tu acceso en este momento. Intenta de nuevo en unos segundos."))
        }
        // One of the configured pages at random; without any, the page the guest was opening.
        return HotspotPortalPages.success(settings.copy(accessMinutes = minutes), pending.name, settings.successUrls.randomOrNull() ?: continueUrl.takeIf { it.isNotBlank() })
    }

    /**
     * Access by customer code on networks configured for it: the code must belong to a customer of
     * this company (or of the server workspace, when the portal runs there) and not inactive or
     * archived. CRM integration must be enabled and the customer must have a lead in the selected
     * pipeline stage; optional lead dates limit access from the day before the start through the end.
     * Every access is kept as a registration with the customer's contact data.
     */
    private fun accessByCode(target: PortalTarget, guest: GuestContext, code: String): String {
        val connection = target.connection
        // The network may have changed to form access since the page was opened.
        val network = codeNetwork(target, guest) ?: return HotspotPortalPages.form(target.settings, guest)
        val settings = network.applyTo(target.settings)
        fun retry(message: String) = HotspotPortalPages.accessCode(settings, guest, network.byUserCode, message)

        if (!macPattern.matches(guest.mac)) return deviceNotIdentified(settings)
        if (tooManyCodeFailures(connection, guest.mac)) {
            return retry(tr("Demasiados intentos. Espera unos minutos y vuelve a intentarlo."))
        }
        if (network.byUserCode) return userCode(target, guest, network, settings, code, ::retry)

        val customer = if (HotspotNetworks.isCustomerCode(code)) findCustomerByCode(connection, code) else null
        val values = customer?.let { FormValues(name = it.name, email = it.email, phone = it.phone) }
        val crmSettings = HotspotSettingsRepository.load(connection)
        val window = if (crmSettings.crmAccessEnabled && HotspotCrmPipeline.isInstalled(connection)) {
            customer?.let { crmSettings.crmStageId?.let { stageId -> HotspotCrmPipeline.currentWindow(connection, it.id, stageId) } }
        } else null
        if (customer == null || values == null || window == null) {
            // The same answer for unknown codes and for customers the network does not let in.
            recordCodeFailure(connection, guest.mac)
            return retry(tr("El codigo no es valido para esta red. Revisalo e intenta de nuevo."))
        }
        val owner = Owner.Customer(customer.id)
        val notAfter = window.expiresAt()
        if (HotspotPasses.HotspotCustomers !in network.accessSources) {
            val sources = (network.accessSources.toSet() intersect HotspotIntegrations.enabled(connection)) - HotspotPasses.HotspotCustomers
            val passes = HotspotPasses.passes(connection, customer.id, sources)
            val notCurrent = tr("Tu acceso a esta red no esta vigente. Consulta al personal del local.")
            val (pass, minutes) = HotspotPasses.usable(passes, settings.accessMinutes, Instant.now()) ?: return retry(notCurrent)
            joinOpenSession(target, guest, settings, owner, values, HotspotNetworks.CustomerCode, ::retry, notAfter = notAfter)?.let { return it }
            val spent = pass.rule == HotspotPasses.Balance
            if (spent && !HotspotPasses.spend(connection, pass.id, minutes)) return retry(notCurrent)
            val sessionSettings = settings.copy(accessMinutes = minOf(minutes, minutesUntil(notAfter, settings.accessMinutes)))
            return authorizeByCode(
                target, guest, sessionSettings, owner, values, HotspotNetworks.CustomerCode, ::retry,
                passId = pass.id, notAfter = notAfter,
                onRejected = { if (spent) HotspotPasses.refund(connection, pass.id, minutes) },
            )
        }
        joinOpenSession(target, guest, settings, owner, values, HotspotNetworks.CustomerCode, ::retry, notAfter = notAfter)?.let { return it }
        minutesUntilReconnect(connection, customer.email, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining, byCode = true)
        }
        val sessionSettings = settings.copy(accessMinutes = minutesUntil(notAfter, settings.accessMinutes))
        return authorizeByCode(
            target, guest, sessionSettings, owner, values, HotspotNetworks.CustomerCode, ::retry, notAfter = notAfter,
        )
    }

    /**
     * Access by user code (Usuarios integration): users of this company, server owners when the
     * portal belongs to the server company, and users of the server company that a server owner
     * authorized for this network. Reconnection is counted per user.
     */
    private fun userCode(
        target: PortalTarget,
        guest: GuestContext,
        network: HotspotNetwork,
        settings: HotspotSettings,
        code: String,
        retry: (String) -> String,
    ): String {
        val connection = target.connection
        val invalid = tr("El codigo no es valido para esta red. Revisalo e intenta de nuevo.")
        if (!HotspotIntegrations.isEnabled(connection, HotspotIntegrations.Users)) {
            return HotspotPortalPages.message(settings, tr("Red no disponible"), tr("El acceso con codigo de usuario no esta activo. Avisa al personal del local."))
        }
        if (!HotspotUserCodes.isUserCode(code)) {
            recordCodeFailure(connection, guest.mac)
            return retry(invalid)
        }
        val found = access.centralConnection().use { central ->
            HotspotUserCodes.findActive(connection, central, code)?.let { it to target.companyCode }
                ?: relatedBusinessOwner(connection, central, target.companyCode, code)?.let { it to target.companyCode }
                ?: grantedServerUser(central, target.companyCode, network.ssid, code)?.let { it to ServerWorkspace.Code }
        }
        if (found == null) {
            recordCodeFailure(connection, guest.mac)
            return retry(invalid)
        }
        val (user, userCompany) = found
        val owner = Owner.User(user.id, userCompany)
        joinOpenSession(target, guest, settings, owner, FormValues(name = user.name), HotspotNetworks.UserCode, retry, indefinite = true)?.let { return it }
        minutesUntilUserReconnect(connection, owner, settings.reconnectMinutes)?.let { remaining ->
            return HotspotPortalPages.wait(settings, remaining, byCode = true)
        }
        return authorizeByCode(
            target, guest, settings.copy(accessMinutes = 0), owner, FormValues(name = user.name),
            HotspotNetworks.UserCode, retry, indefinite = true,
        )
    }

    // A business owner may use a code from any active company explicitly linked to this company's owner group.
    private fun relatedBusinessOwner(tenant: Connection, central: Connection, companyCode: String, code: String): HotspotUser? {
        val relatedCompanies = central.prepareStatement(
            """
            SELECT member_company.code
            FROM companies target_company
            JOIN companies member_company ON member_company.owner_group = target_company.owner_group
            WHERE target_company.code = ?
              AND target_company.is_active AND NOT target_company.is_server_workspace
              AND member_company.is_active AND NOT member_company.is_server_workspace
            ORDER BY member_company.code
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        val codeBelongsToRelatedOwner = relatedCompanies.any { relatedCompany ->
            if (relatedCompany == companyCode) return@any false
            val relatedTenant = access.tenantConnectionForCompany(relatedCompany) ?: return@any false
            relatedTenant.use { HotspotUserCodes.isActiveBusinessOwnerCode(it, code) }
        }
        if (!codeBelongsToRelatedOwner) return null
        return HotspotUserCodes.activeBusinessOwner(tenant)
            ?.takeIf { it.wifiAccessEnabled }
    }

    // A user of the server company with [code] that a server owner authorized for [ssid] of [companyCode].
    private fun grantedServerUser(central: Connection, companyCode: String, ssid: String, code: String): HotspotUser? {
        if (companyCode == ServerWorkspace.Code) return null
        val workspace = access.tenantConnectionForCompany(ServerWorkspace.Code) ?: return null
        return workspace.use { serverCompany ->
            // The server company may not use the hotspot (no tables there).
            runCatching {
                HotspotUserCodes.findActive(serverCompany, central, code)
                    ?.takeIf { HotspotUserCodes.hasGrant(serverCompany, companyCode, ssid, it.id) }
            }.getOrNull()
        }
    }

    /**
     * A device with live access on this SSID, in this company or (on a shared network) in a linked
     * one (see [HotspotRoaming]), is let in again for the time it has left, without the form or a
     * code: it came back after visiting another network, or moved to the other company's gateway.
     * Null when there is none, or the controller did not let it in, so the usual page is shown.
     */
    private fun roamingWelcome(target: PortalTarget, guest: GuestContext): String? {
        if (!macPattern.matches(guest.mac)) return null
        val network = HotspotNetworks.findBySsid(target.connection, guest.ssid)
        val now = Instant.now()
        val companies = if (network?.shared == true) {
            access.centralConnection().use { central -> HotspotRoaming.linkedCompanies(central, target.companyCode) }
        } else {
            listOf(target.companyCode)
        }
        val found = companies.mapNotNull { company ->
            if (company == target.companyCode) {
                HotspotRoaming.liveAccess(target.connection, company, guest.mac, guest.ssid, now, requireShared = false)
            } else {
                // A linked company without the hotspot (or with it disabled) has nothing to carry over.
                access.tenantConnectionForCompany(company)?.use { tenant ->
                    runCatching {
                        if (moduleEnabled(tenant)) HotspotRoaming.liveAccess(tenant, company, guest.mac, guest.ssid, now) else null
                    }.onFailure { log.warn("Hotspot {}: no se pudo revisar el acceso en {}: {}", target.companyCode, company, it.message) }
                        .getOrNull()
                }
            }
        }
        val source = HotspotRoaming.best(found) ?: return null
        val minutesLeft = source.minutesLeft(now)
        // 0 = no limit, for accesses that do not expire.
        val settings = (network?.applyTo(target.settings) ?: target.settings).copy(accessMinutes = minutesLeft ?: 0)
        val connection = target.connection
        val user = source.userId?.let { id -> source.userCompany?.let { Owner.User(id, it) } }
        // The same customer here; a guest of another company is linked to this company's customer
        // with that email, never created here.
        val customerId = if (source.company == target.companyCode) source.customerId else null
        val owner = user ?: Owner.Customer(customerId ?: source.email?.takeIf { it.isNotBlank() }?.let { findCustomerByEmail(connection, it) })
        val values = FormValues(name = source.name, email = source.email.orEmpty(), phone = source.phone.orEmpty())
        val registrationId = UUID.randomUUID()
        insertRegistration(connection, registrationId, owner, values, birthday = null, guest = guest, accessMode = source.accessMode, roamedFrom = source.company)
        val result = target.controller.authorizeGuest(settings, guest.mac, guest.ssid)
        // A user code keeps a session here too, so revoking that user's WiFi also disconnects this gateway.
        val session = if (result.authorized && user != null) {
            HotspotCodeSessions.start(
                connection, user.codeOwner(), settings.accessMinutes, passId = null,
                notAfter = source.expiresAt, indefinite = source.expiresAt == null, now = now,
            )
        } else null
        // joined: a continuation does not move the reconnection wait.
        recordAccess(
            connection, registrationId, owner, values.email.ifBlank { null }, guest.mac, result, settings.accessMinutes,
            sessionId = session?.id, joined = true,
        )
        if (result.wrongNetwork) {
            log.warn("Hotspot {}: red distinta para {}: {}", target.companyCode, guest.mac, result.message)
            return HotspotPortalPages.message(settings, tr("Red WiFi distinta"), wrongNetworkText())
        }
        if (!result.authorized) {
            log.warn("Hotspot {}: {} rechazo la continuacion de {}: {}", target.companyCode, target.controller.name, guest.mac, result.message)
            return null
        }
        // Like the first success page, only form access shows how long it lasts.
        return HotspotPortalPages.welcomeBack(
            settings, source.name, minutesLeft.takeIf { source.accessMode == HotspotNetworks.Form },
            settings.successUrls.randomOrNull() ?: guest.continueUrl.takeIf { it.isNotBlank() },
        )
    }

    private fun findCustomerByEmail(connection: Connection, email: String): UUID? = connection.prepareStatement(
        "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND ${CustomerSql.Visible} ORDER BY created_at LIMIT 1"
    ).use { query ->
        query.setString(1, email)
        query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
    }

    // Keeps the access as a registration, asks the network controller and shows the result.
    private fun authorizeByCode(
        target: PortalTarget,
        guest: GuestContext,
        settings: HotspotSettings,
        owner: Owner,
        values: FormValues,
        accessMode: String,
        retry: (String) -> String,
        passId: UUID? = null,
        onRejected: () -> Unit = {},
        // An open session of the same code this device joins; null starts a new one.
        joining: CodeSession? = null,
        notAfter: Instant? = null,
        indefinite: Boolean = false,
    ): String {
        val connection = target.connection
        val registrationId = UUID.randomUUID()
        insertRegistration(connection, registrationId, owner, values, birthday = null, guest = guest, accessMode = accessMode)
        val result = target.controller.authorizeGuest(settings, guest.mac, guest.ssid)
        // Every device of a code shares one session, so they all lose access when it expires.
        val session = when {
            !result.authorized -> null
            joining != null -> joining
            else -> HotspotCodeSessions.start(
                connection, owner.codeOwner(), settings.accessMinutes, passId, notAfter = notAfter, indefinite = indefinite,
            )
        }
        recordAccess(
            connection, registrationId, owner, values.email.ifBlank { null }, guest.mac, result, settings.accessMinutes, passId,
            sessionId = session?.id, joined = joining != null,
        )
        if (!result.authorized) {
            onRejected()
            log.warn("Hotspot {}: {} rechazo {}: {}", target.companyCode, target.controller.name, guest.mac, result.message)
            if (result.wrongNetwork) return HotspotPortalPages.message(settings, tr("Red WiFi distinta"), wrongNetworkText())
            return retry(tr("No pudimos activar tu acceso en este momento. Intenta de nuevo en unos segundos."))
        }
        return HotspotPortalPages.success(
            settings, values.name, settings.successUrls.randomOrNull() ?: guest.continueUrl.takeIf { it.isNotBlank() }, showDuration = false,
        )
    }

    /**
     * When another device is using the same code, lets this one in for the time that session has
     * left (no reconnection wait, no balance spent); null when the code has no open session.
     */
    private fun joinOpenSession(
        target: PortalTarget,
        guest: GuestContext,
        settings: HotspotSettings,
        owner: Owner,
        values: FormValues,
        accessMode: String,
        retry: (String) -> String,
        notAfter: Instant? = null,
        indefinite: Boolean = false,
    ): String? {
        var session = HotspotCodeSessions.open(target.connection, owner.codeOwner()) ?: return null
        if (notAfter != null) {
            session = HotspotCodeSessions.limit(target.connection, session.id, notAfter) ?: return null
        }
        val minutes = if (indefinite || session.expiresAt == null) 0 else minOf(settings.accessMinutes, session.minutesLeft() ?: settings.accessMinutes)
        return authorizeByCode(
            target, guest, settings.copy(accessMinutes = minutes), owner, values, accessMode, retry,
            joining = session, notAfter = notAfter, indefinite = indefinite || session.expiresAt == null,
        )
    }

    private fun minutesUntilUserReconnect(connection: Connection, owner: Owner.User, reconnectMinutes: Int): Long? {
        if (reconnectMinutes <= 0) return null
        val lastAccess = connection.prepareStatement(
            "SELECT max(created_at) FROM hotspot_accesses WHERE user_id = ? AND user_company = ? AND authorized AND NOT joined"
        ).use { query ->
            query.setObject(1, owner.id)
            query.setString(2, owner.company)
            query.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1)?.toInstant() else null }
        } ?: return null
        val remaining = Duration.between(Instant.now(), lastAccess.plus(Duration.ofMinutes(reconnectMinutes.toLong())))
        return if (remaining.isNegative || remaining.isZero) null else (remaining.toSeconds() + 59) / 60
    }

    // The configured network the guest joined when it asks for a code; null otherwise.
    private fun codeNetwork(target: PortalTarget, guest: GuestContext): HotspotNetwork? =
        HotspotNetworks.findBySsid(target.connection, guest.ssid)?.takeIf { network ->
            when {
                network.byUserCode -> HotspotIntegrations.isEnabled(target.connection, HotspotIntegrations.Users)
                network.byCustomerCode -> {
                    val settings = HotspotSettingsRepository.load(target.connection)
                    settings.crmAccessEnabled && settings.crmStageId != null && HotspotCrmPipeline.isInstalled(target.connection)
                }
                else -> false
            }
        }

    private fun minutesUntil(notAfter: Instant?, fallback: Int): Int =
        notAfter?.let { ((Duration.between(Instant.now(), it).seconds + 59) / 60).toInt().coerceAtLeast(1) }?.let { minOf(it, fallback) }
            ?: fallback

    private fun deviceNotIdentified(settings: HotspotSettings) = HotspotPortalPages.message(
        settings,
        tr("Abre el portal desde la red WiFi"),
        tr("No se pudo identificar tu dispositivo. Conectate a la red {0} y vuelve a abrir el navegador.", settings.networkName),
    )

    private fun findCustomerByCode(connection: Connection, code: String): CodeCustomer? = connection.prepareStatement(
        """
        SELECT id, display_name, primary_email, primary_phone, customer_type
        FROM customers
        WHERE customer_code = ? AND status NOT IN ('inactive', 'archived') AND ${CustomerSql.Visible}
        """.trimIndent()
    ).use { query ->
        query.setString(1, code)
        query.executeQuery().use { rows ->
            if (!rows.next()) null else CodeCustomer(
                id = rows.getObject("id", UUID::class.java),
                name = rows.getString("display_name"),
                email = rows.getString("primary_email"),
                phone = rows.getString("primary_phone"),
                type = rows.getString("customer_type"),
            )
        }
    }

    private fun tooManyCodeFailures(connection: Connection, mac: String): Boolean = connection.prepareStatement(
        """
        SELECT count(*) FILTER (WHERE mac = ?),
               count(*) FILTER (WHERE created_at > ?)
        FROM hotspot_code_attempts
        WHERE created_at > ?
        """.trimIndent()
    ).use { query ->
        val now = Instant.now()
        query.setString(1, mac)
        query.setTimestamp(2, Timestamp.from(now.minus(Duration.ofMinutes(1))))
        query.setTimestamp(3, Timestamp.from(now.minus(codeFailureWindow)))
        query.executeQuery().use { rows ->
            rows.next()
            rows.getInt(1) >= maxCodeFailuresPerDevice || rows.getInt(2) >= maxCodeFailuresPerMinute
        }
    }

    private fun recordCodeFailure(connection: Connection, mac: String) {
        connection.prepareStatement("INSERT INTO hotspot_code_attempts (id, mac) VALUES (?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setString(2, mac)
            insert.executeUpdate()
        }
        // Older attempts no longer count toward any limit.
        connection.prepareStatement("DELETE FROM hotspot_code_attempts WHERE created_at < ?").use { delete ->
            delete.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(1))))
            delete.executeUpdate()
        }
    }

    private fun insertRegistration(
        connection: Connection,
        id: UUID,
        owner: Owner,
        values: FormValues,
        birthday: LocalDate?,
        guest: GuestContext,
        accessMode: String,
        // The company whose access this device continues here (shared networks).
        roamedFrom: String? = null,
    ) = HotspotGuestCodes.insertRegistration(
        connection, id, (owner as? Owner.Customer)?.id, (owner as? Owner.User)?.id, (owner as? Owner.User)?.company,
        values, birthday, guest, accessMode, roamedFrom,
    )

    private fun recordAccess(
        connection: Connection,
        registrationId: UUID,
        owner: Owner,
        email: String?,
        mac: String,
        result: GuestAuthorization,
        minutes: Int,
        passId: UUID? = null,
        sessionId: UUID? = null,
        joined: Boolean = false,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO hotspot_accesses (id, registration_id, email, mac, authorized, minutes, unifi_message, user_id, user_company, pass_id, session_id, joined)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, registrationId)
            insert.setString(3, email)
            insert.setString(4, mac)
            insert.setBoolean(5, result.authorized)
            insert.setInt(6, minutes)
            insert.setString(7, result.message.take(1000))
            insert.setObject(8, (owner as? Owner.User)?.id)
            insert.setString(9, (owner as? Owner.User)?.company)
            insert.setObject(10, passId)
            insert.setObject(11, sessionId)
            insert.setBoolean(12, joined)
            insert.executeUpdate()
        }
    }

    private fun minutesUntilReconnect(connection: Connection, email: String, reconnectMinutes: Int): Long? =
        HotspotGuestCodes.minutesUntilReconnect(connection, email, reconnectMinutes)

    // Guests are kept in Clientes. Existing customers are linked without changing their data or type.
    private fun findOrCreateCustomer(connection: Connection, values: FormValues): UUID =
        HotspotActivity.findOrCreateGuestCustomer(connection, values.name, values.email, values.phone)

    /** Resolves Host -> company -> tenant, checks the module is enabled and runs [block]; responds itself on failure. */
    private suspend fun ApplicationCall.withPortal(block: (PortalTarget) -> String): String? {
        // Nginx forwards the original Host header for every hotspot domain.
        val host = request.host().lowercase().removeSuffix(".")
        val html = try {
            withContext(Dispatchers.IO) {
                // The portal stops answering as soon as the server owner removes the module.
                if (!access.isInstalled(HotspotModule.id)) return@withContext null
                // The company of the domain and its colors (Empresa), which the portal uses.
                val (companyCode, brand) = access.centralConnection().use { central ->
                    central.prepareStatement(
                        """
                        SELECT domains.company_code, companies.primary_color, companies.secondary_color, companies.accent_color
                        FROM public_domains domains
                        LEFT JOIN companies ON companies.code = domains.company_code
                        WHERE domains.domain = ? AND domains.module_id = ? AND domains.status <> 'removing'
                        """.trimIndent()
                    ).use { query ->
                        query.setString(1, host)
                        query.setString(2, HotspotModule.id)
                        query.executeQuery().use { rows ->
                            if (!rows.next()) null else rows.getString(1) to PortalBrand.of(rows.getString(2), rows.getString(3), rows.getString(4))
                        }
                    }
                } ?: return@withContext null
                val tenant = access.tenantConnectionForCompany(companyCode) ?: return@withContext null
                tenant.use { connection ->
                    if (!moduleEnabled(connection)) return@withContext null
                    val settings = HotspotSettingsRepository.load(connection).let { it.copy(brand = brand.with(it.portalColors), templates = HotspotTemplates.load(connection)) }
                    if (settings.domain != host) return@withContext null
                    val controller = HotspotIntegrations.controller(connection)?.takeIf { it.isConfigured(settings) }
                    if (controller == null) {
                        HotspotPortalPages.message(settings, tr("Portal en configuracion"), tr("Este portal WiFi aun no esta listo. Avisa al personal del local."), retry = false)
                    } else {
                        block(PortalTarget(companyCode, connection, settings, controller))
                    }
                }
            }
        } catch (failure: Exception) {
            log.error("Hotspot portal error for host {}", host, failure)
            respondHtml(HotspotPortalPages.message(null, tr("Ocurrio un error"), tr("Intenta de nuevo en unos segundos.")), HttpStatusCode.InternalServerError)
            return null
        }
        if (html == null) {
            respondHtml(HotspotPortalPages.message(null, tr("Portal no encontrado"), tr("Este dominio no tiene un portal WiFi activo."), retry = false), HttpStatusCode.NotFound)
        }
        return html
    }

    private fun moduleEnabled(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
    ).use { query ->
        query.setString(1, HotspotModule.id)
        query.executeQuery().use { rows -> rows.next() }
    }

    private fun guestContext(parameters: Parameters) = GuestContext(
        mac = normalizeMac(parameters["id"]),
        apMac = normalizeMac(parameters["ap"]),
        ssid = parameters["ssid"].orEmpty().take(64),
        continueUrl = safeUrl(parameters["url"]),
    )

    private fun normalizeMac(value: String?): String = value.orEmpty().trim().lowercase().replace('-', ':')

    private fun safeUrl(value: String?): String = value.orEmpty().trim().takeIf {
        it.length <= 500 && (it.startsWith("http://") || it.startsWith("https://"))
    }.orEmpty()

    private fun codeHash(codeId: UUID, code: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("$codeId:$code".toByteArray(Charsets.UTF_8))
    )

    private suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
        response.header("Cache-Control", "no-store")
        response.header("X-Frame-Options", "DENY")
        response.header("Referrer-Policy", "no-referrer")
        response.header("Content-Security-Policy", HotspotPortalPages.ContentSecurityPolicy)
        respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
    }

    private data class PendingCode(
        val registrationId: UUID,
        val customerId: UUID?,
        val name: String,
        val email: String,
        val phone: String,
        val mac: String,
        val ssid: String,
    )

    // Who a registration and its accesses belong to: a customer (form or customer code) or a user.
    private sealed interface Owner {
        data class Customer(val id: UUID?) : Owner
        data class User(val id: UUID, val company: String) : Owner

        fun codeOwner(): CodeOwner = when (this) {
            is Customer -> CodeOwner(customerId = id)
            is User -> CodeOwner(userId = id, userCompany = company)
        }
    }

    private data class CodeCustomer(val id: UUID, val name: String, val email: String, val phone: String, val type: String)
}
