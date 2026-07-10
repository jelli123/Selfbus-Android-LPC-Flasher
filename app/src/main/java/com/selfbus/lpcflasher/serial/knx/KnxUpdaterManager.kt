package com.selfbus.lpcflasher.serial.knx

import android.content.Context
import android.net.wifi.WifiManager
import tuwien.auto.calimero.CloseEvent
import tuwien.auto.calimero.IndividualAddress
import tuwien.auto.calimero.Priority
import tuwien.auto.calimero.DataUnitBuilder
import tuwien.auto.calimero.FrameEvent
import tuwien.auto.calimero.DetachEvent
import tuwien.auto.calimero.cemi.CEMILData
import tuwien.auto.calimero.knxnetip.Discoverer
import tuwien.auto.calimero.link.KNXNetworkLinkIP
import tuwien.auto.calimero.link.KNXNetworkLink
import tuwien.auto.calimero.link.medium.TPSettings
import tuwien.auto.calimero.mgmt.Destination
import tuwien.auto.calimero.mgmt.ManagementClientImpl
import tuwien.auto.calimero.mgmt.TransportLayer
import tuwien.auto.calimero.mgmt.TransportLayerImpl
import tuwien.auto.calimero.mgmt.TransportListener
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * KNX Bus-Updater for Selfbus devices over a KNXnet/IP (WLAN) gateway.
 *
 * This is a Kotlin port of the relevant parts of the Selfbus `firmware_updater`
 * (Darthyson/software-arm-lib), restricted to the **full flash** mode and built
 * on top of the calimero KNX stack.
 *
 * The UPD protocol is exchanged via the manufacturer USERMSG APCIs:
 *  - write   request : APCI 0x2F8
 *  - response        : APCI 0x2FE
 *
 * IMPORTANT / NOT YET FIELD-TESTED:
 *  - calimero running on Android (API 26+) is plausible but unverified.
 *  - Only full-flash mode is ported (no differential / decompression mode).
 *  - The boot descriptor framing is reconstructed from the protocol notes and
 *    should be verified against a real device before relying on it.
 *  - Flashing can brick a device on protocol errors — test carefully.
 */
