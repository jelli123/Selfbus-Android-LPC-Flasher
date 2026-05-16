package com.selfbus.lpcflasher.serial

import com.selfbus.lpcflasher.data.*
import kotlinx.coroutines.delay

/**
 * Port of flash-operations.js – high-level flash/verify/erase/read operations.
 * Works through the UsbSerialManager for low-level I/O.
 */
class FlashOperations(private val serial: UsbSerialManager) {

    // ---- State ----
    var currentFirmwareFileName: String = ""
    var cachedBootDescriptorAddress: Int? = null
        private set

    // Progress callback: (percent: Int) → Unit
    var onProgress: ((Int) -> Unit)? = null

    companion object {
        const val DEFAULT_BOOT_DESCRIPTOR_ADDRESS = 0x2E00
        const val FLASH_PAGE_SIZE = 0x100
        val APP_VER_PTR_MAGIC = intArrayOf(0x21, 0x41, 0x56, 0x50, 0x21, 0x40, 0x3A)
        private var crc32Table: IntArray? = null
    }

    init {
        // Load cached address from settings if available
        val raw = Settings.bootDescOverride
        if (raw.isNotBlank()) {
            parseAddressInput(raw)?.let { cachedBootDescriptorAddress = it }
        }
    }

    // ---- Boot Descriptor ----

    fun saveCachedBootDescriptorAddress(address: Int) {
        cachedBootDescriptorAddress = address
    }

    private fun getEffectiveBootDescriptorAddress(): Int {
        if (Settings.useBootDescOverride) {
            parseAddressInput(Settings.bootDescOverride)?.let { return it }
        }
        return cachedBootDescriptorAddress ?: DEFAULT_BOOT_DESCRIPTOR_ADDRESS
    }

    private fun isBootloaderImage(fileName: String): Boolean =
        fileName.lowercase().contains("bootloader")

    private fun isFlashstartImage(fileName: String): Boolean =
        fileName.lowercase().contains("flashstart")

    fun deriveBootDescriptorAddressFromHex(
        hexData: List<HexParser.HexBlock>,
        pageSize: Int = FLASH_PAGE_SIZE
    ): Int? {
        if (hexData.isEmpty()) return null
        var maxUsed = -1
        for (block in hexData) {
            if (block.data.isEmpty()) continue
            val end = block.address + block.data.size - 1
            if (end > maxUsed) maxUsed = end
        }
        if (maxUsed < 0) return null
        val nextFree = maxUsed + 1
        return ((nextFree + pageSize - 1) / pageSize) * pageSize
    }

    // ---- CRC32 ----

