package com.selfbus.lpcflasher

import android.app.Application
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Settings
import com.selfbus.lpcflasher.serial.knx.Ipv4Compat

class LpcFlasherApp : Application() {

    companion object {
        init {
            // calimero's KNXnet/IP layer (HPAI, Discoverer, ClientConnection) assumes
            // IPv4. On dual-stack Android devices `new InetSocketAddress(0)` resolves to
            // the IPv6 wildcard "::", which makes calimero's static HPAI.Nat/HPAI.Tcp
            // fields fail with "::/:: is not an IPv4 address" (ExceptionInInitializerError).
            // Android ignores java.net.preferIPv4Stack, so we force the IPv4 wildcard via
            // reflection here, as early as possible (class-load of the Application), which
            // runs before any calimero class is touched.
            Ipv4Compat.forceIpv4Wildcard()
        }
    }

    override fun onCreate() {
        // Defensive: ensure the IPv4 wildcard is enforced before any KNX operation.
        Ipv4Compat.forceIpv4Wildcard()
        super.onCreate()
        Settings.init(this)
        I18n.currentLanguage = when (Settings.language) {
            "en" -> I18n.Lang.EN
            else -> I18n.Lang.DE
        }
    }
}
