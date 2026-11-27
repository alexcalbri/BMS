package com.ideasdeveloper.bms.modules.eventos

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Lo que Eventos escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): eventos, inscripciones, lista de
 * espera, asistencia, abonos, clientes creados desde eventos y configuracion. Textos en castellano fijo (nunca `tr`).
 * Cada fila se escribe en la misma transaccion que el cambio, antes de confirmarla.
 */
internal object EventosActivity {
    const val EventType = "event"
    const val RegistrationType = "registration"
    const val PaymentType = "payment"
    const val CustomerType = "customer"
    const val SettingsType = "settings"

    val EventLabels: Map<String, String> = linkedMapOf(
        "title" to "Título", "description" to "Descripción", "category" to "Categoría", "series" to "Serie", "space" to "Espacio",
        "startsAt" to "Inicio", "endsAt" to "Fin", "capacity" to "Cupo", "hostName" to "Anfitrión", "responsible" to "Responsable",
        "priceMember" to "Precio socio", "pricePublic" to "Precio público", "client" to "Cliente", "agreedAmount" to "Monto acordado",
        "guests" to "Invitados", "notes" to "Notas",
    )
    val RegistrationLabels: Map<String, String> = linkedMapOf(
        "customer" to "Cliente", "status" to "Estado", "seats" to "Plazas", "priceKind" to "Tarifa", "amount" to "Importe",
        "paid" to "Pagado", "paymentNote" to "Nota del pago", "attended" to "Asistencia",
    )
    val PaymentLabels: Map<String, String> = linkedMapOf("paidOn" to "Fecha", "amount" to "Importe", "method" to "Método", "note" to "Nota")
    val SettingsLabels: Map<String, String> = linkedMapOf("currency" to "Moneda", "spaces" to "Espacios", "categories" to "Categorías")
    val CustomerLabels: Map<String, String> = linkedMapOf("name" to "Nombre", "email" to "Correo", "phone" to "Teléfono")

    private val DateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val Day: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Escribe una fila de Eventos en [tenant], con la conexion y la transaccion del cambio. */
    fun write(
        tenant: Connection,
        actor: ActivityActor,
        type: String,
        id: UUID?,
        label: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        ActivityLog.record(tenant, actor, ActivityEntry(EventosModule.id, type, id?.toString(), label, action, summary, changes))
    }

    private fun text(value: String?): String? = value?.takeIf { it.isNotBlank() }

    fun dateTime(instant: Instant?): String? = instant?.atZone(ZoneId.systemDefault())?.format(DateTime)

    fun day(date: LocalDate): String = date.format(Day)

    fun money(amount: BigDecimal?): String? = amount?.let { ActivityChanges.format(it) }

    private fun statusName(status: EventStatus): String = when (status) {
        EventStatus.Draft -> "Borrador"
        EventStatus.Published -> "Publicado"
        EventStatus.Full -> "Lleno"
        EventStatus.Done -> "Realizado"
        EventStatus.Cancelled -> "Cancelado"
    }

    private fun registrationStatusName(status: RegistrationStatus): String = when (status) {
        RegistrationStatus.Registered -> "Inscrito"
        RegistrationStatus.Waitlist -> "En lista de espera"
        RegistrationStatus.Cancelled -> "Cancelado"
    }

    private fun priceKindName(kind: PriceKind): String = when (kind) {
        PriceKind.Member -> "Socio"
        PriceKind.General -> "General"
        PriceKind.Courtesy -> "Cortesía"
    }

    /** Valores de un evento tal como se guardaria con [input] (responsable y cliente con su nombre). */
    fun valuesOf(input: EventInput, responsible: String?, client: String?): Map<String, Any?> = mapOf(
        "title" to text(input.title.trim()),
        "description" to text(input.description),
        "category" to text(input.category.trim()),
        "series" to text(input.series.trim()),
        "space" to text(input.space.trim()),
        "startsAt" to dateTime(input.startsAt),
        "endsAt" to dateTime(input.endsAt),
        "capacity" to input.capacity?.toString(),
        "hostName" to text(input.hostName.trim()),
        "responsible" to responsible,
        "priceMember" to money(input.priceMember),
        "pricePublic" to money(input.pricePublic),
        "client" to client,
        "agreedAmount" to money(input.agreedAmount),
        "guests" to input.guests?.toString(),
        "notes" to text(input.notes),
    )

