package com.ideasdeveloper.bms.app.core.theme

import web.storage.localStorage

actual object PersistentPreferences {
    private const val Prefix = "bms.preferences."

    actual fun get(key: String): String? = localStorage.getItem(Prefix + key)

    actual fun set(key: String, value: String) {
        localStorage.setItem(Prefix + key, value)
    }
}
