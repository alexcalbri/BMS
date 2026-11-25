package com.ideasdeveloper.bms.app.core.theme

import platform.Foundation.NSUserDefaults

actual object PersistentPreferences {
    private const val Prefix = "bms.preferences."

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? = defaults.stringForKey(Prefix + key)

    actual fun set(key: String, value: String) {
        defaults.setObject(value, Prefix + key)
    }
}
