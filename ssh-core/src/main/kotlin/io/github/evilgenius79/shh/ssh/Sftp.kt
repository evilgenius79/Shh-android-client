package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

enum class SftpFileType { FILE, DIRECTORY, SYMLINK, OTHER }

/** One file or directory on the server. */
class SftpEntry(
    val name: String,
    /** Absolute path on the server. */
    val path: String,
    val type: SftpFileType,
    val size: Long,
    /** Last modification time in seconds since the epoch. */
    val modifiedSeconds: Long,
    /** Permission bits, for example `0644`. */
    val permissions: Int,
    val uid: Int,
    val gid: Int,
    /** True for directories and for symbolic links that point at one. */
    val isDirectory: Boolean,
) {
    val isSymlink: Boolean get() = type == SftpFileType.SYMLINK

    /** Permissions the way `ls -l` prints them, for example `drwxr-xr-x`. */
    val permissionString: String
        get() {
            val out = StringBuilder(10)
            out.append(
                when (type) {
                    SftpFileType.DIRECTORY -> 'd'
                    SftpFileType.SYMLINK -> 'l'
                    SftpFileType.FILE -> '-'
                    SftpFileType.OTHER -> '?'
                },
            )
            val flags = "rwxrwxrwx"
            for (i in 0 until 9) out.append(if (permissions and (1 shl (8 - i)) != 0) flags[i] else '-')
            return out.toString()
        }
}

/** Thrown when a transfer was stopped through its `isCancelled` callback. */
class TransferCancelledException : IOException("Transfer cancelled")

/** File access over an SSH connection. Methods block and may be called from several threads. */
class SftpSession internal constructor(private val sftp: SFTPClient) : Closeable {
    /** The directory the session starts in, normally the user's home directory. */
    @Throws(IOException::class)
    fun homeDirectory(): String = sftp.canonicalize(".")

    /** Resolves `.`, `..` and symbolic links in [path] to an absolute path. */
    @Throws(IOException::class)
    fun canonicalize(path: String): String = sftp.canonicalize(path)

    /**
     * Lists a directory: directories first, then files, each sorted by name ignoring case.
     * `.` and `..` are not included.
     */
    @Throws(IOException::class)
    fun list(directory: String): List<SftpEntry> {
        val entries = sftp.ls(directory).map { info ->
            var isDirectory = info.isDirectory
            if (info.attributes.type == FileMode.Type.SYMLINK) {
                // Follow the link to find out whether it can be entered. A dangling link fails.
                isDirectory = try {
                    sftp.stat(info.path).type == FileMode.Type.DIRECTORY
                } catch (_: IOException) {
                    false
                }
            }
            entry(info.name, info.path, info.attributes, isDirectory)
        }
        return entries.sortedWith(compareBy<SftpEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Attributes of [path], following symbolic links, or null when it does not exist. */
    @Throws(IOException::class)
    fun stat(path: String): SftpEntry? {
        val attributes = try {
            sftp.stat(path)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) return null
            throw e
        }
        return entry(path.substringAfterLast('/'), path, attributes, attributes.type == FileMode.Type.DIRECTORY)
    }

    private fun entry(name: String, path: String, attributes: FileAttributes, isDirectory: Boolean): SftpEntry {
        val type = when (attributes.type) {
            FileMode.Type.REGULAR -> SftpFileType.FILE
            FileMode.Type.DIRECTORY -> SftpFileType.DIRECTORY
            FileMode.Type.SYMLINK -> SftpFileType.SYMLINK
            else -> SftpFileType.OTHER
        }
        return SftpEntry(
            name = name,
            path = path,
            type = type,
            size = attributes.size,
            modifiedSeconds = attributes.mtime,
            permissions = attributes.mode.permissionsMask,
            uid = attributes.uid,
            gid = attributes.gid,
            isDirectory = isDirectory,
        )
    }

    @Throws(IOException::class)
    fun makeDirectory(path: String) = sftp.mkdir(path)

    @Throws(IOException::class)
    fun rename(from: String, to: String) = sftp.rename(from, to)

    @Throws(IOException::class)
    fun readLink(path: String): String = sftp.readlink(path)

    /** Sets the permission bits, for example `0x1A4` (octal 644). */
    @Throws(IOException::class)
    fun setPermissions(path: String, permissions: Int) = sftp.chmod(path, permissions)

    /** Deletes a file, a symbolic link or, recursively, a directory. Links are never followed. */
    @Throws(IOException::class)
    fun delete(path: String) {
        val attributes = sftp.lstat(path)
        if (attributes.type == FileMode.Type.DIRECTORY) {
            for (child in sftp.ls(path)) delete(child.path)
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    /**
     * Downloads [remotePath] into [sink]. The sink is not closed.
     *
     * @param onProgress called with the number of bytes transferred so far.
     * @param isCancelled polled between chunks; returning true aborts with [TransferCancelledException].
     */
    @Throws(IOException::class)
    fun download(
        remotePath: String,
        sink: OutputStream,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        sftp.open(remotePath).use { file ->
            file.ReadAheadRemoteFileInputStream(READ_AHEAD_REQUESTS).use { input ->
                copy(input, sink, onProgress, isCancelled)
            }
        }
    }

    /**
     * Uploads everything from [source] to [remotePath], replacing an existing file. The source is
     * not closed.
     */
    @Throws(IOException::class)
    fun upload(
        source: InputStream,
        remotePath: String,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.RemoteFileOutputStream(0, UNCONFIRMED_WRITES).use { output ->
                copy(source, output, onProgress, isCancelled)
            }
        }
    }

    /**
     * Reads a file as UTF-8 text.
     *
     * @throws IOException when the file is larger than [maxBytes].
     */
    @Throws(IOException::class)
    fun readText(remotePath: String, maxBytes: Int = 1 shl 20): String {
        val size = sftp.stat(remotePath).size
        if (size > maxBytes) throw IOException("File is too large to open as text ($size bytes)")
        val out = ByteArrayOutputStream()
        download(remotePath, out)
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    @Throws(IOException::class)
    fun writeText(remotePath: String, text: String) {
        upload(text.toByteArray(Charsets.UTF_8).inputStream(), remotePath)
    }

    private fun copy(from: InputStream, to: OutputStream, onProgress: (Long) -> Unit, isCancelled: () -> Boolean) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            val n = from.read(buffer)
            if (n < 0) break
            to.write(buffer, 0, n)
            total += n
            onProgress(total)
        }
        to.flush()
    }

    override fun close() {
        runCatching { sftp.close() }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024

        /** Requests kept in flight; this is what makes transfers fast on high latency links. */
        const val READ_AHEAD_REQUESTS = 16
        const val UNCONFIRMED_WRITES = 16
    }
}
