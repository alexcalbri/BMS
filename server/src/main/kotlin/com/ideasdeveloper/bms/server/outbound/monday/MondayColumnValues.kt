package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Valor listo para una columna de monday ([json] null = se omite) y, si hay, el aviso de por que. */
data class MondayColumnValue(val json: JsonElement?, val warning: String? = null)

/** Convierte valores de BMS al JSON que espera cada tipo de columna de monday (change_multiple_column_values). */
object MondayColumnValues {
    const val MaxLongText = 2000
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    private fun text(value: String) = JsonPrimitive(value)

    /** Nombre en espanol del tipo de valor, para los avisos. */
    fun valueLabel(value: OutboundValue): String = when (value) {
        is OutboundValue.Text -> "texto"
        is OutboundValue.Number -> "numero"
        is OutboundValue.Date -> "fecha"
        is OutboundValue.DateTime -> "fecha y hora"
        is OutboundValue.Persons -> "personas"
        is OutboundValue.Options -> "opciones"
        is OutboundValue.Range -> "rango de fechas"
        is OutboundValue.Bool -> "si/no"
    }

    /** El valor como texto plano (para columnas de texto). */
    fun plain(value: OutboundValue): String = when (value) {
        is OutboundValue.Text -> value.value
        is OutboundValue.Number -> value.value.stripTrailingZeros().toPlainString()
        is OutboundValue.Date -> value.value.toString()
        is OutboundValue.DateTime -> value.value.atOffset(ZoneOffset.UTC).let { "${it.toLocalDate()} ${time.format(it)} UTC" }
        is OutboundValue.Persons -> value.value.joinToString(", ") { it.name }
        is OutboundValue.Options -> value.value.joinToString(", ")
        is OutboundValue.Range -> listOfNotNull(value.from, value.to).joinToString(" - ")
        is OutboundValue.Bool -> if (value.value) "Si" else "No"
    }

    private fun incompatible(columnType: String, value: OutboundValue) =
        MondayColumnValue(null, "no se puede escribir ${valueLabel(value)} en una columna $columnType")

