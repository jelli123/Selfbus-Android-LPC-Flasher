package com.selfbus.lpcflasher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Port of firmware-catalog.js – GitHub/jsdelivr firmware loading, caching, hint generation.
 * Uses java.net.HttpURLConnection to avoid extra dependencies.
 */
object FirmwareCatalog {

    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/selfbus/software-releases/main"
    private const val MAPPING_FILE_PATH = "firmware-mapping.json"

    private val ALLOWED_FETCH_DOMAINS = listOf(
        "raw.githubusercontent.com",
        "api.github.com",
        "data.jsdelivr.com",
        "github.com"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- State ----
    var firmwareMapping: FirmwareMapping? = null
        private set
    private val cachedFirmwareFiles = mutableMapOf<String, List<FirmwareFile>>()
    private var jsdelivrFileCache: List<String>? = null

    // ---- Data Models ----

    data class Category(
        val name: Map<String, String>,
        val icon: String,
        val order: Int
    )

    data class Device(
        val name: Map<String, String>,
        val category: String,
        val path: String,
        val cachedFiles: List<String>
    )

    data class FirmwareMapping(
        val categories: Map<String, Category>,
        val devices: Map<String, Device>,
        val firmwareHints: Map<String, Map<String, String>>
    )

    data class FirmwareFile(
        val name: String,
        val path: String,
        val downloadUrl: String,
        val hints: List<HintMatch> = emptyList()
    )

    data class HintMatch(
        val pattern: String,
        val hint: Map<String, String>
    )

    // ---- URL validation ----

    private fun isAllowedUrl(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            url.protocol == "https" &&
                    ALLOWED_FETCH_DOMAINS.any { domain ->
                        url.host == domain || url.host.endsWith(".$domain")
                    }
        } catch (_: Exception) { false }
    }

