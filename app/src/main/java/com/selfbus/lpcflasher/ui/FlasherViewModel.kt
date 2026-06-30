package com.selfbus.lpcflasher.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.selfbus.lpcflasher.data.*
import com.selfbus.lpcflasher.serial.FlashOperations
import com.selfbus.lpcflasher.serial.UsbSerialManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FlasherViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_FIRMWARE_TOTAL_BYTES = 2 * 1024 * 1024
        private const val MAX_FIRMWARE_ADDRESS_SPAN = 2 * 1024 * 1024
    }

    // ---- USB Serial ----
    val serial = UsbSerialManager(application)
    val flashOps = FlashOperations(serial)

    // ---- UI State ----
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _logEntries = MutableStateFlow<List<Logger.LogEntry>>(emptyList())
    val logEntries: StateFlow<List<Logger.LogEntry>> = _logEntries.asStateFlow()

    data class UiState(
        val isConnected: Boolean = false,
        val chipName: String? = null,
        val chipSpecs: String? = null,
        val uid: String? = null,
        val knxSerial: String? = null,
        val firmwareFileName: String? = null,
        val firmwareSize: Int = 0,
        val progress: Int = -1, // -1 = hidden
        val isBusy: Boolean = false,
        val availableDevices: List<Pair<UsbSerialDriver, String>> = emptyList(),
        val selectedDeviceIndex: Int = 0,

        // Firmware catalog state
        val categories: List<Pair<String, FirmwareCatalog.Category>> = emptyList(),
        val selectedCategory: String? = null,
        val devices: List<Pair<String, FirmwareCatalog.Device>> = emptyList(),
        val selectedDevice: String? = null,
        val firmwareVariants: List<FirmwareCatalog.FirmwareFile> = emptyList(),
        val selectedVariant: FirmwareCatalog.FirmwareFile? = null,
        val isLoadingVariants: Boolean = false,

        // Safety dialog
        val safetyCheckResults: FirmwareSafety.AllCheckResults? = null,
        val showSafetyDialog: Boolean = false,

        // Settings
        val debugVisible: Boolean = false,

        // Language
        val language: I18n.Lang = I18n.currentLanguage
    )

    // Loaded hex data
    var hexData: List<HexParser.HexBlock> = emptyList()
        private set

    init {
        Logger.onChanged = { _logEntries.value = Logger.entries }
        flashOps.onProgress = { percent -> _uiState.value = _uiState.value.copy(progress = percent) }
        serial.onConnectionChanged = { connected, chip ->
            val currentUid = if (connected) serial.detectedUid else _uiState.value.uid
            _uiState.value = _uiState.value.copy(
                isConnected = connected,
                chipName = chip?.name ?: _uiState.value.chipName,
                chipSpecs = chip?.let { "${it.flashSize / 1024} KB Flash, ${it.ramSize / 1024} KB RAM" } ?: _uiState.value.chipSpecs,
                uid = currentUid,
                knxSerial = currentUid?.let { MurmurHash3.knxSerialFromUid(it) } ?: _uiState.value.knxSerial
            )
        }
        refreshDeviceList()
        initCatalog()
    }

    // ---- USB Permission ----

    /** Callback set by Activity to request USB permission. */
    var onRequestUsbPermission: ((android.hardware.usb.UsbDevice) -> Unit)? = null

    /** Pending permission result, signalled by Activity after user grants/denies. */
    private var pendingPermissionResult: CompletableDeferred<Boolean>? = null

    /**
     * Called by Activity when USB permission result arrives.
     */
    fun onUsbPermissionResult(granted: Boolean) {
        pendingPermissionResult?.complete(granted)
        pendingPermissionResult = null
        if (granted) refreshDeviceList()
    }

    /**
     * Ensure USB permission for a device. Requests it if needed and suspends until result.
     * Returns true if permission is (now) granted.
     */
    private suspend fun ensureUsbPermission(device: android.hardware.usb.UsbDevice): Boolean {
        if (serial.hasPermission(device)) return true
        val callback = onRequestUsbPermission ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pendingPermissionResult = deferred
        withContext(Dispatchers.Main) { callback(device) }
        return deferred.await()
    }

    // ---- Device List ----

    fun refreshDeviceList() {
        _uiState.value = _uiState.value.copy(availableDevices = serial.listDevices())
    }

    fun onUsbDeviceAttached() {
        refreshDeviceList()
    }

    // ---- Device Selection ----

    /** Internal exception to signal that safety dialog is pending */
    private class SafetyDialogPendingException : Exception()
    private var _pendingAutoDisconnect = false

    fun selectDeviceIndex(index: Int) {
        _uiState.value = _uiState.value.copy(selectedDeviceIndex = index)
    }

    /**
     * Get the currently selected USB driver, or null if none available.
     */
    private fun getSelectedDriver(): UsbSerialDriver? {
        val devices = _uiState.value.availableDevices
        if (devices.isEmpty()) return null
        val idx = _uiState.value.selectedDeviceIndex.coerceIn(0, devices.size - 1)
        return devices[idx].first
    }

    /**
     * Auto-connect (ISP), run the action, then optionally reset + disconnect.
     * Reset before disconnect is only performed if Settings.autoResetAfterFlash is enabled.
     */
    private suspend fun withAutoConnection(action: suspend () -> Unit) {
        var driver = getSelectedDriver()
            ?: throw Exception(I18n.t("noDevicesFound"))

        if (!ensureUsbPermission(driver.device)) {
            throw Exception(I18n.t("usbPermissionRequired"))
        }

        // Re-fetch driver after potential permission grant (device list may have been refreshed)
        refreshDeviceList()
        driver = getSelectedDriver()
            ?: throw Exception(I18n.t("noDevicesFound"))

        val connected = serial.connect(driver)
        if (!connected) throw Exception(I18n.t("connectionFailed"))

        try {
            action()
        } catch (e: SafetyDialogPendingException) {
            // Safety dialog shown — do NOT disconnect; dialog callbacks handle cleanup
            throw e
        } finally {
            // Only disconnect if no safety dialog is pending
            if (!_pendingAutoDisconnect) {
                if (Settings.autoResetAfterFlash) {
                    try { serial.performReset() } catch (_: Exception) {}
                }
                serial.disconnect()
            }
        }
    }

    fun forceDisconnect() {
        serial.disconnect()
    }

    // ---- File Loading (SAF) ----

    fun loadFirmwareFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val fileName = uri.lastPathSegment ?: "firmware"
                val lowerName = fileName.lowercase()

                if (lowerName.endsWith(".hex")) {
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw Exception("Cannot open file")
                    hexData = HexParser.parseIntelHex(content)
                } else if (lowerName.endsWith(".bin")) {
                    // Binary files → single block at address 0
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: throw Exception("Cannot open file")
                    hexData = listOf(HexParser.HexBlock(0, bytes.map { it.toInt() and 0xFF }.toMutableList()))
                } else {
                    // Try Intel HEX by default
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw Exception("Cannot open file")
                    hexData = HexParser.parseIntelHex(content)
                }

                validateFirmwareLayout(hexData)

                var totalBytes = 0
                hexData.forEach { totalBytes += it.data.size }

                flashOps.currentFirmwareFileName = fileName

                _uiState.value = _uiState.value.copy(
                    firmwareFileName = fileName,
                    firmwareSize = totalBytes
                )

                Logger.success("${I18n.t("firmwareLoaded")}: $fileName ($totalBytes Bytes)")

            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            }
        }
    }

    // ---- Flash Operations ----

    fun flash() {
        if (_uiState.value.isBusy || hexData.isEmpty()) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withAutoConnection {
                    val results = flashOps.flashFirmware(hexData)
                    if (results.summary.hasIssues()) {
                        // Show dialog — the auto-disconnect happens in confirmSafetyAndFlash or cancelSafetyDialog
                        _pendingAutoDisconnect = true
                        _uiState.value = _uiState.value.copy(
                            safetyCheckResults = results,
                            showSafetyDialog = true
                        )
                        // Do NOT disconnect here; the dialog handler will do it
                        throw SafetyDialogPendingException()
                    }
                    // No issues → flash directly
                    flashOps.doFlash(hexData)
                    flashOps.verifyFlash(hexData, performBlankCheck = false)
                }
            } catch (_: SafetyDialogPendingException) {
                // Safety dialog is shown, don't reset busy — handled by dialog callbacks
                return@launch
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                if (!_pendingAutoDisconnect) {
                    _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
                }
            }
        }
    }

    fun confirmSafetyAndFlash() {
        _uiState.value = _uiState.value.copy(showSafetyDialog = false, safetyCheckResults = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Logger.warning(I18n.t("userConfirmedFlash"))
                flashOps.doFlash(hexData)
                flashOps.verifyFlash(hexData, performBlankCheck = false)
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                _pendingAutoDisconnect = false
                if (Settings.autoResetAfterFlash) {
                    try { serial.performReset() } catch (_: Exception) {}
                }
                serial.disconnect()
                _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
            }
        }
    }

    fun cancelSafetyDialog() {
        _uiState.value = _uiState.value.copy(showSafetyDialog = false, safetyCheckResults = null, isBusy = false)
        _pendingAutoDisconnect = false
        Logger.warning(I18n.t("flashCancelled"))
        viewModelScope.launch(Dispatchers.IO) {
            if (Settings.autoResetAfterFlash) {
                try { serial.performReset() } catch (_: Exception) {}
            }
            serial.disconnect()
        }
    }

    fun verify() {
        if (_uiState.value.isBusy || hexData.isEmpty()) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withAutoConnection { flashOps.verifyFlash(hexData) }
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
            }
        }
    }

    fun erase() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withAutoConnection { flashOps.eraseFlash() }
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
            }
        }
    }

    fun blankCheck() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withAutoConnection { flashOps.blankCheck() }
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
            }
        }
    }

    /** Read flash and return hex content (caller saves via SAF). */
    suspend fun readFlash(): String? {
        if (_uiState.value.isBusy) return null
        _uiState.value = _uiState.value.copy(isBusy = true)
        return try {
            withContext(Dispatchers.IO) {
                var hexResult: String? = null
                withAutoConnection {
                    val result = flashOps.readFlashToData()
                    if (result != null) {
                        val (allData, usefulEnd) = result
                        hexResult = HexParser.generateIntelHex(allData.take(usefulEnd))
                    }
                }
                hexResult
            }
        } catch (e: Exception) {
            Logger.error("${I18n.t("error")}: ${e.message}")
            null
        } finally {
            _uiState.value = _uiState.value.copy(isBusy = false, progress = -1)
        }
    }

    fun resetDevice() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var driver = getSelectedDriver()
                    ?: throw Exception(I18n.t("noDevicesFound"))
                if (!ensureUsbPermission(driver.device)) {
                    throw Exception(I18n.t("usbPermissionRequired"))
                }
                // Re-fetch driver after potential permission grant (device list may have been refreshed)
                refreshDeviceList()
                driver = getSelectedDriver()
                    ?: throw Exception(I18n.t("noDevicesFound"))
                // Connect USB only (no ISP sequence)
                val connected = serial.connectUsb(driver)
                if (!connected) throw Exception(I18n.t("connectionFailed"))
                try {
                    serial.performReset()
                } finally {
                    serial.disconnect()
                }
            } catch (e: Exception) {
                Logger.error("${I18n.t("resetFailed")}: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    // ---- Firmware Catalog ----

    private fun initCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            FirmwareCatalog.loadFirmwareMapping()
            _uiState.value = _uiState.value.copy(
                categories = FirmwareCatalog.getSortedCategories()
            )
            // Preload jsdelivr
            FirmwareCatalog.loadJsdelivrFileList()
        }
    }

    fun selectCategory(categoryKey: String?) {
        val lang = if (I18n.currentLanguage == I18n.Lang.DE) "de" else "en"
        _uiState.value = _uiState.value.copy(
            selectedCategory = categoryKey,
            devices = if (categoryKey != null) FirmwareCatalog.getDevicesForCategory(categoryKey, lang) else emptyList(),
            selectedDevice = null,
            firmwareVariants = emptyList(),
            selectedVariant = null
        )
    }

    fun selectDevice(deviceId: String?) {
        _uiState.value = _uiState.value.copy(
            selectedDevice = deviceId,
            firmwareVariants = emptyList(),
            selectedVariant = null,
            isLoadingVariants = deviceId != null
        )
        if (deviceId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val files = FirmwareCatalog.loadFirmwareFilesForDevice(deviceId)
                .sortedByDescending { it.name }
            _uiState.value = _uiState.value.copy(
                firmwareVariants = files,
                isLoadingVariants = false
            )
        }
    }

    fun selectVariant(variant: FirmwareCatalog.FirmwareFile?) {
        if (variant == null) {
            _uiState.value = _uiState.value.copy(selectedVariant = null)
            return
        }

        // Safety: selecting a new catalog firmware invalidates previously loaded firmware
        // until user explicitly confirms with "Load Firmware".
        hexData = emptyList()
        flashOps.currentFirmwareFileName = ""
        _uiState.value = _uiState.value.copy(
            selectedVariant = variant,
            firmwareFileName = "${variant.name} (${I18n.t("selectedNotLoaded")})",
            firmwareSize = 0
        )
        Logger.info("${I18n.t("firmwareSelected")}: ${variant.name}. ${I18n.t("pleaseLoadBeforeFlash")}")
    }

    fun loadSelectedCatalogFirmware() {
        val variant = _uiState.value.selectedVariant ?: return
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = FirmwareCatalog.downloadFirmwareFromGitHub(variant.path, variant.name)
                hexData = HexParser.parseIntelHex(content)
                validateFirmwareLayout(hexData)
                var totalBytes = 0
                hexData.forEach { totalBytes += it.data.size }
                flashOps.currentFirmwareFileName = variant.name
                _uiState.value = _uiState.value.copy(
                    firmwareFileName = "${variant.name} (GitHub)",
                    firmwareSize = totalBytes
                )
                Logger.success("${I18n.t("firmwareLoaded")}: ${variant.name} ($totalBytes Bytes)")
            } catch (e: Exception) {
                Logger.error("${I18n.t("error")}: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    // ---- Log ----

    fun clearLog() = Logger.clear()
    fun getLogText() = Logger.getFullText()
    fun getLogFileName() = Logger.getFileName()

    fun toggleDebug() {
        Logger.debugVisible = !Logger.debugVisible
        _uiState.value = _uiState.value.copy(debugVisible = Logger.debugVisible)
    }

    // ---- Language ----

    fun setLanguage(lang: I18n.Lang) {
        I18n.currentLanguage = lang
        Settings.language = if (lang == I18n.Lang.EN) "en" else "de"
        _uiState.value = _uiState.value.copy(language = lang)
        // Refresh catalog display names
        selectCategory(_uiState.value.selectedCategory)
    }

    private fun validateFirmwareLayout(blocks: List<HexParser.HexBlock>) {
        if (blocks.isEmpty()) throw Exception("Firmware contains no data")

        var totalBytes = 0L
        var minAddress = Int.MAX_VALUE
        var maxEndExclusive = Int.MIN_VALUE

        for (block in blocks) {
            if (block.data.isEmpty()) continue
            totalBytes += block.data.size.toLong()
            if (block.address < minAddress) minAddress = block.address
            val endExclusive = block.address + block.data.size
            if (endExclusive > maxEndExclusive) maxEndExclusive = endExclusive
        }

        if (maxEndExclusive <= minAddress) throw Exception("Firmware contains no data")

        val span = maxEndExclusive - minAddress
        if (totalBytes > MAX_FIRMWARE_TOTAL_BYTES) {
            throw Exception("Firmware too large (${totalBytes} Bytes)")
        }
        if (span > MAX_FIRMWARE_ADDRESS_SPAN) {
            throw Exception("Firmware address span too large (${span} Bytes)")
        }
    }
}
