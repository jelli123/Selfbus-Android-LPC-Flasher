package com.selfbus.lpcflasher.data

/**
 * Port of firmware-safety.js – Pre-flash safety checks.
 * No UI/DOM dependency – returns pure data for the ViewModel/Compose to display.
 */
object FirmwareSafety {

    // CRP level constants
    const val CRP_NO_CRP: Long = 0xFFFFFFFFL
    const val CRP1: Long = 0x12345678L
    const val CRP2: Long = 0x87654321L
    const val CRP3: Long = 0x43218765L
    const val NO_ISP: Long = 0x4E697370L
    const val CRP_ADDRESS = 0x000002FC

    // ---- Result model ----

    data class Issue(val message: String, val details: Map<String, String>? = null)

    data class SafetyCheckResult(
        var passed: Boolean = true,
        val warnings: MutableList<Issue> = mutableListOf(),
        val errors: MutableList<Issue> = mutableListOf(),
        val info: MutableList<String> = mutableListOf()
    ) {
        fun addWarning(message: String, details: Map<String, String>? = null) {
            warnings.add(Issue(message, details))
        }
        fun addError(message: String, details: Map<String, String>? = null) {
            passed = false
            errors.add(Issue(message, details))
        }
        fun addInfo(message: String) { info.add(message) }
        fun hasIssues() = warnings.isNotEmpty() || errors.isNotEmpty()
    }

    data class AllCheckResults(
        val summary: SafetyCheckResult,
        val flashSize: SafetyCheckResult,
        val crp: SafetyCheckResult,
        val ispPin: SafetyCheckResult,
        val bootloader: SafetyCheckResult
    )

    // ---- Checks ----

    fun checkFlashSize(hexData: List<HexParser.HexBlock>, detectedChip: LpcVariants.ChipInfo?): SafetyCheckResult {
        val r = SafetyCheckResult()
        val t = I18n::t

        var firmwareEndAddress = 0
        var totalBytes = 0
        for (block in hexData) {
            val blockEnd = block.address + block.data.size
            if (blockEnd > firmwareEndAddress) firmwareEndAddress = blockEnd
            totalBytes += block.data.size
        }

        r.addInfo("${t("firmwareSize")}: $totalBytes bytes")
        r.addInfo("${t("firmwareEndAddress")}: 0x${firmwareEndAddress.toString(16).uppercase()}")

        if (detectedChip == null || detectedChip.flashSize == 0) {
            r.addWarning(t("noChipDetected"), mapOf("recommendation" to t("connectFirst")))
            return r
        }

        val maxFlash = detectedChip.flashSize
        r.addInfo("${t("chipFlashSize")}: $maxFlash bytes (${maxFlash / 1024} KB)")

        if (firmwareEndAddress > maxFlash) {
            r.addError(t("firmwareTooLarge"), mapOf(
                "firmwareEnd" to "0x${firmwareEndAddress.toString(16).uppercase()}",
                "maxFlash" to "0x${maxFlash.toString(16).uppercase()} (${maxFlash / 1024} KB)",
                "overflow" to "${firmwareEndAddress - maxFlash} bytes"
            ))
        } else {
            val usage = "%.1f".format(firmwareEndAddress.toDouble() / maxFlash * 100)
            r.addInfo("${t("flashUsage")}: $usage%")
        }
        return r
    }

    fun checkCRP(hexData: List<HexParser.HexBlock>): SafetyCheckResult {
        val r = SafetyCheckResult()
        val t = I18n::t

        val crpValue = getValueAtAddress(hexData, CRP_ADDRESS, 4)
        if (crpValue == null) {
            r.addInfo(t("noCrpFound"))
            return r
        }
        val crpHex = "0x${crpValue.toString(16).uppercase().padStart(8, '0')}"

        when (crpValue) {
            CRP_NO_CRP -> r.addInfo("CRP: ${t("noProtection")} ($crpHex)")
            CRP1 -> r.addWarning(t("crp1Detected"), mapOf("value" to crpHex, "effect" to t("crp1Effect")))
            CRP2 -> r.addWarning(t("crp2Detected"), mapOf("value" to crpHex, "effect" to t("crp2Effect"), "recommendation" to t("crp2Recommendation")))
            CRP3 -> r.addError(t("crp3Detected"), mapOf("value" to crpHex, "effect" to t("crp3Effect"), "recommendation" to t("crp3Recommendation")))
            NO_ISP -> r.addError(t("noIspDetected"), mapOf("value" to crpHex, "effect" to t("noIspEffect"), "recommendation" to t("noIspRecommendation")))
            else -> r.addInfo("CRP: ${t("noProtection")} (no CRP pattern)")
        }
        return r
    }

