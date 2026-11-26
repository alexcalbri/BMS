package com.ideasdeveloper.bms.server.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Una instalacion pedida que todavia no termino. Se guarda para retomarla si el servidor se reinicia antes. */
@Serializable
data class PendingPackageInstall(
    /** [PackageInstallQueue.Module] o [PackageInstallQueue.Extension]. */
    val kind: String,
    val id: String,
    val actor: String = "",
    /** De donde sale el paquete: catalog | zip | url. */
    val origin: String = OriginCatalog,
    /** Archivo en disco (zip) o URL de descarga (url); null si sale del catalogo. */
    val location: String? = null,
    /** Etiqueta del origen que se guarda con el paquete (ZIP subido o descargado en preparacion). */
    val sourceUrl: String? = null,
    val sourceType: String? = null,
    /** Veces que la compilacion empezo. Corta el ciclo si compilar este paquete tumba el servidor. */
    val attempts: Int = 0,
) {
    companion object {
        const val OriginCatalog = "catalog"
        const val OriginZip = "zip"
        const val OriginUrl = "url"
    }
}

@Serializable
data class PackageInstallFailure(val kind: String, val id: String, val message: String)

@Serializable
data class PackageInstallJournalState(
    val pending: List<PendingPackageInstall> = emptyList(),
    val failures: List<PackageInstallFailure> = emptyList(),
)

/** Donde se guarda la cola de instalaciones y sus errores, para que sobrevivan a un reinicio. */
interface PackageInstallJournal {
    fun read(): PackageInstallJournalState
    fun write(state: PackageInstallJournalState)
}

class MemoryPackageInstallJournal(var state: PackageInstallJournalState = PackageInstallJournalState()) : PackageInstallJournal {
    override fun read() = state
    override fun write(state: PackageInstallJournalState) {
        this.state = state
    }
}

