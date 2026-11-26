package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiOpenUrl
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Integrations of the CRM with external channels and systems. */
object CrmIntegrationScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // Public URL Meta calls for this company when it uses its own Meta app, e.g. .../modules/crm/extensions/meta/acme/webhook.
    fun metaHookUrl(context: CrmScreens.Context) = "${context.publicBase}${MetaPaths.companyWebhookPath(context.permissions.companyCode)}"

    // Each integration is an extension of the company: without it installed and active, its card only says how to install it.
    fun integrations(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        fun extension(id: String, nodes: () -> List<UiNode>) = CrmExtensions.missingNodes(context.tenant, id) ?: nodes()
        return extension(CrmExtensionIds.Meta) { meta(context, submitted, pickers = params["numbers"], link = params["link"]) } +
            extension(CrmExtensionIds.Encuentra24) { encuentra24(context) } +
            extension(CrmExtensionIds.WordPress) { CrmWordPressScreens.settings(context, params["secret"]) } +
            extension(CrmExtensionIds.Cloudbeds) { CrmCloudbedsScreens.settings(context) } +
            staySettings(context) + translation(context, submitted)
    }

    // ---- Traduccion de plantillas ----

    // Provider and language mapping used by the templates that translate to the guest's language.
    private fun translation(context: CrmScreens.Context, submitted: Map<String, String>?): List<UiNode> {
        val settings = CrmTranslate.load(context.tenant)
        val fields = CrmTranslate.languageFields(context.fields)
        val ai = UiFieldCondition("provider", listOf(CrmTranslate.providerLabels.getValue("anthropic")))
        val on = UiFieldCondition("provider", CrmTranslate.providerLabels.filterKeys { it.isNotEmpty() }.values.toList())
        val status = when {
            settings.provider.isEmpty() -> UiStatus(tr("Traduccion desactivada"), UiTone.Neutral)
            !settings.ready -> UiStatus(tr("Falta la clave de API"), UiTone.Warning)
            settings.languageField.isEmpty() -> UiStatus(tr("Falta el campo de idioma del huesped"), UiTone.Warning)
            else -> UiStatus(tr("Traduccion lista"), UiTone.Success)
        }
        return listOf(
            UiText(tr("Traduccion"), UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(
                        tr("Las plantillas con \"Traducir al idioma del huesped\" se traducen al idioma del huesped antes de enviarse. Solo se envia al traductor el texto de la plantilla: las variables (nombre, codigo, fechas...) se protegen y nunca salen del servidor. Si la traduccion falla, se envia el original y queda anotado en la actividad del lead."),
                    ),
                    UiForm(
                        "translation",
                        listOf(
                            UiField("provider", tr("Proveedor"), type = "dropdown", options = CrmTranslate.providerLabels.values.toList()),
                            UiField("apiKey", tr("Clave de API"), type = "password", visibleWhen = on),
                            UiField("model", tr("Modelo de IA"), visibleWhen = ai, help = tr("Por defecto {0}.", CrmTranslate.DefaultModel)),
                            UiField(
                                "languageField", tr("Campo de idioma del huesped"), type = "dropdown", options = listOf(CrmTranslate.NoField) + fields.map { it.label },
                                visibleWhen = on, help = tr("Un campo personalizado de tipo texto o lista del lead."),
                            ),
                            UiField(
                                "languageMap", tr("Equivalencias de idioma"), type = "textarea", visibleWhen = on,
                                help = tr("Una por linea: Valor = codigo. Por ejemplo Ingles = en. Si el valor ya es un codigo (en, fr) no hace falta."),
                            ),
                        ),
                        CrmTranslate.toFormValues(settings, context.fields) + submitted.orEmpty().filterKeys { it in setOf("provider", "model", "languageField", "languageMap") },
                        submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "translationSave"), formId = "translation")),
                        minFieldWidth = 220,
                    ),
                ),
            ),
        )
    }

    // ---- Fechas de estadia ----

    // Which custom date fields are the arrival and the departure: Cloudbeds, the hotspot, the exit automation and the pipeline cards use them.
    private fun staySettings(context: CrmScreens.Context): List<UiNode> {
        val options = CrmStay.options(context.fields)
        return listOf(
            UiText(tr("Fechas de estadia"), UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    UiText(
                        tr("Elige cuales de tus campos de fecha son el ingreso y la salida del huesped (los campos y sus secciones se crean en Configuracion > General). Cloudbeds los usa como fechas por defecto, la salida automatica archiva el lead al llegar la salida, las plantillas llenan {checkin} y {checkout}, las tarjetas del pipeline los muestran y el hotspot de WiFi los lee para dar acceso durante la estadia. Sin elegir, esas funciones no ven fechas."),
                    ),
                    if (options.size == 1) UiText(tr("Aun no hay campos de tipo fecha: crea uno en Configuracion > General."), tone = UiTone.Warning) else null,
                    UiForm(
                        "stay",
                        listOf(
                            UiField("start", tr("Campo de ingreso (check-in)"), type = "dropdown", options = options),
                            UiField("end", tr("Campo de salida (check-out)"), type = "dropdown", options = options, help = tr("La salida debe ser igual o posterior al ingreso.")),
                        ),
                        CrmStay.formValues(CrmStay.effective(context.stay, context.fields), context.fields),
                        submit = UiButton(tr("Guardar"), UiRequest(
                            UiRoute + "/integrations", params = mapOf("op" to "staySave"), formId = "stay",
                            // Changing a mapping that is in use can clear the dates Cloudbeds, the hotspot and the exit automation read.
                            confirm = tr(CrmStay.ConfirmClear).takeIf { !CrmStay.effective(context.stay, context.fields).isEmpty },
                        )),
                        minFieldWidth = 220,
                    ),
                ).filterNotNull(),
            ),
        )
    }

    // ---- Encuentra24 Leads API ----

    private fun encuentra24(context: CrmScreens.Context): List<UiNode> {
        val settings = CrmHost.encuentra24Settings(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus(tr("Encuentra24 desactivado"), UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus(tr("Error: {0}", settings.lastError), UiTone.Danger)
            settings.lastSync != null -> UiStatus(tr("Ultima sincronizacion {0}: {1}", timeFormat.format(settings.lastSync.atZone(ZoneId.systemDefault())), settings.lastResult), UiTone.Success)
            else -> UiStatus(tr("Esperando la primera sincronizacion"), UiTone.Warning)
        }
        return listOf(
            UiText("Encuentra24", UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(tr("Cada 5 minutos se importan los mensajes, chats y llamadas de los anuncios, y el anuncio queda en los intereses del lead. La API no permite responder desde el CRM. Un WhatsApp, Messenger, Instagram, correo o formulario con el enlace de un anuncio tambien queda con origen Encuentra24 y con ese anuncio en sus intereses.")),
                    UiForm(
                        "e24",
                        listOf(
                            UiField("enabled", tr("Importar los leads de Encuentra24"), type = "boolean"),
                            UiField("apiKey", tr("API key"), type = "password", visibleWhen = on),
                            UiField("slug", tr("Slug de la empresa"), visibleWhen = on, placeholder = "mi-inmobiliaria"),
                        ),
                        CrmEncuentra24Ads.formValues(settings),
                        submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "e24Save"), formId = "e24")),
                    ),
                ),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "e24history",
                        listOf(UiField("fromDate", tr("Desde"), type = "date", help = tr("Vacio: desde la ultima sincronizacion."))),
                        submit = UiButton(tr("Importar ahora"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "e24Sync"), formId = "e24history"), style = "secondary", enabled = settings.ready),
                    ),
                ),
                title = tr("Importar historial"),
            ),
        )
    }

    // ---- Meta: WhatsApp, Messenger, Instagram ----

    private fun meta(context: CrmScreens.Context, submitted: Map<String, String>?, pickers: String?, link: String?): List<UiNode> {
        val settings = CrmHost.metaSettings(context.tenant)
        val serverApp = CrmHost.metaServerApp()
        val company = context.permissions.companyCode
        val workspace = context.permissions.serverOwner && CrmHost.metaServerWorkspace() == company
        val header = listOfNotNull(
            UiText(tr("Meta Business (WhatsApp, Messenger e Instagram)"), UiTextStyle.Subtitle),
            if (workspace) serverAppCard(context, serverApp ?: CrmMetaServerApp(), submitted) else null,
        )
        if (settings.serverMode) return header + serverModeNodes(context, settings, serverApp, link)
        val switch = if (serverApp?.ready == true) UiCard(
            listOf(
                UiText(tr("El servidor tiene su propia app de Meta: la empresa solo inicia sesion con Facebook y elige su pagina, Instagram y numero de WhatsApp. No hace falta crear ni revisar una app en Meta for Developers."), UiTextStyle.Caption),
            ),
            title = tr("Conectar con la app del servidor"),
            actions = listOf(
                UiButton(
                    tr("Usar la app del servidor"),
                    UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaMode", "mode" to ServerMode), confirm = tr("Se borran el App Secret y los tokens de la app propia. ¿Continuar?")),
                ),
            ),
        ) else null
        return header + listOfNotNull(switch) + ownModeNodes(context, settings, submitted, pickers)
    }

    // Company connected through the server app: status per channel, the connection link and options.
    private fun serverModeNodes(context: CrmScreens.Context, settings: CrmMetaSettings, app: CrmMetaServerApp?, link: String?): List<UiNode> {
        fun status(ready: Boolean, yes: String, no: String) = UiStatus(if (ready) yes else no, if (ready) UiTone.Success else UiTone.Neutral)
        val ready = app?.ready == true
        val log = CrmHost.metaRecent(context.tenant).map { UiTimelineEntry(it.event, it.detail, timeFormat.format(it.at.atZone(ZoneId.systemDefault()))) }
        val instagram = settings.instagramUsername?.let { "@$it" } ?: settings.instagramAccountId
        return listOfNotNull(
            UiCard(
                listOfNotNull(
                    if (!ready) UiStatus(tr("La app de Meta del servidor no esta activa: los mensajes no llegan. Avisa al dueno del servidor."), UiTone.Danger) else null,
                    status(settings.whatsAppReady, tr("WhatsApp: numero {0} conectado", settings.phoneNumberId), tr("WhatsApp sin conectar")),
                    status(settings.ready("messenger"), tr("Messenger: pagina {0}", settings.pageName.orEmpty()), tr("Messenger sin conectar")),
                    status(
                        settings.ready("instagram") && settings.instagramAccountId != null, tr("Instagram: {0}", instagram),
                        if (settings.pageToken != null) tr("Instagram: la pagina no tiene una cuenta vinculada") else tr("Instagram sin conectar"),
                    ),
                    UiText(tr("Pulsa Conectar con Meta, abre la ventana, inicia sesion con la cuenta de Facebook que administra la pagina y el WhatsApp de la empresa, y elige la pagina y el numero. Para cambiar de pagina o de numero, conecta otra vez."), UiTextStyle.Caption),
                    if (link != null) UiButton(tr("Abrir la ventana de Meta"), UiOpenUrl(link)) else null,
                ),
                title = tr("Conexion con la app del servidor"),
                actions = listOfNotNull(
                    UiButton(tr("Conectar con Meta"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaLink")), style = if (link == null) "primary" else "secondary")
                        .takeIf { ready },
                    UiButton(
                        "Desconectar", UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaDisconnect"), confirm = tr("Se dejan de recibir y enviar mensajes de Meta. ¿Desconectar?")),
                        style = "secondary",
                    ).takeIf { settings.enabled },
                ),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "metaOptions",
                        listOf(UiField("recordEchoes", tr("Guardar lo que el equipo responde desde la app de WhatsApp Business (coexistencia)"), type = "boolean")),
                        mapOf("recordEchoes" to settings.recordEchoes.toString()),
                        submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaServerOptions"), formId = "metaOptions"), style = "secondary"),
                    ),
                ),
                title = tr("Opciones"),
            ),
            UiCard(
                listOf(UiText(tr("Configura una app de Meta propia de la empresa en lugar de la del servidor."), UiTextStyle.Caption)),
                title = tr("App propia"),
                actions = listOf(
                    UiButton(
                        tr("Usar una app propia"),
                        UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaMode", "mode" to OwnMode), confirm = tr("Se desconecta la app del servidor. ¿Continuar?")),
                        style = "secondary",
                    ),
                ),
                collapsible = true,
                expanded = false,
            ),
            UiCard(listOf(UiTimeline(log, emptyText = tr("Sin eventos de Meta."))), title = tr("Registro de eventos"), collapsible = true, expanded = false),
        )
    }

    // Server workspace: the server owner's Meta app that every company can connect to.
    private fun serverAppCard(context: CrmScreens.Context, app: CrmMetaServerApp, submitted: Map<String, String>?): UiNode {
        val on = UiFieldCondition("serverEnabled", listOf("true"))
        val base = context.publicBase
        val host = base.substringAfter("://").substringBefore('/').substringBefore(':')
        val steps = listOf(
            tr("1. Portafolio de Meta Business de tu empresa (la del servidor), verificado. En Configuracion del negocio > Informacion del negocio, completa ademas la verificacion de acceso como Tech Provider: es obligatoria para manejar paginas y WhatsApp de otras empresas."),
            tr("2. App de Meta for Developers tipo Business en ese portafolio, con los casos de uso de Messenger (Instagram con Facebook login) y de WhatsApp, y el producto Facebook Login for Business. Permisos: pages_messaging, pages_manage_metadata, pages_read_engagement, pages_show_list, business_management, instagram_basic, instagram_manage_messages, whatsapp_business_messaging y whatsapp_business_management."),
            tr("3. Configuracion > Basica: dominio de la app {0}, URL de la politica de privacidad y, en Facebook Login for Business > Configuracion, Dominios permitidos para el SDK de JavaScript: {1}. Callback de desautorizacion: {1}{2}. Solicitud de eliminacion de datos: {3}{4}.", host, base, MetaPaths.DeauthorizePath, base, MetaPaths.DataDeletionPath),
            tr("4. Facebook Login for Business > Configuraciones: crea una para Messenger e Instagram (tipo token de usuario, con los permisos de paginas e Instagram) y otra de WhatsApp Embedded Signup (registro integrado). Copia sus IDs aqui."),
            tr("5. Webhooks con la Callback URL {0}{1} y el verify token de aqui: Page (messages y standby), Instagram (messages) y WhatsApp Business Account (messages y smb_message_echoes). No hace falta suscribir paginas a mano: cada empresa lo hace al conectarse.", base, MetaPaths.WebhookPath),
            tr("6. App Review de los permisos (el video muestra Conectar con Meta desde el CRM) y publica la app. Mientras tanto solo funcionan las personas con rol en la app."),
        )
        return UiCard(
            steps.map { UiText(it, UiTextStyle.Caption) } + listOf(
                UiForm(
                    "metaServer",
                    listOf(
                        UiField("serverEnabled", tr("Permitir que las empresas del servidor se conecten con esta app"), type = "boolean"),
                        UiField("serverAppId", tr("App ID"), visibleWhen = on),
                        UiField("serverAppSecret", tr("App Secret"), type = "password", visibleWhen = on),
                        UiField("serverVerifyToken", tr("Verify token"), visibleWhen = on, help = tr("El mismo que escribes en los webhooks de la app.")),
                        UiField("serverPagesConfigId", tr("ID de la configuracion de Messenger e Instagram"), visibleWhen = on),
                        UiField("serverWhatsAppConfigId", tr("ID de la configuracion de WhatsApp (Embedded Signup)"), visibleWhen = on),
                    ),
                    CrmHost.metaAppFormValues(app) + submitted.orEmpty().filterKeys { it.startsWith("server") },
                    submit = UiButton(tr("Guardar app del servidor"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaServerApp"), formId = "metaServer")),
                ),
            ),
            title = if (app.ready) tr("App de Meta del servidor (activa)") else tr("App de Meta del servidor"),
            collapsible = true,
            expanded = !app.ready,
        )
    }

    // The company's own Meta app (the screen of the WordPress plugin).
    private fun ownModeNodes(context: CrmScreens.Context, settings: CrmMetaSettings, submitted: Map<String, String>?, pickers: String?): List<UiNode> {
        val on = UiFieldCondition("enabled", listOf("true"))
        val shared = UiFieldCondition("sharedTokens", listOf("true"))
        val own = UiFieldCondition("sharedTokens", listOf("false"))
        val whatsApp = "WhatsApp"
        val messenger = "Messenger"
        val instagram = "Instagram"
        // Common settings first (no section); each channel is an accordion with what only it uses.
        val fields = listOf(
            UiField("enabled", tr("Recibir y responder por WhatsApp, Messenger e Instagram"), type = "boolean"),
            UiField("appSecret", tr("App Secret"), type = "password", visibleWhen = on,
                help = tr("Meta for Developers > tu app > Configuracion > Basica. Valida la firma de cada mensaje de los tres canales.")),
            UiField("verifyToken", tr("Verify token"), visibleWhen = on,
                help = tr("Texto libre que escribes igual en Meta al configurar los webhooks de los tres canales.")),
            UiField("systemToken", tr("Token del usuario del sistema (Meta Business)"), type = "password", visibleWhen = on,
                help = tr("Token que no caduca, con whatsapp_business_messaging y whatsapp_business_management. Para compartirlo con Messenger e Instagram, tambien pages_messaging, pages_show_list, pages_manage_metadata, pages_read_engagement, instagram_basic e instagram_manage_messages.")),
            UiField("sharedTokens", tr("Usar este token tambien para Messenger e Instagram"), type = "boolean", visibleWhen = on,
                help = tr("Al guardar se obtiene el token de la pagina y su cuenta de Instagram. Desactivado, Messenger e Instagram usan cada uno su propio token de pagina.")),
            UiField("wabaId", tr("WhatsApp Business Account ID"), visibleWhen = on, section = whatsApp,
                help = tr("WhatsApp Manager > Configuracion de la cuenta. Al guardar, la app se suscribe a esta cuenta.")),
            UiField("phoneNumberId", tr("Phone Number ID"), visibleWhen = on, section = whatsApp,
                help = tr("Se completa al guardar si la cuenta tiene un solo numero. Solo se reciben los mensajes de este numero.")),
            UiField("recordEchoes", tr("Guardar lo que el equipo responde desde la app de WhatsApp Business (coexistencia)"), type = "boolean", visibleWhen = on, section = whatsApp),
            UiField("pageName", tr("Pagina conectada"), readOnly = true, visibleWhen = on, section = messenger),
            UiField("pageId", tr("ID de la pagina de Facebook"), visibleWhen = shared, section = messenger,
                help = tr("Solo si el token administra varias paginas.")),
            UiField("messengerToken", tr("Token de Messenger"), type = "password", visibleWhen = own, section = messenger,
                help = tr("Token de la pagina con el permiso pages_messaging.")),
            UiField("instagramAccount", tr("Cuenta de Instagram"), readOnly = true, visibleWhen = on, section = instagram,
                help = tr("La cuenta vinculada a la pagina; se lee al guardar.")),
            UiField("instagramToken", tr("Token de Instagram"), type = "password", visibleWhen = own, section = instagram,
                help = tr("Token de la pagina vinculada a la cuenta de Instagram, con instagram_basic e instagram_manage_messages.")),
        )
        val notes = mapOf(
            whatsApp to if (settings.whatsAppReady) tr("Listo: numero {0}", settings.phoneNumberId) else tr("Sin configurar"),
            messenger to when {
                settings.ready("messenger") -> if (settings.sharedTokens) tr("Listo: pagina {0}", settings.pageName.orEmpty()) else tr("Listo (token propio)")
                settings.sharedTokens -> tr("Falta conectar la pagina de Facebook")
                else -> tr("Falta el token de Messenger")
            },
            instagram to when {
                settings.ready("instagram") && settings.instagramAccountId != null ->
                    tr("Listo: {0}", settings.instagramUsername?.let { "@$it" } ?: settings.instagramAccountId)
                settings.sharedTokens && settings.pageToken != null -> tr("La pagina no tiene una cuenta de Instagram vinculada")
                settings.sharedTokens -> tr("Falta conectar la pagina de Facebook")
                settings.instagramToken.isNullOrBlank() -> tr("Falta el token de Instagram")
                else -> tr("Guarda para leer la cuenta de Instagram")
            },
        )
        val numbers = pickers?.split(';')?.mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 2) null else {
                val chosen = parts[0] == settings.phoneNumberId
                val detail = listOf(parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty()).filter { it.isNotEmpty() }.joinToString(" - ")
                UiRow(
                    listOfNotNull(
                        UiText("${parts[1]}${if (detail.isNotEmpty()) " ($detail)" else ""}"),
                        if (chosen) UiStatus(tr("Numero del CRM"), UiTone.Success)
                        else UiButton(tr("Usar este numero"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaNumber", "id" to parts[0])), style = "secondary"),
                    ),
                )
            }
        }.orEmpty()
        val log = CrmHost.metaRecent(context.tenant).map { UiTimelineEntry(it.event, it.detail, timeFormat.format(it.at.atZone(ZoneId.systemDefault()))) }
        return listOfNotNull(
            metaGuide(context, open = !settings.enabled),
            UiCard(
                listOf(
                    UiText(tr("Callback URL de los webhooks, la misma para los tres canales:"), UiTextStyle.Caption),
                    UiText(metaHookUrl(context)),
                    UiForm(
                        "meta", fields, CrmHost.metaToFormValues(settings) + submitted.orEmpty(),
                        submit = UiButton(tr("Guardar y conectar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "metaSave"), formId = "meta")),
                        collapsibleSections = true,
                        sectionNotes = notes,
                    ),
                ),
                title = tr("Configuracion"),
            ),
            numbers.takeIf { it.isNotEmpty() }?.let { UiCard(it, title = tr("Numeros de la cuenta de WhatsApp")) },
            UiCard(listOf(UiTimeline(log, emptyText = tr("Sin eventos de Meta."))), title = tr("Registro de eventos"), collapsible = true, expanded = false),
        )
    }

    // Step by step guide to set up the Meta app and connect it here; open while Meta is off.
    private fun metaGuide(context: CrmScreens.Context, open: Boolean): UiNode {
        val steps = listOf(
            tr("1. Negocio: el portafolio de Meta Business de la empresa debe estar verificado y ser dueno de la pagina, la cuenta de Instagram y la de WhatsApp Business. Vincula Instagram a la pagina y, en la app de Instagram, activa Mensajes > Herramientas conectadas > Permitir acceso a los mensajes."),
            tr("2. App de Meta for Developers (tipo Business, del portafolio de la empresa): agrega los casos de uso de Messenger y de WhatsApp (Instagram se configura dentro del de Messenger, con Facebook login). Permisos: pages_messaging, pages_manage_metadata, pages_read_engagement, pages_show_list, business_management, instagram_basic, instagram_manage_messages, whatsapp_business_messaging y whatsapp_business_management. Copia el App Secret de Configuracion > Basica y publica la app (para responder a cualquier persona Meta pide App Review de pages_messaging e instagram_manage_messages)."),
            tr("3. Usuario del sistema: en Configuracion del negocio > Usuarios del sistema crea uno administrador, asignale la app, la pagina, Instagram y la cuenta de WhatsApp, y genera un token que no caduque con los permisos del paso 2."),
            tr("4. Webhooks, con la Callback URL {0} y el mismo verify token: Messenger (objeto Page) suscribe messages y standby; Instagram suscribe messages; WhatsApp suscribe messages y, si usan la app de WhatsApp Business en el mismo numero, smb_message_echoes.", metaHookUrl(context)),
            tr("5. Aqui: escribe el App Secret, el verify token y el token del sistema; en WhatsApp, el WhatsApp Business Account ID. Pulsa Guardar y conectar: se obtiene el token de la pagina, se suscribe Instagram y la cuenta de WhatsApp. Envia un mensaje de prueba por cada canal y revisa el registro de eventos: firma_rechazada significa que el App Secret no coincide."),
        )
        return UiCard(steps.map { UiText(it, UiTextStyle.Caption) }, title = tr("Guia paso a paso"), collapsible = true, expanded = open)
    }
}
