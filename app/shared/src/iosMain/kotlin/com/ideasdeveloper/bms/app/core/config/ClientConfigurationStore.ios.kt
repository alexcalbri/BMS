package com.ideasdeveloper.bms.app.core.config

import platform.Foundation.NSUserDefaults

actual object ClientConfigurationStore {
    private const val ServerUrlKey = "bms.serverUrl"
    private const val CompanyCodeKey = "bms.companyCode"

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(): ClientConfiguration? {
        val serverUrl = defaults.stringForKey(ServerUrlKey)?.takeIf { it.isNotBlank() } ?: return null
        val companyCode = defaults.stringForKey(CompanyCodeKey)?.takeIf { it.isNotBlank() }
        return ClientConfiguration(serverUrl = serverUrl, companyCode = companyCode)
    }

    actual fun save(configuration: ClientConfiguration) {
        defaults.setObject(configuration.serverUrl, ServerUrlKey)
        configuration.companyCode?.let {
            defaults.setObject(it, CompanyCodeKey)
        } ?: defaults.removeObjectForKey(CompanyCodeKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(ServerUrlKey)
        defaults.removeObjectForKey(CompanyCodeKey)
    }
}
