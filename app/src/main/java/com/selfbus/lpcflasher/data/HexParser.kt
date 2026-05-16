package com.selfbus.lpcflasher.data

/**
 * Port of utils.js – Intel HEX Parser & Generator.
 */
object HexParser {

    data class HexBlock(
        val address: Int,
        val data: MutableList<Int>
    )

    /**
     * Parse an Intel HEX string into a list of contiguous data blocks.
     */
    fun parseIntelHex(content: String): List<HexBlock> {
        val lines = content.split('\n')
        val blocks = mutableListOf<HexBlock>()
        var currentBlock: HexBlock? = null
        var extendedAddress = 0
        var eofSeen = false

        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith(':')) continue
            if (trimmed.length < 11) {
                throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: line too short")
            }
            if (((trimmed.length - 1) % 2) != 0) {
                throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: odd number of hex digits")
            }

            val payload = trimmed.substring(1)
            if (!payload.all { it in "0123456789abcdefABCDEF" }) {
                throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: non-hex character found")
            }

            val byteCount = parseHexByte(trimmed, 1, lineNo, "byte count")
            val address = parseHexWord(trimmed, 3, lineNo, "address")
            val recordType = parseHexByte(trimmed, 7, lineNo, "record type")
            val expectedLength = 11 + byteCount * 2
            if (trimmed.length != expectedLength) {
                throw IllegalArgumentException(
                    "Invalid Intel HEX at line $lineNo: length mismatch (expected $expectedLength, got ${trimmed.length})"
                )
            }

            var checksumSum = 0
            var pos = 1
            while (pos < trimmed.length) {
                checksumSum = (checksumSum + parseHexByte(trimmed, pos, lineNo, "checksum data")) and 0xFF
                pos += 2
            }
            if (checksumSum != 0) {
                throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: checksum mismatch")
            }

            when (recordType) {
                0x00 -> { // Data record
                    val fullAddress = extendedAddress + address
                    val data = mutableListOf<Int>()
                    for (i in 0 until byteCount) {
                        data.add(parseHexByte(trimmed, 9 + i * 2, lineNo, "data"))
                    }
                    if (currentBlock != null && currentBlock.address + currentBlock.data.size == fullAddress) {
                        currentBlock.data.addAll(data)
                    } else {
                        currentBlock?.let { blocks.add(it) }
                        currentBlock = HexBlock(fullAddress, data)
                    }
                }
                0x01 -> { // End-of-File
                    if (byteCount != 0 || address != 0) {
                        throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: malformed EOF record")
                    }
                    currentBlock?.let { blocks.add(it) }
                    currentBlock = null
                    eofSeen = true
                    break
                }
                0x02 -> { // Extended Segment Address
                    if (byteCount != 2) {
                        throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: bad segment-address length")
                    }
                    extendedAddress = parseHexWord(trimmed, 9, lineNo, "segment address") shl 4
                }
                0x04 -> { // Extended Linear Address
                    if (byteCount != 2) {
                        throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: bad linear-address length")
                    }
                    extendedAddress = parseHexWord(trimmed, 9, lineNo, "linear address") shl 16
                }
                0x03, 0x05 -> Unit // Optional start address records, already validated via checksum.
                else -> throw IllegalArgumentException(
                    "Invalid Intel HEX at line $lineNo: unsupported record type 0x${recordType.toString(16).uppercase()}"
                )
            }
        }

        if (currentBlock != null) {
            blocks.add(currentBlock)
        }
        if (!eofSeen && blocks.isEmpty()) {
            throw IllegalArgumentException("Invalid Intel HEX: no data records found")
        }
        return blocks
    }

    private fun parseHexByte(line: String, start: Int, lineNo: Int, field: String): Int {
        if (start < 0 || start + 2 > line.length) {
            throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: $field is out of bounds")
        }
        return line.substring(start, start + 2).toIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: invalid $field")
    }

    private fun parseHexWord(line: String, start: Int, lineNo: Int, field: String): Int {
        if (start < 0 || start + 4 > line.length) {
            throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: $field is out of bounds")
        }
        return line.substring(start, start + 4).toIntOrNull(16)
            ?: throw IllegalArgumentException("Invalid Intel HEX at line $lineNo: invalid $field")
    }

    /**
     * Generate an Intel HEX string from a byte array.
     */
    fun generateIntelHex(data: List<Int>, startAddress: Int = 0): String {
        val sb = StringBuilder()
        val bytesPerLine = 16

        var i = 0
        while (i < data.size) {
            val lineData = data.subList(i, minOf(i + bytesPerLine, data.size))
            val address = startAddress + i

            var checksum = lineData.size + (address shr 8 and 0xFF) + (address and 0xFF) // + recordType 0x00

            val line = StringBuilder(":")
            line.append(lineData.size.toString(16).padStart(2, '0').uppercase())
            line.append(address.toString(16).padStart(4, '0').uppercase())
            line.append("00")

            for (byte in lineData) {
                line.append(byte.toString(16).padStart(2, '0').uppercase())
                checksum += byte
            }
            checksum = (checksum.inv() + 1) and 0xFF
            line.append(checksum.toString(16).padStart(2, '0').uppercase())
            sb.appendLine(line)

            i += bytesPerLine
        }
        sb.appendLine(":00000001FF")
        return sb.toString()
    }
}
