package com.selfbus.lpcflasher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which USB-serial modem control line drives a signal. */
enum class ControlLine { RTS, DTR }

/**
 * A named hardware wiring profile: which USB-serial port index carries the
 * data lines (TX/RX), and which port + modem control line drives RESET and
 * ISP (P0.1) respectively. All three can point at the same port (classic
 * single-adapter wiring) or at different ports (e.g. a second UART channel
 * of a multi-port FTDI chip used purely for control lines).
 *
 * Port indices are 0-based, matching [com.hoho.android.usbserial.driver.UsbSerialDriver.getPorts].
 */
@Serializable
data class HardwareConfig(
    val id: String,
    val name: String,
    val dataPortIndex: Int = 0,
    val resetPortIndex: Int = 0,
    val resetLine: ControlLine = ControlLine.DTR,
    val ispPortIndex: Int = 0,
    val ispLine: ControlLine = ControlLine.RTS,
    val builtIn: Boolean = false
)

/** Built-in hardware profiles plus persisted storage for user-defined ones. */
object HardwareConfigs {
    const val ORIGINAL_ID = "builtin-original"
    const val FT2232H_ID = "builtin-ft2232h"

    /** Upstream default: TX/RX + DTR(RESET)/RTS(ISP) all on the same port. */
    val ORIGINAL = HardwareConfig(
        id = ORIGINAL_ID,
        name = "Default (single port, DTR/RTS)",
        dataPortIndex = 0,
        resetPortIndex = 0, resetLine = ControlLine.DTR,
        ispPortIndex = 0, ispLine = ControlLine.RTS,
        builtIn = true
    )

    /** TX/RX on channel A, RESET/ISP on channel B (e.g. FT2232H). */
    val FT2232H = HardwareConfig(
        id = FT2232H_ID,
        name = "FT2232H (RESET/ISP on channel B)",
        dataPortIndex = 0,
        resetPortIndex = 1, resetLine = ControlLine.RTS,
        ispPortIndex = 1, ispLine = ControlLine.DTR,
        builtIn = true
    )

    val BUILT_IN = listOf(ORIGINAL, FT2232H)

    private val json = Json { ignoreUnknownKeys = true }

    fun customConfigs(): List<HardwareConfig> =
        try {
            val raw = Settings.customHardwareConfigsJson
            if (raw.isBlank()) emptyList() else json.decodeFromString(raw)
        } catch (e: Exception) {
            Logger.debug("Failed to parse custom hardware configs: ${e.message}")
            emptyList()
        }

    fun allConfigs(): List<HardwareConfig> = BUILT_IN + customConfigs()

    fun selected(): HardwareConfig =
        allConfigs().find { it.id == Settings.selectedHardwareConfigId } ?: ORIGINAL

    fun select(id: String) {
        Settings.selectedHardwareConfigId = id
    }

    /** Add a new custom config, or replace an existing one with the same id. */
    fun save(config: HardwareConfig) {
        val current = customConfigs().toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        Settings.customHardwareConfigsJson = json.encodeToString(current)
    }

    fun delete(id: String) {
        Settings.customHardwareConfigsJson = json.encodeToString(customConfigs().filterNot { it.id == id })
        if (Settings.selectedHardwareConfigId == id) select(ORIGINAL_ID)
    }
}
