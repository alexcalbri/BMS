package com.ideasdeveloper.bms.server.auth.application

/**
 * Keys of the per-address limits. An IPv6 client usually controls a whole /64 (SLAAC, privacy
 * addresses), so counting each address alone lets it rotate past every limit: IPv6 addresses are
 * grouped by their /64; IPv4 addresses stay as they are. Anything unparsable is used as given.
 */
object ClientAddresses {
    fun group(address: String): String {
        val value = address.trim().removePrefix("[").substringBefore(']').substringBefore('%')
        if (!value.contains(':')) return value
        // IPv4-mapped IPv6 (::ffff:1.2.3.4) is an IPv4 client.
        if (value.contains('.')) return if (value.lowercase().startsWith("::ffff:")) value.substringAfterLast(':') else value
        val hextets = expand(value) ?: return value
        return hextets.take(4).joinToString(":") { it.toString(16) } + "::/64"
    }

    // The eight 16-bit groups of an IPv6 address, or null when it is not one.
    private fun expand(value: String): List<Int>? {
        if (value.count { it == ':' } < 2 || value.any { !(it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':') }) return null
        val halves = value.split("::")
        if (halves.size > 2) return null
        fun parts(text: String) = if (text.isEmpty()) emptyList() else text.split(':')
        val head = parts(halves[0])
        val tail = if (halves.size == 2) parts(halves[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if ((halves.size == 1 && missing != 0) || missing < 0) return null
        val all = head + List(missing) { "0" } + tail
        return all.map { part -> part.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
    }
}
