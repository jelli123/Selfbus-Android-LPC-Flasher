package com.selfbus.lpcflasher

import android.app.Application
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Settings

class LpcFlasherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        I18n.currentLanguage = when (Settings.language) {
            "en" -> I18n.Lang.EN
            else -> I18n.Lang.DE
        }
    }
}
