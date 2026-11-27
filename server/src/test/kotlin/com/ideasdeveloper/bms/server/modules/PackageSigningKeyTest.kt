package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.extensions.packages.BMSPublicKeys
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionSignatureVerifier
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionTreeHash
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C2: the package public key is read from a root-owned folder of the server (fallback: the bundled resource),
 * and with a key installed the module gate is on by default.
 */
class PackageSigningKeyTest {
    private val owner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun base64(pair: KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun sign(pair: KeyPair, hash: String): String = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(HexFormat.of().parseHex(hash))
        Base64.getEncoder().encodeToString(sign())
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pkgkeys")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun signedModule(root: Path, pair: KeyPair?): Path {
        val dir = Files.createDirectories(root.resolve("crm"))
        Files.writeString(dir.resolve("module.json"), """{"id":"crm","version":"1.0.0"}""")
        if (pair != null) {
            val hash = ExtensionTreeHash.compute(dir)
            Files.writeString(
                dir.resolve(ExtensionTreeHash.SignatureFileName),
                """{"packageSha256":"$hash","signature":{"keyId":"bms-2026","value":"${sign(pair, hash)}"}}""",
            )
        }
        return dir
    }

    private fun builder(root: Path, keys: Map<String, String>, enforced: Boolean) =
        ModulePackageBuilder(root.resolve("pkgs"), root.resolve("compiler"), "", "java", verifier = ExtensionSignatureVerifier(keys), signatureEnforced = enforced)

    @Test
    fun serverKeyFileOverridesTheEmptyBundledKey() = withRoot { root ->
        val keysDir = Files.createDirectories(root.resolve("package-keys"))
        Files.writeString(keysDir.resolve("bms-2026.pub"), "# llave del dueno\n${base64(owner)}\n")
        val keys = BMSPublicKeys.load(keysDir, bundled = mapOf("bms-2026" to ""), trusted = { true })
        assertEquals(base64(owner), keys["bms-2026"])
        // The key reaches the module gate: a package signed with it passes.
        val dir = signedModule(root, owner)
        assertEquals(ExtensionTreeHash.compute(dir), builder(root, keys, enforced = true).verify("crm", dir))
    }

    @Test
    fun withoutAServerFileTheBundledResourceIsUsed() = withRoot { root ->
        assertEquals(mapOf("bms-2026" to "BUNDLED"), BMSPublicKeys.load(root.resolve("missing"), bundled = mapOf("bms-2026" to "BUNDLED"), trusted = { true }))
    }

    @Test
    fun keyFilesThatAreNotRootOnlyAreIgnored() = withRoot { root ->
        val keysDir = Files.createDirectories(root.resolve("package-keys"))
        Files.writeString(keysDir.resolve("bms-2026.pub"), base64(stranger))
        Files.writeString(keysDir.resolve("Bad_Name.pub"), base64(stranger))
        // Untrusted folder: nothing in it counts.
        assertEquals(mapOf("bms-2026" to ""), BMSPublicKeys.load(keysDir, bundled = mapOf("bms-2026" to ""), trusted = { it != keysDir }))
        // Untrusted file in a trusted folder: ignored as well; invalid key ids are never loaded.
        assertEquals(mapOf("bms-2026" to ""), BMSPublicKeys.load(keysDir, bundled = mapOf("bms-2026" to ""), trusted = { it == keysDir }))
        assertFalse(BMSPublicKeys.load(keysDir, bundled = emptyMap(), trusted = { true }).containsKey("Bad_Name"))
        // The real check: a temporary file of a non-root test run, or a group-writable one, is not root-only.
        val file = keysDir.resolve("bms-2026.pub")
        if (Files.getAttribute(file, "unix:uid") as Int != 0) assertFalse(BMSPublicKeys.rootOwnedAndNotWritableByOthers(file))
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"))
        assertFalse(BMSPublicKeys.rootOwnedAndNotWritableByOthers(file))
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        if (Files.getAttribute(file, "unix:uid") as Int == 0) assertTrue(BMSPublicKeys.rootOwnedAndNotWritableByOthers(file))
    }

    @Test
    fun withAKeyInstalledAnUnsignedModuleIsRejectedByDefault() = withRoot { root ->
        val keys = mapOf("bms-2026" to base64(owner))
        val enforced = ModuleSignaturePolicy.enforced(setting = null, keyInstalled = { true })
        assertTrue(enforced)
        val dir = signedModule(root, null)
        val failure = assertFailsWith<ModuleBuildException> { builder(root, keys, enforced).verify("crm", dir) }
        assertTrue(failure.message!!.contains("no esta firmado"), failure.message)
    }

    @Test
    fun aModuleSignedByAnotherKeyIsRejected() = withRoot { root ->
        val keys = mapOf("bms-2026" to base64(owner))
        val dir = signedModule(root, stranger)
        val failure = assertFailsWith<ModuleBuildException> { builder(root, keys, enforced = false).verify("crm", dir) }
        assertTrue(failure.message!!.contains("Firma rechazada"), failure.message)
    }

    @Test
    fun theSignatureSettingDefaultsToWhetherAKeyIsInstalled() {
        // Live server today: nothing set, no key -> permissive (unsigned modules keep installing).
        assertFalse(ModuleSignaturePolicy.enforced(setting = null, keyInstalled = { false }))
        assertFalse(ModuleSignaturePolicy.enforced(setting = " ", keyInstalled = { false }))
        assertTrue(ModuleSignaturePolicy.enforced(setting = null, keyInstalled = { true }))
        // An explicit value always wins.
        assertTrue(ModuleSignaturePolicy.enforced(setting = "TRUE", keyInstalled = { false }))
        assertFalse(ModuleSignaturePolicy.enforced(setting = "false", keyInstalled = { true }))
    }

    @Test
    fun theInspectionUsesTheCommitTheCatalogPinned() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val pinned = GitHubLocation("o", "r", sha, "modules")
        assertTrue(pinned.pinned)
        assertFalse(GitHubLocation("o", "r", "master", "modules").pinned)
        assertEquals("https://codeload.github.com/o/r/zip/$sha", pinned.archiveUrl)
    }
}
