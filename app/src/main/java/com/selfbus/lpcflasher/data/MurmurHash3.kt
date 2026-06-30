package com.selfbus.lpcflasher.data

/**
 * MurmurHash3 x86_32 – public domain by Austin Appleby.
 * Kotlin port matching the Java reference in software-arm-lib.
 */
object MurmurHash3 {

    private fun rotl32(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

    private fun getblock32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun fmix32(h: Int): Int {
        var v = h
        v = v xor (v ushr 16)
        v *= 0x85ebca6b.toInt()
        v = v xor (v ushr 13)
        v *= 0xc2b2ae35.toInt()
        v = v xor (v ushr 16)
        return v
    }

    fun murmurHash3_x86_32(key: ByteArray, seed: Int): Int {
        val len = key.size
        val nBlocks = len / 4

        var h1 = seed

        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593

        // body
        for (i in 0 until nBlocks) {
            var k1 = getblock32(key, i * 4)

            k1 *= c1
            k1 = rotl32(k1, 15)
            k1 *= c2

            h1 = h1 xor k1
            h1 = rotl32(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        // tail
        var k1 = 0
        val tailOffset = nBlocks * 4

        when (len and 3) {
            3 -> {
                k1 = k1 xor ((key[tailOffset + 2].toInt() and 0xFF) shl 16)
                k1 = k1 xor ((key[tailOffset + 1].toInt() and 0xFF) shl 8)
                k1 = k1 xor (key[tailOffset].toInt() and 0xFF)
                k1 *= c1
                k1 = rotl32(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((key[tailOffset + 1].toInt() and 0xFF) shl 8)
                k1 = k1 xor (key[tailOffset].toInt() and 0xFF)
                k1 *= c1
                k1 = rotl32(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (key[tailOffset].toInt() and 0xFF)
                k1 *= c1
                k1 = rotl32(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
        }

        // finalization
        h1 = h1 xor len
        h1 = fmix32(h1)

        return h1
    }

    /**
     * Compute KNX serial number from a 16-byte UID.
     * Format: "013A:XXXXXXXX" (Selfbus manufacturer ID + MurmurHash3 of UID).
     */
    fun knxSerialFromUid(uidHexString: String): String? {
        val bytes = uidHexString.split(":").mapNotNull {
            it.trim().toIntOrNull(16)?.toByte()
        }.toByteArray()

        if (bytes.size < 16) return null

        val truncated = bytes.copyOf(16)
        val hash = murmurHash3_x86_32(truncated, 0)
        return "013A:%08X".format(hash.toLong() and 0xFFFFFFFFL)
    }
}
