package com.vanker.vrefresh

import android.os.IBinder
import android.os.Parcel

/**
 * Experimental adapter for the undocumented vivo RMS binder protocol.
 * Independently implemented from publicly documented transaction field layout.
 * May fail on Android builds that block ServiceManager reflection or change the vendor protocol.
 * Do NOT use on other manufacturers.
 * Protocol research credit: AndroPlus-org/vivo-HighRefresh-NoRoot (MIT).
 */
internal class VivoRmsBridge {
    companion object {
        // Dedicated handle; does not collide with our earlier shell-test handle (900001).
        private const val HANDLE = 981734021L
        private const val TX_VOTE_V2 = 31
        private const val TX_RELEASE = 24
        private const val TX_GET = 26
        private const val CALLER = 0
    }

    private fun binder(): IBinder {
        val clazz = Class.forName("android.os.ServiceManager")
        val result = try {
            clazz.getDeclaredMethod("getService", String::class.java).invoke(null, "rms") as? IBinder
        } catch (_: ReflectiveOperationException) {
            clazz.getDeclaredMethod("checkService", String::class.java).invoke(null, "rms") as? IBinder
        }
        return result ?: throw IllegalStateException("RMS Binder is not available")
    }

    /** A successful transact means the request was sent; verify the observed Hz separately. */
    fun request(rate: Int): Boolean {
        require(rate in 60..300)
        val payload = Parcel.obtain()
        try {
            payload.writeLong(HANDLE)
            payload.writeString("FixRate")
            payload.writeString("VRefresh")
            payload.writeInt(-1)  // all displays
            payload.writeInt(0)   // resolution
            payload.writeInt(rate)
            payload.writeInt(0)   // dfps
            payload.writeInt(0)   // config bits
            payload.writeInt(0)   // states
            payload.writeInt(-1)  // priority: avoid overriding preferred dimming config
            payload.writeInt(0)   // duration
            payload.writeInt(CALLER)
            payload.writeInt(0)   // client
            payload.writeInt(14)  // private rate without overriding display config bits
            payload.writeInt(0)
            payload.writeInt(0)
            return binder().transact(TX_VOTE_V2, payload, null, IBinder.FLAG_ONEWAY)
        } finally {
            payload.recycle()
        }
    }

    fun release(): Boolean {
        val payload = Parcel.obtain()
        try {
            payload.writeInt(CALLER)
            payload.writeLong(HANDLE)
            return binder().transact(TX_RELEASE, payload, null, IBinder.FLAG_ONEWAY)
        } finally {
            payload.recycle()
        }
    }

    fun reportedRate(): Float? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!binder().transact(TX_GET, data, reply, 0)) null
            else {
                reply.readException()
                reply.readFloat().takeIf { it.isFinite() && it > 0f }
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
