package com.selfbus.lpcflasher.serial.knx

import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal SLF4J 2.0 backend that forwards calimero (and any other) log output
 * to a global sink so it can be shown inside the app's log view.
 *
 * Registered via `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`
 * ([CalimeroSlf4jProvider]).
 */
object CalimeroLog {
    /** Sink receiving log lines: (level, loggerName, message). */
    @Volatile
    var sink: ((Level, String, String) -> Unit)? = null

    /**
     * When false only WARN/ERROR are forwarded; when true everything
     * (incl. DEBUG/TRACE/INFO) is forwarded.
     */
    @Volatile
    var debugEnabled: Boolean = false

    private val loggers = ConcurrentHashMap<String, Logger>()

    fun getLogger(name: String): Logger = loggers.getOrPut(name) { SinkLogger(name) }

    internal fun isEnabled(level: Level): Boolean =
        if (debugEnabled) true else (level == Level.WARN || level == Level.ERROR)

    internal fun emit(level: Level, name: String, message: String, t: Throwable?) {
        val s = sink ?: return
        val text = if (t != null) "$message: ${t.message}" else message
        try {
            s(level, name, text)
        } catch (_: Throwable) {
            // never let logging break the caller
        }
    }
}

/** SLF4J [Logger] implementation that routes into [CalimeroLog]. */
private class SinkLogger(loggerName: String) : AbstractLogger() {
    init {
        this.name = loggerName
    }

    override fun getFullyQualifiedCallerName(): String = FQCN

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?
    ) {
        if (!CalimeroLog.isEnabled(level)) return
        val msg = if (arguments != null && arguments.isNotEmpty()) {
            MessageFormatter.arrayFormat(messagePattern, arguments).message
        } else {
            messagePattern ?: ""
        }
        CalimeroLog.emit(level, name, msg, throwable)
    }

    private companion object {
        private val FQCN = SinkLogger::class.java.name
    }

    override fun isTraceEnabled(): Boolean = CalimeroLog.isEnabled(Level.TRACE)
    override fun isTraceEnabled(marker: Marker?): Boolean = isTraceEnabled
    override fun isDebugEnabled(): Boolean = CalimeroLog.isEnabled(Level.DEBUG)
    override fun isDebugEnabled(marker: Marker?): Boolean = isDebugEnabled
    override fun isInfoEnabled(): Boolean = CalimeroLog.isEnabled(Level.INFO)
    override fun isInfoEnabled(marker: Marker?): Boolean = isInfoEnabled
    override fun isWarnEnabled(): Boolean = CalimeroLog.isEnabled(Level.WARN)
    override fun isWarnEnabled(marker: Marker?): Boolean = isWarnEnabled
    override fun isErrorEnabled(): Boolean = CalimeroLog.isEnabled(Level.ERROR)
    override fun isErrorEnabled(marker: Marker?): Boolean = isErrorEnabled
}
