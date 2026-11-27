package com.ideasdeveloper.bms.modules.empleados

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.extensions.ServerExtension
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.JobBackoff
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiNode
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Un lugar desde donde se pueden importar datos (por ejemplo Monday). Las extensiones del modulo lo implementan: Empleados
 * lista las activas en la pantalla "Importar", ejecuta su trabajo pendiente en el job `import-runner` y ofrece sus
 * herramientas para agentes.
 */
interface EmpleadosImportSource {
    /** El id de la extension, por ejemplo "empleados_monday". */
    val sourceId: String

    /** Lo que ve el usuario, por ejemplo "Monday". */
    val title: String

    fun enabled(tenant: Connection, companyCode: String): Boolean

    /** El cuerpo de la pantalla de importacion de esta fuente ([params] son los parametros de la pantalla). */
    fun screen(context: EmpleadosImportContext, params: Map<String, String>): List<UiNode>

    /** Una operacion de la pantalla (POST); [values] es lo que envio el formulario. */
    fun action(context: EmpleadosImportContext, op: String, values: Map<String, String>): EmpleadosImportReply

    /** Herramientas para agentes (permiso empleados.import); Empleados las suma a las suyas. */
    val mcpTools: List<McpTool> get() = emptyList()

    /** Avanza la lectura y la aplicacion y devuelve las filas procesadas. Corto, idempotente y con errores registrados. */
    fun runPending(context: EmpleadosImportJobContext, budget: Duration): Int
}

class EmpleadosImportContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val permissions: ModulePermissions,
    val author: String,
    val today: LocalDate,
)

class EmpleadosImportJobContext(
    val access: ModuleAccessService,
    val tenant: Connection,
    val companyCode: String,
    val today: LocalDate,
)

/** La respuesta a una accion: el aviso que se muestra, si es un error y los parametros de la pantalla siguiente. */
data class EmpleadosImportReply(val notice: String, val error: Boolean = false, val params: Map<String, String> = emptyMap())

object EmpleadosImportSources {
    private val log = LoggerFactory.getLogger(EmpleadosImportSources::class.java)

    @Volatile
    private var linked: List<EmpleadosImportSource> = emptyList()

    /** Se llama una vez al arrancar con las extensiones del modulo; se queda con las que son fuentes de importacion. */
    fun link(extensions: List<ServerExtension>) {
        linked = extensions.filterIsInstance<EmpleadosImportSource>()
    }

    val all: List<EmpleadosImportSource> get() = linked

    /** Las fuentes que la empresa tiene activas. Una fuente que no responde cuenta como inactiva. */
    fun enabled(tenant: Connection, companyCode: String): List<EmpleadosImportSource> = all.filter { source ->
        runCatching { source.enabled(tenant, companyCode) }
            .onFailure { log.warn("No se pudo comprobar la fuente de importación {} ({}).", source.sourceId, it.javaClass.simpleName) }
            .getOrDefault(false)
    }

    fun find(id: String): EmpleadosImportSource? = all.firstOrNull { it.sourceId == id }

    /** Ejecuta las fuentes activas una tras otra, cada una con una parte igual de lo que queda de [budget]. */
    fun runPending(context: EmpleadosImportJobContext, budget: Duration): Int {
        val sources = enabled(context.tenant, context.companyCode)
        val started = Instant.now()
        var processed = 0
        sources.forEachIndexed { index, source ->
            val remaining = budget.minus(Duration.between(started, Instant.now()))
            if (remaining.isNegative || remaining.isZero) return processed
            val share = remaining.dividedBy((sources.size - index).toLong())
            // A source that keeps failing (e.g. a migration not applied) waits longer each time and is logged once (JobBackoff).
            processed += JobBackoff.imports.run(log, "empleados/${context.companyCode}/${source.sourceId}", "Falló la importación de ${source.sourceId} en ${context.companyCode}") {
                source.runPending(context, share)
            } ?: 0
        }
        return processed
    }
}

/** Una franja del horario habitual que trae la fuente (dia ISO 1..7). */
data class ImportedSlot(val weekday: Int, val start: LocalTime, val end: LocalTime)

data class ImportedEmployee(
    /** La clave en la fuente ("monday:<itemId>"): es el import_key de la ficha. */
    val key: String,
    val fullName: String,
    val jobTitle: String = "",
    val department: String? = null,
    val site: String? = null,
    val phone: String = "",
    val email: String = "",
    val availability: String = "",
    val notes: String = "",
    /** Vacio: no se toca el horario habitual existente. */
    val weekly: List<ImportedSlot> = emptyList(),
    val active: Boolean = true,
)

