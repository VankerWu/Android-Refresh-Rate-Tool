package com.vanker.vrefresh

import java.io.File
import java.util.zip.ZipFile

/**
 * Independent, read-only inspector for Android DEX files already installed on this phone.
 * Locates the exact AIDL Stub transaction constant. No vendor binary is copied, bundled,
 * loaded into our process or changed. Never guesses a Binder transaction number.
 */
internal object OplusFirmwareDex {
    data class Discovery(val code: Int, val origin: String)
    private const val stubDescriptor = "Lcom/oplus/screenmode/IOplusScreenMode\u0024Stub;"
    private const val fieldName = "TRANSACTION_requestGameRefreshRate"

    fun discover(): Discovery? {
        val candidates = listOf(
            "/system/framework/oplus-framework.jar",
            "/system_ext/framework/oplus-framework.jar",
            "/product/framework/oplus-framework.jar",
            "/system/framework/oplus-services.jar",
            "/system_ext/framework/oplus-services.jar"
        )
        for (path in candidates) {
            val file = File(path)
            if (!file.isFile || !file.canRead()) continue
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!Regex("classes(\\d*)\\.dex").matches(entry.name)) continue
                        val buffer = zip.getInputStream(entry).use { it.readBytes() }
                        val result = parse(buffer)
                        if (result != null && result in 1..255) return Discovery(result, "$path:${entry.name}")
                    }
                }
            } catch (_: Exception) { /* Firmware may deny reading the jar. Continue safely. */ }
        }
        return null
    }

    private class Reader(val b: ByteArray) {
        fun check(offset: Int, count: Int = 1) {
            if (offset < 0 || count < 0 || offset > b.size - count) error("Invalid DEX range")
        }
        fun u8(at: Int): Int { check(at); return b[at].toInt() and 255 }
        fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
        fun u32(at: Int): Int {
            val x = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)
            if (x < 0) error("Unexpected large DEX offset")
            return x
        }
        fun uleb(start: Int): Pair<Int, Int> {
            var off = start
            var value = 0
            for (i in 0..4) {
                val v = u8(off++)
                value = value or ((v and 127) shl (7 * i))
                if (v and 128 == 0) return value to off
            }
            error("Invalid ULEB128")
        }
        fun str(start: Int): String {
            var p = uleb(start).second
            val end = p
            while (u8(p) != 0) p++
            return String(b, end, p - end, Charsets.UTF_8)
        }
    }

    /** AIDL's const int is an encoded static field value in its Stub class. */
    private fun parse(bytes: ByteArray): Int? {
        if (bytes.size < 112 || !String(bytes, 0, 4, Charsets.US_ASCII).startsWith("dex\n")) return null
        return try {
            val r = Reader(bytes)
            val stringCount = r.u32(0x38); val stringOff = r.u32(0x3c)
            val typeCount = r.u32(0x40); val typeOff = r.u32(0x44)
            val fieldCount = r.u32(0x50); val fieldOff = r.u32(0x54)
            val classCount = r.u32(0x60); val classOff = r.u32(0x64)
            if (stringCount > 2000000 || fieldCount > 2000000 || classCount > 200000) return null
            r.check(stringOff, stringCount * 4); r.check(typeOff, typeCount * 4)
            r.check(fieldOff, fieldCount * 8); r.check(classOff, classCount * 32)
            val strings = HashMap<Int, String>()
            fun name(index: Int): String {
                if (index !in 0 until stringCount) error("string index out of range")
                return strings.getOrPut(index) { r.str(r.u32(stringOff + index * 4)) }
            }
            var classType = -1
            for (t in 0 until typeCount) {
                if (name(r.u32(typeOff + t * 4)) == stubDescriptor) { classType = t; break }
            }
            if (classType < 0) return null
            var dataOff = -1; var valuesOff = -1
            for (i in 0 until classCount) {
                val off = classOff + i * 32
                if (r.u32(off) == classType) { dataOff = r.u32(off + 24); valuesOff = r.u32(off + 28); break }
            }
            if (dataOff <= 0 || valuesOff <= 0) return null
            var p = dataOff
            val (staticCount, p1) = r.uleb(p); p = p1
            repeat(3) { p = r.uleb(p).second } // instance/direct/virtual counts
            if (staticCount > 10000) return null
            var fieldIndex = 0
            var targetOrdinal = -1
            for (ordinal in 0 until staticCount) {
                val (diff, afterDiff) = r.uleb(p); p = afterDiff
                fieldIndex += diff
                p = r.uleb(p).second // access flags
                if (fieldIndex !in 0 until fieldCount) return null
                val nameId = r.u32(fieldOff + fieldIndex * 8 + 4)
                if (name(nameId) == fieldName) targetOrdinal = ordinal
            }
            if (targetOrdinal == -1) return null
            var v = valuesOff
            val (valueCount, next) = r.uleb(v); v = next
            if (targetOrdinal >= valueCount) return null
            for (ordinal in 0 until valueCount) {
                val header = r.u8(v++)
                val type = header and 31
                val size = (header ushr 5) + 1
                if (type == 0x1c || type == 0x1d) return null // AIDL const should be scalar
                if (type == 0x1e || type == 0x1f) { if (ordinal == targetOrdinal) return null; continue }
                r.check(v, size)
                if (ordinal == targetOrdinal) {
                    if (type != 0x04 || size > 4) return null // signed int
                    var number = 0
                    for (j in 0 until size) number = number or (r.u8(v + j) shl (j * 8))
                    if (size < 4 && (r.u8(v + size - 1) and 128) != 0) number = number or (-1 shl (size * 8))
                    return number
                }
                v += size
            }
            null
        } catch (_: Exception) { null }
    }
}
