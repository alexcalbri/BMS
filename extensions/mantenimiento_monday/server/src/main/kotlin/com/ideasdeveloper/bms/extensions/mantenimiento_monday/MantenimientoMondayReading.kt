package com.ideasdeveloper.bms.extensions.mantenimiento_monday

import java.time.Duration
import java.time.Instant

/** Como se clasifica un fallo de monday: decide si se reintenta, se baja el tamano de pagina, se relee desde cero o se da por perdido. */
/** [Complexity] y [TooLarge] (respuesta de mas de 5 MB) solo bajan la pagina cuando el fallo es de la lectura de paginas. */
enum class MondayErrorKind { Permanent, Transient, Complexity, TooLarge, CursorExpired }

/**
 * Funciones puras de la lectura paginada de un tablero (mismo patron que la extension de membresia): clasifican el
 * error de monday y deciden que hacer despues. No tocan red ni base de datos.
 */
object MondayReading {
    const val MinPageSize = 10
    const val MaxAttempts = 5
    const val MaxMessage = 200
    const val TooHeavyMessage = "Los elementos son demasiado pesados incluso con 10 por pagina; divide el tablero o quita columnas."

    /** Minutos de espera antes de reintentar un trabajo tras [retries] fallos pasajeros seguidos: 2, 4, 8, 16 y 30 como tope. */
    fun retryDelayMinutes(retries: Int): Long = minOf(1L shl retries.coerceIn(0, 5), 30L)

    /** El trabajo ya puede reintentarse: sin fallos seguidos, o pasada la espera creciente desde su ultima actualizacion. */
    fun retryReady(retries: Int, updatedAt: Instant, now: Instant = Instant.now()): Boolean =
        retries <= 0 || !now.isBefore(updatedAt.plus(Duration.ofMinutes(retryDelayMinutes(retries))))

    /** Cuantos elementos caben en una consulta de actualizaciones (maximo de ids por lote). */
    const val UpdatesBatch = 25

    /** Clasifica una respuesta de monday: 429 y 5xx son transitorios; complejidad y cursor vencido se tratan aparte. */
    fun classify(status: Int, errorCode: String, errorMessage: String): MondayErrorKind {
        val text = (errorCode + " " + errorMessage).lowercase()
        return when {
            "complexity" in text -> MondayErrorKind.Complexity
            "cursor" in text && ("expired" in text || "invalid" in text) -> MondayErrorKind.CursorExpired
            status == 429 || status >= 500 || "rate limit" in text || "ratelimit" in text || "rate_limit" in text || "too many" in text ||
                "concurrency" in text || "temporarily" in text || "timeout" in text || "internal_server_error" in text || "internal server" in text -> MondayErrorKind.Transient
            else -> MondayErrorKind.Permanent
        }
    }

    /** Tamano de pagina tras un fallo de complejidad: la mitad, nunca menos de [MinPageSize]. */
    fun nextPageSize(current: Int): Int = (current / 2).coerceAtLeast(MinPageSize)

    sealed class Step {
        /** Reintenta con [attempts] intentos seguidos y paginas de [pageSize]. */
        data class Retry(val attempts: Int, val pageSize: Int) : Step()

        /** Borra lo leido y relee desde cero. */
        object Restart : Step()

        data class Fail(val message: String) : Step()
    }

    /** Que hacer tras un fallo de la lectura: [attempts] son los reintentos seguidos y [restarts] las relecturas desde cero ya hechas. */
    fun recover(kind: MondayErrorKind, message: String, attempts: Int, pageSize: Int, restarts: Int): Step {
        val shown = message.take(MaxMessage)
        return when (kind) {
            MondayErrorKind.Permanent -> Step.Fail(shown)
            MondayErrorKind.CursorExpired ->
                if (restarts >= 1) Step.Fail("monday vencio el cursor de lectura dos veces seguidas; divide el tablero en otros mas chicos o vuelve a intentarlo mas tarde.")
                else Step.Restart
            // Bajar la pagina no cuenta como intento: termina sola al llegar al minimo.
            MondayErrorKind.Complexity, MondayErrorKind.TooLarge ->
                if (pageSize <= MinPageSize) Step.Fail(TooHeavyMessage) else Step.Retry(attempts, nextPageSize(pageSize))
            MondayErrorKind.Transient -> {
                val next = attempts + 1
                if (next >= MaxAttempts) Step.Fail("monday siguio fallando tras $MaxAttempts intentos: $shown")
                else Step.Retry(next, pageSize)
            }
        }
    }

    /** Aviso que queda en la fila cuando monday no devolvio todas las actualizaciones del elemento. */
    const val IncompleteNotes = "Notas incompletas: monday no devolvio todas las actualizaciones de este elemento (se leen hasta 100)."

    private const val ReplyFields = "id text_body created_at creator { name email }"

    /** Consulta de un lote de elementos con sus actualizaciones y respuestas (solo ids numericos, ya validados). */
    fun updatesQuery(ids: List<String>, updatesLimit: Int): String =
        "query { items(ids: [${ids.joinToString()}], limit: ${ids.size}) { id updates(limit: $updatesLimit) { $ReplyFields assets { id name } replies { $ReplyFields } } } }"
}
