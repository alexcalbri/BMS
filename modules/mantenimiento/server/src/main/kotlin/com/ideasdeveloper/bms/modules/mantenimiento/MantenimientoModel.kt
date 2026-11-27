package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tipo de orden: preventiva (por plan), correctiva (avería o a mano) o proyecto (con aprobación). */
enum class OrderKind(val id: String, val label: String) {
    Preventive("preventive", msg("Preventiva")),
    Corrective("corrective", msg("Correctiva")),
    Project("project", msg("Proyecto"));

    companion object {
        fun fromId(id: String?): OrderKind? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** Acepta la etiqueta visible (sin importar mayúsculas) y, por comodidad, el código. */
        fun fromLabel(label: String?): OrderKind? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Estados fijos de una orden: filtros, tablero y agentes dependen de ellos. */
enum class OrderStatus(val id: String, val label: String) {
    Requested("requested", msg("Pendiente de aprobación")),
    Quoting("quoting", msg("En cotización")),
    Open("open", msg("Abierta")),
    InProgress("in_progress", msg("En curso")),
    OnHold("on_hold", msg("En espera")),
    Done("done", msg("Completada")),
    Rejected("rejected", msg("Rechazada")),
    Cancelled("cancelled", msg("Cancelada"));

    /** Sigue necesitando trabajo o decisión: ni completada, ni rechazada, ni cancelada. */
    val isActive: Boolean get() = this != Done && this != Rejected && this != Cancelled

    /** Estados de trabajo: la orden ya fue aprobada (o no lo necesita) y no está cerrada. */
    val isWorking: Boolean get() = this == Open || this == InProgress || this == OnHold

    companion object {
        fun fromId(id: String?): OrderStatus? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): OrderStatus? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

enum class OrderPriority(val id: String, val label: String) {
    Urgent("urgent", msg("Urgente")),
    High("high", msg("Alta")),
    Medium("medium", msg("Media")),
    Low("low", msg("Baja"));

    companion object {
        fun fromId(id: String?): OrderPriority? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): OrderPriority? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

enum class PlanFrequency(val id: String, val label: String) {
    Monthly("monthly", msg("Mensual")),
    Quarterly("quarterly", msg("Trimestral")),
    Yearly("yearly", msg("Anual")),
    EveryNDays("every_n_days", msg("Cada cierto número de días"));

    companion object {
        fun fromId(id: String?): PlanFrequency? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): PlanFrequency? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Calendario fijo (la fecha avanza sola) o desde el último cierre (la próxima fecha sale de cerrar la orden). */
enum class ScheduleMode(val id: String, val label: String) {
    Fixed("fixed", msg("Calendario fijo")),
    Floating("floating", msg("Desde el último cierre"));

    companion object {
        fun fromId(id: String?): ScheduleMode? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun fromLabel(label: String?): ScheduleMode? {
            val text = label?.trim().orEmpty()
            return entries.firstOrNull { it.label.equals(text, ignoreCase = true) } ?: fromId(text)
        }
    }
}

/** Orden en que se listan las órdenes. */
/** [Recent] ordena por la última actualización (cerradas: las más recientes primero). */
enum class OrderSort { Due, Newest, Recent }

/** Se lanza cuando se rompe una regla del módulo (límites, referencias que no existen); el mensaje se muestra al usuario. */
class MantenimientoValidationException(message: String) : Exception(message)

data class Property(val id: UUID, val name: String, val position: Int, val archived: Boolean)

data class Asset(
    val id: UUID,
    val propertyId: UUID?,
    val propertyName: String?,
    val name: String,
    val category: String,
    val location: String,
    val notes: String,
    val archived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Plan(
    val id: UUID,
    val title: String,
    val description: String,
    val propertyId: UUID?,
    val propertyName: String?,
    val assetId: UUID?,
    val assetName: String?,
    val frequency: PlanFrequency,
    val intervalDays: Int?,
    val scheduleMode: ScheduleMode,
    val nextDue: LocalDate?,
    val leadDays: Int,
    val defaultAssignee: UUID?,
    val priority: OrderPriority,
    val active: Boolean,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** La fecha fija desde la que se cuentan las ocurrencias del calendario fijo (evita que el fin de mes se arrastre). */
    val anchorDate: LocalDate? = null,
    /** Último intento fallido del generador: el plan se excluye del lote unas horas. */
    val lastErrorAt: Instant? = null,
)

data class PlanCheck(val id: UUID, val planId: UUID, val text: String, val position: Int)

data class Order(
    val id: UUID,
    val number: Long,
    val kind: OrderKind,
    val status: OrderStatus,
    val priority: OrderPriority,
    val title: String,
    val description: String,
    val propertyId: UUID?,
    val propertyName: String?,
    val assetId: UUID?,
    val assetName: String?,
    val planId: UUID?,
    val planDue: LocalDate?,
    val dueDate: LocalDate?,
    val assigneeId: UUID?,
    val requestedBy: UUID?,
    val requestedByName: String,
    val department: String,
    val quoteAmount: BigDecimal?,
    val quoteCurrency: String,
    val quotedBy: String,
    val costAmount: BigDecimal?,
    val costCurrency: String,
    val resolution: String,
    val rejectedReason: String,
    val approvedAt: Instant?,
    val approvedBy: UUID?,
    val completedAt: Instant?,
    val completedBy: UUID?,
    val tareasTaskId: UUID?,
    val purchaseRequestId: UUID?,
    val purchaseFolio: String,
    val purchaseLinkingAt: Instant?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
) {
    /** El folio visible, por ejemplo "OT-0007". */
    val displayNumber: String get() = MantenimientoFolio.format(number)

    /** Abierta de verdad y con la fecha límite ya pasada. */
    fun isOverdue(today: LocalDate): Boolean = status.isActive && dueDate != null && dueDate.isBefore(today)
}

object MantenimientoFolio {
    fun format(number: Long): String = "OT-" + number.toString().padStart(4, '0')

    /** Lee "OT-0007", "ot-7" o "7"; null si no es un folio. */
    fun parse(text: String?): Long? {
        val clean = text?.trim()?.removePrefix("OT-")?.removePrefix("ot-")?.trim().orEmpty()
        return clean.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { char -> char.isDigit() } }?.toLongOrNull()?.takeIf { it > 0 }
    }
}

data class OrderCheck(
    val id: UUID,
    val orderId: UUID,
    val text: String,
    val done: Boolean,
    val position: Int,
    val doneBy: UUID?,
    val doneAt: Instant?,
)

/** [kind] es note, status, assign o system. */
data class OrderNote(val id: UUID, val orderId: UUID, val kind: String, val body: String, val authorId: UUID?, val authorName: String, val createdAt: Instant)

data class Attachment(
    val id: UUID,
    val orderId: UUID?,
    val name: String,
    val path: String,
    val contentType: String,
    val size: Long,
    val uploadedBy: UUID?,
    val createdAt: Instant,
)

/** Un usuario asignable: [label] es lo que ven pantallas y agentes (único dentro de la lista). */
data class MantenimientoUser(val id: UUID, val label: String, val email: String?)

/** Quién hace un cambio: su id (null para el dueño del servidor, que no es usuario de la empresa) y su nombre para las notas. */
/** Quien hace un cambio de orden: [name] para su historial; [activity] para el registro de actividad de la empresa. */
data class MantenimientoActor(val userId: UUID?, val name: String, val activity: com.ideasdeveloper.bms.server.activity.ActivityActor)

data class MantenimientoSettings(val tareasBoardId: UUID?, val tareasPublisherId: UUID?, val dueSoonDays: Int)

data class OrderMetrics(val open: Int, val overdue: Int, val pendingApproval: Int, val completedThisMonth: Int)

/** Aviso de vencimiento pendiente: [kind] es "due_soon" o "overdue". */
data class ReminderCandidate(val orderId: UUID, val userId: UUID, val kind: String, val dueDate: LocalDate, val number: Long, val title: String)

data class OrderFilter(
    val status: OrderStatus? = null,
    /** Solo las que siguen abiertas (ni completadas, ni rechazadas, ni canceladas). */
    val activeOnly: Boolean = false,
    /** Solo las cerradas (completadas, rechazadas o canceladas). */
    val closedOnly: Boolean = false,
    /** Con última actualización igual o posterior: acota las cerradas a una ventana reciente. */
    val closedSince: Instant? = null,
    val kind: OrderKind? = null,
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val assigneeId: UUID? = null,
    val planId: UUID? = null,
    /** Las asignadas a este usuario y las que reportó, abiertas ("Mis órdenes"). */
    val mineOf: UUID? = null,
    /** Con fecha límite igual o anterior a ésta. */
    val dueOnOrBefore: LocalDate? = null,
    val query: String? = null,
    val sort: OrderSort = OrderSort.Due,
    val limit: Int = 200,
)

private val MinDate: LocalDate = LocalDate.of(2000, 1, 1)
private val MaxDate: LocalDate = LocalDate.of(2100, 12, 31)
private val MaxAmount = BigDecimal("999999999999.99")
private val CurrencyPattern = Regex("^[A-Z]{3}$")

private fun MutableList<String>.checkDate(label: String, date: LocalDate?) {
    if (date != null && (date.isBefore(MinDate) || date.isAfter(MaxDate))) add(tr("{0} debe estar entre {1} y {2}.", label, MinDate, MaxDate))
}

private fun MutableList<String>.checkAmount(label: String, amount: BigDecimal?) {
    if (amount == null) return
    if (amount.signum() < 0) add(tr("{0} no puede ser negativo.", label))
    else if (amount > MaxAmount) add(tr("{0} es demasiado grande.", label))
    else if (amount.stripTrailingZeros().scale() > 2) add(tr("{0} admite hasta 2 decimales.", label))
}

/** Lo que un formulario o un agente envía para crear o editar una orden (estado y responsable se cambian aparte al editar). */
data class OrderInput(
    val kind: OrderKind = OrderKind.Corrective,
    val title: String,
    val description: String = "",
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val priority: OrderPriority = OrderPriority.Medium,
    val dueDate: LocalDate? = null,
    val assigneeId: UUID? = null,
    val department: String = "",
    val quoteAmount: BigDecimal? = null,
    val quoteCurrency: String = "USD",
    val quotedBy: String = "",
    val costAmount: BigDecimal? = null,
    val costCurrency: String = "USD",
    val resolution: String = "",
) {
    /** Mensajes para el usuario; vacío si la entrada es válida. */
    fun validate(): List<String> = buildList {
        if (title.isBlank()) add(tr("El título es obligatorio."))
        else if (title.trim().length > MaxTitle) add(tr("El título admite hasta {0} caracteres.", MaxTitle))
        if (description.length > MaxDescription) add(tr("La descripción admite hasta {0} caracteres.", MaxDescription))
        if (department.trim().length > MaxDepartment) add(tr("El departamento admite hasta {0} caracteres.", MaxDepartment))
        if (quotedBy.trim().length > MaxPerson) add(tr("El nombre de quien cotizó admite hasta {0} caracteres.", MaxPerson))
        if (resolution.length > MaxResolution) add(tr("La resolución admite hasta {0} caracteres.", MaxResolution))
        if (!CurrencyPattern.matches(quoteCurrency.trim())) add(tr("La moneda de la cotización debe tener 3 letras mayúsculas, por ejemplo USD."))
        if (!CurrencyPattern.matches(costCurrency.trim())) add(tr("La moneda del costo debe tener 3 letras mayúsculas, por ejemplo USD."))
        checkAmount(tr("La cotización"), quoteAmount)
        checkAmount(tr("El costo"), costAmount)
        checkDate(tr("La fecha límite"), dueDate)
    }

    companion object {
        const val MaxTitle = 200
        const val MaxDescription = 20000
        const val MaxDepartment = 80
        const val MaxPerson = 120
        const val MaxResolution = 5000
    }
}

/** Lo que se envía para crear o editar un plan preventivo; [checklist] reemplaza la lista de comprobación del plan. */
data class PlanInput(
    val title: String,
    val description: String = "",
    val propertyId: UUID? = null,
    val assetId: UUID? = null,
    val frequency: PlanFrequency = PlanFrequency.Monthly,
    val intervalDays: Int? = null,
    val scheduleMode: ScheduleMode = ScheduleMode.Fixed,
    val nextDue: LocalDate? = null,
    val leadDays: Int = 7,
    val defaultAssignee: UUID? = null,
    val priority: OrderPriority = OrderPriority.Medium,
    val checklist: List<String> = emptyList(),
) {
    /** [requireNextDue] es false al editar un plan "desde el último cierre" que espera su próxima fecha. */
    fun validate(requireNextDue: Boolean = true): List<String> = buildList {
        if (title.isBlank()) add(tr("El título es obligatorio."))
        else if (title.trim().length > MaxTitle) add(tr("El título admite hasta {0} caracteres.", MaxTitle))
        if (description.length > MaxDescription) add(tr("La descripción admite hasta {0} caracteres.", MaxDescription))
        if (frequency == PlanFrequency.EveryNDays) {
            if (intervalDays == null || intervalDays !in 1..MaxIntervalDays) add(tr("Indica cada cuántos días se repite (de 1 a {0}).", MaxIntervalDays))
        } else if (intervalDays != null) {
            add(tr("Los días solo se indican con la frecuencia \"{0}\".", tr(PlanFrequency.EveryNDays.label)))
        }
        if (leadDays !in 0..MaxLeadDays) add(tr("Los días de anticipación van de 0 a {0}.", MaxLeadDays))
        if (requireNextDue && nextDue == null) add(tr("La próxima fecha es obligatoria."))
        checkDate(tr("La próxima fecha"), nextDue)
        if (checklist.size > MaxChecklistItems) add(tr("La lista de comprobación admite hasta {0} elementos.", MaxChecklistItems))
        if (checklist.any { it.isBlank() }) add(tr("Un elemento de la lista de comprobación está vacío."))
        if (checklist.any { it.trim().length > MaxChecklistText }) add(tr("Cada elemento de la lista admite hasta {0} caracteres.", MaxChecklistText))
    }

    companion object {
        const val MaxTitle = 200
        const val MaxDescription = 5000
        const val MaxIntervalDays = 3650
        const val MaxLeadDays = 90
        const val MaxChecklistItems = 100
        const val MaxChecklistText = 300
    }
}

data class AssetInput(
    val propertyId: UUID? = null,
    val name: String,
    val category: String = "",
    val location: String = "",
    val notes: String = "",
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add(tr("El nombre del activo es obligatorio."))
        else if (name.trim().length > MaxName) add(tr("El nombre del activo admite hasta {0} caracteres.", MaxName))
        if (category.trim().length > MaxCategory) add(tr("La categoría admite hasta {0} caracteres.", MaxCategory))
        if (location.trim().length > MaxLocation) add(tr("La ubicación admite hasta {0} caracteres.", MaxLocation))
        if (notes.length > MaxNotes) add(tr("Las notas admiten hasta {0} caracteres.", MaxNotes))
    }

    companion object {
        const val MaxName = 160
        const val MaxCategory = 60
        const val MaxLocation = 160
        const val MaxNotes = 5000
    }
}
