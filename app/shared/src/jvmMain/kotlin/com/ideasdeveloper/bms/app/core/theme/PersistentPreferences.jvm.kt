package com.ideasdeveloper.bms.app.core.theme

import java.util.prefs.Preferences

actual object PersistentPreferences {
    private val preferences = Preferences.userRoot().node("com/ideasdeveloper/bms/preferences")

    actual fun get(key: String): String? = preferences.get(key, null)

    actual fun set(key: String, value: String) {
        preferences.put(key, value)
    }
}
