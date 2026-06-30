package com.selfbus.lpcflasher.serial.knx

import java.util.zip.CRC32

/**
 * Kotlin port of the Selfbus firmware-updater UPD/UDP protocol constants.
 *
 * Source of truth: Darthyson/software-arm-lib `firmware_updater` (Java, calimero based).
 * Only the constants and helpers required for the "full flash" mode are ported here.
 * Differential / decompression mode is intentionally omitted.
 */
object Mcu {
    /** Maximum APDU/ASDU payload of a UPD telegram. */
    const val MAX_ASDU_LENGTH = 14

    /** Maximum user payload per SEND_DATA telegram (V1 bootloader). */
    const val MAX_PAYLOAD = 13

    /** Flash page size of the LPC controller. */
    const val FLASH_PAGE_SIZE = 256

    /** Block size used by the V1 update protocol (one PROGRAM cycle). */
    const val UPD_PROGRAM_SIZE = 1024

    /** Index of the command byte inside a received UPD telegram. */
    const val COMMAND_POSITION = 2

    /** Index of the first data byte inside a received UPD telegram. */
    const val DATA_POSITION = 3

    /** Number of UID bytes actually used for unlocking. */
    const val UID_LENGTH_USED = 12

    /** Maximum number of UID bytes. */
    const val UID_LENGTH_MAX = 16

    /** Flash erase timeout in milliseconds. */
    const val MAX_FLASH_ERASE_TIMEOUT_MS = 5_000L
}

/** UPD command bytes (see UPDProtocol.java / UPDCommand). */
enum class UpdCommand(val code: Int) {
    SEND_DATA(0xef),
    PROGRAM(0xee),
    UPDATE_BOOT_DESC(0xed),
    ERASE_COMPLETE_FLASH(0xea),
    ERASE_ADDRESS_RANGE(0xe9),
    REQUEST_STATISTIC(0xdf),
    RESPONSE_STATISTIC(0xde),
    SEND_LAST_ERROR(0xdc),
    UNLOCK_DEVICE(0xbf),
    REQUEST_UID(0xbe),
    RESPONSE_UID(0xbd),
    APP_VERSION_REQUEST(0xbc),
    APP_VERSION_RESPONSE(0xbb),
    REQUEST_BOOT_DESC(0xba),
    RESPONSE_BOOT_DESC(0xb9),
    REQUEST_BL_IDENTITY(0xb8),
    RESPONSE_BL_IDENTITY(0xb7),
    RESPONSE_BL_VERSION_MISMATCH(0xb6),
    SET_EMULATION(0x01),
    INVALID(0x00);

    val byte: Byte get() = code.toByte()
}

/** UPD result/return codes returned by the bootloader (see UDPResult.java). */
enum class UpdResult(val code: Int, val message: String) {
    IAP_SUCCESS(0x7f, "OK"),
    IAP_INVALID_COMMAND(0x7e, "Invalid command"),
    IAP_SRC_ADDR_ERROR(0x7d, "Source address error"),
    IAP_DST_ADDR_ERROR(0x7c, "Destination address error"),
    IAP_SRC_ADDR_NOT_MAPPED(0x7b, "Source address not mapped"),
    IAP_DST_ADDR_NOT_MAPPED(0x7a, "Destination address not mapped"),
    IAP_COUNT_ERROR(0x79, "Count error"),
    IAP_INVALID_SECTOR(0x78, "Invalid sector"),
    IAP_SECTOR_NOT_BLANK(0x77, "Sector not blank"),
    IAP_SECTOR_NOT_PREPARED(0x76, "Sector not prepared for write"),
    IAP_COMPARE_ERROR(0x75, "Compare error (try block size 256)"),
    IAP_BUSY(0x74, "IAP busy"),
    IAP_UNKNOWN(0x73, "IAP unknown error"),
    UNKNOWN_COMMAND(0x5f, "Unknown command"),
    CRC_ERROR(0x5e, "CRC error"),
    ADDRESS_NOT_ALLOWED_TO_FLASH(0x5d, "Address not allowed to flash"),
    SECTOR_NOT_ALLOWED_TO_ERASE(0x5c, "Sector not allowed to erase"),
    RAM_BUFFER_OVERFLOW(0x5b, "RAM buffer overflow"),
    WRONG_DESCRIPTOR_BLOCK(0x5a, "Wrong descriptor block"),
    APPLICATION_NOT_STARTABLE(0x59, "Application not startable"),
    DEVICE_LOCKED(0x58, "Device locked"),
    UID_MISMATCH(0x57, "UID mismatch"),
    ERASE_FAILED(0x56, "Erase failed"),
    INVALID_DATA(0x55, "Invalid data"),
    NO_DATA(0x54, "No data"),
    FLASH_ERROR(0x53, "Flash error"),
    PAGE_NOT_ALLOWED_TO_ERASE(0x52, "Page not allowed to erase"),
    ADDRESS_RANGE_NOT_ALLOWED_TO_ERASE(0x51, "Address range not allowed to erase"),
    BYTECOUNT_RECEIVED_TOO_LOW(0x50, "Byte count received too low"),
    BYTECOUNT_RECEIVED_TOO_HIGH(0x4f, "Byte count received too high"),
    UID_OFFSET_INVALID(0x4e, "UID offset invalid"),
    NOT_IMPLEMENTED(0x02, "Not implemented"),
    INVALID(0x01, "Invalid");

    companion object {
        fun fromCode(code: Int): UpdResult =
            entries.firstOrNull { it.code == (code and 0xff) } ?: INVALID
    }
}

/** Little-endian stream helpers, matching Utils.java of the firmware-updater. */
object UpdStreams {
    /** Write a 32-bit value little-endian into [stream] at [offset]. */
    fun longToStream(stream: ByteArray, offset: Int, value: Long) {
        stream[offset] = (value and 0xff).toByte()
        stream[offset + 1] = ((value ushr 8) and 0xff).toByte()
        stream[offset + 2] = ((value ushr 16) and 0xff).toByte()
        stream[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    /** Write a 16-bit value little-endian into [stream] at [offset]. */
    fun shortToStream(stream: ByteArray, offset: Int, value: Int) {
        stream[offset] = (value and 0xff).toByte()
        stream[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    /** Read a 32-bit little-endian value from [stream] at [offset]. */
    fun streamToLong(stream: ByteArray, offset: Int): Long {
        return (stream[offset].toLong() and 0xff) or
            ((stream[offset + 1].toLong() and 0xff) shl 8) or
            ((stream[offset + 2].toLong() and 0xff) shl 16) or
            ((stream[offset + 3].toLong() and 0xff) shl 24)
    }

    /** CRC32 over the given buffer, matching Utils.crc32Value. */
    fun crc32Value(buffer: ByteArray): Long {
        val crc = CRC32()
        crc.update(buffer)
        return crc.value
    }
}