    private fun ensureCrc32Table(): IntArray {
        crc32Table?.let { return it }
        val t = IntArray(256)
        for (i in 0 until 256) {
            var c = i
            for (j in 0 until 8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[i] = c
        }
        crc32Table = t
        return t
    }

    fun crc32Bytes(data: List<Int>): Long {
        val table = ensureCrc32Table()
        var crc = -1 // 0xFFFFFFFF
        for (b in data) {
            crc = table[(crc xor (b and 0xFF)) and 0xFF] xor (crc ushr 8)
        }
        return (crc xor -1).toLong() and 0xFFFFFFFFL
    }

    // ---- Build boot descriptor ----

    data class BootDescriptor(
        val descriptor: List<Int>,
        val appVersionAddress: Long,
        val crc: Long,
        val endAddress: Long
    )

    fun buildBootDescriptorBytes(flashData: List<Int>, baseAddress: Int): BootDescriptor {
        val markerPos = findSubArray(flashData, APP_VER_PTR_MAGIC.toList())
        val appVersionAddress = if (markerPos >= 0)
            (baseAddress + markerPos + APP_VER_PTR_MAGIC.size).toLong() and 0xFFFFFFFFL
        else 0xFFFFFFFFL

        val endAddress = (baseAddress + flashData.size - 1).toLong() and 0xFFFFFFFFL
        val crc = crc32Bytes(flashData)

        val descriptor = MutableList(16) { 0 }
        writeU32LE(descriptor, 0, baseAddress.toLong())
        writeU32LE(descriptor, 4, endAddress)
        writeU32LE(descriptor, 8, crc)
        writeU32LE(descriptor, 12, appVersionAddress)

        return BootDescriptor(descriptor, appVersionAddress, crc, endAddress)
    }

    // ---- Flat data / vector checksum ----

    data class FlatData(val data: List<Int>, val baseAddress: Int)

    fun createFlatData(hexData: List<HexParser.HexBlock>): FlatData {
        if (hexData.isEmpty()) return FlatData(emptyList(), 0)

        val baseAddress = hexData[0].address
        var maxAddress = baseAddress
        for (block in hexData) {
            val end = block.address + block.data.size
            if (end > maxAddress) maxAddress = end
        }

        val totalSize = maxAddress - baseAddress
        val flashData = MutableList(totalSize) { 0xFF }
        for (block in hexData) {
            val offset = block.address - baseAddress
            for (i in block.data.indices) {
                flashData[offset + i] = block.data[i]
            }
        }

        // Patch vector checksum at 0x1C if base is 0
        if (baseAddress == 0 && flashData.size >= 32) {
            var sum = 0L
            for (i in 0 until 7) {
                val idx = i * 4
                val v = (flashData[idx].toLong() and 0xFF) or
                        ((flashData[idx + 1].toLong() and 0xFF) shl 8) or
                        ((flashData[idx + 2].toLong() and 0xFF) shl 16) or
                        ((flashData[idx + 3].toLong() and 0xFF) shl 24)
                sum = (sum + v) and 0xFFFFFFFFL
            }
            val checksum = ((sum.inv()) + 1) and 0xFFFFFFFFL
            flashData[0x1C] = (checksum and 0xFF).toInt()
            flashData[0x1D] = ((checksum shr 8) and 0xFF).toInt()
            flashData[0x1E] = ((checksum shr 16) and 0xFF).toInt()
            flashData[0x1F] = ((checksum shr 24) and 0xFF).toInt()
        }

        return FlatData(flashData, baseAddress)
    }

    // ---- ISP command helper ----

    private suspend fun ensureIspSession() {
        if (!serial.ensureIspMode()) {
            throw Exception("Unable to enter ISP mode")
        }
    }

    private fun effectiveReadChunkSize(): Int = Settings.readChunkSize.coerceIn(128, 512)
    private fun effectiveWriteChunkSize(): Int = Settings.writeChunkSize.coerceIn(256, 512)
    private fun effectiveUuLineDelay(): Int = Settings.uuLineDelay.coerceIn(0, 50)

    private suspend fun sendISPCommand(command: String, timeout: Int = 2000): String? {
        serial.sendLine(command)
        delay(10)
        return serial.readLine(timeout)
    }

    private fun addressToSector(address: Int) = address / 4096

    private fun getFlashSize(): Int = serial.detectedChipInfo?.flashSize ?: 32768
    private fun getSectorCount(): Int = serial.detectedChipInfo?.sectorCount ?: 8

    // ---- Pad chunk for ISP ----

    data class PaddedChunk(val data: List<Int>, val size: Int)

    fun padChunkForIsp(data: List<Int>): PaddedChunk {
        val chunkData = data.toMutableList()
        while (chunkData.size % 4 != 0) chunkData.add(0xFF)
        val validSizes = intArrayOf(256, 512, 1024, 4096)
        var targetSize = 256
        for (vs in validSizes) {
            if (chunkData.size <= vs) { targetSize = vs; break }
        }
        while (chunkData.size < targetSize) chunkData.add(0xFF)
        return PaddedChunk(chunkData, targetSize)
    }

    // ---- Program a single chunk to flash ----

    private suspend fun programChunkToFlash(
        address: Int, chunkData: List<Int>, size: Int,
        ramStart: Long, uuLineDelay: Int, label: String = ""
    ) {
        val t = I18n::t

        var response = sendISPCommand("W $ramStart $size")
        if (response != "0") throw Exception("${t("writeRamFailed")} $label: $response")

        val uuLines = UuCodec.encode(chunkData)
        for (j in uuLines.indices) {
            serial.sendRaw(uuLines[j] + "\r\n")
            if (uuLineDelay > 0 && j % 5 == 4) delay(uuLineDelay.toLong())
        }

        var checksum = 0L
        for (byte in chunkData) checksum = (checksum + byte) and 0xFFFFFFFFL
        serial.sendRaw("$checksum\r\n")
        delay(20)
        response = serial.readLine(3000)

        if (response == "RESEND") {
            for (j in uuLines.indices) {
                serial.sendRaw(uuLines[j] + "\r\n")
                delay(2)
            }
            serial.sendRaw("$checksum\r\n")
            delay(20)
            response = serial.readLine(3000)
            if (response != "OK") throw Exception("RESEND failed $label: $response")
        }

        val sector = addressToSector(address)
        response = sendISPCommand("P $sector $sector")
        if (response != "0") throw Exception("${t("prepareFailed")} sector $sector: $response")

        response = sendISPCommand("C $address $ramStart $size", 3000)
        if (response != "0") throw Exception("${t("copyToFlashFailed")} 0x${address.toString(16)}: $response")
    }

    // ---- Write boot descriptor if needed ----

    private suspend fun writeBootDescriptorIfNeeded(
        flashData: List<Int>, baseAddress: Int, ramStart: Long, uuLineDelay: Int
    ) {
        if (!isFlashstartImage(currentFirmwareFileName)) return
        val t = I18n::t

        if (Settings.useBootDescOverride && parseAddressInput(Settings.bootDescOverride) == null) {
            throw Exception(t("bootDescriptorAddressInvalid"))
        }

        val descriptorAddress = getEffectiveBootDescriptorAddress()
        val bd = buildBootDescriptorBytes(flashData, baseAddress)

        if (bd.appVersionAddress == 0xFFFFFFFFL) {
            Logger.warning(t("bootDescriptorSkippedNoMagic"))
        }

        Logger.info("${t("bootDescriptorWriting")}: ${formatHex(descriptorAddress)} " +
                "(${formatHex(baseAddress)}-${formatHex(bd.endAddress.toInt())}, " +
                "crc32 0x${bd.crc.toString(16).uppercase().padStart(8, '0')})")

        val existing = readFlashData(descriptorAddress, 16)
        val isSame = existing.size >= 16 && bd.descriptor.indices.all { existing[it] == bd.descriptor[it] }
        if (isSame) {
            Logger.info("${t("bootDescriptorWritten")}: ${formatHex(descriptorAddress)} (already up to date)")
            return
        }

        val isBlank = existing.size >= 16 && existing.all { it == 0xFF }
        if (!isBlank) {
            throw Exception("${t("bootDescriptorWriteFailed")}: target page is not blank (${formatHex(descriptorAddress)})")
        }

        val padded = padChunkForIsp(bd.descriptor)
        programChunkToFlash(descriptorAddress, padded.data, padded.size, ramStart, uuLineDelay, "(boot descriptor)")
        Logger.success("${t("bootDescriptorWritten")}: ${formatHex(descriptorAddress)}")
    }

    // ========================================================
    // PUBLIC OPERATIONS
    // ========================================================

    /** Erase entire flash. */
    suspend fun eraseFlash() {
        ensureIspSession()
        val t = I18n::t
        Logger.info(t("erasingFlash"))
        onProgress?.invoke(0)

        val endSector = getSectorCount() - 1

        var response = sendISPCommand("U 23130")
        if (response != "0") throw Exception("${t("unlockFailed")}: $response")
        onProgress?.invoke(20)

        response = sendISPCommand("P 0 $endSector")
        if (response != "0") throw Exception("${t("prepareFailed")}: $response")
        onProgress?.invoke(40)

        response = sendISPCommand("E 0 $endSector", 20000)
        if (response != "0") throw Exception("${t("eraseFailed")}: $response")
        onProgress?.invoke(80)

        response = sendISPCommand("I 0 $endSector", 5000)
        onProgress?.invoke(100)

        if (response == "0") {
            Logger.success(t("flashErased"))
        } else {
            serial.readLine(300)
            serial.readLine(300)
            Logger.warning(t("flashErasedNotBlank"))
        }
    }

    /** Blank check. Returns true if flash is blank. */
    suspend fun blankCheck(): Boolean {
        ensureIspSession()
        val t = I18n::t
        Logger.info(t("checkingBlank"))
        onProgress?.invoke(0)

        val endSector = getSectorCount() - 1
        val response = sendISPCommand("I 0 $endSector", 5000)
        onProgress?.invoke(100)

        return when (response) {
            "0" -> { Logger.success(t("flashIsBlank")); true }
            "8" -> {
                val offset = serial.readLine(500)
                serial.readLine(500)
                Logger.info("${t("flashContainsData")} $offset)")
                false
            }
            else -> throw Exception("Blank check error: $response")
        }
    }

    /** Read flash data at a given address. */
    suspend fun readFlashData(address: Int, length: Int): List<Int> {
        var lastResponse: String? = null

        repeat(3) { attempt ->
            val response = sendISPCommand("R $address $length")
            lastResponse = response
            if (response == "0") {
                val expectedLines = (length + 44) / 45
                val uuLines = mutableListOf<String>()

                for (i in 0 until expectedLines + 5) {
                    val line = serial.readLine(500) ?: break
                    if (line.matches(Regex("^\\d+$"))) {
                        val num = line.toIntOrNull() ?: 0
                        if (num > 100) break
                    }
                    if (line.isNotEmpty()) {
                        val firstChar = line[0].code
                        if (firstChar in 32..96) uuLines.add(line)
                    }
                }

                serial.sendLine("OK")
                delay(10)

                val decoded = UuCodec.decode(uuLines)
                if (decoded.size >= length) {
                    return decoded.take(length)
                }
                Logger.debug("Read short data at 0x${address.toString(16)}: ${decoded.size}/$length")
            } else {
                Logger.debug("Read error at 0x${address.toString(16)} (try ${attempt + 1}): $response")
            }

            // Recovery path
            serial.clearBuffer()
            if (attempt == 1) {
                serial.ensureIspMode()
            }
            delay(20L * (attempt + 1))
        }

        throw Exception("Read failed at 0x${address.toString(16)}: $lastResponse")
    }

    /** Read entire flash to a byte list. Returns the data + useful length. */
    suspend fun readFlashToData(): Pair<List<Int>, Int>? {
        ensureIspSession()
        val t = I18n::t
        Logger.info(t("checkingBlank"))
        onProgress?.invoke(0)

        val endSector = getSectorCount() - 1
        val blankResponse = sendISPCommand("I 0 $endSector", 5000)
        if (blankResponse == "0") {
            Logger.info(t("flashEmpty"))
            onProgress?.invoke(100)
            return null
        }
        if (blankResponse == "8") {
            serial.readLine(300)
            serial.readLine(300)
        }

        Logger.info(t("readingFlash"))
        val flashSize = getFlashSize()
        val chunkSize = effectiveReadChunkSize()
        val allData = mutableListOf<Int>()

        var address = 0
        while (address < flashSize) {
            val bytesToRead = minOf(chunkSize, flashSize - address)
            val data = readFlashData(address, bytesToRead)
            allData.addAll(data)
            address += bytesToRead
            onProgress?.invoke(((address.toDouble() / flashSize) * 100).toInt())
        }
        onProgress?.invoke(100)

        var usefulEnd = allData.size
        while (usefulEnd > 0 && allData[usefulEnd - 1] == 0xFF) usefulEnd--

        if (usefulEnd == 0) {
            Logger.info(t("flashEmpty"))
            return null
        }
        return Pair(allData, usefulEnd)
    }

    /** Verify flash against hex data. */
    suspend fun verifyFlash(hexData: List<HexParser.HexBlock>, performBlankCheck: Boolean = true): Boolean {
        ensureIspSession()
        val t = I18n::t
        if (performBlankCheck) {
            Logger.info(t("checkingBlank"))
            onProgress?.invoke(0)

            val endSector = getSectorCount() - 1
            val blankResponse = sendISPCommand("I 0 $endSector", 5000)
            if (blankResponse == "0") {
                Logger.warning(t("flashEmptyPleaseFlash"))
                onProgress?.invoke(100)
                return false
            }
            if (blankResponse == "8") {
                serial.readLine(300); serial.readLine(300)
            } else if (blankResponse != "0") {
                throw Exception("Blank check error before verify: $blankResponse")
            }
        }

        Logger.info(t("verifyingFlash"))
        val (refData, baseAddress) = createFlatData(hexData)
        if (refData.isEmpty()) {
            Logger.warning(t("flashEmptyPleaseFlash"))
            return false
        }

        val chunkSize = effectiveReadChunkSize()
        val startTime = System.currentTimeMillis()
        var errors = 0
        var verified = 0

        var offset = 0
        while (offset < refData.size) {
            val size = minOf(chunkSize, refData.size - offset)
            val addr = baseAddress + offset
            val flashData = readFlashData(addr, size)

            for (i in 0 until size) {
                if (flashData[i] != refData[offset + i]) {
                    errors++
                    if (errors <= 5) {
                        Logger.error("Mismatch @ 0x${(addr + i).toString(16)}: " +
                                "exp 0x${refData[offset + i].toString(16).padStart(2, '0')}, " +
                                "got 0x${flashData[i].toString(16).padStart(2, '0')}")
                    }
                }
            }
            verified += size
            offset += chunkSize
            onProgress?.invoke(((verified.toDouble() / refData.size) * 100).toInt())
        }

        val elapsed = "%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)
        if (errors == 0) {
            Logger.success("${t("verificationOK")} ($verified bytes) - ${elapsed}s")
            return true
        } else {
            Logger.error("${t("verificationFailed")} $errors ${t("errors")}")
            return false
        }
    }

    /** Flash firmware. */
    fun flashFirmware(hexData: List<HexParser.HexBlock>): FirmwareSafety.AllCheckResults {
        val t = I18n::t
        Logger.info(t("performingSafetyChecks"))

        val checkResults = FirmwareSafety.performSafetyChecks(
            hexData, serial.detectedChipInfo, currentFirmwareFileName
        )

        for (info in checkResults.summary.info) Logger.info("  ℹ️ $info")
        for (warn in checkResults.summary.warnings) Logger.warning("  ⚠️ ${warn.message}")
        for (err in checkResults.summary.errors) Logger.error("  🛑 ${err.message}")

        // Return check results so the UI can show the safety dialog if needed.
        // The actual flashing is done by doFlash() after user confirms.
        return checkResults
    }

    /** Actually perform the flash after user confirmation. */
    suspend fun doFlash(hexData: List<HexParser.HexBlock>): Boolean {
        ensureIspSession()
        val t = I18n::t
        Logger.info(t("flashingFirmware"))
        onProgress?.invoke(0)

        val startTime = System.currentTimeMillis()
        val writeChunkSize = effectiveWriteChunkSize()
        val uuLineDelay = effectiveUuLineDelay()
        val ramStart = LpcVariants.RAM_START

        val (flashData, baseAddress) = createFlatData(hexData)
        if (flashData.isEmpty()) throw Exception("No data to flash")

        if (isBootloaderImage(currentFirmwareFileName)) {
            val derivedAddress = deriveBootDescriptorAddressFromHex(hexData)
            if (derivedAddress != null) {
                saveCachedBootDescriptorAddress(derivedAddress)
                Logger.info("${t("bootDescriptorAddressDerived")}: ${formatHex(derivedAddress)}")
            }
        }

        Logger.info(t("vectorChecksumOK"))

        val startSector = addressToSector(baseAddress)
        val endSector = addressToSector(baseAddress + flashData.size - 1)
        Logger.info("${t("sectorsToFlash")}: $startSector - $endSector")

        // Unlock
        var response = sendISPCommand("U 23130")
        if (response != "0") throw Exception("${t("unlockFailed")}: $response")

        // Prepare + Erase
        response = sendISPCommand("P $startSector $endSector")
        if (response != "0") throw Exception("${t("prepareFailed")}: $response")

        response = sendISPCommand("E $startSector $endSector", 20000)
        if (response != "0") throw Exception("${t("eraseFailed")}: $response")
        Logger.success("${t("sectorsDeleted")}: $startSector-$endSector")

        // Build chunks
        data class Chunk(val address: Int, val data: List<Int>, val size: Int)
        val chunks = mutableListOf<Chunk>()
        var offset = 0
        while (offset < flashData.size) {
            val chunkData = flashData.subList(offset, minOf(offset + writeChunkSize, flashData.size)).toMutableList()
            while (chunkData.size % 4 != 0) chunkData.add(0xFF)
            val validSizes = intArrayOf(256, 512, 1024, 4096)
            var targetSize = 256
            for (vs in validSizes) {
                if (chunkData.size <= vs) { targetSize = vs; break }
            }
            while (chunkData.size < targetSize) chunkData.add(0xFF)
            chunks.add(Chunk(baseAddress + offset, chunkData, targetSize))
            offset += writeChunkSize
        }

        Logger.info("${chunks.size} ${t("chunksToWrite")} ($writeChunkSize bytes)")

        // Write chunks
        for (i in chunks.indices) {
            val chunk = chunks[i]

            response = sendISPCommand("W $ramStart ${chunk.size}")
            if (response != "0") throw Exception("${t("writeRamFailed")} @ chunk $i: $response")

            val uuLines = UuCodec.encode(chunk.data)
            for (j in uuLines.indices) {
                serial.sendRaw(uuLines[j] + "\r\n")
                if (uuLineDelay > 0 && j % 5 == 4) delay(uuLineDelay.toLong())
            }

            var checksum = 0L
            for (byte in chunk.data) checksum = (checksum + byte) and 0xFFFFFFFFL
            serial.sendRaw("$checksum\r\n")
            delay(20)
            response = serial.readLine(3000)

            if (response == "RESEND") {
                Logger.debug("RESEND for chunk ${i + 1}, retrying...")
                for (j in uuLines.indices) {
                    serial.sendRaw(uuLines[j] + "\r\n")
                    delay(2)
                }
                serial.sendRaw("$checksum\r\n")
                delay(20)
                response = serial.readLine(3000)
                if (response != "OK") throw Exception("RESEND failed at chunk ${i + 1}: $response")
            }

            val sector = addressToSector(chunk.address)
            response = sendISPCommand("P $sector $sector")
            if (response != "0") throw Exception("${t("prepareFailed")} sector $sector: $response")

            response = sendISPCommand("C ${chunk.address} $ramStart ${chunk.size}", 3000)
            if (response != "0") throw Exception("${t("copyToFlashFailed")} 0x${chunk.address.toString(16)}: $response")

            onProgress?.invoke(((i + 1).toDouble() / chunks.size * 100).toInt())
        }

        // Boot descriptor
        writeBootDescriptorIfNeeded(flashData, baseAddress, ramStart, uuLineDelay)

        val elapsed = "%.1f".format((System.currentTimeMillis() - startTime) / 1000.0)
        val speed = "%.1f".format(flashData.size / 1024.0 / ((System.currentTimeMillis() - startTime) / 1000.0))

        onProgress?.invoke(100)
        Logger.success("${t("flashComplete")} - ${elapsed}s ($speed KB/s)")
        return true
    }

    // ---- Utility ----

    private fun formatHex(value: Int) = "0x${value.toString(16).uppercase()}"

    private fun parseAddressInput(input: String): Int? {
        val str = input.trim().replace("\\s+".toRegex(), "")
        if (str.isEmpty()) return null
        val value = when {
            str.startsWith("0x", ignoreCase = true) -> str.substring(2).toLongOrNull(16)
            str.all { it.isDigit() } -> str.toLongOrNull(10)
            str.all { it in "0123456789abcdefABCDEF" } -> str.toLongOrNull(16)
            else -> null
        } ?: return null
        if (value < 0 || value > 0xFFFFFFFFL) return null
        return value.toInt()
    }

    private fun findSubArray(haystack: List<Int>, needle: List<Int>): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..haystack.size - needle.size) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
    }

    private fun writeU32LE(buffer: MutableList<Int>, offset: Int, value: Long) {
        val v = value.toInt()
        buffer[offset] = v and 0xFF
        buffer[offset + 1] = (v ushr 8) and 0xFF
        buffer[offset + 2] = (v ushr 16) and 0xFF
        buffer[offset + 3] = (v ushr 24) and 0xFF
    }
}
