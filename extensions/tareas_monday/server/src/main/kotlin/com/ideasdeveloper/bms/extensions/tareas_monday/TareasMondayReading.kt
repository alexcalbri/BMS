package com.ideasdeveloper.bms.extensions.tareas_monday

import java.time.Duration
import java.time.Instant

/**
 * Como se clasifica un fallo de monday: decide si se reintenta, se reduce la pagina, se relee desde cero o se da por perdido.
 * [Complexity] (limite de complejidad de monday) y [TooLarge] (respuesta de mas de 5 MB) se resuelven igual, con paginas mas
 * chicas, pero solo en la lectura de paginas de elementos.
 */
enum class MondayErrorKind { Permanent, Transient, Complexity, TooLarge, CursorExpired }

/**
 * Funciones puras de la lectura paginada y reanudable de un tablero (mismo patron que membresia_monday): ante un limite de
 * complejidad la pagina se reduce a la mitad (minimo [MinPageSize]) y el nuevo tamano queda guardado en el trabajo; ante un
 * cursor vencido se relee el tablero desde cero una sola vez.
 */
object TareasMondayReading {
    const val MinPageSize = 10
    const val MaxAttempts = 5
    const val MaxMessage = 200
    const val TooHeavyMessage = "Los elementos son demasiado pesados incluso con 10 por pagina; divide el tablero o quita columnas."

    // Fallos que pasan solos: limites de uso, concurrencia, errores del servidor o tiempos agotados.
    private val transientWords = listOf(
        "rate limit", "ratelimit", "rate_limit", "too many", "concurrency", "temporarily", "timeout", "timed out",
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

    /** La mitad de la pagina actual, sin bajar de [MinPageSize]. */
    fun nextPageSize(current: Int): Int = (current / 2).coerceAtLeast(MinPageSize)

    sealed class Step {
        /** Deja el estado como esta y reintenta en la corrida siguiente: [attempts] seguidos y [pageSize] (menor si se redujo la pagina). */
        data class Retry(val attempts: Int, val pageSize: Int) : Step()

        /** Borra lo leido y relee desde cero. */
        object Restart : Step()

        data class Fail(val message: String) : Step()
    }

    /**
     * Que hacer tras un fallo de la lectura: [attempts] son los reintentos seguidos y [restarts] las relecturas desde cero ya hechas.
     * [canShrink] es false cuando la llamada que fallo no tiene pagina que reducir (personas, notas): el error es claro y no se
     * toca el tamano. Reducir la pagina no cuenta como intento.
     */
    fun recover(kind: MondayErrorKind, message: String, attempts: Int, pageSize: Int, restarts: Int, canShrink: Boolean = true): Step {
        val shown = message.take(MaxMessage)
        return when (kind) {
            MondayErrorKind.Permanent -> Step.Fail(shown)
            MondayErrorKind.CursorExpired ->
                if (restarts >= 1) Step.Fail("monday vencio el cursor de lectura dos veces seguidas; divide el tablero en otros mas chicos o vuelve a intentarlo mas tarde.")
                else Step.Restart
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
    fun retryDue(attempts: Int, updatedAt: Instant, now: Instant = Instant.now()): Boolean =
        attempts <= 0 || !now.isBefore(updatedAt.plus(Duration.ofMinutes(backoffMinutes(attempts))))
}
