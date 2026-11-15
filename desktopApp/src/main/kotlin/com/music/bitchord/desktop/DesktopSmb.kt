package com.music.bitchord.desktop

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet

/** SMB2/SMB3 access for desktop builds. File handles are independent for concurrent reads and seeks. */
internal object DesktopSmbClient {
    data class Credentials(val username: String = "", val password: String = "", val domain: String = "")
    data class Entry(val name: String, val path: String, val directory: Boolean, val size: Long)

    private const val SECRET_SERVICE = "bitchord-smb"

    fun saveCredentials(host: String, share: String, credentials: Credentials): Boolean {
        val key = attributes(host, share)
        return if (credentials.username.isBlank() && credentials.password.isBlank()) {
            DesktopSecretStore.remove(key)
        } else {
            DesktopSecretStore.store(
                "BitChord SMB $host/$share", key,
                "${credentials.username}\u0000${credentials.domain}\u0000${credentials.password}".toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun loadCredentials(host: String, share: String): Credentials? {
        val raw = DesktopSecretStore.lookup(attributes(host, share)) ?: return null
        return runCatching {
            val parts = raw.toString(Charsets.UTF_8).split('\u0000', limit = 3)
            Credentials(parts.getOrElse(0) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(1) { "" })
        }.getOrNull()
    }

    private fun attributes(host: String, share: String) = mapOf(
        "application" to SECRET_SERVICE,
        "host" to host.trim().lowercase(),
        "share" to share.trim().lowercase(),
    )

    fun connect(host: String, share: String, credentials: Credentials = Credentials()): Session {
        require(host.isNotBlank()) { "Server address is required" }
        require(share.isNotBlank() && !share.contains('/') && !share.contains('\\')) { "Enter a share name, not a path" }
        val client = SMBClient()
        try {
            val connection = client.connect(host.trim())
            val auth = if (credentials.username.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(credentials.username, credentials.password.toCharArray(), credentials.domain)
            val session = connection.authenticate(auth)
            val disk = session.connectShare(share) as? DiskShare
                ?: error("The requested SMB share is not a disk share")
            return Session(client, connection, disk)
        } catch (failure: Throwable) {
            client.close()
            throw IOException("Could not connect to \\\\$host\\\\$share: ${failure.message}", failure)
        }
    }

    class Session internal constructor(
        private val client: SMBClient,
        private val connection: com.hierynomus.smbj.connection.Connection,
        private val share: DiskShare,
    ) : Closeable {
        fun list(path: String = ""): List<Entry> {
            val wirePath = normalize(path)
            val directory = share.openDirectory(
                wirePath, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            )
            return directory.use { handle ->
                handle.list().asSequence()
                    .filterNot { it.fileName == "." || it.fileName == ".." }
                    .map { item ->
                        val child = if (wirePath.isBlank()) item.fileName else "$wirePath\\\\${item.fileName}"
                        Entry(
                            item.fileName, child.replace('\\\\', '/'),
                            (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                            item.endOfFile,
                        )
                    }.toList()
            }
        }

        fun open(path: String): RemoteFile {
            val file = share.openFile(
                normalize(path), EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            return RemoteFile(file)
        }

        override fun close() {
            runCatching { share.close() }
            runCatching { connection.close() }
            client.close()
        }
    }

    class RemoteFile internal constructor(private val file: File) : Closeable {
        val length: Long get() = file.fileInformation.standardInformation.endOfFile
        fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
            require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            return file.read(buffer, position, offset, length)
        }
        override fun close() = file.close()
    }

    private fun normalize(path: String): String {
        val parts = path.trim().trim('/', '\\\\').replace('/', '\\\\').split('\\\\')
            .filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Parent-directory traversal is not allowed" }
        return parts.joinToString("\\\\")
    }
}

/** Creates and removes shares through the native Windows or Samba service. */
internal object DesktopSmbServer {
    data class Share(val name: String, val directory: Path, val readOnly: Boolean = true)

    fun create(share: Share): Result<Unit> = runCatching {
        require(share.name.matches(Regex("[A-Za-z0-9 _.-]{1,80}"))) { "Share name contains unsupported characters" }
        val path = share.directory.toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "Choose an existing folder" }
        when (platform()) {
            Platform.WINDOWS -> runCommand(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "New-SmbShare -Name '${psQuote(share.name)}' -Path '${psQuote(path.toString())}' -ReadAccess 'Everyone' -ErrorAction Stop | Out-Null",
            )
            Platform.LINUX -> {
                require(commandExists("net")) { "Samba is required. Install the samba package first." }
                val args = mutableListOf("net", "conf", "addshare", share.name, path.toString(), "guest_ok=n", "browseable=y")
                args += if (share.readOnly) "read only=y" else "read only=n"
                runCommand(*args.toTypedArray())
                if (commandExists("systemctl")) runCommand("systemctl", "reload", "smb")
            }
        }
    }

    fun remove(name: String): Result<Unit> = runCatching {
        require(name.matches(Regex("[A-Za-z0-9 _.-]{1,80}"))) { "Invalid share name" }
        when (platform()) {
            Platform.WINDOWS -> runCommand(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Remove-SmbShare -Name '${psQuote(name)}' -Force -ErrorAction Stop",
            )
            Platform.LINUX -> {
                runCommand("net", "conf", "delshare", name)
                if (commandExists("systemctl")) runCommand("systemctl", "reload", "smb")
            }
        }
    }

    private enum class Platform { WINDOWS, LINUX }
    private fun platform() = when {
        System.getProperty("os.name").contains("Windows", true) -> Platform.WINDOWS
        System.getProperty("os.name").contains("Linux", true) -> Platform.LINUX
        else -> error("SMB sharing is supported on Windows and Linux only")
    }
    private fun psQuote(value: String) = value.replace("'", "''")
    private fun commandExists(command: String): Boolean =
        runCatching { ProcessBuilder("sh", "-c", "command -v '$command'").start().waitFor() == 0 }.getOrDefault(false)

    private fun runCommand(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { output.ifBlank { "Command failed (${command.first()}) with exit code $exit" } }
    }
}
