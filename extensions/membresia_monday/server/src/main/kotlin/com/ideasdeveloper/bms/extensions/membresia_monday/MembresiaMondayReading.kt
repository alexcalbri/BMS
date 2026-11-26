package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.ImportPlanProposal
import com.ideasdeveloper.bms.modules.membresia.ImportSummary
import com.ideasdeveloper.bms.modules.membresia.ImportedMember
import com.ideasdeveloper.bms.server.i18n.msg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Como se clasifica un fallo de monday: decide si se reintenta, se relee desde cero o se da por perdido. */
enum class MondayErrorKind { Permanent, Transient, Complexity, TooLarge, CursorExpired }

/** Error con un mensaje que se le puede mostrar al usuario (nunca lleva el token). */
class MembresiaMondayException(message: String, val kind: MondayErrorKind = MondayErrorKind.Permanent) : Exception(message)

/** Funciones puras de la lectura paginada y reanudable de un tablero. */
object MondayReading {
    const val DefaultPageSize = 50
    const val MinPageSize = 10
    const val MaxPages = 1_000
    const val MaxAttempts = 5
    const val MaxMessage = 200
    val TooHeavyMessage = msg("Los elementos son demasiado pesados incluso con 10 por pagina; divide el tablero o quita columnas.")

    // Fallos que pasan solos: limites de uso, concurrencia, errores del servidor o tiempos agotados.
    private val transientWords = listOf(
        "rate limit", "ratelimit", "rate_limit", "concurrency", "temporarily", "timeout", "timed out",
        "internal_server_error", "internal server",
    )

    /** Clasifica una respuesta de monday: 429 y 5xx son transitorios; complejidad y cursor vencido se tratan aparte. */
    fun classify(status: Int, errorCode: String, errorMessage: String): MondayErrorKind {
        val text = (errorCode + " " + errorMessage).lowercase()
        return when {
            "complexity" in text -> MondayErrorKind.Complexity
            "cursor" in text && ("expired" in text || "invalid" in text) -> MondayErrorKind.CursorExpired
            status == 429 || status >= 500 || transientWords.any { it in text } -> MondayErrorKind.Transient
            else -> MondayErrorKind.Permanent
        }
    }

    fun nextPageSize(current: Int): Int = (current / 2).coerceAtLeast(MinPageSize)

    /** El cursor que devolvio monday es el mismo que se uso: seguiria leyendo la misma pagina para siempre. */
    fun repeatedCursor(used: String?, returned: String): Boolean = returned.isNotEmpty() && returned == used

    sealed class Step {
        /** Deja el estado como esta, suma un intento y reintenta en la corrida siguiente (con paginas mas chicas si [pageSize] bajo). */
        data class Retry(val attempts: Int, val pageSize: Int) : Step()

        /** Borra lo leido y relee desde cero. */
        object Restart : Step()

        data class Fail(val message: String) : Step()
    }

    /** Que hacer tras un fallo de la lectura: [attempts] son los reintentos seguidos y [restarts] las relecturas desde cero ya hechas. */
    fun recover(kind: MondayErrorKind, message: String, attempts: Int, pageSize: Int, restarts: Int, canShrink: Boolean = true): Step {
        val shown = message.take(MaxMessage)
        return when (kind) {
            MondayErrorKind.Permanent -> Step.Fail(shown)
            MondayErrorKind.CursorExpired ->
                if (restarts >= 1) Step.Fail(msg("monday vencio el cursor de lectura dos veces seguidas; divide el tablero en otros mas chicos o vuelve a intentarlo mas tarde."))
                else Step.Restart
            // Reducir la pagina no cuenta como intento; con la pagina al minimo (o sin pagina que reducir) el error es claro.
            MondayErrorKind.Complexity, MondayErrorKind.TooLarge -> when {
                !canShrink -> Step.Fail("monday rechazo la consulta por su tamano o complejidad y no se puede reducir: $shown")
                pageSize <= MinPageSize -> Step.Fail(TooHeavyMessage)
                else -> Step.Retry(attempts, nextPageSize(pageSize))
            }
            MondayErrorKind.Transient -> {
                val next = attempts + 1
                if (next >= MaxAttempts) Step.Fail("monday siguio fallando tras $MaxAttempts intentos: $shown")
                else Step.Retry(next, pageSize)
            }
        }
    }

    /** Minutos de espera antes de reintentar tras [attempts] fallos pasajeros seguidos: 2, 4, 8, 16... con tope de 30. */
    fun backoffMinutes(attempts: Int): Long = if (attempts <= 0) 0L else minOf(1L shl attempts.coerceAtMost(5), 30L)

    /** El trabajo ya puede reintentarse: sin fallos seguidos, o pasada la espera creciente desde su ultima actualizacion. */
    fun retryDue(attempts: Int, updatedAt: java.time.Instant, now: java.time.Instant = java.time.Instant.now()): Boolean =
        attempts <= 0 || !now.isBefore(updatedAt.plus(java.time.Duration.ofMinutes(backoffMinutes(attempts))))

    // Codigos de problema por fila, sin nombres ni texto libre de monday.
    const val NoName = "sin_nombre"
    const val NoPlan = "sin_plan"
    const val NoDates = "sin_fechas"
    const val EndBeforeStart = "fin_antes_de_inicio"
    const val Unreadable = "fila_ilegible"

    fun problemCodes(rows: List<ImportedMember>, unreadable: Int = 0): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        fun add(code: String) { counts[code] = (counts[code] ?: 0) + 1 }
        rows.forEach { row ->
            if (row.name.isBlank()) add(NoName)
            if (row.planName.isBlank()) add(NoPlan)
            val start = row.startsOn
            val end = row.endsOn
            if (start == null || end == null) add(NoDates) else if (end.isBefore(start)) add(EndBeforeStart)
        }
        if (unreadable > 0) counts[Unreadable] = unreadable
        return counts
    }
}

@Serializable
data class MondayPlanDto(val name: String, val price: String, val durationDays: Int, val guestPasses: Int) {
    fun toProposal() = ImportPlanProposal(name, BigDecimal(price), durationDays, guestPasses)
}

/** Resumen previo guardado una sola vez en jobs.summary al terminar la lectura. */
@Serializable
data class MondaySummaryDto(
    val members: Int,
    val visits: Int,
    val newPlans: List<MondayPlanDto> = emptyList(),
    val customerFields: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
    val problemCodes: Map<String, Int> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun from(summary: ImportSummary, codes: Map<String, Int>, unreadable: Int = 0) = MondaySummaryDto(
            members = summary.members,
            visits = summary.visits,
            newPlans = summary.newPlans.map { MondayPlanDto(it.name, it.price.toPlainString(), it.durationDays, it.guestPasses) },
            customerFields = summary.customerFields,
            problems = summary.problems + List(if (unreadable > 0) 1 else 0) { "$unreadable filas no se pudieron leer." },
            problemCodes = codes,
        )

        fun encode(dto: MondaySummaryDto): String = json.encodeToString(serializer(), dto)

        fun decode(text: String): MondaySummaryDto? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
