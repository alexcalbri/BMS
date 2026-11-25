package com.ideasdeveloper.bms.app.core.company

import platform.Foundation.NSUserDefaults

actual object PersistentCompanyIdentityStore {
    private const val IdKey = "bms.companyIdentity.id"
    private const val CodeKey = "bms.companyIdentity.code"
    private const val NameKey = "bms.companyIdentity.name"
    private const val LogoUrlKey = "bms.companyIdentity.logoUrl"
    private const val PrimaryColorKey = "bms.companyIdentity.primaryColor"
    private const val SecondaryColorKey = "bms.companyIdentity.secondaryColor"
    private const val AccentColorKey = "bms.companyIdentity.accentColor"

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(): CompanyIdentity? {
        return CompanyIdentity(
            id = defaults.stringForKey(IdKey)?.takeIf { it.isNotBlank() } ?: return null,
            code = defaults.stringForKey(CodeKey)?.takeIf { it.isNotBlank() } ?: return null,
            name = defaults.stringForKey(NameKey)?.takeIf { it.isNotBlank() } ?: return null,
            logoUrl = defaults.stringForKey(LogoUrlKey)?.takeIf { it.isNotBlank() },
            primaryColor = defaults.stringForKey(PrimaryColorKey) ?: ServerOwnerIdentity.primaryColor,
            secondaryColor = defaults.stringForKey(SecondaryColorKey) ?: ServerOwnerIdentity.secondaryColor,
            accentColor = defaults.stringForKey(AccentColorKey) ?: ServerOwnerIdentity.accentColor,
        )
    }

    actual fun save(identity: CompanyIdentity) {
        defaults.setObject(identity.id, IdKey)
        defaults.setObject(identity.code, CodeKey)
        defaults.setObject(identity.name, NameKey)
        identity.logoUrl?.let {
            defaults.setObject(it, LogoUrlKey)
        } ?: defaults.removeObjectForKey(LogoUrlKey)
        defaults.setObject(identity.primaryColor, PrimaryColorKey)
        defaults.setObject(identity.secondaryColor, SecondaryColorKey)
        defaults.setObject(identity.accentColor, AccentColorKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(IdKey)
        defaults.removeObjectForKey(CodeKey)
        defaults.removeObjectForKey(NameKey)
        defaults.removeObjectForKey(LogoUrlKey)
        defaults.removeObjectForKey(PrimaryColorKey)
        defaults.removeObjectForKey(SecondaryColorKey)
        defaults.removeObjectForKey(AccentColorKey)
    }
}
