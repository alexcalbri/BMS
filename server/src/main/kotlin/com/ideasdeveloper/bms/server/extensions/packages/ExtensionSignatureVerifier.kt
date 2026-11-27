package com.ideasdeveloper.bms.server.extensions.packages

import com.ideasdeveloper.bms.server.extensions.ExtensionSignature
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/**
 * Llaves publicas Ed25519 de los paquetes por keyId. Cada valor es base64 de un X.509 SubjectPublicKeyInfo
 * (vacio = sin configurar).
 *
 * Primero se leen los archivos `<keyId>.pub` de [ServerKeysDir], de root: asi el dueno instala o cambia la
 * llave sin recompilar el servidor (SIGNING.md). Un archivo que no sea de root o que otro usuario pueda
 * escribir se ignora. Si no hay archivo para un keyId se usa el recurso incluido extensions/<keyId>.pub.
 */
object BMSPublicKeys {
    const val DefaultKeyId = "bms-2026"
    const val ServerKeysDir = "/usr/local/lib/bms/package-keys"
    private val log = org.slf4j.LoggerFactory.getLogger(BMSPublicKeys::class.java)
    private val KeyIdPattern = Regex("[a-z0-9][a-z0-9-]{0,39}")

    val keys: Map<String, String> by lazy { load(Path.of(ServerKeysDir)) }

    /** true si hay al menos una llave configurada (archivo del servidor o recurso incluido no vacio). */
    val installed: Boolean get() = keys.values.any { it.isNotBlank() }

    /**
     * Llaves de [directory] (si son de confianza segun [trusted]) sobre las incluidas en [bundled]. Las pruebas
     * pasan un directorio temporal y su propio criterio de confianza.
     */
    fun load(
        directory: Path,
        bundled: Map<String, String> = mapOf(DefaultKeyId to readResource("/extensions/$DefaultKeyId.pub")),
        trusted: (Path) -> Boolean = ::rootOwnedAndNotWritableByOthers,
    ): Map<String, String> {
        val result = bundled.toMutableMap()
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return result
        if (!trusted(directory)) {
            // The owner installed keys here: a rejected folder means unsigned packages may pass silently otherwise.
            log.error("Se ignora {}: debe ser de root y nadie mas puede escribirlo. Las llaves de paquetes de ese directorio NO se usan.", directory)
            return result
        }
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".pub") }.sorted().forEach { file ->
                val keyId = file.fileName.toString().removeSuffix(".pub")
                when {
                    !KeyIdPattern.matches(keyId) -> log.warn("Se ignora la llave {}: nombre no valido.", file)
                    !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !trusted(file) ->
                        log.error("Se ignora la llave {}: debe ser un archivo de root que nadie mas pueda escribir.", file)
                    else -> clean(Files.readString(file)).takeIf { it.isNotEmpty() }?.let { result[keyId] = it }
                }
            }
        }
        return result
    }

    /** Dueno root (uid 0) y sin permiso de escritura para el grupo ni para otros. */
    fun rootOwnedAndNotWritableByOthers(path: Path): Boolean = runCatching {
        val uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        uid == 0 && PosixFilePermission.GROUP_WRITE !in permissions && PosixFilePermission.OTHERS_WRITE !in permissions
    }.getOrDefault(false)

    private fun readResource(resource: String): String =
        BMSPublicKeys::class.java.getResourceAsStream(resource)?.use { clean(it.readBytes().decodeToString()) }.orEmpty()

    private fun clean(text: String): String =
        text.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("").filterNot { it.isWhitespace() }
}

/** Verifica la firma Ed25519 de un paquete: se firma el hash del arbol (32 bytes), no su texto hex. */
class ExtensionSignatureVerifier(private val publicKeys: Map<String, String> = BMSPublicKeys.keys) {
    /** Devuelve null cuando la firma es valida, o el motivo del rechazo. */
    fun verify(packageSha256: String, signature: ExtensionSignature): String? {
        val encoded = publicKeys[signature.keyId] ?: return "unknown signing key '${signature.keyId}'"
        if (encoded.isBlank()) return "signing key not configured"
        val hash = runCatching { HexFormat.of().parseHex(packageSha256) }.getOrNull()
        if (hash == null || hash.size != 32) return "packageSha256 must be 64 hex characters"
        return try {
            val key: PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(hash)
            if (verifier.verify(Base64.getDecoder().decode(signature.value))) null else "invalid signature"
        } catch (_: Exception) {
            "invalid signature"
        }
    }
}
