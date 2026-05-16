package com.selfbus.lpcflasher.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Logger
import com.selfbus.lpcflasher.data.LpcVariants
import com.selfbus.lpcflasher.data.Settings
import kotlinx.coroutines.delay

/**
 * Port of serial.js – USB Serial communication via usb-serial-for-android.
 *
 * Handles device detection, connection, ISP mode entry, synchronization,
 * chip identification, UID reading, and low-level line-based I/O.
 */
class UsbSerialManager(private val context: Context) {

    private data class IspLineProfile(
        val name: String
    )

    // ---- State ----
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var readBuffer = StringBuilder()

    var detectedChipInfo: LpcVariants.ChipInfo? = null
        private set
    var detectedUid: String? = null
        private set
    var isConnected: Boolean = false
        private set
    private var ispSynchronized: Boolean = false

    private var activeIspProfile = IspLineProfile("direct DTR/RTS")

    // Callback for UI updates
    var onConnectionChanged: ((connected: Boolean, chip: LpcVariants.ChipInfo?) -> Unit)? = null

    companion object {
        private const val MAX_READ_BUFFER_CHARS = 64 * 1024

        private val ISP_LINE_PROFILES = listOf(
            IspLineProfile("direct DTR/RTS")
        )

        /** Known USB serial adapter names (vid:pid → human-readable name) */
        val USB_SERIAL_DEVICE_NAMES = mapOf(
            "10C4:EA60" to "Silicon Labs CP210x USB-UART",
            "0403:6001" to "FTDI FT232 USB-UART",
            "1A86:7523" to "QinHeng CH340 USB-UART",
            "1A86:55D4" to "QinHeng CH9102 USB-UART",
            "067B:2303" to "Prolific PL2303 USB-UART"
        )

        fun getDeviceName(device: UsbDevice): String {
            val vid = device.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid = device.productId.toString(16).uppercase().padStart(4, '0')
            val key = "$vid:$pid"
            return USB_SERIAL_DEVICE_NAMES[key]
                ?: device.productName
                ?: "USB serial device ($key)"
        }
    }

    // ---- Device Discovery ----

