package com.linktomac.data

import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import com.linktomac.net.FileEntry
import java.io.File

/**
 * Browses the phone's shared storage root (`Environment.getExternalStorageDirectory()`), the
 * same "Device Storage" the stock Files app shows. Every path crossing the wire is relative to
 * that root ("" is the root itself) — see FilesListRequestPayload in docs/PROTOCOL.md — so
 * `resolve` is also the one place a `..`-escape out of the root gets rejected.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (API 30+), a "special app access" permission only grantable
 * via Settings, not a runtime permission dialog — same manual-grant pattern as notification
 * listener/accessibility access elsewhere in this app. Below API 30, falls back to the legacy
 * READ/WRITE_EXTERNAL_STORAGE runtime permissions, which is all scoped storage required then.
 */
class FileRepository {
    private val root: File = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // caller already gated legacy runtime permissions before reaching here
        }

    fun list(path: String): List<FileEntry>? {
        val dir = resolve(path)?.takeIf { it.isDirectory } ?: return null
        val files = dir.listFiles() ?: return null
        return files
            .filter { !it.isHidden }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map {
                FileEntry(
                    name = it.name,
                    isDirectory = it.isDirectory,
                    sizeBytes = if (it.isDirectory) 0.0 else it.length().toDouble(),
                    modifiedAt = it.lastModified().toDouble()
                )
            }
    }

    /** Distinguishes "too large to transfer" from other read failures so the Mac side (and the
     *  user) can tell why a preview or download didn't come through, instead of a generic
     *  "couldn't read that file" either way — this came up specifically for video previews,
     *  which are far more likely than other file types to land on the size cap. */
    sealed class ReadFileResult {
        data class Success(val bytes: ByteArray, val mimeType: String) : ReadFileResult()
        data class TooLarge(val maxBytes: Long) : ReadFileResult()
        object Failed : ReadFileResult()
    }

    fun readFile(path: String): ReadFileResult {
        val file = resolve(path)?.takeIf { it.isFile } ?: return ReadFileResult.Failed
        if (file.length() > MAX_TRANSFER_BYTES) return ReadFileResult.TooLarge(MAX_TRANSFER_BYTES)
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return ReadFileResult.Failed
        }
        return ReadFileResult.Success(bytes, mimeTypeFor(file.name))
    }

    /** Writes into an existing directory only — this is a drop target, not a mkdir tool. */
    fun writeFile(directoryPath: String, name: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_TRANSFER_BYTES) return false
        val dir = resolve(directoryPath)?.takeIf { it.isDirectory } ?: return false
        val safeName = File(name).name // strips any path components from an untrusted name
        if (safeName.isBlank()) return false
        return try {
            File(dir, safeName).writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun createFolder(parentPath: String, name: String): Boolean {
        val dir = resolve(parentPath)?.takeIf { it.isDirectory } ?: return false
        val safeName = File(name).name
        if (safeName.isBlank()) return false
        val target = File(dir, safeName)
        if (target.exists()) return false
        return target.mkdir()
    }

    /** `path`'s root itself can't be renamed/deleted — resolve() already lets it through as a
     *  valid target, so that's guarded here explicitly rather than relying on the caller. */
    fun rename(path: String, newName: String): Boolean {
        val file = resolve(path)?.takeIf { it.exists() && it != root } ?: return false
        val safeName = File(newName).name
        if (safeName.isBlank()) return false
        val destination = File(file.parentFile ?: return false, safeName)
        if (destination.exists()) return false
        return file.renameTo(destination)
    }

    fun delete(path: String): Boolean {
        val file = resolve(path)?.takeIf { it.exists() && it != root } ?: return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun copy(sourcePath: String, destinationDirPath: String): Boolean {
        val source = resolve(sourcePath)?.takeIf { it.exists() } ?: return false
        val destinationDir = resolve(destinationDirPath)?.takeIf { it.isDirectory } ?: return false
        val destination = File(destinationDir, source.name)
        if (destination.exists() || destination.path.startsWith("${source.path}/")) return false
        return try {
            if (source.isDirectory) source.copyRecursively(destination) else { source.copyTo(destination); true }
        } catch (e: Exception) {
            false
        }
    }

    /** Same-volume rename rather than copy+delete — everything here lives under one shared
     *  storage root, so this is always atomic. */
    fun move(sourcePath: String, destinationDirPath: String): Boolean {
        val source = resolve(sourcePath)?.takeIf { it.exists() && it != root } ?: return false
        val destinationDir = resolve(destinationDirPath)?.takeIf { it.isDirectory } ?: return false
        val destination = File(destinationDir, source.name)
        if (destination.exists() || destination.path.startsWith("${source.path}/")) return false
        return source.renameTo(destination)
    }

    /** Resolves a wire path against the storage root, rejecting anything that escapes it
     *  (including via ".." segments) so a malformed/malicious request can't read or write
     *  outside shared storage. */
    private fun resolve(path: String): File? {
        val target = if (path.isBlank()) root else File(root, path)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = try {
            target.canonicalPath
        } catch (e: Exception) {
            return null
        }
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) return null
        return File(canonicalTarget)
    }

    private fun mimeTypeFor(fileName: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)?.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        /** File bytes are base64'd twice on the wire — once for the JSON `dataBase64` field,
         *  again when the encrypted envelope itself is serialized (see `SecureChannel.seal` /
         *  `MacConnection.send`) — roughly 1.78x total inflation, no chunking/resume. OkHttp's
         *  WebSocket send queue caps a single outgoing message at 16MB and silently drops (not
         *  errors) anything that would exceed it, so this needs real headroom under
         *  16MB / 1.78 ≈ 9MB, not just under Photos' unrelated 50MB full-resolution-fetch cap
         *  this used to (wrongly) match. Matches the Mac side's `max_transfer_mb` default.
         */
        const val MAX_TRANSFER_BYTES = 8L * 1024 * 1024
    }
}
