package com.selfbus.lpcflasher.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Port of utils.js Logger – collects log entries as state (no DOM).
 * UI observes the entries list via ViewModel/StateFlow.
 */
object Logger {

    private const val MAX_ENTRIES = 2000

    enum class LogType { INFO, SUCCESS, WARNING, ERROR, DEBUG }

    data class LogEntry(
        val timestamp: String,
        val message: String,
        val type: LogType
    )

    private val _entries = mutableListOf<LogEntry>()
    val entries: List<LogEntry> get() = synchronized(_entries) { _entries.toList() }

    var debugVisible = false
    var onChanged: (() -> Unit)? = null

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun log(message: String, type: LogType = LogType.INFO) {
        synchronized(_entries) {
            _entries.add(LogEntry(now(), message, type))
            if (_entries.size > MAX_ENTRIES) {
                val removeCount = _entries.size - MAX_ENTRIES
                repeat(removeCount) { _entries.removeAt(0) }
            }
        }
        android.util.Log.d("LPCFlasher", "[${type.name}] $message")
        onChanged?.invoke()
    }

    fun debug(message: String) = log(message, LogType.DEBUG)
    fun info(message: String) = log(message, LogType.INFO)
    fun success(message: String) = log(message, LogType.SUCCESS)
    fun warning(message: String) = log(message, LogType.WARNING)
    fun error(message: String) = log(message, LogType.ERROR)

    fun clear() {
        synchronized(_entries) { _entries.clear() }
        log(I18n.t("logCleared"), LogType.INFO)
    }

    fun getFullText(): String =
        synchronized(_entries) {
            _entries.joinToString("\n") { "[${it.timestamp}] [${it.type.name}] ${it.message}" }
        }

    fun getFileName(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        return "flasher_log_$ts.txt"
    }
}
