package com.vanker.vrefresh

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import dalvik.system.DexClassLoader
import java.io.File

/**
 * VRefresh's independent IPC client for OPlus screen-mode requests.
 * It discovers the named transaction constant from the device's own ROM.
 * No guessed transaction indices are sent to vendor services.
 * A successful binder reply is not proof of the actual display mode.
 */
internal class OplusScreenModeBridge(private val context: Context) {
    data class Outcome(val accepted: Boolean, val detail: String)
    private val expectedDescriptor = "com.oplus.screenmode.IOplusScreenMode"
    private val stubClassName = "com.oplus.screenmode.IOplusScreenMode\$Stub"
    private val requestTransactionName = "TRANSACTION_requestGameRefreshRate"

    private fun vendorService(): IBinder {
        val manager = Class.forName("android.os.ServiceManager")
        val method = manager.getDeclaredMethod("getService", String::class.java)
        return method.invoke(null, "oplusscreenmode") as? IBinder
            ?: error("oplusscreenmode service was not available")
    }

    // Class loading of the device's own ROM JAR is best-effort; it may be blocked.
    // We never ship, patch, or copy the vendor JAR into our APK.
    private fun findTransactionCode(): Int {
        val loaders = mutableListOf<ClassLoader?>()
        loaders.add(context.classLoader)
        loaders.add(ClassLoader.getSystemClassLoader())
        val candidates = listOf(
            "/system/framework/oplus-framework.jar",
            "/system_ext/framework/oplus-framework.jar",
            "/product/framework/oplus-framework.jar"
        )
        for (path in candidates) {
            if (File(path).isFile && File(path).canRead()) {
                try {
                    loaders.add(DexClassLoader(path, context.codeCacheDir.absolutePath, null, context.classLoader))
                } catch (_: Throwable) { /* ROM may forbid dynamic access to its classes */ }
            }
        }
        for (loader in loaders) {
            try {
                val stub = Class.forName(stubClassName, false, loader)
                val field = stub.getDeclaredField(requestTransactionName)
                if (!field.isAccessible) field.isAccessible = true
                val code = field.getInt(null)
                if (code in 1..255) return code
            } catch (_: Throwable) { /* Try the next ROM-provided class loader */ }
        }
        error("Firmware transaction not discoverable; no vendor call was attempted")
    }

    private val connection: Pair<IBinder, Int> by lazy { resolveConnection() }
    private var transactionSource = "unresolved"

    private fun verifiedConnection(): Pair<IBinder, Int> = connection

    private fun resolveConnection(): Pair<IBinder, Int> {
        val binder = vendorService()
        val descriptor = binder.interfaceDescriptor
        require(descriptor == expectedDescriptor) { "Unexpected interface: $descriptor" }
        val dex = OplusFirmwareDex.discover()
        val transaction = if (dex != null) {
            transactionSource = "firmware DEX: ${dex.origin}"
            dex.code
        } else {
            transactionSource = "firmware Java Stub reflection"
            findTransactionCode()
        }
        return binder to transaction
    }

    fun probe(): Outcome = try {
        val (_, code) = verifiedConnection()
        Outcome(true, "OPlus interface ready; code=$code; source=$transactionSource")
    } catch (e: Throwable) {
        Outcome(false, "OPlus unavailable: ${e.javaClass.simpleName}: ${e.message.orEmpty()} ; descriptor=$expectedDescriptor. Vendor classes or hidden Android service access may be restricted on this firmware.")
    }

    fun request(packageName: String, hz: Int): Outcome {
        val rateId = when (hz) {
            0 -> 0       // Withdraw this package's request (firmware-specific; check reply)
            60 -> 2
            90 -> 1
            120 -> 3
            144 -> 4
            165 -> 7
            185 -> 8
            else -> return Outcome(false, "No vendor rate id for $hz Hz")
        }
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            return Outcome(false, "Invalid Android package name")
        }
        return try {
            val (service, tx) = verifiedConnection()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(expectedDescriptor)
                data.writeString(packageName)
                data.writeInt(rateId)
                if (!service.transact(tx, data, reply, 0)) {
                    return Outcome(false, "Vendor did not accept Binder transaction $tx")
                }
                reply.readException()
                if (reply.dataAvail() < 4) return Outcome(false, "Vendor returned no confirmation")
                val returned = reply.readInt() != 0
                if (returned) Outcome(true, "OPlus confirmed request for $packageName ($hz Hz)")
                else Outcome(false, "OPlus rejected rateId $rateId for $packageName")
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Throwable) {
            Outcome(false, "OPlus request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
    }

    /**
     * This vendor API uses a SET operation, not a toggle. A repeat of the same rate
     * keeps the pin; rateId=0 withdraws the selected package's vote on the
     * ROMs covered by the published protocol research. The vendor may still deny it.
     * Only call for package names previously recorded by VRefresh.
     */
    fun release(packageName: String): Outcome = request(packageName, 0)
}
