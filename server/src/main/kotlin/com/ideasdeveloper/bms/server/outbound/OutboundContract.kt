package com.ideasdeveloper.bms.server.outbound

import com.ideasdeveloper.bms.server.modules.ModuleFiles
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/*
 * Contrato de SALIDA (EXTENSIONS.md, "Envio a servicios externos"): un modulo avisa de lo que paso con sus registros
 * sin saber quien lo escucha, y una extension (por ejemplo, la de monday de cada modulo) lo envia fuera.
 *
 *  - El modulo implementa [OutboundSource] (lee sus registros, campos y archivos) y llama a [OutboundQueue.publish]
 *    justo despues de guardar. publish solo escribe una fila en la cola de la empresa (tabla outbound_events): nunca
 *    llama a la red y nunca lanza; un fallo se registra en el log y la operacion del usuario sigue.
 *  - La extension implementa [OutboundSink]. El modulo recoge sus sinks en linkExtensions (solo las que declaran el
 *    permiso de exportar del modulo) y los entrega a publish y al trabajo [OutboundQueue.job].
 *  - El trabajo (uno por modulo, cada minuto) entrega la cola con reintentos y espera creciente, respeta los limites
 *    del servicio ([OutboundResult.Throttled]) y deja a la vista lo que fallo y por que.
 *  - Los borrados y archivados NO se avisan: borrar en BMS nunca borra nada fuera (decision de seguridad).
 */

/** Que paso con un registro. */
enum class OutboundEventKind(val code: String) {
    Created("created"),
    Updated("updated"),
    Note("note"),
    File("file");

    companion object {
        fun of(code: String): OutboundEventKind? = entries.firstOrNull { it.code == code }
    }
}

/** Quien hizo el cambio; [Import] lleva el id de la fuente ("import:monday", "import:file"). */
object OutboundOrigin {
    const val User = "user"
    const val Agent = "mcp"
    const val Automation = "automation"
    const val Channel = "channel"
    const val ImportPrefix = "import:"

    fun import(sourceId: String) = ImportPrefix + sourceId

    fun isImport(origin: String) = origin.startsWith(ImportPrefix)
}

/** Una persona de BMS (autor de una nota, mencionado, responsable). El correo permite encontrarla fuera. */
@Serializable
data class OutboundPerson(val userId: String? = null, val name: String, val email: String? = null)

/**
 * Lo que un modulo avisa. Solo lleva lo que no se puede leer despues (el texto de la nota, quien la escribio, a quien
 * menciona); los datos del registro se leen al enviarlo con [OutboundSource.record], asi se envian siempre los ultimos.
 */
@Serializable
data class OutboundEvent(
    val moduleId: String,
    /** Tipo de registro del modulo, p. ej. "lead"; [OutboundSource.recordTypes] lo describe. */
    val recordType: String,
    val recordId: String,
    val kind: OutboundEventKind,
    val origin: String = OutboundOrigin.User,
    /** Updated: claves de los campos que cambiaron ([OutboundFieldDef.key]). */
    val changed: List<String> = emptyList(),
    /** Note: id de la nota en el modulo, texto, autor y mencionados. */
    val noteId: String? = null,
    val text: String? = null,
    val author: OutboundPerson? = null,
    val mentions: List<OutboundPerson> = emptyList(),
    /** File: id del archivo en el modulo (se abre con [OutboundSource.file]), nombre y tamano en bytes. */
    val fileId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
) {
    companion object {
        fun created(moduleId: String, recordType: String, recordId: String, origin: String = OutboundOrigin.User) =
            OutboundEvent(moduleId, recordType, recordId, OutboundEventKind.Created, origin)

        fun updated(moduleId: String, recordType: String, recordId: String, changed: Collection<String>, origin: String = OutboundOrigin.User) =
            OutboundEvent(moduleId, recordType, recordId, OutboundEventKind.Updated, origin, changed = changed.distinct())

        fun note(
            moduleId: String, recordType: String, recordId: String, noteId: String, text: String, author: OutboundPerson?,
            mentions: List<OutboundPerson> = emptyList(), origin: String = OutboundOrigin.User,
        ) = OutboundEvent(moduleId, recordType, recordId, OutboundEventKind.Note, origin, noteId = noteId, text = text, author = author, mentions = mentions)

        fun file(moduleId: String, recordType: String, recordId: String, fileId: String, fileName: String, fileSize: Long?, origin: String = OutboundOrigin.User) =
            OutboundEvent(moduleId, recordType, recordId, OutboundEventKind.File, origin, fileId = fileId, fileName = fileName, fileSize = fileSize)
    }
}

/** Tipo de un campo, para elegir que columnas de fuera lo aceptan y como se escribe. */
enum class OutboundFieldType { Text, LongText, Number, Date, DateTime, Email, Phone, Url, Person, Status, Options, Timeline, Boolean }

data class OutboundFieldDef(
    val key: String,
    val label: String,
    val type: OutboundFieldType,
    /** Opciones conocidas (etapas, intereses, opciones de una lista). */
    val options: List<String> = emptyList(),
)

/**
 * [namespace] is the i18n namespace (I18n.moduleNamespace) of the package that declares the type: its
 * [label] and the field labels are translated with that catalog. Null: the caller's own.
 */
data class OutboundRecordType(val id: String, val label: String, val fields: List<OutboundFieldDef>, val namespace: String? = null)