    /**
     * El JSON de [value] para una columna de tipo monday [columnType]. [people] resuelve personas a ids de usuario de
     * monday (los que no encuentra se omiten con aviso). [phoneCountry] es el pais por defecto de los telefonos (ISO-2).
     */
    fun value(columnType: String, value: OutboundValue, people: (List<OutboundPerson>) -> List<Long>, phoneCountry: String?): MondayColumnValue =
        when (columnType) {
            "text", "name" -> MondayColumnValue(text(plain(value)))
            "long_text" -> plain(value).let { full ->
                MondayColumnValue(obj("text" to text(full.take(MaxLongText))), if (full.length > MaxLongText) "texto recortado a $MaxLongText caracteres" else null)
            }
            "status" -> when (value) {
                is OutboundValue.Text -> MondayColumnValue(obj("label" to text(value.value)))
                is OutboundValue.Options -> value.value.firstOrNull()?.let { MondayColumnValue(obj("label" to text(it))) }
                    ?: MondayColumnValue(null, "no hay opcion que escribir en la columna status")
                else -> incompatible(columnType, value)
            }
            "dropdown" -> when (value) {
                is OutboundValue.Text -> MondayColumnValue(obj("labels" to JsonArray(listOf(text(value.value)))))
                is OutboundValue.Options -> MondayColumnValue(obj("labels" to JsonArray(value.value.map(::text))))
                else -> incompatible(columnType, value)
            }
            "date" -> when (value) {
                is OutboundValue.Date -> MondayColumnValue(obj("date" to text(value.value.toString())))
                is OutboundValue.DateTime -> value.value.atOffset(ZoneOffset.UTC).let {
                    MondayColumnValue(obj("date" to text(it.toLocalDate().toString()), "time" to text(time.format(it))))
                }
                else -> incompatible(columnType, value)
            }
            "people" -> when (value) {
                is OutboundValue.Persons -> {
                    val ids = people(value.value).distinct()
                    val warning = if (ids.size < value.value.distinct().size) "no se encontraron en monday todas las personas (${value.value.joinToString(", ") { it.name }.take(120)})" else null
                    if (ids.isEmpty()) MondayColumnValue(null, warning ?: "no hay personas que escribir")
                    else MondayColumnValue(obj("personsAndTeams" to JsonArray(ids.map { obj("id" to JsonPrimitive(it), "kind" to text("person")) })), warning)
                }
                else -> incompatible(columnType, value)
            }
            "phone" -> when (value) {
                is OutboundValue.Text -> phone(value.value, phoneCountry)
                else -> incompatible(columnType, value)
            }
            "email" -> when (value) {
                is OutboundValue.Text -> value.value.trim().let {
                    if ('@' in it && ' ' !in it) MondayColumnValue(obj("email" to text(it), "text" to text(it)))
                    else MondayColumnValue(null, "el correo \"${it.take(60)}\" no es valido")
                }
                else -> incompatible(columnType, value)
            }
            "numbers" -> when (value) {
                is OutboundValue.Number -> MondayColumnValue(text(value.value.stripTrailingZeros().toPlainString()))
                is OutboundValue.Text -> value.value.trim().toBigDecimalOrNull()?.let { MondayColumnValue(text(it.stripTrailingZeros().toPlainString())) }
                    ?: MondayColumnValue(null, "\"${value.value.take(40)}\" no es un numero")
                else -> incompatible(columnType, value)
            }
            "link" -> when (value) {
                is OutboundValue.Text -> value.value.trim().let {
                    if (it.startsWith("http://", true) || it.startsWith("https://", true)) MondayColumnValue(obj("url" to text(it), "text" to text(it)))
                    else MondayColumnValue(null, "el enlace \"${it.take(60)}\" no es una direccion http(s)")
                }
                else -> incompatible(columnType, value)
            }
            "timeline" -> when (value) {
                // monday pide inicio y fin: con una sola fecha se usa para las dos.
                is OutboundValue.Range -> (value.from ?: value.to)?.let { from ->
                    MondayColumnValue(obj("from" to text(from.toString()), "to" to text((value.to ?: from).toString())))
                } ?: MondayColumnValue(null, "el rango no tiene fechas")
                else -> incompatible(columnType, value)
            }
            "checkbox" -> when (value) {
                is OutboundValue.Bool -> MondayColumnValue(obj("checked" to text(value.value.toString())))
                else -> incompatible(columnType, value)
            }
            else -> MondayColumnValue(null, "no se puede escribir ${valueLabel(value)} en una columna $columnType")
        }

    private fun phone(raw: String, country: String?): MondayColumnValue {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 5) return MondayColumnValue(null, "el telefono \"${trimmed.take(30)}\" no es valido")
        val code = country?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: return MondayColumnValue(null, "falta el pais del telefono en la configuracion")
        val normalized = (if (trimmed.startsWith("+")) "+" else "") + digits
        return MondayColumnValue(obj("phone" to text(normalized), "countryShortName" to text(code)))
    }

    /** Tipos de columna de monday que aceptan un campo de ese tipo (para filtrar el mapeo en la pantalla). */
    fun compatible(fieldType: OutboundFieldType): Set<String> = when (fieldType) {
        OutboundFieldType.Text -> setOf("text", "long_text", "name", "status", "dropdown")
        OutboundFieldType.LongText -> setOf("long_text", "text")
        OutboundFieldType.Number -> setOf("numbers", "text")
        OutboundFieldType.Date, OutboundFieldType.DateTime -> setOf("date", "text")
        OutboundFieldType.Email -> setOf("email", "text")
        OutboundFieldType.Phone -> setOf("phone", "text")
        OutboundFieldType.Url -> setOf("link", "text")
        OutboundFieldType.Person -> setOf("people", "text")
        OutboundFieldType.Status -> setOf("status", "dropdown", "text")
        OutboundFieldType.Options -> setOf("dropdown", "status", "text", "long_text")
        OutboundFieldType.Timeline -> setOf("timeline")
        OutboundFieldType.Boolean -> setOf("checkbox", "text")
    }
}