    // ---- HTTP helper ----

    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        if (!isAllowedUrl(urlString)) {
            Logger.debug("URL rejected: $urlString")
            return@withContext null
        }
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Logger.debug("HTTP ${conn.responseCode} for $urlString")
                null
            }
        } catch (e: Exception) {
            Logger.debug("HTTP fetch failed: ${e.message}")
            null
        }
    }

    // ---- Embedded Fallback ----

    fun getEmbeddedFallbackMapping(): FirmwareMapping {
        return FirmwareMapping(
            categories = mapOf(
                "bootloader" to Category(mapOf("en" to "Bootloader", "de" to "Bootloader"), "🚀", 0),
                "outputs" to Category(mapOf("en" to "Outputs", "de" to "Ausgänge"), "💡", 1),
                "inputs" to Category(mapOf("en" to "Inputs", "de" to "Eingänge"), "🔘", 2),
                "sensors" to Category(mapOf("en" to "Sensors", "de" to "Sensoren"), "🌡️", 3),
                "blinds" to Category(mapOf("en" to "Blinds/Shutters", "de" to "Rollläden/Jalousien"), "▦", 4),
                "interfaces" to Category(mapOf("en" to "Interfaces", "de" to "Schnittstellen"), "🔌", 5),
                "misc" to Category(mapOf("en" to "Miscellaneous", "de" to "Sonstiges"), "📦", 6)
            ),
            devices = mapOf(
                "bootloader" to Device(
                    mapOf("en" to "Selfbus Bootloader", "de" to "Selfbus Bootloader"),
                    "bootloader", "firmware_updater/bootloader",
                    listOf("bootloader_release_v1.20_libv2.10.hex", "bootloader_release_v1.10_libv2.02.hex")
                ),
                "out8-bcu1" to Device(
                    mapOf("en" to "8-channel Binary Output (BCU1)", "de" to "8-fach Binärausgang (BCU1)"),
                    "outputs", "actuators/outputs/out8-bcu1",
                    listOf(
                        "out8-bcu1_release_o8_4t_v5.10_libv2.02.hex",
                        "out8-bcu1_release_o8_4t_bi_v5.10_libv2.02.hex",
                        "out8-bcu1_release_o8_4t_ha_v5.12_libv2.10.hex",
                        "out8-bcu1_release_o8_4t_ha_bi_v5.12_libv2.10.hex",
                        "out8-bcu1_release_o8_4t_bf_ha_v5.10_libv2.02.hex",
                        "out8-bcu1_release_o8_4t_bf_ha_bi_v5.10_libv2.02.hex"
                    )
                ),
                "out-cs-bim112" to Device(
                    mapOf("en" to "Switching Actuator with Current Sensing (BIM112)", "de" to "Schaltaktor strommessend (BIM112)"),
                    "outputs", "actuators/outputs/out-cs-bim112",
                    listOf(
                        "out-cs-bim112_release_2ch_v1.20_libv2.02.hex",
                        "out-cs-bim112_release_2ch_wo_cs_v1.20_libv2.02.hex",
                        "out-cs-bim112_release_6ch_v1.20_libv2.02.hex",
                        "out-cs-bim112_release_8ch_wo_cs_v1.21_libv2.10.hex"
                    )
                ),
                "out4-dimmer" to Device(
                    mapOf("en" to "4-channel LED Dimmer", "de" to "4-fach LED Dimmer"),
                    "outputs", "actuators/outputs/out4-dimmer", emptyList()
                ),
                "in8-bcu1" to Device(
                    mapOf("en" to "8-channel Binary Input (BCU1)", "de" to "8-fach Binäreingang (BCU1)"),
                    "inputs", "sensors/binary-inputs/in8-bcu1", emptyList()
                ),
                "in16-bim112" to Device(
                    mapOf("en" to "16-channel Binary Input (BIM112)", "de" to "16-fach Binäreingang (BIM112)"),
                    "inputs", "sensors/binary-inputs/in16-bim112", emptyList()
                ),
                "4sense-bcu1" to Device(
                    mapOf("en" to "4-channel Temperature Sensor (BCU1)", "de" to "4-fach Temperatursensor (BCU1)"),
                    "sensors", "sensors/misc/4sense-bcu1", emptyList()
                ),
                "weatherstation-bim112" to Device(
                    mapOf("en" to "Weather Station (BIM112)", "de" to "Wetterstation (BIM112)"),
                    "sensors", "misc/weatherstation-bim112", emptyList()
                ),
                "rol-jal-bim112" to Device(
                    mapOf("en" to "Blind/Shutter Actuator (BIM112)", "de" to "Rolladen-/Jalousieaktor (BIM112)"),
                    "blinds", "actuators/blind-shutter/rol-jal-bim112", emptyList()
                ),
                "usb-interface-bcu1" to Device(
                    mapOf("en" to "USB Interface (BCU1)", "de" to "USB Interface (BCU1)"),
                    "interfaces", "misc/USB-Interface-bcu1", emptyList()
                ),
                "ft12" to Device(
                    mapOf("en" to "FT12 Interface", "de" to "FT12 Schnittstelle"),
                    "interfaces", "misc/FT12", emptyList()
                ),
                "rauchmelder-bcu1" to Device(
                    mapOf("en" to "Smoke Detector (BCU1)", "de" to "Rauchmelder (BCU1)"),
                    "misc", "misc/Rauchmelder-bcu1",
                    listOf(
                        "rauchmelder-bcu1_release_v1.03_libv2.02.hex",
                        "rauchmelder-bcu1_release_v3.00_libv2.02.hex",
                        "rauchmelder-bcu1_release_v3.00_libv2.10.hex",
                        "rauchmelder-bcu1_release_v3.10_libv2.10.hex"
                    )
                )
            ),
            firmwareHints = mapOf(
                "release" to mapOf("en" to "Standalone firmware", "de" to "Standalone Firmware"),
                "flashstart" to mapOf("en" to "Flashstart image - flash only if a bootloader is already present", "de" to "Flashstart-Datei - nur bei vorhandenem Bootloader flashen"),
                "_bi" to mapOf("en" to "For bistable relays", "de" to "Für bistabile Relais"),
                "_ha" to mapOf("en" to "With hand actuation", "de" to "Mit Handbetätigung"),
                "_bf" to mapOf("en" to "With bus failure handling", "de" to "Mit Busspannungsausfall"),
                "_wo_cs" to mapOf("en" to "Without current sensing", "de" to "Ohne Strommessung"),
                "_cs" to mapOf("en" to "With current sensing", "de" to "Mit Strommessung"),
                "2ch" to mapOf("en" to "2-channel", "de" to "2-Kanal"),
                "6ch" to mapOf("en" to "6-channel", "de" to "6-Kanal"),
                "8ch" to mapOf("en" to "8-channel", "de" to "8-Kanal"),
                "4t" to mapOf("en" to "With 4 timers", "de" to "Mit 4 Zeitschaltuhren"),
                "o8" to mapOf("en" to "8 outputs", "de" to "8 Ausgänge")
            )
        )
    }

    // ---- Load mapping from GitHub ----

    suspend fun loadFirmwareMapping(): Boolean {
        try {
            val url = "$GITHUB_RAW_BASE/$MAPPING_FILE_PATH"
            Logger.debug("Loading mapping from: $url")
            val body = httpGet(url)
            if (body != null) {
                val data = json.parseToJsonElement(body).jsonObject
                if ("categories" in data && "devices" in data) {
                    firmwareMapping = parseMappingJson(data)
                    Logger.debug("Mapping loaded from GitHub (${firmwareMapping!!.devices.size} devices)")
                    return true
                }
            }
        } catch (e: Exception) {
            Logger.debug("Could not load mapping: ${e.message}")
        }
        Logger.debug("Using embedded fallback mapping")
        firmwareMapping = getEmbeddedFallbackMapping()
        return true
    }

    private fun parseMappingJson(data: JsonObject): FirmwareMapping {
        val categories = mutableMapOf<String, Category>()
        data["categories"]?.jsonObject?.forEach { (key, value) ->
            val obj = value.jsonObject
            val nameObj = obj["name"]?.jsonObject
            categories[key] = Category(
                name = nameObj?.map { (k, v) -> k to v.jsonPrimitive.content }?.toMap() ?: emptyMap(),
                icon = obj["icon"]?.jsonPrimitive?.content ?: "📦",
                order = obj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 99
            )
        }

        val devices = mutableMapOf<String, Device>()
        data["devices"]?.jsonObject?.forEach { (key, value) ->
            val obj = value.jsonObject
            val nameObj = obj["name"]?.jsonObject
            devices[key] = Device(
                name = nameObj?.map { (k, v) -> k to v.jsonPrimitive.content }?.toMap() ?: emptyMap(),
                category = obj["category"]?.jsonPrimitive?.content ?: "misc",
                path = obj["path"]?.jsonPrimitive?.content ?: "",
                cachedFiles = obj["cachedFiles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            )
        }

        val hints = mutableMapOf<String, Map<String, String>>()
        data["firmwareHints"]?.jsonObject?.forEach { (key, value) ->
            hints[key] = value.jsonObject.map { (k, v) -> k to v.jsonPrimitive.content }.toMap()
        }

        return FirmwareMapping(categories, devices, hints)
    }

    // ---- jsdelivr API ----

    suspend fun loadJsdelivrFileList(): List<String> {
        jsdelivrFileCache?.let { return it }

        try {
            val url = "https://data.jsdelivr.com/v1/packages/gh/selfbus/software-releases@main?structure=flat"
            Logger.debug("Loading file list from jsdelivr: $url")
            val body = httpGet(url) ?: run {
                jsdelivrFileCache = emptyList()
                return emptyList()
            }
            val data = json.parseToJsonElement(body).jsonObject
            val files = data["files"]?.jsonArray?.map { fileObj ->
                fileObj.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            }?.filter { it.isNotEmpty() } ?: emptyList()

            jsdelivrFileCache = files
            Logger.debug("jsdelivr: Loaded ${files.size} files total")
            return files
        } catch (e: Exception) {
            Logger.debug("jsdelivr fetch failed: ${e.message}")
            jsdelivrFileCache = emptyList()
            return emptyList()
        }
    }

    private fun getFilesFromJsdelivrCache(path: String): List<FirmwareFile> {
        val cache = jsdelivrFileCache ?: return emptyList()
        val normalizedPath = if (path.startsWith("/")) path else "/$path"

        return cache.filter { filePath ->
            val np = if (filePath.startsWith("/")) filePath else "/$filePath"
            val fileDir = np.substring(0, np.lastIndexOf('/'))
            fileDir == normalizedPath && np.endsWith(".hex")
        }.map { filePath ->
            val np = if (filePath.startsWith("/")) filePath else "/$filePath"
            val fileName = np.substring(np.lastIndexOf('/') + 1)
            FirmwareFile(
                name = fileName,
                path = path,
                downloadUrl = "$GITHUB_RAW_BASE/$path/$fileName"
            )
        }
    }

    // ---- GitHub tree-commit-info (backup) ----

    private suspend fun fetchFirmwareListFromGitHub(path: String): List<FirmwareFile> {
        try {
            val url = "https://github.com/selfbus/software-releases/tree-commit-info/main/$path"
            Logger.debug("Trying GitHub tree-commit-info: $url")
            val body = httpGet(url) ?: return emptyList()
            val data = json.parseToJsonElement(body).jsonObject
            val files = data.keys
                .filter { it.endsWith(".hex") }
                .map { name ->
                    FirmwareFile(
                        name = name,
                        path = path,
                        downloadUrl = "$GITHUB_RAW_BASE/$path/$name"
                    )
                }
            if (files.isNotEmpty()) Logger.debug("GitHub tree-commit-info: Found ${files.size} files")
            return files
        } catch (e: Exception) {
            Logger.debug("GitHub tree-commit-info failed: ${e.message}")
            return emptyList()
        }
    }

    // ---- Load firmware files for device ----

    suspend fun loadFirmwareFilesForDevice(deviceId: String): List<FirmwareFile> {
        val mapping = firmwareMapping ?: return emptyList()
        val device = mapping.devices[deviceId] ?: run {
            Logger.debug("Device not found: $deviceId")
            return emptyList()
        }
        val cacheKey = device.path
        cachedFirmwareFiles[cacheKey]?.let { return it }

        // 1. jsdelivr
        if (device.path.isNotEmpty()) {
            loadJsdelivrFileList()
            var files = getFilesFromJsdelivrCache(device.path)
            if (files.isNotEmpty()) {
                Logger.debug("Found ${files.size} files via jsdelivr for: ${device.path}")
                files = files.map { it.copy(hints = generateHintsForFile(it.name)) }
                cachedFirmwareFiles[cacheKey] = files
                return files
            }

            // 2. GitHub tree-commit-info
            files = fetchFirmwareListFromGitHub(device.path)
            if (files.isNotEmpty()) {
                Logger.debug("Found ${files.size} files via GitHub for: ${device.path}")
                files = files.map { it.copy(hints = generateHintsForFile(it.name)) }
                cachedFirmwareFiles[cacheKey] = files
                return files
            }
        }

        // 3. cachedFiles from mapping
        if (device.cachedFiles.isNotEmpty()) {
            Logger.debug("Using cachedFiles from mapping for: ${device.path}")
            val files = device.cachedFiles.map { name ->
                FirmwareFile(name, device.path, "$GITHUB_RAW_BASE/${device.path}/$name", generateHintsForFile(name))
            }
            cachedFirmwareFiles[cacheKey] = files
            return files
        }

        // 4. Embedded fallback
        val embeddedDevice = getEmbeddedFallbackMapping().devices[deviceId]
        if (embeddedDevice != null && embeddedDevice.cachedFiles.isNotEmpty()) {
            Logger.debug("Using embedded fallback for: $deviceId")
            val files = embeddedDevice.cachedFiles.map { name ->
                FirmwareFile(name, embeddedDevice.path, "$GITHUB_RAW_BASE/${embeddedDevice.path}/$name", generateHintsForFile(name))
            }
            cachedFirmwareFiles[cacheKey] = files
            return files
        }

        Logger.debug("No firmware files found for: $deviceId")
        return emptyList()
    }

    // ---- Hint generation ----

    fun generateHintsForFile(filename: String): List<HintMatch> {
        val hints = mutableListOf<HintMatch>()
        val matchedPatterns = mutableListOf<String>()
        val lowerName = filename.lowercase()
        val hintSource = firmwareMapping?.firmwareHints ?: getEmbeddedFallbackMapping().firmwareHints

        val sortedPatterns = hintSource.keys.sortedByDescending { it.length }

        for (pattern in sortedPatterns) {
            val lowerPattern = pattern.lowercase()
            if (!lowerName.contains(lowerPattern)) continue

            val isSubstring = matchedPatterns.any {
                it.lowercase().contains(lowerPattern) && it.length > pattern.length
            }
            if (!isSubstring) {
                matchedPatterns.add(pattern)
                hints.add(HintMatch(pattern, hintSource[pattern] ?: emptyMap()))
            }
        }

        if (lowerName.contains("flashstart")) {
            val flashstartHint = hintSource["flashstart"]
                ?: getEmbeddedFallbackMapping().firmwareHints["flashstart"]
            val filtered = hints.filter { it.pattern.lowercase() != "release" }.toMutableList()
            if (flashstartHint != null) {
                filtered.add(0, HintMatch("flashstart", flashstartHint))
            }
            return filtered
        }
        return hints
    }

    fun getHintText(hints: List<HintMatch>, lang: String): String {
        return hints.mapNotNull { h ->
            h.hint[lang] ?: h.hint["en"]
        }.filter { it.isNotEmpty() }.joinToString(" | ")
    }

    fun formatFirmwareName(filename: String, hints: List<HintMatch> = emptyList()): String {
        val match = Regex("""v(\d+\.\d+).*lib.?v?(\d+\.\d+)""", RegexOption.IGNORE_CASE).find(filename)
        if (match != null) {
            val version = match.groupValues[1]
            val lib = match.groupValues[2]
            val tags = hints.map { it.pattern }
                .filter { it.lowercase() != "release" }
                .map { it.removePrefix("_") }
            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
            return "v$version (lib $lib)$tagStr"
        }
        return filename.removeSuffix(".hex").replace('_', ' ')
    }

    // ---- Download ----

    suspend fun downloadFirmwareFromGitHub(path: String, filename: String): String {
        val url = "$GITHUB_RAW_BASE/$path/$filename"
        Logger.info("${I18n.t("downloadingFirmware")}: $filename")

        val body = httpGet(url)
            ?: throw Exception("Download failed: $filename")
        return body
    }

    // ---- Sorted categories / devices for UI ----

    fun getSortedCategories(): List<Pair<String, Category>> {
        val mapping = firmwareMapping ?: return emptyList()
        return mapping.categories.entries
            .filter { (key, _) ->
                mapping.devices.values.any { it.category == key }
            }
            .sortedBy { it.value.order }
            .map { it.key to it.value }
    }

    fun getDevicesForCategory(category: String, lang: String = "en"): List<Pair<String, Device>> {
        val mapping = firmwareMapping ?: return emptyList()
        return mapping.devices.entries
            .filter { it.value.category == category }
            .sortedBy { it.value.name[lang] ?: it.value.name["en"] ?: it.key }
            .map { it.key to it.value }
    }
}
