package com.selfbus.lpcflasher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Port of utils.js settings management – backed by SharedPreferences.
 * Replaces localStorage-based persistence.
 */
object Settings {

    private const val PREFS_NAME = "lpc11xx_flasher_settings"
    private const val LANG_KEY = "lpc11xx_flasher_language"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---- Individual setting accessors ----

    var baudRate: Int
        get() = prefs.getInt("baudRate", 115200)
        set(v) = prefs.edit().putInt("baudRate", v).apply()

    var oscillator: Int
        get() = prefs.getInt("oscillator", 12000)
        set(v) = prefs.edit().putInt("oscillator", v).apply()

    var t1Timing: Int
        get() = prefs.getInt("t1Timing", 100)
        set(v) = prefs.edit().putInt("t1Timing", v).apply()

    var t2Timing: Int
        get() = prefs.getInt("t2Timing", 200)
        set(v) = prefs.edit().putInt("t2Timing", v).apply()

    var resetDuration: Int
        get() = prefs.getInt("resetDuration", 100)
        set(v) = prefs.edit().putInt("resetDuration", v).apply()

    var postResetDelay: Int
        get() = prefs.getInt("postResetDelay", 100)
        set(v) = prefs.edit().putInt("postResetDelay", v).apply()

    var autoResetAfterFlash: Boolean
        get() = prefs.getBoolean("autoResetAfterFlash", true)
        set(v) = prefs.edit().putBoolean("autoResetAfterFlash", v).apply()

    var useBootDescOverride: Boolean
        get() = prefs.getBoolean("useBootDescOverride", false)
        set(v) = prefs.edit().putBoolean("useBootDescOverride", v).apply()

    var bootDescOverride: String
        get() = prefs.getString("bootDescOverride", "") ?: ""
        set(v) = prefs.edit().putString("bootDescOverride", v).apply()

    /** Read chunk size index: 0=128, 1=256, 2=512 */
    var readChunkSizeIndex: Int
        get() = prefs.getInt("readChunkSize", 2)
        set(v) = prefs.edit().putInt("readChunkSize", v).apply()

    /** Write chunk size index: 0=256, 1=512 */
    var writeChunkSizeIndex: Int
        get() = prefs.getInt("writeChunkSize", 1)
        set(v) = prefs.edit().putInt("writeChunkSize", v).apply()

    var uuLineDelay: Int
        get() = prefs.getInt("uuLineDelay", 0)
        set(v) = prefs.edit().putInt("uuLineDelay", v).apply()

    var readLineDelay: Int
        get() = prefs.getInt("readLineDelay", 1)
        set(v) = prefs.edit().putInt("readLineDelay", v).apply()

    var language: String
        get() = prefs.getString(LANG_KEY, "de") ?: "de"
        set(v) = prefs.edit().putString(LANG_KEY, v).apply()

    // ---- Derived values ----

    val readChunkSizes = intArrayOf(128, 256, 512)
    val writeChunkSizes = intArrayOf(256, 512)

    val readChunkSize: Int get() = readChunkSizes.getOrElse(readChunkSizeIndex) { 512 }
    val writeChunkSize: Int get() = writeChunkSizes.getOrElse(writeChunkSizeIndex) { 512 }

}