    fun checkISPPin(hexData: List<HexParser.HexBlock>): SafetyCheckResult {
        val r = SafetyCheckResult()
        val t = I18n::t
        val ioconPattern = intArrayOf(0x0C, 0x40, 0x04, 0x40)
        var found = false

        for (block in hexData) {
            if (found) break
            for (i in 0..block.data.size - 4) {
                if (block.data[i] == ioconPattern[0] &&
                    block.data[i + 1] == ioconPattern[1] &&
                    block.data[i + 2] == ioconPattern[2] &&
                    block.data[i + 3] == ioconPattern[3]
                ) {
                    r.addWarning(t("ispPinConfigFound"), mapOf(
                        "address" to "0x${(block.address + i).toString(16).uppercase()}",
                        "effect" to t("ispPinEffect"),
                        "recommendation" to t("ispPinRecommendation")
                    ))
                    found = true
                    break
                }
            }
        }
        if (!found) r.addInfo(t("ispPinOk"))
        return r
    }

    fun checkBootloaderCompatibility(hexData: List<HexParser.HexBlock>, firmwareFileName: String = ""): SafetyCheckResult {
        val r = SafetyCheckResult()
        val t = I18n::t
        val hasCodeAt0 = hasDataAtAddress(hexData, 0x00000000)
        val hasCodeAt3000 = hasDataAtAddress(hexData, 0x00003000)
        val isBootloader = firmwareFileName.lowercase().let {
            "bootloader" in it || "boot_" in it || "_boot" in it
        }

        if (hasCodeAt0) {
            if (isBootloader) {
                r.addInfo(t("bootloaderDetected"))
            } else {
                r.addInfo(t("standaloneDetected"))
                r.addWarning(t("bootloaderOverwrite"), mapOf("effect" to t("bootloaderOverwriteEffect")))
            }
        }
        if (hasCodeAt3000 && !hasCodeAt0) {
            r.addInfo(t("updaterFirmwareDetected"))
            r.addWarning(t("requiresBootloader"), mapOf(
                "effect" to t("requiresBootloaderEffect"),
                "recommendation" to t("requiresBootloaderRecommendation")
            ))
        }
        return r
    }

    // ---- Main entry point ----

    fun performSafetyChecks(
        hexData: List<HexParser.HexBlock>,
        detectedChip: LpcVariants.ChipInfo?,
        firmwareFileName: String = ""
    ): AllCheckResults {
        val flashSizeResult = checkFlashSize(hexData, detectedChip)
        val crpResult = checkCRP(hexData)
        val ispPinResult = checkISPPin(hexData)
        val bootloaderResult = checkBootloaderCompatibility(hexData, firmwareFileName)

        val summary = SafetyCheckResult()
        for (res in listOf(flashSizeResult, crpResult, ispPinResult, bootloaderResult)) {
            summary.info.addAll(res.info)
            summary.warnings.addAll(res.warnings)
            summary.errors.addAll(res.errors)
            if (!res.passed) summary.passed = false
        }

        return AllCheckResults(summary, flashSizeResult, crpResult, ispPinResult, bootloaderResult)
    }

    // ---- Helpers ----

    fun getValueAtAddress(hexData: List<HexParser.HexBlock>, address: Int, byteCount: Int): Long? {
        for (block in hexData) {
            val blockEnd = block.address + block.data.size
            if (address >= block.address && address + byteCount <= blockEnd) {
                val offset = address - block.address
                var value = 0L
                for (i in 0 until byteCount) {
                    value = value or ((block.data[offset + i].toLong() and 0xFF) shl (i * 8))
                }
                return value and 0xFFFFFFFFL
            }
        }
        return null
    }

    fun hasDataAtAddress(hexData: List<HexParser.HexBlock>, address: Int): Boolean {
        return hexData.any { address >= it.address && address < it.address + it.data.size }
    }

    fun verifyVectorChecksum(hexData: List<HexParser.HexBlock>, baseAddress: Int): Boolean {
        var sum = 0L
        for (i in 0 until 8) {
            val value = getValueAtAddress(hexData, baseAddress + i * 4, 4) ?: return false
            sum = (sum + value) and 0xFFFFFFFFL
        }
        return sum == 0L
    }
}
