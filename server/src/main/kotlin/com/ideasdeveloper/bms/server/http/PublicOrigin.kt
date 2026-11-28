package com.ideasdeveloper.bms.server.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The public origin (scheme and authority, no trailing slash) of this server as seen by a client.
 *
 * Ktor listens on the loopback behind the shipped Nginx, which sets `Host`, `X-Forwarded-Proto` and `X-Real-IP`.
 * Those headers are client-controlled when the server is reached without the proxy, so:
 * - `X-Forwarded-Proto` is only believed from [ClientAddresses.LoopbackProxies] and only as `http` or `https`;
 * - `X-Forwarded-Host` is never read: the authority comes from `Host`, validated (see [requestHost]).
 *
 * Two policies, because they answer different questions:
 * - [forLinks]: an origin to put in a link that somebody else will follow (e-mail). Only the configured base or a
 *   host the server really serves is accepted; otherwise null and the caller builds no link.
 * - [forRequest]: an origin to hand back to the client that is calling right now (OAuth metadata, webhook URLs).
 *   It never returns null: the validated `Host`, or the server's own host when `Host` is missing or unusable.
 */
object PublicOrigin {
    private val HostPattern = Regex("[a-z0-9.-]+(:[0-9]{1,5})?")

    /** BMS_PUBLIC_BASE_URL (http or https, no trailing slash) when the owner set it. It wins over any request header. */
    fun configured(): String? = configuredFrom(System.getenv("BMS_PUBLIC_BASE_URL"))

    /** The scheme of the request: `X-Forwarded-Proto` from the local proxy (`http` or `https`), else the connection's own. */
    fun scheme(call: ApplicationCall): String =
        schemeOf(call.request.local.remoteAddress, call.request.headers["X-Forwarded-Proto"], call.request.local.scheme)

    /**
     * The `Host` header: trimmed, lowercased, at most 255 characters and only `[a-z0-9.-]+` with an optional `:port`.
     * Null when it is missing or does not fit. `X-Forwarded-Host` is never consulted.
     */
    fun requestHost(call: ApplicationCall): String? = hostOf(call.request.headers["Host"])

    /**
     * Origin for links in e-mails: [configured] when set; otherwise `scheme://host` of the request only when
     * [knownHost] (given the host without its port) says it is a domain of this server. Null: build no link.
     */
    fun forLinks(call: ApplicationCall, knownHost: (String) -> Boolean): String? = resolve(
        configured = configured(),
        remoteAddress = call.request.local.remoteAddress,
        forwardedProto = call.request.headers["X-Forwarded-Proto"],
        localScheme = call.request.local.scheme,
        hostHeader = call.request.headers["Host"],
        serverHost = "",
        knownHost = knownHost,
    )

    internal fun configuredFrom(value: String?): String? =
        value?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    internal fun schemeOf(remoteAddress: String, forwardedProto: String?, localScheme: String): String {
        val proxied = remoteAddress in ClientAddresses.LoopbackProxies
        return (if (proxied) forwardedProto?.substringBefore(',')?.trim() else null)
            ?.takeIf { it == "https" || it == "http" } ?: localScheme
    }

    internal fun hostOf(hostHeader: String?): String? =
        hostHeader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 255 && HostPattern.matches(it) }

    /**
     * The pure function behind [forLinks] (and [forRequest]). With a [knownHost] it is the links policy and may be
     * null; without one it is the request policy and falls back to [serverHost].
     */
    internal fun resolve(
        configured: String?,
        remoteAddress: String,
        forwardedProto: String?,
        localScheme: String,
        hostHeader: String?,
        serverHost: String,
        knownHost: ((String) -> Boolean)?,
    ): String? {
        configured?.let { return it }
        val scheme = schemeOf(remoteAddress, forwardedProto, localScheme)
        val host = hostOf(hostHeader)
        if (knownHost == null) return "$scheme://${host ?: serverHost}"
        host ?: return null
        return if (knownHost(host.substringBefore(':'))) "$scheme://$host" else null
    }
}
