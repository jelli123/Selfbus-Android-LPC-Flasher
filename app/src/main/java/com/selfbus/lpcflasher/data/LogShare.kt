package com.selfbus.lpcflasher.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shares log text with other apps.
 *
 * Small logs are shared as plain text via `ACTION_SEND`. Large logs (e.g. with
 * debug output enabled) are compressed into a `.zip` file and shared as an
 * attachment instead, because passing very large strings through
 * `EXTRA_TEXT` can freeze receiving apps (e.g. chat clients).
 */
object LogShare {

    /** Above this many characters the log is zipped and shared as a file. */
    private const val TEXT_THRESHOLD = 100_000

    fun shareLog(
        context: Context,
        text: String,
        chooserTitle: String,
        baseName: String,
    ) {
        if (text.length <= TEXT_THRESHOLD || !shareAsZip(context, text, chooserTitle, baseName)) {
            shareAsText(context, text, chooserTitle)
        }
    }

    private fun shareAsText(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /** @return true if the zip was created and the share intent was launched. */
    private fun shareAsZip(
        context: Context,
        text: String,
        chooserTitle: String,
        baseName: String,
    ): Boolean {
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Remove previously shared archives so the cache doesn't grow.
            dir.listFiles()?.forEach { it.delete() }
            val zipFile = File(dir, "$baseName.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                zos.putNextEntry(ZipEntry("$baseName.txt"))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zipFile
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, chooserTitle))
            true
        } catch (_: Exception) {
            false
        }
    }
}
