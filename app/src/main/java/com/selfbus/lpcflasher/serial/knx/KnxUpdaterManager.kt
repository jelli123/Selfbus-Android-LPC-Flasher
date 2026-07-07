package com.selfbus.lpcflasher.serial.knx

import tuwien.auto.calimero.IndividualAddress
import tuwien.auto.calimero.Priority
import tuwien.auto.calimero.DataUnitBuilder
import tuwien.auto.calimero.FrameEvent
import tuwien.auto.calimero.DetachEvent
import tuwien.auto.calimero.CloseEvent
import tuwien.auto.calimero.cemi.CEMILData
import tuwien.auto.calimero.link.KNXNetworkLinkIP
import tuwien.auto.calimero.link.KNXNetworkLink
import tuwien.auto.calimero.link.medium.TPSettings
import tuwien.auto.calimero.mgmt.Destination
import tuwien.auto.calimero.mgmt.TransportLayer
import tuwien.auto.calimero.mgmt.TransportLayerImpl
import tuwien.auto.calimero.mgmt.TransportListener
import java.net.InetSocketAddress
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
    private val log: (String) -> Unit
) {
    companion object {
        private const val APCI_USERMSG_WRITE = 0x2F8
        private const val APCI_USERMSG_RESPONSE = 0x2FE

        private const val DEFAULT_RESPONSE_TIMEOUT_MS = 3_000L
        private const val ERASE_RESPONSE_TIMEOUT_MS = 10_000L

        private const val BLOCK_SIZE = Mcu.UPD_PROGRAM_SIZE   // 1024 (V1)
        private const val MAX_PAYLOAD = Mcu.MAX_PAYLOAD       // 13

        /** Magic marker preceding the application-version pointer inside firmware. */
        private val APP_VER_PTR_MAGIC = byteArrayOf(
            '!'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'P'.code.toByte(),
            '!'.code.toByte(), '@'.code.toByte(), ':'.code.toByte()
        )
    }

    class KnxUpdaterException(message: String) : Exception(message)

    private var link: KNXNetworkLink? = null
    private var transport: TransportLayer? = null
    private var destination: Destination? = null

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

    val isConnected: Boolean get() = link?.isOpen == true

    // ---------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------

    fun connect(gatewayIp: String, port: Int, ownAddress: String, progAddress: String) {
        disconnect()
        log("KNX: Verbinde mit Gateway $gatewayIp:$port ...")
        val localEp = InetSocketAddress(0)
        val remoteEp = InetSocketAddress(gatewayIp, port)
        val settings = TPSettings(IndividualAddress(ownAddress))
        val newLink = try {
            KNXNetworkLinkIP.newTunnelingLink(localEp, remoteEp, false, settings)
        } catch (ex: Exception) {
            throw KnxUpdaterException("Tunneling-Verbindung fehlgeschlagen: ${ex.message}")
        }
        val tl = TransportLayerImpl(newLink)
        tl.addTransportListener(listener)
        val dst = tl.createDestination(IndividualAddress(progAddress), true)
        try {
            tl.connect(dst)
        } catch (ex: Exception) {
            tl.detach()
            newLink.close()
            throw KnxUpdaterException("Aufbau der Verbindung zu $progAddress fehlgeschlagen: ${ex.message}")
        }
        link = newLink
        transport = tl
        destination = dst
        log("KNX: Verbunden mit Programmiergerät $progAddress")
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

    /** Send a UPD telegram and wait for the matching response ASDU. */
    private fun sendUpd(command: UpdCommand, data: ByteArray, timeoutMs: Long): ByteArray {
        val tl = transport ?: throw KnxUpdaterException("Nicht verbunden")
        val dst = destination ?: throw KnxUpdaterException("Nicht verbunden")
        responses.clear()
        val asdu = ByteArray(1 + data.size)
        asdu[0] = command.byte
        System.arraycopy(data, 0, asdu, 1, data.size)
        val apdu = DataUnitBuilder.createAPDU(APCI_USERMSG_WRITE, *asdu)
        try {
            tl.sendData(dst, Priority.SYSTEM, apdu)
        } catch (ex: Exception) {
            throw KnxUpdaterException("Senden von ${command.name} fehlgeschlagen: ${ex.message}")
        }
        val response = responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw KnxUpdaterException("Keine Antwort auf ${command.name} (Timeout)")
        return response
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
            val apdu = DataUnitBuilder.createAPDU(0x0380, *byteArrayOf())
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