    /**
     * List all connected USB serial devices.
     */
    fun listDevices(): List<Pair<UsbSerialDriver, String>> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.map { it to getDeviceName(it.device) }
    }

    /**
     * Check if USB permission is granted for the given device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }

    // ---- Connection ----

    /**
     * Connect to a USB serial device, enter ISP mode, synchronize, and identify the chip.
     * This is the equivalent of connectToDevice() in serial.js.
     */
    suspend fun connect(driver: UsbSerialDriver): Boolean {
        try {
            Logger.info(I18n.t("connecting"))

            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val usbConnection = manager.openDevice(driver.device)
                ?: throw Exception("Cannot open USB device – permission denied?")

            val port = driver.ports[0]
            port.open(usbConnection)
            port.setParameters(
                Settings.baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            this.connection = usbConnection
            this.serialPort = port
            this.readBuffer.clear()

            // Initialize to safe idle state: RESET inactive, ISP inactive
            setResetAndIsp(port, resetAsserted = false, ispAsserted = false)

            // ISP mode entry
            val syncOk = enterIspAndSynchronize()
            if (!syncOk) {
                throw Exception("Synchronization failed")
            }

            // Identify chip
            val chipInfo = identifyChip()
            detectedChipInfo = chipInfo
            isConnected = true

            if (chipInfo != null) {
                Logger.success("${I18n.t("connected")}: ${chipInfo.name}")
            } else {
                Logger.success(I18n.t("connected"))
            }

            // Read UID before notifying UI so that detectedUid is available
            detectedUid = readUID()

            onConnectionChanged?.invoke(true, chipInfo)

            return true

        } catch (e: Exception) {
            Logger.error("${I18n.t("connectionFailed")}: ${e.message}")
            disconnect()
            return false
        }
    }

    /**
     * Connect to a USB serial device without ISP mode entry.
     * Opens the USB port only — used for reset-only operations.
     */
    fun connectUsb(driver: UsbSerialDriver): Boolean {
        try {
            Logger.info(I18n.t("connectingUsb"))

            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val usbConnection = manager.openDevice(driver.device)
                ?: throw Exception("Cannot open USB device – permission denied?")

            val port = driver.ports[0]
            port.open(usbConnection)
            port.setParameters(
                Settings.baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            this.connection = usbConnection
            this.serialPort = port
            this.readBuffer.clear()
            isConnected = true

            // Initialize to safe idle state: RESET inactive, ISP inactive
            setResetAndIsp(port, resetAsserted = false, ispAsserted = false)

            Logger.debug("USB port opened (no ISP)")
            return true

        } catch (e: Exception) {
            Logger.error("${I18n.t("connectionFailed")}: ${e.message}")
            disconnect()
            return false
        }
    }

    /**
     * Disconnect and clean up.
     */
    fun disconnect() {
        try { serialPort?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        serialPort = null
        connection = null
        readBuffer.clear()
        isConnected = false
        ispSynchronized = false
        detectedChipInfo = null
        onConnectionChanged?.invoke(false, null)
        Logger.info(I18n.t("connectionClosed"))
    }

    /**
     * User-requested disconnect: perform reset first, then release USB port.
     */
    suspend fun disconnectWithReset() {
        try {
            if (serialPort != null) {
                performReset()
            }
        } catch (_: Exception) {
            // Ignore reset errors during disconnect sequence and always release port.
        } finally {
            disconnect()
        }
    }

    // ---- ISP Mode Entry ----

    /**
     * Enter ISP mode using DTR/RTS signal sequence.
     * DTR → RESET (active low), RTS → P0.1/ISP (active low).
     */
    private suspend fun enterISPMode(profile: IspLineProfile) {
        val port = serialPort ?: return
        val t1 = Settings.t1Timing
        val t2 = Settings.t2Timing
        // val postDelay = Settings.postResetDelay

        Logger.debug("ISP mode sequence (${profile.name})...")

        try {
            // 1) resetAsserted=true, ispAsserted=true
            setResetAndIsp(port, resetAsserted = true, ispAsserted = true)
            delay(t1.toLong())

            // 2) resetAsserted=false, ispAsserted=true
            setResetAndIsp(port, resetAsserted = false, ispAsserted = true)
            delay(t2.toLong())

            // 3) resetAsserted=false, ispAsserted=false
            // won't work due to intermediate reset pulses, so let ISP Enable asserted
            // setResetAndIsp(port, resetAsserted = false, ispAsserted = false)
            // delay(postDelay.toLong())

            Logger.debug("ISP mode sequence complete")
        } catch (e: Exception) {
            Logger.debug("Hardware signals error: ${e.message}")
        }
    }

    private fun setResetAndIsp(port: UsbSerialPort, resetAsserted: Boolean, ispAsserted: Boolean) {
        // Direct mapping requested:
        // resetAsserted = DTR, ispAsserted = RTS
        port.dtr = resetAsserted
        port.rts = ispAsserted
    }

    private suspend fun enterIspAndSynchronize(): Boolean {
        val port = serialPort ?: return false

        for (profile in ISP_LINE_PROFILES) {
            for (attempt in 1..2) {
                Logger.debug("ISP sync try $attempt with ${profile.name}")
                readBuffer.clear()
                enterISPMode(profile)
                if (synchronize()) {
                    activeIspProfile = profile
                    ispSynchronized = true
                    Logger.info("ISP synchronized with ${profile.name}")
                    return true
                }
                // Put lines in released state before next profile/attempt
                try { setResetAndIsp(port, resetAsserted = false, ispAsserted = false) } catch (_: Exception) {}
                delay(80)
            }
        }
        ispSynchronized = false
        return false
    }

    /** Ensure ISP session is active. Re-enters ISP mode if needed (e.g. after reset). */
    suspend fun ensureIspMode(): Boolean {
        if (!isConnected) return false
        if (ispSynchronized) return true
        Logger.info(I18n.t("reenteringIspMode"))
        return enterIspAndSynchronize()
    }

    /**
     * Perform a hardware reset (DTR pulse, RTS stays low → normal boot).
     */
    suspend fun performReset() {
        val port = serialPort ?: return

        try {
            setResetAndIsp(port, resetAsserted = true, ispAsserted = false)
            delay(Settings.resetDuration.toLong())

            setResetAndIsp(port, resetAsserted = false, ispAsserted = false)
            delay(Settings.postResetDelay.toLong())

            // After hardware reset, bootloader ISP session is no longer guaranteed.
            ispSynchronized = false

            Logger.success(I18n.t("resetComplete"))
        } catch (e: Exception) {
            Logger.error("${I18n.t("resetFailed")}: ${e.message}")
        }
    }

    // ---- Synchronization ----

    /**
     * NXP ISP synchronization: send '?', expect "Synchronized", exchange freq, disable echo.
     */
    private suspend fun synchronize(): Boolean {
        val oscillator = Settings.oscillator
        Logger.debug("Starting synchronization...")
        readBuffer.clear()

        for (attempt in 0 until 15) {
            sendRaw("?")
            delay(100)

            val response = readLine(300)
            if (response != null && "Synchronized" in response) {
                Logger.debug("Received \"Synchronized\"")
                sendLine("Synchronized")

                // Wait for OK
                var gotOK = false
                for (i in 0 until 5) {
                    val line = readLine(300)
                    Logger.debug("Sync response $i: \"$line\"")
                    if (line != null && "OK" in line) { gotOK = true; break }
                }
                if (!gotOK) continue

                // Send frequency
                Logger.debug("Sync OK, sending frequency")
                sendLine(oscillator.toString())

                gotOK = false
                for (i in 0 until 5) {
                    val line = readLine(300)
                    Logger.debug("Freq response $i: \"$line\"")
                    if (line != null && "OK" in line) { gotOK = true; break }
                }
                if (!gotOK) continue

                // Disable echo
                Logger.debug("Frequency OK")
                sendLine("A 0")
                for (i in 0 until 3) {
                    val line = readLine(300)
                    Logger.debug("Echo off response $i: \"$line\"")
                    if (line == "0") break
                }

                readBuffer.clear()
                Logger.debug("Synchronization successful")
                return true
            }
        }

        Logger.debug("Synchronization failed")
        return false
    }

    // ---- Chip Identification ----

    /**
     * ISP 'J' command → read Part ID → look up in LpcVariants.
     */
    suspend fun identifyChip(): LpcVariants.ChipInfo? {
        sendLine("J")
        val returnCode = readLine(1000)
        if (returnCode != "0") {
            Logger.debug("J command returned: $returnCode")
            return null
        }
        val partIdLine = readLine(500) ?: return null
        val partId = partIdLine.trim().toLongOrNull() ?: return null
        if (partId <= 0) return null

        Logger.debug("Part ID: $partId (0x${partId.toString(16).uppercase()})")
        return LpcVariants.detectChip(partId)
    }

    // ---- UID Reading ----

    /**
     * ISP 'N' command → 4 words → 16-byte UID hex string.
     */
    suspend fun readUID(): String? {
        sendLine("N")
        val returnCode = readLine(1000)
        if (returnCode != "0") {
            Logger.debug("N command returned: $returnCode")
            return null
        }

        val words = mutableListOf<Long>()
        for (i in 0 until 4) {
            val line = readLine(500) ?: return null
            val v = line.trim().toLongOrNull() ?: return null
            words.add(v and 0xFFFFFFFFL)
        }

        // 4 words → 16 bytes (little-endian)
        val bytes = mutableListOf<Int>()
        for (w in words) {
            bytes.add((w and 0xFF).toInt())
            bytes.add((w shr 8 and 0xFF).toInt())
            bytes.add((w shr 16 and 0xFF).toInt())
            bytes.add((w shr 24 and 0xFF).toInt())
        }
        val uidString = bytes.joinToString(":") {
            it.toString(16).uppercase().padStart(2, '0')
        }
        Logger.debug("UID: $uidString")
        return uidString
    }

    // ---- Low-Level I/O ----

    /**
     * Send raw bytes (no line terminator).
     */
    fun sendRaw(data: String) {
        val port = serialPort ?: return
        val bytes = data.toByteArray(Charsets.US_ASCII)
        port.write(bytes, 200)
    }

    /**
     * Send a line with CR+LF terminator.
     */
    fun sendLine(line: String) {
        sendRaw("$line\r\n")
        Logger.debug("TX: $line")
    }

    /**
     * Read a line from the serial port with timeout.
     * Buffers incoming data and returns the first complete line.
     */
    suspend fun readLine(timeoutMs: Int = 1000): String? {
        val startTime = System.currentTimeMillis()
        val buf = ByteArray(256)
        val pollDelayMs = Settings.readLineDelay.coerceIn(0, 20).toLong()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Try to read more data into the buffer
            try {
                val port = serialPort
                if (port != null) {
                    val bytesRead = port.read(buf, 50)
                    if (bytesRead > 0) {
                        readBuffer.append(String(buf, 0, bytesRead, Charsets.US_ASCII))
                        if (readBuffer.length > MAX_READ_BUFFER_CHARS) {
                            // Keep only the newest data to avoid unbounded memory growth
                            readBuffer.delete(0, readBuffer.length - MAX_READ_BUFFER_CHARS)
                            Logger.debug("RX buffer trimmed to prevent overflow")
                        }
                    }
                }
            } catch (_: Exception) {
                // timeout on read is normal
            }

            // Check for complete line
            val nlIndex = readBuffer.indexOf('\n')
            if (nlIndex != -1) {
                val line = readBuffer.substring(0, nlIndex).replace("\r", "").trim()
                readBuffer.delete(0, nlIndex + 1)
                if (line.isNotEmpty()) {
                    Logger.debug("RX: $line")
                    return line
                }
                continue
            }

            if (pollDelayMs > 0) delay(pollDelayMs)
        }

        // Final check
        val nlIndex = readBuffer.indexOf('\n')
        if (nlIndex != -1) {
            val line = readBuffer.substring(0, nlIndex).replace("\r", "").trim()
            readBuffer.delete(0, nlIndex + 1)
            if (line.isNotEmpty()) {
                Logger.debug("RX: $line")
                return line
            }
        }
        return null
    }

    /**
     * Clear the read buffer.
     */
    fun clearBuffer() {
        readBuffer.clear()
    }
}
