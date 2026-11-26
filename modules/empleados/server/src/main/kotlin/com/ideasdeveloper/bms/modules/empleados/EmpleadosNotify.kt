package com.ideasdeveloper.bms.modules.empleados

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Avisos en el centro de notificaciones del Core. Un aviso nunca rompe el cambio que lo causo: cada uno se crea dentro de
 * runCatching y un fallo solo se registra. El autor del cambio nunca recibe el aviso. La importacion no avisa.
 */
object EmpleadosNotify {
    private val log = LoggerFactory.getLogger(EmpleadosNotify::class.java)

    private const val MaxText = 1000

    // Abreviaturas en espanol (texto fuente). En ingles se usan las de abajo; "mar" es martes y marzo a la vez,
    // por eso no pasan por el catalogo.
    private val WeekdayNames = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val WeekdayNamesEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MonthNames = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val MonthNamesEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun weekdayName(index: Int) = if (I18n.language() == I18n.Source) WeekdayNames[index] else WeekdayNamesEn[index]

    private fun monthName(index: Int) = if (I18n.language() == I18n.Source) MonthNames[index] else MonthNamesEn[index]

    /** "lun 24 nov" (en ingles, "Mon 24 Nov"). */
    fun dayLabel(date: LocalDate): String = "${weekdayName(date.dayOfWeek.value - 1)} ${date.dayOfMonth} ${monthName(date.monthValue - 1)}"

    /** El horario de un dia como lo ve el empleado: "08:00–16:00 · Vista Mar", "libre" o "Vacaciones". */
    fun scheduleText(schedule: DaySchedule, siteNames: Map<UUID, String>): String = when (schedule) {
        is DaySchedule.Absent -> tr(schedule.kind.label)
        is DaySchedule.Off -> tr("libre")
        is DaySchedule.Working -> schedule.slots.joinToString(", ") { slot ->
            listOfNotNull(EmpleadosSchedule.formatRange(slot.start, slot.end), slot.siteId?.let { siteNames[it] }).joinToString(" · ")
        }
    }

    /** "lun 24 nov: 08:00–16:00 · Vista Mar". */
    fun shiftText(date: LocalDate, schedule: DaySchedule, siteNames: Map<UUID, String>): String = "${dayLabel(date)}: ${scheduleText(schedule, siteNames)}"

    /** Resumen del horario habitual: "lun 08:00–16:00, mar 08:00–16:00". */
    fun weeklyText(slots: List<WeeklySlot>, siteNames: Map<UUID, String>): String {
        if (slots.isEmpty()) return tr("sin horario habitual")
        return slots.sortedWith(compareBy({ it.weekday }, { it.start })).joinToString(", ") { slot ->
            listOfNotNull("${weekdayName(slot.weekday - 1)} ${EmpleadosSchedule.formatRange(slot.start, slot.end)}", slot.siteId?.let { siteNames[it] }).joinToString(" · ")
        }
    }

    /** Solo se avisa de lo que afecta a hoy o al futuro. */
    fun shouldNotify(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(today)

    /** Cuentas a avisar de un cambio: nunca el autor. */
    fun recipients(candidates: Collection<UUID>, byUserId: UUID?): List<UUID> = candidates.distinct().filter { it != byUserId }

    fun mineTarget(date: LocalDate) = NotificationTarget("mine", mapOf("week" to EmpleadosSchedule.weekStart(date).toString()))

    val AbsencesTarget = NotificationTarget("absences", emptyMap())

    /** Un turno de [date] cambio: el empleado recibe el horario vigente de ese dia (despues del cambio). */
    fun shiftChanged(tenant: Connection, notifier: ModuleNotifier?, employeeId: UUID, dates: Collection<LocalDate>, byUserId: UUID?, today: LocalDate) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            val names = EmpleadosRepository.catalogNames(tenant, Catalog.Sites)
            dates.distinct().filter { shouldNotify(it, today) }.sorted().forEach { date ->
                val (schedule, _) = EmpleadosRepository.daySchedule(tenant, employeeId, date)
                send(notifier, tenant, userId, mineTarget(date)) {
                    NotificationText(tr("Tu turno cambió"), shiftText(date, schedule, names))
                }
            }
        }.onFailure { log.warn("No se pudo preparar el aviso de cambio de turno ({}).", it.javaClass.simpleName) }
    }

    /** El horario habitual del empleado cambio. */
    fun weeklyChanged(tenant: Connection, notifier: ModuleNotifier?, employeeId: UUID, byUserId: UUID?, today: LocalDate) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            val names = EmpleadosRepository.catalogNames(tenant, Catalog.Sites)
            val slots = EmpleadosRepository.weekly(tenant, listOf(employeeId))
            send(notifier, tenant, userId, mineTarget(today)) { NotificationText(tr("Tu horario habitual cambió"), weeklyText(slots, names)) }
        }.onFailure { log.warn("No se pudo preparar el aviso de cambio del horario habitual ({}).", it.javaClass.simpleName) }
    }

    /** Se pidio una ausencia: la reciben quien gestiona turnos y los duenos (hasta 20), sin el autor. */
    fun absenceRequested(tenant: Connection, notifier: ModuleNotifier?, employeeName: String, absence: AbsenceInput, byUserId: UUID?) {
        if (notifier == null) return
        runCatching {
            recipients(EmpleadosRepository.approvers(tenant), byUserId).forEach { approver ->
                send(notifier, tenant, approver, AbsencesTarget) {
                    NotificationText(tr("Solicitud de ausencia"), tr("{0} pidió {1} del {2} al {3}", employeeName, tr(absence.kind.label).lowercase(), absence.startDate, absence.endDate))
                }
            }
        }.onFailure { log.warn("No se pudo preparar el aviso de ausencia pedida ({}).", it.javaClass.simpleName) }
    }

    /** Una ausencia se aprobo o rechazo: la recibe el empleado enlazado. */
    fun absenceDecided(tenant: Connection, notifier: ModuleNotifier?, absence: Absence, approved: Boolean, note: String, byUserId: UUID?) {
        if (notifier == null) return
        runCatching {
            val userId = EmpleadosRepository.userIdOf(tenant, absence.employeeId) ?: return
            if (recipients(listOf(userId), byUserId).isEmpty()) return
            send(notifier, tenant, userId, AbsencesTarget) {
                val base = tr("{0} del {1} al {2}", tr(absence.kind.label), absence.startDate, absence.endDate)
                val text = if (note.isBlank()) base else "$base. ${note.trim()}"
                NotificationText(if (approved) tr("Ausencia aprobada") else tr("Ausencia rechazada"), text)
            }
        }.onFailure { log.warn("No se pudo preparar el aviso de ausencia decidida ({}).", it.javaClass.simpleName) }
    }

    /** Escrito por [build] en el idioma de cada destinatario (ModuleNotifier.notify con builder). */
    private fun send(notifier: ModuleNotifier, tenant: Connection, userId: UUID, target: NotificationTarget, build: () -> NotificationText) {
        runCatching { notifier.notify(tenant, userId, target) { build().let { NotificationText(it.title, it.text.take(MaxText)) } } }
            .onFailure { log.warn("No se pudo crear un aviso de empleados ({}).", it.javaClass.simpleName) }
    }
}