/** La cola vive en server_settings (base de control, V004) como un JSON; no necesita tabla propia. */
class JdbcPackageInstallJournal(private val connection: () -> Connection) : PackageInstallJournal {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): PackageInstallJournalState = connection().use { open ->
        open.prepareStatement("SELECT setting_value FROM server_settings WHERE setting_key = ?").use { query ->
            query.setString(1, SettingKey)
            query.executeQuery().use { rows ->
                if (rows.next()) json.decodeFromString(PackageInstallJournalState.serializer(), rows.getString(1)) else PackageInstallJournalState()
            }
        }
    }

    override fun write(state: PackageInstallJournalState) {
        connection().use { open ->
            open.prepareStatement(
                """
                INSERT INTO server_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, SettingKey)
                upsert.setString(2, json.encodeToString(PackageInstallJournalState.serializer(), state))
                upsert.executeUpdate()
            }
        }
    }

    companion object {
        const val SettingKey = "packages.install.queue"
    }
}

/**
 * Cola unica de compilacion de paquetes (modulos y extensiones), de a uno: compilar usa cerca de 1 GB.
 *
 * Cargar un paquete nuevo exige reiniciar el servidor. El reinicio se pide una sola vez y solo cuando
 * la cola quedo vacia, asi instalar varios paquetes seguidos no pierde los que esperaban. Cada pedido
 * se anota en [journal] hasta que termina: si el servidor se reinicia antes (caida, actualizacion,
 * reinicio manual), el siguiente arranque lo retoma con [resume], que hay que llamar para cada tipo:
 * la cola no reinicia mientras le quede algo por retomar. Un paquete que falla no detiene a los demas
 * y su error queda guardado para mostrarse tambien despues del reinicio.
 */
class PackageInstallQueue(
    private val journal: PackageInstallJournal,
    private val requestRestart: () -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "package-build").apply { isDaemon = true } },
) {
    private val log = LoggerFactory.getLogger(PackageInstallQueue::class.java)
    private val lock = Any()

    // Lo que dejo sin terminar el proceso anterior; se lee una vez, antes de aceptar pedidos nuevos.
    private val interrupted: List<PendingPackageInstall> = try {
        journal.read().pending
    } catch (failure: Exception) {
        log.error("The package install queue could not be read; interrupted installations will not resume.", failure)
        emptyList()
    }

    // Pedidos encolados o compilando en este proceso.
    private val active = mutableSetOf<Pair<String, String>>()
    // Pedidos del proceso anterior que [resume] todavia no volvio a pedir: mientras queden, no se reinicia.
    private val unresumed = interrupted.map { it.kind to it.id }.toMutableSet()
    private var restartNeeded = false
    private var restartRequested = false

    /**
     * Encola [build], que devuelve null si el paquete quedo guardado (falta reiniciar para cargarlo) o
     * el mensaje del error. Con el reinicio ya pedido, el pedido solo se anota y lo retoma el arranque.
     */
    fun enqueue(install: PendingPackageInstall, build: () -> String?) {
        val key = install.kind to install.id
        val run = synchronized(lock) {
            update { state ->
                // Un pedido retomado conserva sus intentos.
                val attempts = state.pending.firstOrNull { it.kind == install.kind && it.id == install.id }?.attempts ?: install.attempts
                state.copy(
                    pending = state.pending.without(key) + install.copy(attempts = attempts),
                    failures = state.failures.filterNot { it.kind == install.kind && it.id == install.id },
                )
            }
            !restartRequested && active.add(key)
        }
        if (!run) return
        executor.execute {
            synchronized(lock) {
                update { state -> state.copy(pending = state.pending.map { if (it.kind == install.kind && it.id == install.id) it.copy(attempts = it.attempts + 1) else it }) }
            }
            val failure = try {
                build()
            } catch (failure: Throwable) {
                log.error("Installation of {} {} failed unexpectedly", install.kind, install.id, failure)
                "Error inesperado: ${failure.message}"
            }
            finish(install, failure)
        }
    }

    private fun finish(install: PendingPackageInstall, failure: String?) {
        synchronized(lock) {
            settle(install, failure)
            if (failure == null) restartNeeded = true
            active.remove(install.kind to install.id)
        }
        restartIfIdle()
    }

    // Reinicia una sola vez, cuando algo se compilo y ya no queda nada por compilar ni por retomar.
    private fun restartIfIdle() {
        val restart = synchronized(lock) {
            (active.isEmpty() && unresumed.isEmpty() && restartNeeded && !restartRequested).also { if (it) restartRequested = true }
        }
        if (restart) {
            log.info("Package install queue is empty; restarting to load the new packages.")
            requestRestart()
        }
    }

    // Saca el pedido de la cola guardada y deja (o limpia) su error.
    private fun settle(install: PendingPackageInstall, failure: String?) {
        update { state ->
            val others = state.failures.filterNot { it.kind == install.kind && it.id == install.id }
            state.copy(
                pending = state.pending.without(install.kind to install.id),
                failures = (if (failure == null) others else others + PackageInstallFailure(install.kind, install.id, failure)).takeLast(MaxFailures),
            )
        }
    }

    /**
     * Retoma, al arrancar, las instalaciones de [kind] que el proceso anterior dejo sin terminar.
     * [start] vuelve a pedir la instalacion por el camino normal; si lanza, el error queda visible.
     * Un pedido cuya compilacion ya se interrumpio [MaxAttempts] veces no se reintenta.
     */
    fun resume(kind: String, start: (PendingPackageInstall) -> Unit) {
        interrupted.filter { it.kind == kind }.forEach { install ->
            synchronized(lock) { unresumed.remove(kind to install.id) }
            if (install.attempts >= MaxAttempts) {
                log.warn("Installation of {} {} was interrupted {} times; it is not retried.", kind, install.id, install.attempts)
                synchronized(lock) {
                    settle(install, "La instalacion se interrumpio ${install.attempts} veces porque el servidor se reinicio mientras compilaba. Revisa la memoria disponible y vuelve a intentarlo.")
                }
                return@forEach
            }
            log.info("Resuming the installation of {} {} left pending by the previous run.", kind, install.id)
            val failure = try {
                start(install)
                null
            } catch (failure: Exception) {
                log.warn("Installation of {} {} could not be resumed: {}", kind, install.id, failure.message)
                failure.message ?: "No se pudo retomar la instalacion."
            }
            synchronized(lock) {
                // Si [start] no lo encolo (ya estaba instalado) o fallo, el pedido se cierra aqui.
                if ((kind to install.id) !in active || failure != null) settle(install, failure)
            }
        }
        restartIfIdle()
    }

    /** Errores guardados de [kind], por id: los de este proceso y los de antes del ultimo reinicio. */
    fun failures(kind: String): Map<String, String> = synchronized(lock) {
        try {
            journal.read().failures.filter { it.kind == kind }.associate { it.id to it.message }
        } catch (failure: Exception) {
            log.warn("Package install failures could not be read: {}", failure.message)
            emptyMap()
        }
    }

    /** Olvida el error guardado de un paquete (se quito, o ya no aplica). */
    fun clearFailure(kind: String, id: String) {
        synchronized(lock) {
            update { state -> state.copy(failures = state.failures.filterNot { it.kind == kind && it.id == id }) }
        }
    }

    /** Hay paquetes compilados esperando el reinicio que se pedira al vaciarse la cola. */
    val restartPending: Boolean get() = synchronized(lock) { restartNeeded }

    // Un fallo al guardar no detiene la cola: solo se pierde la reanudacion de ese pedido.
    private fun update(change: (PackageInstallJournalState) -> PackageInstallJournalState) {
        try {
            val current = journal.read()
            val next = change(current)
            if (next != current) journal.write(next)
        } catch (failure: Exception) {
            log.warn("The package install queue could not be saved: {}", failure.message)
        }
    }

    private fun List<PendingPackageInstall>.without(key: Pair<String, String>) = filterNot { it.kind == key.first && it.id == key.second }

    companion object {
        const val Module = "module"
        const val Extension = "extension"

        /** Veces que se empieza a compilar un paquete antes de darlo por fallido. */
        const val MaxAttempts = 2
        private const val MaxFailures = 50
    }
}
