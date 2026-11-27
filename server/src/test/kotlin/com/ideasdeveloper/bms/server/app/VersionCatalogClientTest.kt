package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionCatalogClientTest {
    @Test
    fun onlyHttpsCatalogsAreRead() {
        assertTrue(VersionCatalogClient.allowed("https://raw.githubusercontent.com/x/y/master/version.json"))
        assertFalse(VersionCatalogClient.allowed("http://raw.githubusercontent.com/x/y/master/version.json"))
        assertFalse(VersionCatalogClient.allowed("file:///etc/passwd"))
        assertFalse(VersionCatalogClient.allowed("https://user:pass@example.com/v.json"))
        // Refused before any connection.
        assertNull(VersionCatalogClient.fetch("http://127.0.0.1:1/version.json"))
    }

    @Test
    fun extensionArchiveDownloadsOnlyFollowGitHubOverHttps() {
        assertTrue(ExtensionPackageBuilder.allowedDownloadUrl("https://codeload.github.com/o/r/zip/abc"))
        assertTrue(ExtensionPackageBuilder.allowedDownloadUrl("https://api.github.com/repos/o/r/zipball/abc"))
        assertFalse(ExtensionPackageBuilder.allowedDownloadUrl("http://codeload.github.com/o/r/zip/abc"))
        assertFalse(ExtensionPackageBuilder.allowedDownloadUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(ExtensionPackageBuilder.allowedDownloadUrl("https://evil.example/x.zip"))
        assertFalse(ExtensionPackageBuilder.allowedDownloadUrl("https://codeload.github.com:8443/o/r"))
    }
}
