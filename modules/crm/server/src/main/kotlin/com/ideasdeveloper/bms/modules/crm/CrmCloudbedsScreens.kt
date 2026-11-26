package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
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
import java.time.LocalDate

/** Rooms of Cloudbeds for a lead, the reservation and the Cloudbeds settings. */
object CrmCloudbedsScreens {
    private const val UiRoute = CrmScreens.UiRoute

    data class Stay(val start: LocalDate, val end: LocalDate, val adults: Int, val children: Int)

    /** Dates and guests of the search: the params, else the lead's dates (tomorrow and 2 nights by default). */
    fun stay(lead: CrmLead, params: Map<String, String>, today: LocalDate): Stay {
        fun date(key: String) = params[key]?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CrmValidationException(tr("Las fechas deben tener el formato AAAA-MM-DD."))
        }
        val start = date("start") ?: lead.targetStart ?: today.plusDays(1)
        val end = date("end") ?: lead.targetEnd?.takeIf { it.isAfter(start) } ?: start.plusDays(2)
        val adults = params["adults"]?.toIntOrNull()?.coerceIn(1, 50) ?: 2
        val children = params["children"]?.toIntOrNull()?.coerceIn(0, 50) ?: 0
        return Stay(start, end, adults, children)
    }

    // ---- Search of free rooms ----

    fun rooms(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>): List<UiNode> {
        val stay = try {
            stay(lead, params, context.today)
        } catch (failure: CrmValidationException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val keep = mapOf("lead" to lead.id.toString(), "start" to stay.start.toString(), "end" to stay.end.toString(), "adults" to stay.adults.toString(), "children" to stay.children.toString())
        val form = UiCard(
            listOf(
                UiForm(
                    "stay",
                    listOf(
                        UiField("start", tr("Entrada"), type = "date", required = true),
                        UiField("end", tr("Salida"), type = "date", required = true),
                        UiField("adults", tr("Adultos"), type = "number"),
                        UiField("children", tr("Ninos"), type = "number"),
                    ),
                    keep - "lead",
                    submit = UiButton(tr("Buscar habitaciones"), UiNavigate("rooms", mapOf("lead" to lead.id.toString()), formId = "stay"), style = "secondary"),
                    minFieldWidth = 160,
                ),
            ),
        )
        val chosen = CrmHost.chosenRooms(context.tenant, lead.id).map { it.externalId }.toSet()
        // Sin la extensión instalada no hay búsqueda: se muestra el aviso en lugar de la lista.
        val provider = CrmModuleExtensions.cloudbeds() ?: return listOf(form, UiText(tr(CrmCloudbedsMissing), tone = UiTone.Warning))
        val result = try {
            provider.availability(CrmHost, context.tenant, stay.start, stay.end, stay.adults, stay.children)
        } catch (failure: CrmCloudbedsException) {
            return listOf(form, UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        } catch (failure: CrmValidationException) {
            return listOf(form, UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val nights = stay.end.toEpochDay() - stay.start.toEpochDay()
        val list = UiList(
            result.map { room ->
                val isChosen = room.roomId in chosen
                UiListItem(
                    title = "${room.roomName} - ${room.roomTypeName}",
                    subtitle = listOfNotNull(
                        room.maxGuests.takeIf { it > 0 }?.let { tr("Hasta {0} personas", it) },
                        room.total?.let { tr("Total {0} noche(s): {1}", nights, it.toPlainString()) },
                    ).joinToString(" - "),
                    trailing = if (isChosen) tr("Asignada") else tr("Asignar"),
                    badges = listOfNotNull(UiBadge(tr("Asignada"), UiTone.Success).takeIf { isChosen }),
                    action = if (isChosen) null else UiRequest(
                        UiRoute + "/rooms",
                        params = keep + mapOf(
                            "op" to "assign", "room" to room.roomId, "type" to room.roomTypeId, "roomName" to room.roomName,
                            "typeName" to room.roomTypeName, "total" to room.total?.toPlainString().orEmpty(), "max" to room.maxGuests.toString(),
                        ),
                    ),
                )
            },
            emptyText = tr("No hay habitaciones libres para esas fechas."),
        )
        return listOf(
            UiRow(listOf(UiButton(tr("Volver al lead"), UiNavigate("lead", mapOf("id" to lead.id.toString())), style = "secondary"), UiText(tr("Habitaciones para {0}", lead.name), UiTextStyle.Subtitle))),
            form,
            UiCard(listOf(UiText(tr("Toca una habitacion para asignarla al lead; puedes asignar varias."), UiTextStyle.Caption), list), title = tr("{0} libre(s) del {1} al {2}", result.size, stay.start, stay.end)),
        )
    }

    // ---- Lead detail: chosen rooms and reservation ----

    fun reservation(context: CrmScreens.Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        // Sin la extension activa no se busca ni se reserva; las reservas ya hechas se siguen mostrando.
        val settings = CrmHost.cloudbedsSettings(context.tenant).let { if (CrmHost.cloudbedsEnabled(context.tenant)) it else it.copy(enabled = false) }
        val reservations = CrmHost.reservationsForLead(context.tenant, lead.id)
        if (!settings.ready && reservations.isEmpty()) return null
        val rooms = CrmHost.chosenRooms(context.tenant, lead.id)
        val existing = reservations.map { reservation ->
            UiStatus(
                tr("Reserva {0} ({1}) del {2} al {3}", reservation.reservationId, CrmReservations.statusLabel(reservation.status), reservation.startDate, reservation.endDate) +
                    (reservation.total?.let { " - ${it.toPlainString()}" }.orEmpty()) + if (reservation.welcomeSent) tr(" - bienvenida enviada") else "",
                when (reservation.status) {
                    "canceled", "no_show" -> UiTone.Danger
                    "checked_out" -> UiTone.Neutral
                    else -> UiTone.Success
                },
            )
        }
        val form = if (canEdit && rooms.isNotEmpty()) {
            val fields = rooms.flatMap { room ->
                listOf(
                    UiField("adults_${room.id}", tr("Adultos en {0}", room.title), type = "number", section = tr("Huespedes por habitacion")),
                    UiField("children_${room.id}", tr("Ninos en {0}", room.title), type = "number", section = tr("Huespedes por habitacion")),
                )
            } + listOf(
                UiField("country", tr("Pais (codigo de 2 letras)"), placeholder = "NI", section = tr("Datos del huesped"), help = tr("Vacio usa el pais por defecto de Integraciones > Cloudbeds.")),
                UiField("zip", tr("Codigo postal"), section = tr("Datos del huesped")),
                UiField("payment", tr("Metodo de pago"), type = "dropdown", required = true, options = CrmCloudbedsStore.paymentLabels.values.toList(), section = tr("Datos del huesped"),
                    help = tr("Cloudbeds lo exige; el cobro se gestiona en Cloudbeds.")),
            )
            val values = rooms.flatMap { room ->
                listOf("adults_${room.id}" to room.data["adults"].orEmpty().ifEmpty { "2" }, "children_${room.id}" to room.data["children"].orEmpty().ifEmpty { "0" })
            }.toMap() + ("payment" to (CrmCloudbedsStore.paymentLabels[settings.paymentMethod] ?: CrmCloudbedsStore.paymentLabels.getValue("cash"))) +
                ("country" to settings.defaultCountry)
            UiForm(
                "reservation", fields, values,
                submit = UiButton(
                    tr("Confirmar reserva"),
                    UiRequest(UiRoute + "/lead", params = mapOf("id" to lead.id.toString(), "op" to "reserve"), formId = "reservation",
                        confirm = tr("Se crea la reserva en Cloudbeds con {0} habitacion(es) y el lead pasa a ganado. Continuar?", rooms.size)),
                ),
                minFieldWidth = 200,
            )
        } else null
        val hint = when {
            !settings.ready -> null
            rooms.isEmpty() && reservations.none { it.status in setOf("confirmed", "not_confirmed", "checked_in") } ->
                UiText(tr("Busca las habitaciones libres para las fechas del cliente y asignale una o varias."), UiTextStyle.Caption)
            lead.email.isBlank() && rooms.isNotEmpty() -> UiText(tr("Cloudbeds exige el correo del huesped: agregalo en los datos del lead."), tone = UiTone.Warning)
            else -> null
        }
        return UiCard(
            existing + listOfNotNull(hint, form),
            title = tr("Reserva (Cloudbeds)"),
            actions = listOfNotNull(UiButton(tr("Buscar habitaciones"), UiNavigate("rooms", mapOf("lead" to lead.id.toString())), style = "secondary").takeIf { canEdit && settings.ready }),
        )
    }

    // ---- Settings: Integraciones > Cloudbeds ----

    fun settings(context: CrmScreens.Context): List<UiNode> {
        val settings = CrmHost.cloudbedsSettings(context.tenant)
        val templates = CrmMail.templates(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val welcome = UiFieldCondition("welcomeEnabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus(tr("Cloudbeds desactivado"), UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus(tr("Error: {0}", settings.lastError), UiTone.Danger)
            else -> UiStatus(tr("Cloudbeds listo"), UiTone.Success)
        }
        return listOf(
            UiText(tr("Cloudbeds (PMS)"), UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(tr("Fechas de la estadia (Fechas de estadia, mas abajo): {0} Son las fechas que Cloudbeds propone al buscar habitaciones.", CrmStay.summary(context.stay, context.fields)), UiTextStyle.Caption),
                    UiText(
                        tr("Con la API key de la propiedad (permisos read:room, read:rate, read:reservation, write:reservation) el lead muestra las habitaciones libres en sus fechas; al confirmar se crea la reserva en Cloudbeds. El cobro se gestiona en Cloudbeds; el metodo de pago solo se envia porque Cloudbeds lo exige. Cada 30 minutos se lee el estado de las reservas."),
                    ),
                    UiForm(
                        "cloudbeds",
                        listOf(
                            UiField("enabled", tr("Usar Cloudbeds"), type = "boolean"),
                            UiField("apiKey", tr("API key"), type = "password", visibleWhen = on),
                            UiField("propertyId", tr("ID de la propiedad"), visibleWhen = on, help = tr("Solo si la API key tiene varias propiedades.")),
                            UiField("paymentMethod", tr("Metodo de pago por defecto"), help = tr("Se puede cambiar en cada reserva."), type = "dropdown", options = CrmCloudbedsStore.paymentLabels.values.toList(), visibleWhen = on),
                            UiField("defaultCountry", tr("Pais por defecto del huesped (codigo de 2 letras)"), placeholder = "US", visibleWhen = on,
                                help = tr("Cloudbeds exige el pais. Se usa en las reservas automaticas y cuando la reserva no lo indica.")),
                            UiField("archiveOnCheckout", tr("Archivar el lead cuando el huesped hace check-out"), type = "boolean", visibleWhen = on),
                            UiField("welcomeEnabled", tr("Enviar correo de bienvenida"), type = "boolean", visibleWhen = on, section = tr("Bienvenida")),
                            UiField("welcomeTemplate", tr("Plantilla"), type = "dropdown", options = templates.map { it.name }, visibleWhen = welcome, section = tr("Bienvenida"),
                                help = tr("Usa {codigo_cliente} para la contrasena del WiFi, {checkin}, {checkout}, {habitaciones} y {reserva}.")),
                            UiField("welcomeDaysBefore", tr("Dias antes del check-in"), type = "number", visibleWhen = welcome, section = tr("Bienvenida")),
                            UiField("welcomeHour", tr("Hora de envio (0-23)"), type = "number", visibleWhen = welcome, section = tr("Bienvenida")),
                        ),
                        mapOf(
                            "enabled" to settings.enabled.toString(),
                            "apiKey" to CrmSecrets.mask(settings.apiKey),
                            "propertyId" to settings.propertyId.orEmpty(),
                            "paymentMethod" to (CrmCloudbedsStore.paymentLabels[settings.paymentMethod] ?: msg("Efectivo")),
                            "defaultCountry" to settings.defaultCountry,
                            "archiveOnCheckout" to settings.archiveOnCheckout.toString(),
                            "welcomeEnabled" to settings.welcomeEnabled.toString(),
                            "welcomeTemplate" to templates.firstOrNull { it.id == settings.welcomeTemplateId }?.name.orEmpty(),
                            "welcomeDaysBefore" to settings.welcomeDaysBefore.toString(),
                            "welcomeHour" to settings.welcomeHour.toString(),
                        ),
                        submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "cbSave"), formId = "cloudbeds")),
                    ),
                ),
                actions = listOf(UiButton(tr("Probar conexion"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "cbTest")), style = "secondary", enabled = settings.ready)),
            ),
        )
    }
}