    /** Valores de un evento ya leido (sin estado: el estado tiene su propia fila). */
    fun valuesOf(event: Event, responsible: String?): Map<String, Any?> = mapOf(
        "title" to text(event.title),
        "description" to text(event.description),
        "category" to text(event.category),
        "series" to text(event.series),
        "space" to text(event.space),
        "startsAt" to dateTime(event.startsAt),
        "endsAt" to dateTime(event.endsAt),
        "capacity" to event.capacity?.toString(),
        "hostName" to text(event.hostName),
        "responsible" to responsible,
        "priceMember" to money(event.priceMember),
        "pricePublic" to money(event.pricePublic),
        "client" to text(event.clientName),
        "agreedAmount" to money(event.agreedAmount),
        "guests" to event.guests?.toString(),
        "notes" to text(event.notes),
    )

    fun eventCreated(tenant: Connection, actor: ActivityActor, id: UUID, input: EventInput, responsible: String?, client: String?, summary: String) {
        write(
            tenant, actor, EventType, id, input.title.trim(), ActivityActions.Created, summary,
            ActivityChanges.created(valuesOf(input, responsible, client), EventLabels),
        )
    }

    /** Edicion de un evento: solo se escribe si algun campo cambia. El tipo y el estado no cambian aqui. */
    fun eventUpdated(tenant: Connection, actor: ActivityActor, before: Event, beforeResponsible: String?, input: EventInput, responsible: String?, client: String?) {
        val changes = ActivityChanges.diff(valuesOf(before, beforeResponsible), valuesOf(input, responsible, client), EventLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, EventType, before.id, input.title.trim(), ActivityActions.Updated, "Modifica el evento ${input.title.trim()}.", changes)
    }

    /** Un evento que la importacion actualiza: lo que cambia entre el antes y el despues (solo se escribe si hay cambios). */
    fun eventChanged(tenant: Connection, actor: ActivityActor, before: Event, beforeResponsible: String?, after: Event, afterResponsible: String?, summary: String) {
        val changes = ActivityChanges.diff(valuesOf(before, beforeResponsible), valuesOf(after, afterResponsible), EventLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, EventType, before.id, after.title, ActivityActions.Updated, summary, changes)
    }

    /** Cambio de estado del evento (publicar, cancelar con motivo, realizar, reabrir, llenar). */
    fun eventStatusChanged(tenant: Connection, actor: ActivityActor, id: UUID, title: String, from: EventStatus, to: EventStatus, reason: String, automatic: Boolean = false) {
        val summary = when {
            automatic && to == EventStatus.Done -> "Marca como realizado el evento $title al terminar (cierre automático)."
            to == EventStatus.Published -> "Publica el evento $title."
            to == EventStatus.Cancelled -> "Cancela el evento $title" + (text(reason)?.let { ": $it." } ?: ".")
            to == EventStatus.Done -> "Marca como realizado el evento $title."
            to == EventStatus.Full -> "Marca el evento $title como lleno."
            to == EventStatus.Draft -> "Reabre el evento $title como borrador."
            else -> "Cambia el estado del evento $title."
        }
        val changes = buildList {
            add(ActivityChange("status", "Estado", statusName(from), statusName(to)))
            if (to == EventStatus.Cancelled) text(reason)?.let { add(ActivityChange("reason", "Motivo", null, it)) }
        }
        write(tenant, actor, EventType, id, title, ActivityActions.StatusChanged, summary, changes)
    }

    private fun registrationValues(customer: String, status: RegistrationStatus, seats: Int, priceKind: PriceKind, amount: BigDecimal, paid: Boolean, paymentNote: String?): Map<String, Any?> = mapOf(
        "customer" to customer,
        "status" to registrationStatusName(status),
        "seats" to seats.toString(),
        "priceKind" to priceKindName(priceKind),
        "amount" to money(amount),
        "paid" to paid,
        "paymentNote" to text(paymentNote),
    )

    fun registrationCreated(
        tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, customer: String,
        status: RegistrationStatus, seats: Int, priceKind: PriceKind, amount: BigDecimal, paid: Boolean,
    ) {
        val summary = if (status == RegistrationStatus.Waitlist) {
            "Pone a $customer en la lista de espera de $eventTitle."
        } else {
            "Inscribe a $customer en $eventTitle ($seats ${if (seats == 1) "plaza" else "plazas"})."
        }
        write(
            tenant, actor, RegistrationType, id, "Inscripción de $customer en $eventTitle", ActivityActions.Created, summary,
            ActivityChanges.created(registrationValues(customer, status, seats, priceKind, amount, paid, null), RegistrationLabels),
        )
    }

