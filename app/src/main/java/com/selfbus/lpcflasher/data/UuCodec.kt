package com.selfbus.lpcflasher.data

/**
 * Port of utils.js – UU Encoding/Decoding for NXP ISP protocol.
 * Lines of max 45 raw bytes → 4/3 encoding + length byte.
 */
object UuCodec {

    /**
     * UU-encode a list of bytes into a list of encoded lines.
     * Each line starts with a length character and encodes up to 45 bytes.
     */
    fun encode(data: List<Int>): List<String> {
        val lines = mutableListOf<String>()
        var i = 0
        while (i < data.size) {
            val end = minOf(i + 45, data.size)
            val chunkSize = end - i
            val sb = StringBuilder()
            sb.append((chunkSize + 32).toChar())

            var j = i
            while (j < end) {
                val b1 = data.getOrElse(j) { 0 }
                val b2 = data.getOrElse(j + 1) { 0 }
                val b3 = data.getOrElse(j + 2) { 0 }

                sb.append((((b1 shr 2) and 0x3F) + 32).toChar())
                sb.append(((((b1 shl 4) or (b2 shr 4)) and 0x3F) + 32).toChar())
                sb.append(((((b2 shl 2) or (b3 shr 6)) and 0x3F) + 32).toChar())
                sb.append(((b3 and 0x3F) + 32).toChar())

                j += 3
            }

            lines.add(sb.toString())
            i += 45
        }
        return lines
    }

    /**
     * UU-decode a list of encoded lines back to raw bytes.
     */
    fun decode(lines: List<String>): List<Int> {
        val data = mutableListOf<Int>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val declaredLength = line[0].code - 32
            if (declaredLength <= 0 || declaredLength > 45) continue

            val decoded = mutableListOf<Int>()
            var i = 1
            while (i + 3 < line.length) {
                val c1 = (line[i].code - 32) and 0x3F
                val c2 = (line[i + 1].code - 32) and 0x3F
                val c3 = (line[i + 2].code - 32) and 0x3F
                val c4 = (line[i + 3].code - 32) and 0x3F

                decoded.add((c1 shl 2) or (c2 shr 4))
                decoded.add(((c2 shl 4) or (c3 shr 2)) and 0xFF)
                decoded.add(((c3 shl 6) or c4) and 0xFF)

                i += 4
            }
            data.addAll(decoded.subList(0, minOf(declaredLength, decoded.size)))
        }
        return data
    }
}
