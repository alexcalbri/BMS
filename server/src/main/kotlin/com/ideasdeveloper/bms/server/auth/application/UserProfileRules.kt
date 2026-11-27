package com.ideasdeveloper.bms.server.auth.application

import com.ideasdeveloper.bms.server.i18n.tr

/** Name and email every user must have (changes are recorded with them; the email gets reset codes). */
object UserProfileRules {
    const val MaxNameLength = 120
    const val MaxEmailLength = 254
    private val emailPattern = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    fun validName(name: String): Boolean = name.trim().length in 2..MaxNameLength && !hasControlCharacters(name)

    fun validEmail(email: String): Boolean = email.trim().let { it.length <= MaxEmailLength && emailPattern.matches(it) && !hasControlCharacters(it) }

    /**
     * Control characters (C0, DEL, C1, line and paragraph separators) are refused in names of users,
     * companies, the server and its workspace: they break audit rows, logs and what other users see.
     */
    fun hasControlCharacters(text: String): Boolean = text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }

    // The first problem with a name and email, or null when both are valid.
    fun problem(name: String, email: String): String? = when {
        hasControlCharacters(name) -> tr("el nombre no puede tener caracteres de control.")
        !validName(name) -> tr("escribe un nombre de 2 a {0} caracteres.", MaxNameLength)
        // The activity log adds these itself ("Agente de Ana", "Luis (desde el servidor)"): a name may not fake them.
        reservedName(name) -> tr("el nombre no puede contener \"Agente de\", \"desde el servidor\", \"Importacion (\", \"Automatizacion\" ni \"Tarea programada\".")
        !validEmail(email) -> tr("escribe un correo valido.")
        else -> null
    }

    /** Whether [name] contains a marker of the activity log (ActivityNames); existing names are only quoted when shown. */
    fun reservedName(name: String): Boolean = com.ideasdeveloper.bms.server.activity.ActivityNames.hasMarker(name)

    fun isComplete(name: String?, email: String?): Boolean = name != null && email != null && validName(name) && validEmail(email)
}
