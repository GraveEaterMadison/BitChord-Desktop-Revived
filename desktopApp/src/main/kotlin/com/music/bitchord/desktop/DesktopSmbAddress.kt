package com.music.bitchord.desktop

/** Parsed SMB endpoint. IPv6 literals must be bracketed when a port is supplied. */
internal data class DesktopSmbAddress(val host: String, val port: Int = DEFAULT_PORT) {
    init {
        require(host.isNotBlank()) { "Server address is required" }
        require(port in 1..65535) { "SMB port must be between 1 and 65535" }
    }

    companion object {
        const val DEFAULT_PORT = 445

        fun parse(input: String): DesktopSmbAddress {
            val value = input.trim().removePrefix("smb://").substringBefore('/').trimEnd('/')
            require(value.isNotBlank()) { "Server address is required" }

            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                require(end > 1) { "Invalid IPv6 address" }
                val host = value.substring(1, end)
                val suffix = value.substring(end + 1)
                require(suffix.isEmpty() || suffix.startsWith(":")) { "Invalid server address" }
                val port = suffix.removePrefix(":").takeIf(String::isNotEmpty)?.toIntOrNull() ?: DEFAULT_PORT
                return DesktopSmbAddress(host, port)
            }

            val firstColon = value.indexOf(':')
            val lastColon = value.lastIndexOf(':')
            if (firstColon >= 0 && firstColon == lastColon) {
                val portText = value.substring(lastColon + 1)
                require(portText.isNotBlank()) { "Invalid SMB port" }
                val port = portText.toIntOrNull() ?: error("Invalid SMB port")
                return DesktopSmbAddress(value.substring(0, lastColon), port)
            }
            require(firstColon < 0) { "IPv6 addresses with a port must use [address]:port notation" }
            return DesktopSmbAddress(value)
        }
    }
}

/** Share-relative paths use forward slashes in the UI and backslashes on the SMB wire. */
internal object DesktopSmbPath {
    fun normalize(path: String): String {
        val parts = path.trim().trim('/', '\\').replace('/', '\\').split('\\')
            .filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Parent-directory traversal is not allowed" }
        return parts.joinToString("\\")
    }

    fun display(path: String): String = normalize(path).replace('\\', '/')
}
