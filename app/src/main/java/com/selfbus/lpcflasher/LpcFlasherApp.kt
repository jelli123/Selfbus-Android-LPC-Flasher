package com.selfbus.lpcflasher

import android.app.Application
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Settings

class LpcFlasherApp : Application() {

    companion object {
        init {
            // calimero's KNXnet/IP layer (HPAI, Discoverer, ClientConnection) assumes
            // IPv4. On dual-stack Android devices `new InetSocketAddress(0)` resolves to
            // the IPv6 wildcard "::", which makes calimero's static HPAI.Nat/HPAI.Tcp
            // fields fail with "::/:: is not an IPv4 address" (ExceptionInInitializerError).
            // Forcing an IPv4-only network stack must happen before any networking class
            // caches the wildcard address, i.e. as early as possible at process start.
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
        }
    }

    override fun onCreate() {
        // Set again defensively in case the static initializer above ran after some
        // framework code already touched the network stack.
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
        super.onCreate()
        Settings.init(this)
        I18n.currentLanguage = when (Settings.language) {
            "en" -> I18n.Lang.EN
            else -> I18n.Lang.DE
        }
    }
}
