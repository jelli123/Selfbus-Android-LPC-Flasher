package com.selfbus.lpcflasher.serial.knx

import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Android compatibility shim for calimero's KNXnet/IP layer.
 *
 * calimero 2.6 initialises static [tuwien.auto.calimero.knxnetip.util.HPAI]
 * fields with `new InetSocketAddress(0)`. On dual-stack Android devices the
 * wildcard address resolved by `InetAddress.anyLocalAddress()` is the IPv6
 * address `::` (an `Inet6Address`), but HPAI's constructor rejects anything that
 * is not an `Inet4Address`. This makes `HPAI.<clinit>` fail with
 * `ExceptionInInitializerError: ::/:: is not an IPv4 address`, taking down every
 * search and connect attempt.
 *
 * Android/libcore ignores the `java.net.preferIPv4Stack` system property (the
 * IPv4/IPv6 wildcard choice is made natively via `isIPv6Supported()`), and on
 * targetSdk 35 direct reflection on `InetAddress.impl` /
 * `VMRuntime.setHiddenApiExemptions` is blocked by hidden-API enforcement.
 *
 * We therefore use LSPosed's [HiddenApiBypass] to lift the hidden-API
 * restriction for this process, then overwrite the cached `anyLocalAddress`
 * field of the current `InetAddressImpl` with the IPv4 wildcard `0.0.0.0`,
 * *before* calimero's HPAI class is loaded.
 */
object Ipv4Compat {

    private const val TAG = "Ipv4Compat"

    @Volatile
    private var applied = false

    /** Idempotent; safe to call multiple times and from the Application init. */
    @Synchronized
    fun forceIpv4Wildcard() {
        if (applied) return
        applied = true

        if (wildcardIsIpv4()) {
            Log.i(TAG, "IPv4 wildcard already active")
            return
        }

        // Lift hidden-API restrictions for this process (Android 9-15). An empty
        // prefix exempts every hidden API, so the java.net reflection below works.
        val exempted = runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .getOrDefault(false)
        if (!exempted) {
            Log.w(TAG, "HiddenApiBypass could not lift hidden-API restrictions")
        }

        val ipv4Any = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) as? Inet4Address
        if (ipv4Any == null) {
            Log.w(TAG, "could not create IPv4 wildcard address")
            return
        }

        // Preferred: replace only the cached wildcard address of the current impl.
        runCatching { patchAnyLocalAddress(ipv4Any) }
            .onFailure { Log.w(TAG, "patchAnyLocalAddress failed: ${it.message}") }

        // Fallback: swap the whole impl to an IPv4-only implementation.
        if (!wildcardIsIpv4()) {
            runCatching { swapImplToIpv4() }
                .onFailure { Log.w(TAG, "swapImplToIpv4 failed: ${it.message}") }
        }

        if (wildcardIsIpv4()) {
            Log.i(TAG, "IPv4 wildcard active")
        } else {
            Log.w(TAG, "IPv4 wildcard could NOT be enforced; calimero may crash on HPAI init")
        }
    }

    /** Whether `new java.net.InetSocketAddress(0)` currently yields an IPv4 address. */
    private fun wildcardIsIpv4(): Boolean =
        runCatching { java.net.InetSocketAddress(0).address is Inet4Address }.getOrDefault(false)

    /**
     * Overwrite the cached `anyLocalAddress` field on the current InetAddressImpl
     * with the IPv4 wildcard. Only the wildcard field is touched; loopback and
     * DNS resolution stay untouched.
     */
    private fun patchAnyLocalAddress(ipv4Any: Inet4Address) {
        val impl = readImpl() ?: return
        for (field in impl.javaClass.declaredFields) {
            if (!field.name.equals("anyLocalAddress", ignoreCase = true)) continue
            if (!InetAddress::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            field.set(impl, ipv4Any)
        }
    }

    /** Replace the whole static `InetAddress.impl` with a fresh Inet4AddressImpl. */
    private fun swapImplToIpv4() {
        val implField = InetAddress::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        val inet4ImplClass = Class.forName("java.net.Inet4AddressImpl")
        val ctor = inet4ImplClass.getDeclaredConstructor()
        ctor.isAccessible = true
        implField.set(null, ctor.newInstance())
    }

    private fun readImpl(): Any? {
        val implField = InetAddress::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        return implField.get(null)
    }
}
