package com.selfbus.lpcflasher.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfbus.lpcflasher.data.HexParser
import com.selfbus.lpcflasher.data.MurmurHash3
import com.selfbus.lpcflasher.serial.knx.KnxUpdaterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for the KNX Bus-Updater feature (flashing Selfbus devices over a
 * KNXnet/IP / WLAN gateway).
 */
class BusUpdaterViewModel(application: Application) : AndroidViewModel(application) {

    /** How the device to be programmed is identified. */
    enum class DeviceSearchMode { PROG_BUTTON, SERIAL, MANUAL }

    data class UiState(
        val gatewayIp: String = "",
        val gatewayPort: String = "3671",
        val ownAddress: String = "15.15.250",
        val progAddress: String = "15.15.192",
        val eraseBeforeFlash: Boolean = true,

        // Gateway discovery
        val discoveredGateways: List<KnxUpdaterManager.GatewayInfo> = emptyList(),
        val selectedGatewayIndex: Int = -1,
        val isDiscovering: Boolean = false,

        // Device search
        val deviceSearchMode: DeviceSearchMode = DeviceSearchMode.PROG_BUTTON,
        val knxSerialInput: String = "",
        val foundDeviceAddress: String? = null,

        val firmwareFileName: String? = null,
        val firmwareSize: Int = 0,
        val firmwareStartAddress: Int = 0,

        val uid: String? = null,
        val knxSerial: String? = null,
        val bootloaderInfo: String? = null,
        val appVersion: String? = null,

        val isConnected: Boolean = false,
        val isBusy: Boolean = false,
        val progress: Int = -1 // -1 = hidden
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val manager = KnxUpdaterManager(application, ::appendLog)

    /** Flattened firmware image, plus its start address. */
    private var firmware: ByteArray = ByteArray(0)

    private fun appendLog(message: String) {
        val line = "[${timeFmt.format(Date())}] $message"
        _log.value = (_log.value + line).takeLast(500)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ---- Form field setters ----
    fun setGatewayIp(v: String) { _uiState.value = _uiState.value.copy(gatewayIp = v) }
    fun setGatewayPort(v: String) { _uiState.value = _uiState.value.copy(gatewayPort = v.filter { it.isDigit() }.take(5)) }
    fun setOwnAddress(v: String) { _uiState.value = _uiState.value.copy(ownAddress = v) }
    fun setProgAddress(v: String) { _uiState.value = _uiState.value.copy(progAddress = v) }
    fun setEraseBeforeFlash(v: Boolean) { _uiState.value = _uiState.value.copy(eraseBeforeFlash = v) }
    fun setDeviceSearchMode(mode: DeviceSearchMode) { _uiState.value = _uiState.value.copy(deviceSearchMode = mode) }
    fun setKnxSerialInput(v: String) { _uiState.value = _uiState.value.copy(knxSerialInput = v) }

    // ---- Firmware loading ----
    fun loadFirmwareFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val name = queryDisplayName(uri)
                val isHex = name?.lowercase()?.let { it.endsWith(".hex") || it.endsWith(".ihex") } ?: false

                val (start, bytes) = withContext(Dispatchers.IO) {
                    if (isHex) {
                        val content = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: throw IllegalStateException("Datei konnte nicht gelesen werden")
                        flatten(HexParser.parseIntelHex(content))
                    } else {
                        val raw = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes() }
                            ?: throw IllegalStateException("Datei konnte nicht gelesen werden")
                        0 to raw
                    }
                }
                firmware = bytes
                _uiState.value = _uiState.value.copy(
                    firmwareFileName = name ?: "firmware",
                    firmwareSize = bytes.size,
                    firmwareStartAddress = start
                )
                appendLog("Firmware geladen: ${name ?: "?"} (${bytes.size} Bytes ab 0x%X)".format(start))
            } catch (ex: Exception) {
                appendLog("Fehler beim Laden der Firmware: ${ex.message}")
            }
        }
    }

    /** Flatten Intel-HEX blocks into one contiguous image padded with 0xFF. */
    private fun flatten(blocks: List<HexParser.HexBlock>): Pair<Int, ByteArray> {
        if (blocks.isEmpty()) return 0 to ByteArray(0)
        val minAddr = blocks.minOf { it.address }
        val maxAddr = blocks.maxOf { it.address + it.data.size }
        val out = ByteArray(maxAddr - minAddr) { 0xFF.toByte() }
        for (b in blocks) {
            for (i in b.data.indices) {
                out[b.address - minAddr + i] = (b.data[i] and 0xFF).toByte()
            }
        }
        return minAddr to out
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Device operations ----

    private inline fun runBusy(crossinline block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (ex: KnxUpdaterManager.KnxUpdaterException) {
                appendLog("Fehler: ${ex.message}")
            } catch (ex: Exception) {
                appendLog("Fehler: ${ex.message}")
            } finally {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    isConnected = manager.isConnected,
                    progress = -1
                )
            }
        }
    }

    /** Connect to the gateway, read UID and unlock the device. */
    fun connectAndIdentify() {
        val s = _uiState.value
        if (s.gatewayIp.isBlank()) {
            appendLog("Bitte Gateway wählen oder IP eingeben")
            return
        }
        runBusy {
            try {
                val port = s.gatewayPort.toIntOrNull() ?: 3671
                manager.openLink(s.gatewayIp.trim(), port, s.ownAddress.trim())

                val address = resolveDeviceAddress(s)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(foundDeviceAddress = address, progAddress = address)
                }
                appendLog("KNX: Programmiergerät = $address")

                manager.openDevice(address)
                val uid = manager.readUid()
                val uidStr = uid.joinToString(":") { "%02X".format(it) }
                manager.unlock(uid)
                val bl = try { manager.requestBootloaderIdentity() } catch (_: Exception) { null }
                val appVer = try { manager.requestAppVersion() } catch (_: Exception) { null }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        uid = uidStr,
                        knxSerial = MurmurHash3.knxSerialFromUid(uidStr),
                        bootloaderInfo = bl,
                        appVersion = appVer
                    )
                }
            } catch (ex: Exception) {
                manager.disconnect()
                throw ex
            }
        }
    }

    /** Discover KNXnet/IP gateways and populate the selection list. */
    fun discoverGateways() {
        if (_uiState.value.isBusy || _uiState.value.isDiscovering) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDiscovering = true)
            try {
                val gateways = withContext(Dispatchers.IO) { manager.discoverGateways() }
                _uiState.value = _uiState.value.copy(discoveredGateways = gateways)
                if (gateways.isNotEmpty()) {
                    // Auto-select the first gateway if none is chosen yet.
                    if (_uiState.value.selectedGatewayIndex !in gateways.indices) {
                        selectGateway(0)
                    }
                } else {
                    appendLog("Keine Gateways gefunden – IP ggf. manuell eingeben")
                }
            } catch (ex: Exception) {
                appendLog("Fehler: ${ex.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isDiscovering = false)
            }
        }
    }

    /** Select a discovered gateway and copy its endpoint into the input fields. */
    fun selectGateway(index: Int) {
        val gw = _uiState.value.discoveredGateways.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(
            selectedGatewayIndex = index,
            gatewayIp = gw.ip,
            gatewayPort = gw.port.toString()
        )
    }

    /** Determine the device address from the selected search mode. */
    private fun resolveDeviceAddress(s: UiState): String {
        return when (s.deviceSearchMode) {
            DeviceSearchMode.PROG_BUTTON -> {
                val devices = manager.findDevicesInProgrammingMode()
                when {
                    devices.isEmpty() ->
                        throw KnxUpdaterManager.KnxUpdaterException(
                            "Kein Gerät im Programmiermodus gefunden (Programmierknopf drücken)"
                        )
                    devices.size > 1 ->
                        throw KnxUpdaterManager.KnxUpdaterException(
                            "Mehrere Geräte im Programmiermodus: ${devices.joinToString()} – nur eines aktivieren"
                        )
                    else -> devices.first()
                }
            }
            DeviceSearchMode.SERIAL -> {
                val serial = parseKnxSerial(s.knxSerialInput)
                    ?: throw KnxUpdaterManager.KnxUpdaterException(
                        "Ungültige KNX-Seriennummer (Format: 013A:XXXXXXXX)"
                    )
                manager.findDeviceBySerial(serial)
                    ?: throw KnxUpdaterManager.KnxUpdaterException(
                        "Kein Gerät mit dieser Seriennummer gefunden"
                    )
            }
            DeviceSearchMode.MANUAL -> {
                if (s.progAddress.isBlank())
                    throw KnxUpdaterManager.KnxUpdaterException("Bitte Geräteadresse eingeben")
                s.progAddress.trim()
            }
        }
    }

    /** Parse a KNX serial like "013A:1A2B3C4D" (or without colon) into 6 bytes. */
    private fun parseKnxSerial(input: String): ByteArray? {
        val hex = input.filter { it.isLetterOrDigit() }.filter { it.lowercaseChar() in "0123456789abcdef" }
        if (hex.length != 12) return null
        return try {
            ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    fun disconnect() {
        runBusy { manager.disconnect() }
    }

    fun eraseFlash() {
        runBusy { manager.eraseCompleteFlash() }
    }

    fun flashFirmware() {
        if (firmware.isEmpty()) {
            appendLog("Keine Firmware geladen")
            return
        }
        val s = _uiState.value
        runBusy {
            manager.flashFirmware(
                firmware = firmware,
                startAddress = s.firmwareStartAddress,
                eraseRange = s.eraseBeforeFlash
            ) { p ->
                _uiState.value = _uiState.value.copy(progress = (p * 100).toInt())
            }
            manager.restartDevice()
        }
    }

    fun restartDevice() {
        runBusy { manager.restartDevice() }
    }

    override fun onCleared() {
        super.onCleared()
        manager.disconnect()
    }
}
