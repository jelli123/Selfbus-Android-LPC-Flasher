package com.selfbus.lpcflasher.serial.knx

import android.util.Log
import java.lang.reflect.Modifier
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
 * choice of IPv4/IPv6 wildcard is made natively via `isIPv6Supported()`), so the
 * only reliable fix is to force `InetAddress.anyLocalAddress()` to return the
 * IPv4 wildcard `0.0.0.0` via reflection, *before* calimero's HPAI class is
 * loaded.
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
        // libcore's java.net.* internals are hidden API on Android 9+; lift the
        // restriction for this process so the reflection below is allowed.
        relaxHiddenApiChecks()

        val ipv4Any = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) as? Inet4Address
        if (ipv4Any == null) {
            Log.w(TAG, "could not create IPv4 wildcard address")
            return
        }

        // Preferred fix: replace the cached wildcard address held by the current
        // InetAddressImpl instance so any future new InetSocketAddress(0) is IPv4.
        val patchedImpl = runCatching { patchImplWildcard(ipv4Any) }.getOrDefault(false)
        if (patchedImpl) {
            Log.i(TAG, "patched InetAddressImpl wildcard to IPv4")
        }

        // Verify; if the wildcard is still IPv6, try swapping the whole impl.
        if (!wildcardIsIpv4()) {
            val swapped = runCatching { swapImplToIpv4() }.getOrDefault(false)
            if (swapped) Log.i(TAG, "swapped InetAddress.impl to Inet4AddressImpl")
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

    /** Overwrite the cached `anyLocalAddress` field on the current InetAddressImpl. */
    private fun patchImplWildcard(ipv4Any: Inet4Address): Boolean {
        val impl = readImpl() ?: return false
        var patched = false
        for (field in impl.javaClass.declaredFields) {
            if (!InetAddress::class.java.isAssignableFrom(field.type)) continue
            runCatching {
                field.isAccessible = true
                val current = field.get(impl) as? InetAddress
                // Only replace wildcard / IPv6 entries, leave IPv4 ones untouched.
                if (current == null || current !is Inet4Address) {
                    field.set(impl, ipv4Any)
                    patched = true
                }
            }
        }
        return patched
    }

    /** Replace the whole `InetAddress.impl` with a fresh Inet4AddressImpl. */
    private fun swapImplToIpv4(): Boolean {
        val implField = InetAddress::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        // Drop the `final` modifier if present (ART generally allows this).
        runCatching {
            val modifiers = java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
            modifiers.isAccessible = true
            modifiers.setInt(implField, implField.modifiers and Modifier.FINAL.inv())
        }
        val inet4ImplClass = Class.forName("java.net.Inet4AddressImpl")
        val ctor = inet4ImplClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val inet4Impl = ctor.newInstance()
        implField.set(null, inet4Impl)
        return true
    }

    private fun readImpl(): Any? {
        val implField = InetAddress::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        return implField.get(null)
    }

    /**
     * Lift Android's hidden-API restrictions for this process so reflection on
     * `java.net` internals is permitted. No-op on Android < 9 or if unavailable.
     */
    private fun relaxHiddenApiChecks() {
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val vmRuntime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java
            )
            setExemptions.isAccessible = true
            setExemptions.invoke(vmRuntime, arrayOf("L"))
        }
    }
}