data class EmpleadosImportSummary(
    val employees: Int,
    val sites: List<String>,
    val departments: List<String>,
    val withSchedule: Int,
    val linkedUsers: List<String>,
    val problems: List<String>,
)

sealed class EmpleadosImportOutcome {
    /** [warning]: algo no se pudo aplicar pero la ficha se guardo (por ejemplo una sede que no cupo). */
    data class Created(val id: UUID, val warning: String? = null) : EmpleadosImportOutcome()

    data class Updated(val id: UUID, val warning: String? = null) : EmpleadosImportOutcome()

    data class Skipped(val reason: String) : EmpleadosImportOutcome()
}

/** Escribe filas importadas en Empleados. Cada fila es su propia transaccion, idempotente, y nunca envia avisos. */
object EmpleadosImportApply {
    private val log = LoggerFactory.getLogger(EmpleadosImportApply::class.java)
    private const val MaxName = 160
    private const val MaxJobTitle = 120
    private const val MaxPhone = 40
    private const val MaxEmail = 254
    private const val MaxAvailability = 500
    private const val MaxNotes = 5000
    private const val MaxCatalogName = 120
    private const val MaxProblems = 50
    private const val MaxKey = 200

    // ---- reglas puras ----

    /** Recorta [text] a [max] caracteres terminando en "…" en lugar de fallar. */
    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private fun validEmail(email: String): Boolean = email.isNotEmpty() && email.length <= MaxEmail && EmployeeInput.EmailPattern.matches(email)

    /**
     * Las franjas que se pueden guardar: dia 1..7, horas distintas, sin repetidas ni solapadas y hasta 4 por dia (se queda
     * con las primeras por hora). Ordenadas por dia y hora.
     */
    fun cleanSlots(slots: List<ImportedSlot>): List<Triple<Int, LocalTime, LocalTime>> =
        slots.filter { it.weekday in 1..7 && it.start != it.end }
            .distinct()
            .groupBy { it.weekday }
            .toSortedMap()
            .flatMap { (weekday, ofDay) ->
                val kept = mutableListOf<ImportedSlot>()
                ofDay.sortedBy { it.start }.forEach { slot ->
                    if (kept.size < EmpleadosRepository.MaxSlotsPerDay && kept.none { EmpleadosSchedule.overlaps(it.start, it.end, slot.start, slot.end) }) kept += slot
                }
                kept.map { Triple(weekday, it.start, it.end) }
            }

    /** La vista previa: cuantas fichas, que sedes y departamentos se crearian, a quien se enlazaria y que filas tienen problemas. */
    fun summary(rows: List<ImportedEmployee>, userEmails: Set<String>): EmpleadosImportSummary {
        val problems = mutableListOf<String>()
        val seen = HashSet<String>()
        val valid = mutableListOf<ImportedEmployee>()
        rows.forEach { row ->
            val label = row.fullName.trim().ifEmpty { row.key }
            when {
                row.fullName.isBlank() -> problems += tr("Fila sin nombre ({0}): se omite.", row.key.ifBlank { tr("sin clave") })
                row.key.isBlank() || row.key.length > MaxKey -> problems += tr("{0}: la clave de origen no es válida, se omite.", label)
                !seen.add(row.key.trim()) -> problems += tr("{0}: clave repetida ({1}), se omite la repetición.", label, row.key)
                else -> valid += row
            }
        }
        valid.forEach { row ->
            if (row.weekly.size != cleanSlots(row.weekly).size) problems += tr("{0}: hay franjas de horario no válidas o repetidas; se ignoran.", row.fullName.trim())
            if (row.email.isNotBlank() && !validEmail(row.email.trim())) problems += tr("{0}: el correo no es válido, se omite.", row.fullName.trim())
            if (row.phone.isNotBlank() && !EmployeeInput.PhonePattern.matches(row.phone.trim())) problems += tr("{0}: el teléfono no es válido, pasa a las notas.", row.fullName.trim())
        }
        fun distinctNames(values: List<String?>): List<String> {
            val names = LinkedHashMap<String, String>()
            values.mapNotNull { it?.trim()?.takeIf { text -> text.isNotEmpty() } }.forEach { names.putIfAbsent(it.lowercase(), clip(it, MaxCatalogName)) }
            return names.values.toList()
        }
        return EmpleadosImportSummary(
            employees = valid.size,
            sites = distinctNames(valid.map { it.site }),
            departments = distinctNames(valid.map { it.department }),
            withSchedule = valid.count { cleanSlots(it.weekly).isNotEmpty() },
            // Posibles cuentas por correo (solo informativo: la importacion nunca enlaza usuarios; se hace a mano en la ficha).
            linkedUsers = valid.filter { it.email.trim().lowercase() in userEmails }.map { "${it.fullName.trim()} (${it.email.trim()})" }.distinct(),
            problems = problems.take(MaxProblems),
        )
    }