class KnxUpdaterManager(
    private val context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        private const val APCI_USERMSG_WRITE = 0x2F8
        private const val APCI_USERMSG_RESPONSE = 0x2FE

        private const val DEFAULT_RESPONSE_TIMEOUT_MS = 3_000L
        private const val ERASE_RESPONSE_TIMEOUT_MS = 10_000L

        private const val BLOCK_SIZE = Mcu.UPD_PROGRAM_SIZE   // 1024 (V1)
        private const val MAX_PAYLOAD = Mcu.MAX_PAYLOAD       // 13

        /** Maximum retries for a single UPD command (mirrors the Java updater). */
        private const val MAX_UPD_RETRY = 3

        /** Master-reset erase code / channel used to restart a device into the bootloader. */
        private const val RESTART_ERASE_CODE = 7
        private const val RESTART_CHANNEL = 255

        /** Default seconds to wait for a device to restart into the bootloader. */
        private const val DEFAULT_RESTART_TIME_SECONDS = 6

        /** Fixed KNX address the Selfbus bootloader uses in programming mode. */
        const val BOOTLOADER_ADDRESS = "15.15.192"

        /** Magic marker preceding the application-version pointer inside firmware. */
        private val APP_VER_PTR_MAGIC = byteArrayOf(
            '!'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte(),
            '!'.code.toByte(), '@'.code.toByte(), ':'.code.toByte()
        )
    }

    class KnxUpdaterException(message: String) : Exception(message)

    /** A KNXnet/IP gateway found during discovery. */
    data class GatewayInfo(val name: String, val ip: String, val port: Int) {
        override fun toString(): String = "$name ($ip:$port)"
    }

    private var link: KNXNetworkLink? = null
    private var transport: TransportLayer? = null
    private var destination: Destination? = null
    private var ownAddress: String = "15.15.250"

    /** Queue of received UPD response ASDUs (asdu[0]=command, asdu[1..]=data). */
    private val responses = ArrayBlockingQueue<ByteArray>(8)

    private val listener = object : TransportListener {
        override fun dataConnected(e: FrameEvent) = onFrame(e)
        override fun dataIndividual(e: FrameEvent) = onFrame(e)
        override fun broadcast(e: FrameEvent) { /* ignore */ }
        override fun group(e: FrameEvent) { /* ignore */ }
        override fun disconnected(d: Destination) { log("KNX: Verbindung getrennt (${d.address})") }
        override fun detached(e: DetachEvent) { /* ignore */ }
        override fun linkClosed(e: CloseEvent) { log("KNX: Link geschlossen") }
    }

    private fun onFrame(e: FrameEvent) {
        val cemi = e.frame
        if (cemi !is CEMILData) return
        val apdu = cemi.payload
        if (apdu.size < 2) return
        val service = DataUnitBuilder.getAPDUService(apdu)
        if (service != APCI_USERMSG_RESPONSE) return
        val asdu = DataUnitBuilder.extractASDU(apdu)
        if (asdu.isEmpty()) return
        responses.offer(asdu)
    }

    /** True once a device destination is connected (ready for UPD). */
    val isConnected: Boolean get() = link?.isOpen == true && destination != null

    /** True once the KNXnet/IP link to the gateway is open. */
    val isLinkOpen: Boolean get() = link?.isOpen == true

    // ---------------------------------------------------------------------
    // Gateway discovery (KNXnet/IP multicast search 224.0.23.12)
    // ---------------------------------------------------------------------

    /**
     * Discover KNXnet/IP gateways on the local network via multicast search.
     * Returns the direct control endpoint (IP + port) of every gateway found.
     *
     * On Android a [WifiManager.MulticastLock] is required to receive the
     * multicast/unicast search responses over Wi-Fi.
     */
    fun discoverGateways(timeoutSeconds: Int = 4): List<GatewayInfo> {
        log("KNX: Suche nach Gateways (Multicast ${Discoverer.SEARCH_MULTICAST}) ...")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("selfbus-knx-discovery")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        val found = LinkedHashMap<String, GatewayInfo>()
        try {
            val discoverer = Discoverer(0, false)
            // NOTE: Discoverer.startSearch(timeout, wait) internally calls
            // NetworkInterface.networkInterfaces() (a Java 9 API only available on
            // Android 13+ / API 33). To stay compatible with our minSdk 26 we
            // enumerate interfaces the classic way and use the per-interface
            // startSearch overload, which avoids that call.
            val interfaces = usableMulticastInterfaces()
            if (interfaces.isEmpty()) {
                // Fall back to the default multicast interface (null).
                runCatching { discoverer.startSearch(null, timeoutSeconds, false) }
            } else {
                for (ni in interfaces) {
                    runCatching { discoverer.startSearch(ni, timeoutSeconds, false) }
                }
            }
            // Wait for the background receivers to collect responses.
            try {
                Thread.sleep(timeoutSeconds * 1000L + 500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { discoverer.stopSearch() }

            for (result in discoverer.getSearchResponses()) {
                val response = result.response()
                val ctrl = response.controlEndpoint
                var ip = ctrl.address?.hostAddress
                // Fall back to the sender endpoint when the HPAI carries a wildcard (NAT).
                if (ip == null || ip == "0.0.0.0") {
                    ip = result.remoteEndpoint()?.address?.hostAddress
                }
                if (ip == null) continue
                val port = if (ctrl.port in 1..0xFFFF) ctrl.port else Discoverer.SEARCH_PORT
                val name = runCatching { response.device.name }.getOrNull() ?: "KNX IP Gateway"
                found.putIfAbsent("$ip:$port", GatewayInfo(name, ip, port))
            }
        } catch (ex: Exception) {
            throw KnxUpdaterException("Gateway-Suche fehlgeschlagen: ${ex.message}")
        } finally {
            runCatching { lock?.release() }
        }
        log("KNX: ${found.size} Gateway(s) gefunden")
        return found.values.toList()
    }

    /** Network interfaces that are up, multicast-capable and carry an IPv4 address. */
    private fun usableMulticastInterfaces(): List<NetworkInterface> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
                runCatching {
                    ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                        ni.inetAddresses.toList().any { it is Inet4Address && !it.isAnyLocalAddress }
                }.getOrDefault(false)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------

    /** Open the KNXnet/IP tunneling link to the gateway (no device destination yet). */
    fun openLink(gatewayIp: String, port: Int, ownAddress: String) {
        disconnect()
        this.ownAddress = ownAddress
        log("KNX: Verbinde mit Gateway $gatewayIp:$port ...")
        val localEp = InetSocketAddress(0)
        val remoteEp = InetSocketAddress(gatewayIp, port)
        val settings = TPSettings(IndividualAddress(ownAddress))
        link = try {
            KNXNetworkLinkIP.newTunnelingLink(localEp, remoteEp, false, settings)
        } catch (ex: Exception) {
            throw KnxUpdaterException("Tunneling-Verbindung fehlgeschlagen: ${ex.message}")
        }
        log("KNX: Gateway verbunden")
    }

    /** Open a connection-oriented destination to the programming device for UPD. */
    fun openDevice(progAddress: String) {
        val currentLink = link ?: throw KnxUpdaterException("Kein Gateway verbunden")
        val tl = TransportLayerImpl(currentLink)
        tl.addTransportListener(listener)
        val dst = tl.createDestination(IndividualAddress(progAddress), true)
        try {
            tl.connect(dst)
        } catch (ex: Exception) {
            tl.detach()
            throw KnxUpdaterException("Aufbau der Verbindung zu $progAddress fehlgeschlagen: ${ex.message}")
        }
        transport = tl
        destination = dst
        log("KNX: Verbunden mit Programmiergerät $progAddress")
    }

    /** Convenience: open link and device destination in one step. */
    fun connect(gatewayIp: String, port: Int, ownAddress: String, progAddress: String) {
        openLink(gatewayIp, port, ownAddress)
        openDevice(progAddress)
    }

    // ---------------------------------------------------------------------
    // Device lookup (requires an open link, but no destination yet)
    // ---------------------------------------------------------------------

    /**
     * Find the individual addresses of all devices currently in programming mode
     * (programming button pressed). Uses a temporary management client that is
     * detached afterwards without closing the link.
     */
    fun findDevicesInProgrammingMode(): List<String> {
        val currentLink = link ?: throw KnxUpdaterException("Kein Gateway verbunden")
        log("KNX: Suche Gerät im Programmiermodus ...")
        val mc = ManagementClientImpl(currentLink)
        return try {
            mc.readAddress(false).map { it.toString() }
        } catch (ex: Exception) {
            emptyList()
        } finally {
            runCatching { mc.detach() }
        }
    }

    /**
     * Restart a device that is running its normal application into the bootloader
     * (programming mode). Requires the device's current individual address.
     *
     * This mirrors the Selfbus updater's `restartDeviceToBootloader` (KNX master
     * reset, erase code 7 / channel 255). After the restart the device answers on
     * the fixed bootloader address [BOOTLOADER_ADDRESS].
     */
    fun restartDeviceToBootloader(deviceAddress: String) {
        val currentLink = link ?: throw KnxUpdaterException("Kein Gateway verbunden")
        log("KNX: Starte Gerät $deviceAddress in den Bootloader ...")
        val mc = ManagementClientImpl(currentLink)
        val dst = mc.createDestination(IndividualAddress(deviceAddress), true)
        var waitSeconds = DEFAULT_RESTART_TIME_SECONDS
        try {
            val reported = mc.restart(dst, RESTART_ERASE_CODE, RESTART_CHANNEL)
            if (reported > 0) waitSeconds = reported
        } catch (ex: Exception) {
            // A master reset may not be acknowledged before the device restarts;
            // this is not necessarily an error. Continue and wait.
            log("KNX: Neustart-Rückmeldung unklar (${ex.message}) – warte trotzdem")
        } finally {
            runCatching { mc.detach() }
        }
        try {
            Thread.sleep(waitSeconds * 1000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        log("KNX: Gerät sollte nun im Bootloader-Modus sein ($BOOTLOADER_ADDRESS)")
    }

    fun disconnect() {
        try {
            destination?.let { transport?.disconnect(it) }
        } catch (_: Exception) {
        }
        try {
            transport?.removeTransportListener(listener)
            transport?.detach()
        } catch (_: Exception) {
        }
        try {
            link?.close()
        } catch (_: Exception) {
        }
        destination = null
        transport = null
        link = null
        responses.clear()
    }

    // ---------------------------------------------------------------------
    // Low level UPD telegram exchange
    // ---------------------------------------------------------------------

    /** Send a UPD telegram once and wait for the matching response ASDU. */
    private fun sendUpdOnce(command: UpdCommand, data: ByteArray, timeoutMs: Long): ByteArray {
        val tl = transport ?: throw KnxUpdaterException("Nicht verbunden")
        val dst = destination ?: throw KnxUpdaterException("Nicht verbunden")
        responses.clear()
        val asdu = ByteArray(1 + data.size)
        asdu[0] = command.byte
        System.arraycopy(data, 0, asdu, 1, data.size)
        val apdu = DataUnitBuilder.createAPDU(APCI_USERMSG_WRITE, *asdu)
        tl.sendData(dst, Priority.SYSTEM, apdu)
        return responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw KnxUpdaterException("Keine Antwort auf ${command.name} (Timeout)")
    }

    /**
     * Send a UPD telegram, retrying on transient failures (timeout / send error).
     * Mirrors the Java updater's `sendWithRetry` (MAX_UPD_RETRY attempts).
     */
    private fun sendUpd(command: UpdCommand, data: ByteArray, timeoutMs: Long): ByteArray {
        var lastError: Exception? = null
        for (attempt in 1..MAX_UPD_RETRY) {
            try {
                return sendUpdOnce(command, data, timeoutMs)
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < MAX_UPD_RETRY) {
                    log("KNX: ${command.name} Versuch $attempt fehlgeschlagen (${ex.message}) – wiederhole")
                }
            }
        }
        throw KnxUpdaterException("${command.name} fehlgeschlagen: ${lastError?.message}")
    }

    /**
     * Send a UPD telegram and verify the device returned IAP_SUCCESS.
     * The bootloader answers most write commands with SEND_LAST_ERROR carrying
     * the [UpdResult] in the first data byte.
     */
    private fun sendUpdChecked(command: UpdCommand, data: ByteArray, timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS) {
        val response = sendUpd(command, data, timeoutMs)
        // response[0] = answering command, response[1] = result code
        if (response.size < 2) {
            throw KnxUpdaterException("${command.name}: ungültige Antwort")
        }
        val result = UpdResult.fromCode(response[1].toInt())
        if (result != UpdResult.IAP_SUCCESS) {
            throw KnxUpdaterException("${command.name}: ${result.message} (0x%02X)".format(result.code))
        }
    }

    // ---------------------------------------------------------------------
    // Device operations
    // ---------------------------------------------------------------------

    /** Read the full 16-byte UID (12 bytes + 4 bytes in two requests). */
    fun readUid(): ByteArray {
        val first = sendUpd(UpdCommand.REQUEST_UID, ByteArray(0), DEFAULT_RESPONSE_TIMEOUT_MS)
        if (first.isEmpty() || UpdResult.fromCode(first.getOrElse(0) { 0 }.toInt()) == UpdResult.INVALID && first.size < 13) {
            // continue anyway, parse below
        }
        // first[0] = RESPONSE_UID command, first[1..12] = first 12 UID bytes
        val firstData = first.copyOfRange(1, first.size)
        if (firstData.size < Mcu.UID_LENGTH_USED) {
            throw KnxUpdaterException("UID-Antwort zu kurz (${firstData.size} Bytes)")
        }
        val uid = ByteArray(Mcu.UID_LENGTH_MAX)
        System.arraycopy(firstData, 0, uid, 0, Mcu.UID_LENGTH_USED)

        val second = sendUpd(UpdCommand.REQUEST_UID, byteArrayOf(Mcu.UID_LENGTH_USED.toByte()), DEFAULT_RESPONSE_TIMEOUT_MS)
        val secondData = second.copyOfRange(1, second.size)
        val remaining = Mcu.UID_LENGTH_MAX - Mcu.UID_LENGTH_USED
        if (secondData.size < remaining) {
            throw KnxUpdaterException("UID-Restantwort zu kurz (${secondData.size} Bytes)")
        }
        System.arraycopy(secondData, 0, uid, Mcu.UID_LENGTH_USED, remaining)
        log("KNX: UID = ${uid.joinToString(":") { "%02X".format(it) }}")
        return uid
    }

    /** Unlock the device using the first 12 bytes of the UID. */
    fun unlock(uid: ByteArray) {
        if (uid.size < Mcu.UID_LENGTH_USED) {
            throw KnxUpdaterException("UID zu kurz zum Entsperren")
        }
        sendUpdChecked(UpdCommand.UNLOCK_DEVICE, uid.copyOfRange(0, Mcu.UID_LENGTH_USED))
        log("KNX: Gerät entsperrt")
    }

    fun requestBootloaderIdentity(versionMajor: Int = 1, versionMinor: Int = 0): String {
        val response = sendUpd(
            UpdCommand.REQUEST_BL_IDENTITY,
            byteArrayOf(versionMajor.toByte(), versionMinor.toByte()),
            DEFAULT_RESPONSE_TIMEOUT_MS
        )
        val data = response.copyOfRange(1, response.size)
        return data.joinToString(" ") { "%02X".format(it) }
    }

    fun requestAppVersion(): String {
        val response = sendUpd(UpdCommand.APP_VERSION_REQUEST, ByteArray(0), DEFAULT_RESPONSE_TIMEOUT_MS)
        val data = response.copyOfRange(1, response.size)
        return String(data.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.US_ASCII)
    }

    /** Erase the complete flash. */
    fun eraseCompleteFlash() {
        log("KNX: Lösche kompletten Flash ...")
        sendUpdChecked(UpdCommand.ERASE_COMPLETE_FLASH, ByteArray(0), ERASE_RESPONSE_TIMEOUT_MS)
        log("KNX: Flash gelöscht")
    }

    /** Erase the address range [start, start+length-1]. */
    fun eraseAddressRange(start: Int, length: Int) {
        val end = start + length - 1
        log("KNX: Lösche Bereich 0x%X .. 0x%X ...".format(start, end))
        val data = ByteArray(8)
        UpdStreams.longToStream(data, 0, start.toLong())
        UpdStreams.longToStream(data, 4, end.toLong())
        sendUpdChecked(UpdCommand.ERASE_ADDRESS_RANGE, data, ERASE_RESPONSE_TIMEOUT_MS)
        log("KNX: Bereich gelöscht")
    }

    /** Restart the device into the flashed application. */
    fun restartDevice() {
        try {
            val tl = transport ?: return
            val dst = destination ?: return
            // A_Restart (basic) — APCI 0x0380, no ASDU
            val apdu = DataUnitBuilder.createAPDU(0x0380)
            tl.sendData(dst, Priority.SYSTEM, apdu)
            log("KNX: Neustart-Befehl gesendet")
        } catch (ex: Exception) {
            log("KNX: Neustart fehlgeschlagen: ${ex.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Full flash
    // ---------------------------------------------------------------------

    /**
     * Flash [firmware] starting at [startAddress] using full-flash mode.
     *
     * On any failure this throws and leaves the device **in the bootloader**
     * (it is intentionally NOT restarted). This is important: after a reset a
     * partially flashed device can no longer be programmed over the bus without
     * physically removing it, so the caller should retry immediately instead.
     *
     * @param eraseRange erase the affected address range before programming.
     * @param progress 0f..1f progress callback.
     */
    fun flashFirmware(
        firmware: ByteArray,
        startAddress: Int,
        eraseRange: Boolean,
        progress: (Float) -> Unit
    ) {
        if (firmware.isEmpty()) throw KnxUpdaterException("Firmware ist leer")

        if (eraseRange) {
            eraseAddressRange(startAddress, firmware.size)
        }

        var progAddress = startAddress
        var offset = 0
        val total = firmware.size
        log("KNX: Programmiere $total Bytes ab 0x%X ...".format(startAddress))

        while (offset < total) {
            val blockLen = minOf(BLOCK_SIZE, total - offset)
            val block = firmware.copyOfRange(offset, offset + blockLen)

            // 1) stream the block to the device RAM buffer in MAX_PAYLOAD chunks
            sendDataBlock(block)

            // 2) program the buffered block to flash
            val crc = UpdStreams.crc32Value(block)
            val progPars = ByteArray(10)
            UpdStreams.shortToStream(progPars, 0, blockLen)
            UpdStreams.longToStream(progPars, 2, progAddress.toLong())
            UpdStreams.longToStream(progPars, 6, crc)
            sendUpdChecked(UpdCommand.PROGRAM, progPars)

            progAddress += blockLen
            offset += blockLen
            progress(offset.toFloat() / total.toFloat())
        }
        log("KNX: Programmierung abgeschlossen")

        // 3) program the boot descriptor so the new application is started
        programBootDescriptor(firmware, startAddress)
    }

    /** Stream a block to the device's RAM buffer using SEND_DATA telegrams. */
    private fun sendDataBlock(block: ByteArray) {
        var pos = 0
        while (pos < block.size) {
            val len = minOf(MAX_PAYLOAD, block.size - pos)
            val chunk = block.copyOfRange(pos, pos + len)
            sendUpdChecked(UpdCommand.SEND_DATA, chunk)
            pos += len
        }
    }

    /**
     * Build and program the application boot descriptor.
     *
     * NOTE: The exact framing is reconstructed from the protocol notes and must
     * be verified against a real device. Layout used here:
     *  descriptor stream = startAddress(4 LE) endAddress(4 LE) crc32(4 LE) appVersionAddr(4 LE)
     *  UPDATE_BOOT_DESC payload = length(4 LE) crc32-of-descriptor(4 LE)
     */
    private fun programBootDescriptor(firmware: ByteArray, startAddress: Int) {
        val endAddress = startAddress + firmware.size - 1
        val appCrc = UpdStreams.crc32Value(firmware)
        val appVersionAddress = findAppVersionAddress(firmware, startAddress)

        val descriptor = ByteArray(16)
        UpdStreams.longToStream(descriptor, 0, startAddress.toLong())
        UpdStreams.longToStream(descriptor, 4, endAddress.toLong())
        UpdStreams.longToStream(descriptor, 8, appCrc)
        UpdStreams.longToStream(descriptor, 12, appVersionAddress.toLong())

        log("KNX: Schreibe Boot-Deskriptor ...")
        sendDataBlock(descriptor)

        val descCrc = UpdStreams.crc32Value(descriptor)
        val payload = ByteArray(8)
        UpdStreams.longToStream(payload, 0, descriptor.size.toLong())
        UpdStreams.longToStream(payload, 4, descCrc)
        sendUpdChecked(UpdCommand.UPDATE_BOOT_DESC, payload)
        log("KNX: Boot-Deskriptor geschrieben")
    }

    /** Locate the app-version pointer via the APP_VER_PTR_MAGIC marker. */
    private fun findAppVersionAddress(firmware: ByteArray, startAddress: Int): Int {
        val idx = indexOf(firmware, APP_VER_PTR_MAGIC)
        return if (idx >= 0) startAddress + idx + APP_VER_PTR_MAGIC.size else 0
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
