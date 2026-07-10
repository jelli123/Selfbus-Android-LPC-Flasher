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

        Log.i(TAG, "Android SDK ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")

        if (wildcardIsIpv4()) {
            Log.i(TAG, "IPv4 wildcard already active")
            return
        }

        // Lift hidden-API restrictions for this process. An empty prefix exempts
        // every hidden API, so the java.net reflection below is permitted.
        val exempted = runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .getOrDefault(false)
        if (!exempted) {
            Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions returned false")
        }
        // Belt-and-suspenders: once the bypass lifted the restriction, the direct
        // VMRuntime call also works and covers cases the library return value lied about.
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val vmRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").invoke(null)
            vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(vmRuntime, arrayOf("L"))
        }

        val ipv4Any = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) as? Inet4Address
        if (ipv4Any == null) {
            Log.w(TAG, "could not create IPv4 wildcard address")
            return
        }

        // Adaptively replace any cached wildcard address with the IPv4 wildcard.
        runCatching { patchAnyLocalAddress(ipv4Any) }
            .onFailure { Log.w(TAG, "patchAnyLocalAddress failed: ${it.message}") }

        // Most robust fallback: wrap InetAddress.impl in a proxy whose
        // anyLocalAddress() returns IPv4. Depends only on the stable interface
        // method name, not on the field layout that changed in Android 17.
        if (!wildcardIsIpv4()) {
            runCatching { installIpv4ImplProxy(ipv4Any) }
                .onFailure { Log.w(TAG, "installIpv4ImplProxy failed: ${it.message}") }
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
     * Adaptively replace any cached "any-local" wildcard address with the IPv4
     * wildcard `0.0.0.0`. The exact field/class names of libcore's InetAddress
     * internals differ between Android versions (Android 17 dropped
     * `java.net.Inet4AddressImpl`), so instead of hard-coding names we scan:
     *   1. the current `InetAddressImpl` instance's InetAddress-typed fields, and
     *   2. the static InetAddress-typed fields of `java.net.InetAddress` itself,
     * and overwrite any that are null-and-wildcard-named or hold a non-IPv4
     * any-local address. Only wildcard entries are touched; loopback / DNS stay
     * intact. All discovered fields are logged for diagnostics.
     */
    private fun patchAnyLocalAddress(ipv4Any: Inet4Address) {
        // 1) Patch fields on the impl instance.
        val impl = readImpl()
        if (impl != null) {
            Log.i(TAG, "impl class = ${impl.javaClass.name}")
            patchInetAddressFields(impl.javaClass, impl, ipv4Any, "impl")
        } else {
            Log.w(TAG, "InetAddress.impl not found")
        }

        // 2) Patch static fields directly on InetAddress (some ports cache here).
        patchInetAddressFields(InetAddress::class.java, null, ipv4Any, "InetAddress")

        // 3) Patch static fields on the impl's own class (cached any-local).
        if (impl != null) {
            patchInetAddressFields(impl.javaClass, null, ipv4Any, "impl-static")
        }
    }

    /**
     * For every declared field of [owner] that can hold an [Inet4Address],
     * overwrite it with [ipv4Any] when it is currently null (and looks like a
     * wildcard field) or holds a non-IPv4 any-local address.
     */
    private fun patchInetAddressFields(
        owner: Class<*>,
        instance: Any?,
        ipv4Any: Inet4Address,
        label: String,
    ) {
        // Walk the class hierarchy: the wildcard field may be declared in a superclass.
        var cls: Class<*>? = owner
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                // Only fields that can actually store an Inet4Address.
                if (!field.type.isAssignableFrom(Inet4Address::class.java)) continue
                val isStatic = java.lang.reflect.Modifier.isStatic(field.modifiers)
                if ((instance == null) != isStatic) continue
                runCatching {
                    field.isAccessible = true
                    val current = field.get(instance) as? InetAddress
                    val nameHintsWildcard = field.name.contains("any", true) ||
                        field.name.contains("wildcard", true) || field.name.contains("unspecified", true)
                    val shouldPatch = when {
                        current == null -> nameHintsWildcard
                        current is Inet4Address -> false
                        current.isAnyLocalAddress -> true
                        else -> false
                    }
                    if (shouldPatch) {
                        field.set(instance, ipv4Any)
                        Log.i(TAG, "$label: patched field '${field.name}' (${field.type.simpleName}) -> 0.0.0.0")
                    } else if (current != null) {
                        Log.i(TAG, "$label: field '${field.name}' = ${current.hostAddress}")
                    }
                }.onFailure { Log.i(TAG, "$label: field '${field.name}' skip (${it.message})") }
            }
            cls = cls.superclass
        }
    }

    private fun readImpl(): Any? {
        // Prefer the well-known "impl" field, otherwise scan for an *AddressImpl.
        runCatching {
            val implField = InetAddress::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            return implField.get(null)
        }
        for (field in InetAddress::class.java.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                val value = field.get(null)
                if (value != null && value.javaClass.simpleName.contains("AddressImpl", true)) {
                    return value
                }
            }
        }
        return null
    }

    /**
     * Replace the static `InetAddress.impl` with a dynamic proxy that delegates
     * every call to the real implementation except `anyLocalAddress()`, which
     * returns the IPv4 wildcard. This only relies on the (stable) package-private
     * `java.net.InetAddressImpl` interface and its `anyLocalAddress` method name,
     * so it survives the field-layout changes introduced in Android 17.
     */
    private fun installIpv4ImplProxy(ipv4Any: Inet4Address) {
        val implField = InetAddress::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        val realImpl = implField.get(null) ?: run {
            Log.w(TAG, "cannot proxy: InetAddress.impl is null")
            return
        }
        val implInterface = realImpl.javaClass.interfaces.firstOrNull {
            it.name.contains("InetAddressImpl")
        }
        if (implInterface == null) {
            Log.w(TAG, "cannot proxy: InetAddressImpl interface not found on ${realImpl.javaClass.name}")
            return
        }
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            implInterface.classLoader,
            arrayOf(implInterface),
        ) { _, method, args ->
            if (method.name == "anyLocalAddress" && method.parameterTypes.isEmpty()) {
                ipv4Any
            } else {
                @Suppress("SpreadOperator")
                if (args == null) method.invoke(realImpl) else method.invoke(realImpl, *args)
            }
        }
        implField.set(null, proxy)
        Log.i(TAG, "installed IPv4 InetAddressImpl proxy for ${implInterface.name}")
    }
}
