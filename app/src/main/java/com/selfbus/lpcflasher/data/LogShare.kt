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
 * Depending on [zipThresholdLines] the log is shared either as plain text via
 * `ACTION_SEND` or, for large logs, compressed into a `.zip` file and shared as
 * an attachment — passing very large strings through `EXTRA_TEXT` can freeze
 * receiving apps (e.g. chat clients).
 */
object LogShare {

    /** Default line threshold above which a log is zipped when none is configured. */
    const val DEFAULT_ZIP_THRESHOLD_LINES = 500

    /**
     * @param zipThresholdLines number of log lines from which to zip:
     *   `0` = never zip (always text), `1` = always zip, `N` = zip if the text
     *   has at least `N` lines.
     */
    fun shareLog(
        context: Context,
        text: String,
        chooserTitle: String,
        baseName: String,
        zipThresholdLines: Int = DEFAULT_ZIP_THRESHOLD_LINES,
    ) {
        val zip = when {
            zipThresholdLines <= 0 -> false
            else -> lineCount(text) >= zipThresholdLines
        }
        if (!zip || !shareAsZip(context, text, chooserTitle, baseName)) {
            shareAsText(context, text, chooserTitle)
        }
    }

    private fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        return text.count { it == '\n' } + 1
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
