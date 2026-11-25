package com.ideasdeveloper.bms.app.core.company

import web.storage.localStorage

actual object PersistentCompanyIdentityStore {
    private const val IdKey = "bms.companyIdentity.id"
    private const val CodeKey = "bms.companyIdentity.code"
    private const val NameKey = "bms.companyIdentity.name"
    private const val LogoUrlKey = "bms.companyIdentity.logoUrl"
    private const val PrimaryColorKey = "bms.companyIdentity.primaryColor"
    private const val SecondaryColorKey = "bms.companyIdentity.secondaryColor"
    private const val AccentColorKey = "bms.companyIdentity.accentColor"

    actual fun load(): CompanyIdentity? {
        return CompanyIdentity(
            id = localStorage.getItem(IdKey)?.takeIf { it.isNotBlank() } ?: return null,
            code = localStorage.getItem(CodeKey)?.takeIf { it.isNotBlank() } ?: return null,
            name = localStorage.getItem(NameKey)?.takeIf { it.isNotBlank() } ?: return null,
            logoUrl = localStorage.getItem(LogoUrlKey)?.takeIf { it.isNotBlank() },
            primaryColor = localStorage.getItem(PrimaryColorKey) ?: ServerOwnerIdentity.primaryColor,
            secondaryColor = localStorage.getItem(SecondaryColorKey) ?: ServerOwnerIdentity.secondaryColor,
            accentColor = localStorage.getItem(AccentColorKey) ?: ServerOwnerIdentity.accentColor,
        )
    }

    actual fun save(identity: CompanyIdentity) {
        localStorage.setItem(IdKey, identity.id)
        localStorage.setItem(CodeKey, identity.code)
        localStorage.setItem(NameKey, identity.name)
        identity.logoUrl?.let {
            localStorage.setItem(LogoUrlKey, it)
        } ?: localStorage.removeItem(LogoUrlKey)
        localStorage.setItem(PrimaryColorKey, identity.primaryColor)
        localStorage.setItem(SecondaryColorKey, identity.secondaryColor)
        localStorage.setItem(AccentColorKey, identity.accentColor)
    }

    actual fun clear() {
        localStorage.removeItem(IdKey)
        localStorage.removeItem(CodeKey)
        localStorage.removeItem(NameKey)
        localStorage.removeItem(LogoUrlKey)
        localStorage.removeItem(PrimaryColorKey)
        localStorage.removeItem(SecondaryColorKey)
        localStorage.removeItem(AccentColorKey)
    }
}