    /** Paso de la lista de espera a inscrito, o cancelacion de una inscripcion. */
    fun registrationStatusChanged(tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, customer: String, from: RegistrationStatus, to: RegistrationStatus) {
        val summary = when (to) {
            RegistrationStatus.Registered -> "Pasa a $customer de la lista de espera a inscrito en $eventTitle."
            RegistrationStatus.Cancelled -> "Cancela la inscripción de $customer en $eventTitle."
            RegistrationStatus.Waitlist -> "Pone a $customer en la lista de espera de $eventTitle."
        }
        write(
            tenant, actor, RegistrationType, id, "Inscripción de $customer en $eventTitle", ActivityActions.StatusChanged, summary,
            listOf(ActivityChange("status", "Estado", registrationStatusName(from), registrationStatusName(to))),
        )
    }

    fun registrationPaidChanged(tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, customer: String, paid: Boolean, note: String) {
        val summary = if (paid) "Marca como pagada la inscripción de $customer en $eventTitle." else "Quita el pago de la inscripción de $customer en $eventTitle."
        write(
            tenant, actor, RegistrationType, id, "Inscripción de $customer en $eventTitle", ActivityActions.Updated, summary,
            buildList {
                add(ActivityChange.of("paid", "Pagado", !paid, paid))
                text(note)?.let { add(ActivityChange("paymentNote", "Nota del pago", null, it)) }
            },
        )
    }

    fun registrationAttendanceChanged(tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, customer: String, attended: Boolean) {
        val summary = if (attended) "Registra la asistencia de $customer a $eventTitle." else "Quita la asistencia de $customer a $eventTitle."
        write(
            tenant, actor, RegistrationType, id, "Inscripción de $customer en $eventTitle", ActivityActions.Updated, summary,
            listOf(ActivityChange.of("attended", "Asistencia", !attended, attended)),
        )
    }

    private fun paymentValues(paidOn: LocalDate, amount: BigDecimal, method: String, note: String): Map<String, Any?> = mapOf(
        "paidOn" to day(paidOn), "amount" to money(amount), "method" to text(method), "note" to text(note),
    )

    fun paymentCreated(tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, paidOn: LocalDate, amount: BigDecimal, method: String, note: String) {
        write(
            tenant, actor, PaymentType, id, "Abono de $eventTitle", ActivityActions.Created,
            "Registra un abono de ${money(amount)} en $eventTitle.",
            ActivityChanges.created(paymentValues(paidOn, amount, method, note), PaymentLabels),
        )
    }

    /** Un abono que se borra: la fila se escribe antes del DELETE, con su importe y fecha. */
    fun paymentDeleted(tenant: Connection, actor: ActivityActor, id: UUID, eventTitle: String, paidOn: LocalDate, amount: BigDecimal, method: String, note: String) {
        write(
            tenant, actor, PaymentType, id, "Abono de $eventTitle", ActivityActions.Deleted,
            "Elimina un abono de ${money(amount)} de $eventTitle.",
            ActivityChanges.deleted(paymentValues(paidOn, amount, method, note), PaymentLabels),
        )
    }

    fun settingsChanged(tenant: Connection, actor: ActivityActor, before: Map<String, Any?>, after: Map<String, Any?>) {
        val changes = ActivityChanges.diff(before, after, SettingsLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, SettingsType, null, "Eventos", ActivityActions.SettingsChanged, "Cambia la configuración de eventos.", changes)
    }

    fun customerCreated(tenant: Connection, actor: ActivityActor, id: UUID, name: String, email: String, phone: String) {
        write(
            tenant, actor, CustomerType, id, name, ActivityActions.Created, "Crea el cliente $name desde eventos.",
            ActivityChanges.created(mapOf("name" to text(name), "email" to text(email), "phone" to text(phone)), CustomerLabels),
        )
    }

    /** Valores de la configuracion de eventos (moneda, espacios y categorias). */
    fun settingsValues(currency: String, spaces: List<String>, categories: List<String>): Map<String, Any?> = mapOf(
        "currency" to currency,
        "spaces" to spaces.joinToString(", ").ifEmpty { null },
        "categories" to categories.joinToString(", ").ifEmpty { null },
    )
}
