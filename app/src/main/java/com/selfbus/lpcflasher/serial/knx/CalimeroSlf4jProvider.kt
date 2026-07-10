package com.selfbus.lpcflasher.serial.knx

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * SLF4J 2.0 service provider that routes all log output (in particular the
 * calimero KNX stack) into [CalimeroLog], so it can be displayed in the app's
 * Bus-Updater log view.
 *
 * Discovered by SLF4J via
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`.
 */
class CalimeroSlf4jProvider : SLF4JServiceProvider {
    private val loggerFactory = ILoggerFactory { name -> CalimeroLog.getLogger(name) }
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = BasicMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory
    override fun getMarkerFactory(): IMarkerFactory = markerFactory
    override fun getMDCAdapter(): MDCAdapter = mdcAdapter
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() {}
}
