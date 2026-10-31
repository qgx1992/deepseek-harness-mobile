package com.qgx1992.dshmobile.ui.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a workspace file to another app on the phone.
 *
 * The workspace lives on the harness computer, so there is no file on this device to point an
 * `Intent` at. The bytes arrive over the wire, land in a cache directory this app owns, and are
 * exposed through the app's single [FileProvider] under a read-only grant — the smallest surface
 * that makes "share" and "open with" work without asking for storage permissions.
 *
 * Everything here fails soft. A phone without a PDF viewer, or with sharing disabled by policy,
 * still gets a toast instead of a crash, because "the file opened" is not something this app can
 * promise — "the file was offered" is.
 */
object ShareTarget {

    /** Where cached copies live under `cacheDir`. Must match `res/xml/file_paths_share.xml`. */
    const val CACHE_SUBDIR = "share"

    /** The one authority this app declares, in the manifest and here, in that order. */
    fun authority(context: Context): String = context.packageName + ".files"

    /**
     * Best guess at the type, from the file's own name.
     *
     * `MimeTypeMap` knows the common ones; the two fallbacks matter more than they look. A `.md`
     * file is mapped to `text/plain` rather than `text/markdown`, because Android has no idea what
     * markdown is and would show an empty handler list.
     */
    fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "application/octet-stream"
        nativelyTyped[extension]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private val nativelyTyped = mapOf(
        "md" to "text/plain",
        "markdown" to "text/plain",
        "log" to "text/plain",
        "json" to "application/json",
        "yml" to "text/plain",
        "yaml" to "text/plain",
        "svg" to "image/svg+xml",
        "toml" to "text/plain",
        "ini" to "text/plain",
        "kts" to "text/plain",
    )

    /** Just the last path segment, with anything that could escape the cache directory removed. */
    fun safeName(path: String): String {
        val base = path.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.map { c ->
            when {
                c.isLetterOrDigit() -> c
                c in "._-+ @" -> c
                else -> '_'
            }
        }.joinToString("").trimStart('.').takeIf { it.isNotBlank() && it != "_" }
        return cleaned ?: "file"
    }

    /**
     * Writes [bytes] into `cacheDir/share/` and returns the provider [Uri] for them.
     *
     * A name collision overwrites: the cache is a hand-off directory, not a library, and the newest
     * bytes for a given name are the ones the reader just asked for.
     */
    fun cacheFile(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, safeName(name))
        file.writeBytes(bytes)
        return file
    }

    /** The `content://` uri for a cached copy, readable by the app it is handed to. */
    fun uriFor(context: Context, name: String, bytes: ByteArray): Uri =
        FileProvider.getUriForFile(context, authority(context), cacheFile(context, name, bytes))

    /** `ACTION_SEND` through the system chooser. False when nothing can take the file. */
    fun share(context: Context, name: String, bytes: ByteArray): Boolean = try {
        val uri = uriFor(context, name, bytes)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, safeName(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply { addFlags(newTaskFlag(context)) }
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        // Nothing installed to share to, a policy that blocks it, or a cache directory that could
        // not be written: all of them mean "the file was not offered", and the caller says so.
        false
    }

    /** `ACTION_VIEW`, i.e. "open with" without sending it anywhere. False when nothing claims it. */
    fun openWith(context: Context, name: String, bytes: ByteArray): Boolean = try {
        val uri = uriFor(context, name, bytes)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(view)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun newTaskFlag(context: Context): Int =
        if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
}
