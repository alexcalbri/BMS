package com.ideasdeveloper.bms.server.auth.application

import com.ideasdeveloper.bms.server.i18n.tr

/** Name and email every user must have (changes are recorded with them; the email gets reset codes). */
object UserProfileRules {
    const val MaxNameLength = 120
    const val MaxEmailLength = 254
    private val emailPattern = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    fun validName(name: String): Boolean = name.trim().length in 2..MaxNameLength

    fun validEmail(email: String): Boolean = email.trim().let { it.length <= MaxEmailLength && emailPattern.matches(it) }

    // The first problem with a name and email, or null when both are valid.
    fun problem(name: String, email: String): String? = when {
        !validName(name) -> tr("escribe un nombre de 2 a {0} caracteres.", MaxNameLength)
        !validEmail(email) -> tr("escribe un correo valido.")
        else -> null
    }

    fun isComplete(name: String?, email: String?): Boolean = name != null && email != null && validName(name) && validEmail(email)
}