/** Valor de un campo leido del registro. */
sealed interface OutboundValue {
    data class Text(val value: String) : OutboundValue
    data class Number(val value: BigDecimal) : OutboundValue
    data class Date(val value: LocalDate) : OutboundValue
    data class DateTime(val value: Instant) : OutboundValue
    data class Persons(val value: List<OutboundPerson>) : OutboundValue
    data class Options(val value: List<String>) : OutboundValue
    data class Range(val from: LocalDate?, val to: LocalDate?) : OutboundValue
    data class Bool(val value: Boolean) : OutboundValue
}

/**
 * Estado actual de un registro. [title] es el nombre del elemento fuera. [values] solo lleva los campos con valor
 * (un campo vacio no aparece). [importKey] es la clave con la que se importo ("monday:<itemId>"), o null.
 */
data class OutboundRecord(
    val type: String,
    val id: String,
    val title: String,
    val values: Map<String, OutboundValue>,
    val importKey: String? = null,
)

/** Un archivo listo para enviar por streaming; [open] se llama una vez y el que lo llama cierra el flujo. */
class OutboundFile(val name: String, val contentType: String, val size: Long, val open: () -> InputStream)

/** Lo que un modulo expone de sus registros para enviarlos fuera. Lo implementa el modulo, una vez. */
interface OutboundSource {
    val moduleId: String

    /** Tipos de registro que avisa el modulo, con sus campos (los personalizados de la empresa incluidos). */
    fun recordTypes(tenant: Connection): List<OutboundRecordType>

    /** El registro como esta ahora; null si ya no existe. Los archivados se siguen leyendo. */
    fun record(tenant: Connection, recordType: String, recordId: String): OutboundRecord?

    /** Abre un archivo del registro; null si ya no existe o se quito. */
    fun file(tenant: Connection, files: ModuleFiles, recordType: String, recordId: String, fileId: String): OutboundFile?

    /**
     * Guarda la clave externa del registro (p. ej. "monday:123") despues de crearlo fuera, para que una importacion
     * posterior desde ese servicio lo actualice en vez de duplicarlo. No pisa una clave que ya exista.
     */
    fun linked(tenant: Connection, recordType: String, recordId: String, importKey: String) {
    }

    /**
     * El mapeo de la ultima importacion desde [importSource] para ese tipo de registro: titulo de la columna de fuera
     * a clave del campo. Sirve para proponer el mapeo de salida al reves. Vacio si no hubo importacion.
     */
    fun importMapping(tenant: Connection, recordType: String, importSource: String): Map<String, String> = emptyMap()
}

/** Enlace de un registro con su elemento fuera (tabla outbound_links). */
data class OutboundLink(val externalId: String, val container: String? = null)

/** Enlaces de un sink; [OutboundQueue.links] da la implementacion JDBC. */
interface OutboundLinks {
    fun get(tenant: Connection, sinkId: String, recordType: String, recordId: String): OutboundLink?

    fun put(tenant: Connection, sinkId: String, recordType: String, recordId: String, link: OutboundLink)
}

/** Lo que recibe un sink para entregar un aviso. */
class OutboundDelivery(
    val tenant: Connection,
    val eventId: Long,
    val event: OutboundEvent,
    /** 1 en el primer intento. */
    val attempt: Int,
    val source: OutboundSource,
    /** Archivos del modulo en la empresa; null si el trabajo no los tiene (entonces los File se reintentan). */
    val files: ModuleFiles?,
    val links: OutboundLinks,
    val now: Instant,
)

/** Resultado de entregar un aviso. Los textos se muestran al usuario: nunca llevan el token ni datos de la peticion. */
sealed interface OutboundResult {
    /** Entregado. [warnings]: lo que se omitio sin fallar (un campo sin columna, una mencion sin usuario). */
    data class Sent(val detail: String = "", val warnings: List<String> = emptyList()) : OutboundResult

    /** Nada que hacer (por ejemplo, ya se apago ese tipo de envio). No cuenta como fallo. */
    data class Skipped(val reason: String) : OutboundResult

    /** Fallo pasajero (red, 5xx): se reintenta con espera creciente; tras [OutboundQueue.MaxAttempts] queda fallido. */
    data class Retry(val reason: String, val after: Duration? = null) : OutboundResult

    /**
     * El servicio pidio esperar (HTTP 429, presupuesto de complejidad). El aviso vuelve a la cola sin gastar un intento
     * y el trabajo deja de entregar a ese sink hasta la proxima pasada.
     */
    data class Throttled(val after: Duration, val reason: String) : OutboundResult

    /** Fallo que no se arregla reintentando solo (columna invalida, archivo muy grande): queda a la vista con "Reintentar". */
    data class Failed(val reason: String) : OutboundResult
}

/** Lo implementa una extension que envia los avisos de un modulo a un servicio externo. */
interface OutboundSink {
    /** Id de la extension; los avisos y enlaces de la cola se guardan con el. */
    val sinkId: String

    /** Modulo cuyos avisos recibe. */
    val moduleId: String

    /**
     * Si esta empresa quiere este aviso (lee su configuracion: tipo de registro activo, altas, cambios, notas,
     * archivos, importaciones). Corre dentro de la peticion del usuario: una consulta corta, nunca la red. Debe
     * rechazar los avisos que vienen de su propia importacion (evita el bucle importar -> enviar -> importar).
     */
    fun wants(tenant: Connection, event: OutboundEvent): Boolean

    /** Entrega un aviso; corre en el trabajo en segundo plano. Una excepcion cuenta como [OutboundResult.Retry]. */
    fun deliver(delivery: OutboundDelivery): OutboundResult
}
