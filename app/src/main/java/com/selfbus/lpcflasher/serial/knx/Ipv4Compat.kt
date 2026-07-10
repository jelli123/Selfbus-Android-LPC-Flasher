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
     * Replace libcore's cached IPv6 "any-local" wildcard with the IPv4 wildcard
     * `0.0.0.0`.
     *
     * On Android 17 the impl is `java.net.Inet6AddressImpl` and its
     * `anyLocalAddress()` method caches the wildcard *lazily* in a field. If we
     * patch fields before that method has ever run, the lazy initialiser simply
     * recreates the IPv6 wildcard afterwards (which is exactly what happened in
     * earlier attempts). Therefore we:
     *   1. invoke `anyLocalAddress()` once to force the lazy initialisation, then
     *   2. overwrite every field (instance or static, across the hierarchy) that
     *      now actually holds a non-IPv4 any-local address with the IPv4 wildcard.
     * We match by value, not by name, so the field-layout changes across Android
     * versions don't matter. All discovered fields are logged for diagnostics.
     */
    private fun patchAnyLocalAddress(ipv4Any: Inet4Address) {
        val impl = readImpl()
        if (impl == null) {
            Log.w(TAG, "InetAddress.impl not found")
            return
        }
        Log.i(TAG, "impl class = ${impl.javaClass.name}")

        // 1) Force lazy initialisation of the cached wildcard address.
        runCatching {
            val m = impl.javaClass.getMethod("anyLocalAddress")
            m.isAccessible = true
            val v = m.invoke(impl) as? InetAddress
            Log.i(TAG, "anyLocalAddress() before patch = ${v?.hostAddress} (${v?.javaClass?.simpleName})")
        }.onFailure { Log.i(TAG, "anyLocalAddress() invoke skipped: ${it.message}") }

        // 2) Overwrite every field that currently holds a non-IPv4 any-local addr.
        patchWildcardFields(impl.javaClass, impl, ipv4Any, "impl")        // instance fields
        patchWildcardFields(impl.javaClass, null, ipv4Any, "impl-static") // static fields
        patchWildcardFields(InetAddress::class.java, null, ipv4Any, "InetAddress")

        // 3) Verify by invoking the method again.
        runCatching {
            val m = impl.javaClass.getMethod("anyLocalAddress")
            m.isAccessible = true
            val v = m.invoke(impl) as? InetAddress
            Log.i(TAG, "anyLocalAddress() after patch = ${v?.hostAddress} (${v?.javaClass?.simpleName})")
        }
    }

    /**
     * Overwrite every field of [owner] (walking the hierarchy) that currently
     * holds a non-IPv4 any-local address with [ipv4Any]. Matches strictly by
     * value, so only the IPv6 wildcard is replaced; loopback / DNS stay intact.
     */
    private fun patchWildcardFields(
        owner: Class<*>,
        instance: Any?,
        ipv4Any: Inet4Address,
        label: String,
    ) {
        var cls: Class<*>? = owner
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!field.type.isAssignableFrom(Inet4Address::class.java)) continue
                val isStatic = java.lang.reflect.Modifier.isStatic(field.modifiers)
                if ((instance == null) != isStatic) continue
                runCatching {
                    field.isAccessible = true
                    val current = field.get(instance) as? InetAddress
                    if (current != null && current !is Inet4Address && current.isAnyLocalAddress) {
                        field.set(instance, ipv4Any)
                        Log.i(TAG, "$label: patched '${field.name}' (${current.hostAddress}) -> 0.0.0.0")
                    } else if (current != null) {
                        Log.i(TAG, "$label: '${field.name}' = ${current.hostAddress}")
                    }
                }.onFailure { Log.i(TAG, "$label: '${field.name}' skip (${it.message})") }
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
