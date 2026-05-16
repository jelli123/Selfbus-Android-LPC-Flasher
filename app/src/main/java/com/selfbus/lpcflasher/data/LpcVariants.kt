package com.selfbus.lpcflasher.data

/**
 * Full port of lpc-variants.js – NXP LPC11xx chip database.
 * Source: NXP User Manual UM10398 (LPC111x/LPC11Cxx), Table 387.
 */
object LpcVariants {

    data class ChipVariant(
        val names: List<String>,
        val partId: Long,
        val flashSize: Int,
        val ramSize: Int,
        val sectorCount: Int
    )

    data class ChipInfo(
        val name: String,
        val partId: Long,
        val flashSize: Int,
        val ramSize: Int,
        val sectorCount: Int
    )

    private val variants = listOf(
        // LPC1110 – 4 KB Flash, 1 KB RAM
        ChipVariant(listOf("LPC1110"), 0x0A07102BL, 4096, 1024, 1),
        ChipVariant(listOf("LPC1110"), 0x1A07102BL, 4096, 1024, 1),
        // LPC1111 – 8 KB Flash
        ChipVariant(listOf("LPC1111FDH20/002"), 0x0A16D02BL, 8192, 2048, 2),
        ChipVariant(listOf("LPC1111FDH20/002"), 0x1A16D02BL, 8192, 2048, 2),
        ChipVariant(listOf("LPC1111FHN33/101"), 0x041E502BL, 8192, 2048, 2),
        ChipVariant(listOf("LPC1111FHN33/101"), 0x2516D02BL, 8192, 2048, 2),
        ChipVariant(listOf("LPC1111FHN33/103"), 0x00010013L, 8192, 2048, 2),
        ChipVariant(listOf("LPC1111FHN33/201"), 0x0416502BL, 8192, 4096, 2),
        ChipVariant(listOf("LPC1111FHN33/201"), 0x2516902BL, 8192, 4096, 2),
        ChipVariant(listOf("LPC1111FHN33/203"), 0x00010012L, 8192, 4096, 2),
        // LPC1112 – 16 KB Flash
        ChipVariant(listOf("LPC1112FDH20/102", "LPC1112FDH28/102", "LPC1112FHN33/101"), 0x042D502BL, 16384, 2048, 4),
        ChipVariant(listOf("LPC1112FDH20/102", "LPC1112FDH28/102", "LPC1112FHN33/101"), 0x2524D02BL, 16384, 2048, 4),
        ChipVariant(listOf("LPC1112FHN33/102", "LPC1112FHN24/102"), 0x0A24902BL, 16384, 4096, 4),
        ChipVariant(listOf("LPC1112FHN33/102", "LPC1112FHN24/102"), 0x1A24902BL, 16384, 4096, 4),
        ChipVariant(listOf("LPC1112FHN33/103", "LPC1112FHI33/103"), 0x00020023L, 16384, 4096, 4),
        ChipVariant(listOf("LPC1112FHN33/201", "LPC1112FHI33/202"), 0x0425502BL, 16384, 4096, 4),
        ChipVariant(listOf("LPC1112FHN33/201", "LPC1112FHI33/202"), 0x2524902BL, 16384, 4096, 4),
        ChipVariant(listOf("LPC1112FHN33/203", "LPC1112FHI33/203"), 0x00020022L, 16384, 4096, 4),
        // LPC1113 – 24 KB Flash
        ChipVariant(listOf("LPC1113FBD48/201", "LPC1113FHN33/201"), 0x0434502BL, 24576, 4096, 6),
        ChipVariant(listOf("LPC1113FBD48/201", "LPC1113FHN33/201"), 0x2532902BL, 24576, 4096, 6),
        ChipVariant(listOf("LPC1113FBD48/203", "LPC1113FHN33/203"), 0x00030032L, 24576, 4096, 6),
        ChipVariant(listOf("LPC1113FBD48/301", "LPC1113FHN33/301"), 0x0434102BL, 24576, 8192, 6),
        ChipVariant(listOf("LPC1113FBD48/301", "LPC1113FHN33/301"), 0x2532102BL, 24576, 8192, 6),
        ChipVariant(listOf("LPC1113FBD48/303", "LPC1113FHN33/303"), 0x00030030L, 24576, 8192, 6),
        // LPC1114 – 32 KB Flash (and 48/56 KB variants)
        ChipVariant(listOf("LPC1114FDH28/102", "LPC1114FN28/102"), 0x0A40902BL, 32768, 4096, 8),
        ChipVariant(listOf("LPC1114FDH28/102", "LPC1114FN28/102"), 0x1A40902BL, 32768, 4096, 8),
        ChipVariant(listOf("LPC1114FBD48/201", "LPC1114FHN33/201"), 0x0444502BL, 32768, 4096, 8),
        ChipVariant(listOf("LPC1114FBD48/201", "LPC1114FHN33/201"), 0x2540902BL, 32768, 4096, 8),
        ChipVariant(listOf("LPC1114FBD48/203", "LPC1114FHI33/203"), 0x00040042L, 32768, 4096, 8),
        ChipVariant(listOf("LPC1114FBD48/301", "LPC1114FHN33/301"), 0x0444102BL, 32768, 8192, 8),
        ChipVariant(listOf("LPC1114FBD48/301", "LPC1114FHN33/301"), 0x2540102BL, 32768, 8192, 8),
        ChipVariant(listOf("LPC1114FBD48/303", "LPC1114FHI33/303"), 0x00040040L, 32768, 8192, 8),
        ChipVariant(listOf("LPC1114FBD48/323", "LPC1114FHI33/323"), 0x00040060L, 49152, 8192, 12),
        ChipVariant(listOf("LPC1114FBD48/333", "LPC1114FHI33/333"), 0x00040070L, 57344, 8192, 14),
        // LPC1115 – 64 KB Flash, 8 KB RAM
        ChipVariant(listOf("LPC1115FBD48/303"), 0x00050080L, 65536, 8192, 16),
        // LPC11C12 – 16 KB Flash, 8 KB RAM (CAN)
        ChipVariant(listOf("LPC11C12FBD48/301"), 0x1421102BL, 16384, 8192, 4),
        // LPC11C14 – 32 KB Flash, 8 KB RAM (CAN)
        ChipVariant(listOf("LPC11C14FBD48/301"), 0x1440102BL, 32768, 8192, 8),
        // LPC11C22 – 16 KB Flash, 8 KB RAM (CAN, on-chip driver)
        ChipVariant(listOf("LPC11C22FBD48/301"), 0x1431102BL, 16384, 8192, 4),
        // LPC11C24 – 32 KB Flash, 8 KB RAM (CAN, on-chip driver)
        ChipVariant(listOf("LPC11C24FBD48/301"), 0x1430102BL, 32768, 8192, 8)
    )

    fun findChipByPartId(partId: Long): ChipInfo? {
        val matches = variants.filter { it.partId == partId }
        if (matches.isEmpty()) return null

        val allNames = mutableListOf<String>()
        for (m in matches) {
            for (n in m.names) {
                if (n !in allNames) allNames.add(n)
            }
        }
        val first = matches[0]
        return ChipInfo(
            name = allNames.joinToString(", "),
            partId = partId,
            flashSize = first.flashSize,
            ramSize = first.ramSize,
            sectorCount = first.sectorCount
        )
    }

    fun detectChip(partId: Long): ChipInfo {
        return findChipByPartId(partId) ?: ChipInfo(
            name = "Unknown LPC (0x${partId.toString(16).uppercase()})",
            partId = partId,
            flashSize = 32768,
            ramSize = 8192,
            sectorCount = 8
        )
    }

    const val RAM_START = 0x10000300L

    fun getSectorFromAddress(address: Int): Int {
        return if (address < 0x10000) address / 0x1000 else -1
    }
}
