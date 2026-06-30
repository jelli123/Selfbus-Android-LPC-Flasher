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

    data class UiState(
        val gatewayIp: String = "",
        val gatewayPort: String = "3671",
        val ownAddress: String = "15.15.250",
        val progAddress: String = "15.15.192",
        val eraseBeforeFlash: Boolean = true,

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

    private val manager = KnxUpdaterManager(::appendLog)

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
            appendLog("Bitte Gateway-IP eingeben")
            return
        }
        runBusy {
            val port = s.gatewayPort.toIntOrNull() ?: 3671
            manager.connect(s.gatewayIp.trim(), port, s.ownAddress.trim(), s.progAddress.trim())
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
