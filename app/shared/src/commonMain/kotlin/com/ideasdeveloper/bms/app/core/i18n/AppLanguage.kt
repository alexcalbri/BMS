package com.ideasdeveloper.bms.app.core.i18n

import androidx.compose.runtime.mutableStateOf
import com.ideasdeveloper.bms.app.core.theme.PersistentPreferences

/**
 * Idioma de la app. El espanol es el idioma fuente (y la clave de los textos); el ingles viene de
 * [EnglishStrings]. Agregar otro idioma solo necesita su mapa en [catalogFor] y su codigo en [Supported].
 *
 * El idioma efectivo es un estado de Compose: toda pantalla que llame a [tr] se recompone al cambiarlo.
 * Orden de prioridad: eleccion del usuario, idioma de la empresa y, antes de iniciar sesion, el del dispositivo.
 * Tras iniciar sesion manda el idioma que devuelve el servidor.
 */
object AppLanguage {
    const val Source = "es"
    val Supported: List<String> = listOf("es", "en")

    private const val EffectiveKey = "i18n.language"
    private const val ChoiceKey = "i18n.choice"
    private const val CompanyKey = "i18n.companyLanguage"

    // Eleccion explicita del usuario; null = seguir el idioma de la empresa.
    private val choiceState = mutableStateOf<String?>(normalize(PersistentPreferences.get(ChoiceKey)))
    private val companyState = mutableStateOf<String?>(normalize(PersistentPreferences.get(CompanyKey)))
    private val languageState = mutableStateOf(
        normalize(PersistentPreferences.get(EffectiveKey)) ?: Source
    )

    // Idioma del dispositivo ("en" o el fuente). Lo informa App() desde una funcion @Composable: leerlo aqui,
    // fuera de composicion, no compila de forma segura con todas las versiones de Compose.
    private var deviceState: String = Source

    // Idioma con el que se pintan los textos y que viaja en Accept-Language.
    val current: String get() = languageState.value

    // Eleccion del usuario: null sigue a la empresa, "es" o "en" fuerzan ese idioma.
    val choice: String? get() = choiceState.value

    // Idioma por defecto de la empresa de la sesion (null si el servidor no lo informo).
    val companyLanguage: String? get() = companyState.value

    // "en-US", "en_GB", "EN" -> "en"; desconocido o vacio -> null.
    fun normalize(tag: String?): String? {
        val base = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
        return base.takeIf { it in Supported }
    }

    private fun deviceLanguage(): String = deviceState

    // Recibe el idioma del dispositivo. Solo cambia lo que se ve si aun no hay idioma guardado ni sesion.
    fun useDeviceLanguage(tag: String?) {
        deviceState = if (normalize(tag) == "en") "en" else Source
        val hasStored = normalize(PersistentPreferences.get(EffectiveKey)) != null
        if (!hasStored && choiceState.value == null && companyState.value == null) {
            languageState.value = deviceState
        }
    }

    // El usuario elige idioma en Ajustes o en su perfil. null = seguir el idioma de la empresa.
    fun select(language: String?) {
        val chosen = normalize(language)
        choiceState.value = chosen
        PersistentPreferences.set(ChoiceKey, chosen.orEmpty())
        apply(chosen ?: companyState.value ?: deviceLanguage())
    }

    // Tras iniciar sesion: [language] es el idioma efectivo del usuario, [companyLanguage] el de su empresa y
    // [userLanguage] la eleccion propia del usuario (null = sigue al idioma de la empresa).
    fun applyServer(language: String?, companyLanguage: String?, userLanguage: String?) {
        val company = normalize(companyLanguage)
        if (company != null) {
            companyState.value = company
            PersistentPreferences.set(CompanyKey, company)
        }
        val effective = normalize(language) ?: return
        // Un servidor sin userLanguage (antiguo) tampoco informa language, asi que aqui la respuesta es completa.
        val chosen = normalize(userLanguage)
        choiceState.value = chosen
        PersistentPreferences.set(ChoiceKey, chosen.orEmpty())
        apply(effective)
    }

    private fun apply(language: String) {
        languageState.value = language
        PersistentPreferences.set(EffectiveKey, language)
    }
}

/**
 * Traduce un texto fuente en espanol al idioma actual. Los marcadores {0}, {1}... se sustituyen por [args].
 * Sin traduccion (o en espanol) devuelve el texto fuente.
 */
fun tr(source: String, vararg args: Any?): String {
    val language = AppLanguage.current
    val text = if (language == AppLanguage.Source) source else catalogFor(language)?.get(source) ?: source
    return fillPlaceholders(text, args)
}

// Catalogo del idioma, o null si es el fuente o no hay catalogo.
internal fun catalogFor(language: String): Map<String, String>? = when (language) {
    "en" -> EnglishStrings
    else -> null
}

// Reemplaza {n} por el argumento n en una sola pasada; un {n} sin argumento se deja tal cual.
internal fun fillPlaceholders(text: String, args: Array<out Any?>): String {
    if (args.isEmpty() || text.indexOf('{') < 0) return text
    val out = StringBuilder(text.length + 16)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '{') {
            val close = text.indexOf('}', i + 1)
            if (close > i + 1) {
                val index = text.substring(i + 1, close).toIntOrNull()
                if (index != null && index >= 0 && index < args.size) {
                    out.append(args[index].toString())
                    i = close + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}
