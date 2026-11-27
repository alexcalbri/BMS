package com.ideasdeveloper.bms.extensions.unifi

import com.ideasdeveloper.bms.modules.hotspot.HotspotModule
import com.ideasdeveloper.bms.modules.hotspot.HotspotSettings
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection

/**
 * The UniFi configuration in the company's activity log: console, site and API key. The values are
 * stored in hotspot_settings by the hotspot; this extension records its own keys, in the transaction
 * of the change (the hotspot calls [record] through NetworkControllerProvider.recordConfiguration).
 * The API key is never stored: the row says only that it changed.
 */
object UnifiActivity {
    const val ControllerId = "unifi"

    // Spanish labels of the UniFi keys (stored text).
    val Labels: Map<String, String> = linkedMapOf(
        "unifiConsoleId" to "Consola de UniFi",
        "unifiSiteId" to "Sitio de UniFi",
        "unifiApiKey" to "API key de UniFi",
    )

    /** The UniFi fields that changed between [before] and [after]; the API key only as "changed". */
    fun changes(before: HotspotSettings, after: HotspotSettings): List<ActivityChange> =
        ActivityChanges.diff(values(before), values(after), Labels, secrets = setOf("unifiApiKey"))

    /** Writes one row for the UniFi change, if any, for [actor] on [connection]. */
    fun record(connection: Connection, actor: ActivityActor, before: HotspotSettings, after: HotspotSettings) {
        val changes = changes(before, after)
        if (changes.isEmpty()) return
        ActivityLog.record(
            connection, actor,
            ActivityEntry(HotspotModule.id, "controlador", ControllerId, "UniFi", ActivityActions.SettingsChanged, "Cambio la configuracion de UniFi.", changes),
        )
    }

    private fun values(settings: HotspotSettings): Map<String, Any?> = mapOf(
        "unifiConsoleId" to settings.unifiConsoleId,
        "unifiSiteId" to settings.unifiSiteId,
        "unifiApiKey" to settings.unifiApiKey,
    )
}