    // ---- registro de actividad ----

    /**
     * El actor de una importacion: [startedBy] (usuario de la empresa que la empezo) con su nombre actual leido de
     * application_users en la misma conexion; si la fila ya no existe, "Usuario desconocido"; sin usuario, una importacion anonima.
     */
    fun importActor(tenant: Connection, startedBy: UUID?, stored: String? = null): ActivityActor {
        // The actor kept with the job when it started (an AI agent, a server owner...): rebuilt exactly; old jobs fall back to the user id.
        ActivityActor.restoreImportOrNull(stored)?.let { return it }
        if (startedBy == null) return ActivityActor.import(null)
        val name = tenant.prepareStatement("SELECT COALESCE(NULLIF(trim(display_name), ''), postgres_role) FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, startedBy)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return ActivityActor.import(ActivityActor.user(startedBy, name?.takeIf { it.isNotBlank() } ?: "Usuario desconocido"))
    }

    /**
     * La fila unica de una importacion terminada (quien la empezo, el origen y los totales); el llamador la escribe en la
     * misma transaccion que cierra el trabajo. [outcome] aclara un final distinto de terminar bien.
     */
    fun recordImportTotal(
        tenant: Connection,
        activity: ActivityActor,
        jobId: UUID?,
        source: String,
        board: String,
        created: Int,
        updated: Int,
        skipped: Int,
        failed: Int,
        outcome: String = "",
    ) = EmpleadosActivity.importTotal(tenant, activity, jobId, source, board, created, updated, skipped, failed, outcome)

    // ---- escritura ----

    /** Usuarios activos por correo en minusculas; un correo que usan varios usuarios no se ofrece (no se sabria a quien enlazar). */
    fun userEmails(tenant: Connection): Map<String, UUID> = tenant.prepareStatement(
        """
        SELECT lower(btrim(u.email)) AS email, (array_agg(u.id))[1] AS id
        FROM application_users u
        WHERE u.is_active AND btrim(COALESCE(u.email, '')) <> ''
        GROUP BY lower(btrim(u.email))
        HAVING count(*) = 1
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rows ->
            buildMap { while (rows.next()) put(rows.getString("email"), rows.getObject("id", UUID::class.java)) }
        }
    }

    /**
     * Crea o actualiza una ficha por su clave de origen, con su sede, departamento y horario habitual, en una transaccion.
     * Al actualizar solo se pisan los campos que la fila trae no vacios; la baja solo se aplica si la fila no esta activa y
     * nunca se reactiva. No enlaza usuarios. Sin avisos. Las SQLException que no son clave repetida (23505) se relanzan.
     * [activity]: quien importa (ActivityActor.import de quien la empezo); cada fila del registro lo lleva como actor.
     */
    fun apply(
        tenant: Connection,
        row: ImportedEmployee,
        users: Map<String, UUID>,
        actorId: UUID?,
        activity: ActivityActor = ActivityActor.import(null),
    ): EmpleadosImportOutcome {
        val name = row.fullName.trim()
        if (name.isEmpty()) return EmpleadosImportOutcome.Skipped(tr("La fila no tiene nombre."))
        val key = row.key.trim()
        if (key.isEmpty() || key.length > MaxKey) return EmpleadosImportOutcome.Skipped(tr("La clave de origen no es válida."))
        val email = row.email.trim().let { if (validEmail(it)) it else "" }
        // Un telefono que no cumple el patron del modulo no se guarda en su campo: queda en las notas para no perderlo.
        val phoneText = row.phone.trim()
        val phoneOk = phoneText.isEmpty() || EmployeeInput.PhonePattern.matches(phoneText)
        val notes = if (phoneOk) row.notes.trim() else listOf(row.notes.trim(), "Teléfono en Monday: ${clip(phoneText, 100)}").filter { it.isNotEmpty() }.joinToString("\n")
        val fields = EmpleadosRepository.ImportFields(
            fullName = clip(name, MaxName),
            jobTitle = clip(row.jobTitle.trim(), MaxJobTitle),
            phone = if (phoneOk) phoneText else "",
            email = email,
            availability = clip(row.availability.trim(), MaxAvailability),
            notes = clip(notes, MaxNotes),
        )
        val warnings = mutableListOf<String>()
        // El enlace con una cuenta (user_id) nunca se hace aqui: quien edita el origen podria poner el correo de otra persona.
        // [users] solo sirve para la vista previa; el enlace se hace a mano en la ficha.
        return try {
            EmpleadosRepository.transaction(tenant) {
                val existing = EmpleadosRepository.findByImportKey(tenant, key)
                fun catalogId(catalog: Catalog, raw: String?, importKey: (String) -> String?): UUID? {
                    val text = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { clip(it, MaxCatalogName) } ?: return null
                    return try {
                        val known = EmpleadosRepository.listCatalog(tenant, catalog, includeArchived = true).firstOrNull { it.name.equals(text, ignoreCase = true) }
                        val id = EmpleadosRepository.findOrCreateCatalog(tenant, catalog, text, importKey(text))
                        // Una sede o departamento nuevo, o archivado que se reactiva, queda en el registro.
                        if (known == null || known.archived) EmpleadosActivity.catalogCreated(tenant, activity, catalog, id, text, restored = known != null)
                        id
                    } catch (failure: EmpleadosValidationException) {
                        // Sin sitio para una sede o departamento nuevo: la ficha se guarda sin ella y se avisa.
                        warnings += tr("No se asignó {0} \"{1}\": {2}", tr(catalog.singular), text, failure.message.orEmpty())
                        null
                    }
                }
                val siteId = catalogId(Catalog.Sites, row.site) { "monday:site:${it.lowercase()}" }
                val departmentId = catalogId(Catalog.Departments, row.department) { null }
                val today = EmpleadosRepository.today()
                val warning = { warnings.takeIf { it.isNotEmpty() }?.joinToString(" ") }
                // Nombres de sede y departamento de la fila (los que se guardan), para el registro.
                val siteName = siteId?.let { clip(row.site.orEmpty().trim(), MaxCatalogName) }
                val deptName = departmentId?.let { clip(row.department.orEmpty().trim(), MaxCatalogName) }
                val outcome: EmpleadosImportOutcome = if (existing == null) {
                    val created = EmpleadosRepository.insertImported(tenant, key, fields, departmentId, siteId, null, row.active, actorId, today)
                    EmpleadosActivity.write(
                        tenant, activity, EmpleadosActivity.EmployeeType, created, name, ActivityActions.Created, "Importa la ficha de $name.",
                        ActivityChanges.created(EmpleadosActivity.importedEmployeeCreated(fields, deptName, siteName, row.active), EmpleadosActivity.EmployeeLabels),
                    )
                    EmpleadosImportOutcome.Created(created, warning())
                } else {
                    val deactivate = !row.active && existing.status == EmployeeStatus.Active
                    // Antes de cambiarla: la ficha tal como esta, para escribir solo lo que cambia.
                    val before = EmpleadosRepository.get(tenant, EmpleadosScope(null, null, canShifts = true, canManage = true, owner = true), existing.id)
                    EmpleadosRepository.updateImported(tenant, existing.id, fields, departmentId, siteId, null, deactivate = deactivate, today = today)
                    if (before != null) {
                        val changes = EmpleadosActivity.importedEmployeeChanges(before, EmpleadosRepository.userLabel(tenant, before.userId), fields, deptName, siteName, deactivate)
                        if (changes.isNotEmpty()) {
                            EmpleadosActivity.write(
                                tenant, activity, EmpleadosActivity.EmployeeType, existing.id, name, ActivityActions.Updated,
                                "Actualiza la ficha de $name desde la importación.", changes,
                            )
                        }
                    }
                    EmpleadosImportOutcome.Updated(existing.id, warning())
                }
                val id = when (outcome) {
                    is EmpleadosImportOutcome.Created -> outcome.id
                    is EmpleadosImportOutcome.Updated -> outcome.id
                    is EmpleadosImportOutcome.Skipped -> null
                }
                val slots = cleanSlots(row.weekly)
                if (id != null && slots.isNotEmpty()) {
                    val beforeSlots = EmpleadosRepository.weekly(tenant, listOf(id)).map { Triple(it.weekday, it.start, it.end) }
                    EmpleadosRepository.replaceWeekly(tenant, id, slots)
                    EmpleadosActivity.weeklyReplaced(tenant, activity, id, name, EmpleadosActivity.slotsText(beforeSlots), EmpleadosActivity.slotsText(slots))
                }
                outcome
            }
        } catch (failure: EmpleadosValidationException) {
            EmpleadosImportOutcome.Skipped(failure.message.orEmpty())
        } catch (failure: java.sql.SQLException) {
            // Se registra solo el codigo SQLSTATE, nunca valores de la fila.
            val state = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
            log.warn("No se pudo importar una ficha (sqlState={}).", state)
            // Una clave repetida (otra peticion se adelanto) se omite; cualquier otro error se relanza: el runner lo cuenta como fallo y reintenta.
            if (state == "23505") EmpleadosImportOutcome.Skipped(tr("La ficha ya existe (otra importación se adelantó).")) else throw failure
        }
    }
}
