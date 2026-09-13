package dev.vecflash.rts

import java.io.ByteArrayOutputStream

/**
 * Serializacion CLAD (little-endian) tal como la usa el protocolo RTS de Vector.
 */
class CladWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun bool(v: Boolean) = apply { u8(if (v) 1 else 0) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Long) = apply { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }
    fun raw(b: ByteArray) = apply { out.write(b, 0, b.size) }

    /** Array de bytes precedido por una longitud uint8. */
    fun stringU8(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 255) { "cadena demasiado larga para CLAD: ${b.size}" }
        u8(b.size); raw(b)
    }

    /** Array de bytes precedido por una longitud uint16 (little-endian). */
    fun stringU16(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 65535) { "cadena demasiado larga para CLAD: ${b.size}" }
        u16(b.size); raw(b)
    }

    fun bytesU8(b: ByteArray) = apply {
        require(b.size <= 255) { "array demasiado largo para CLAD: ${b.size}" }
        u8(b.size); raw(b)
    }

    fun build(): ByteArray = out.toByteArray()
}

class CladReader(private val buf: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        check(pos < buf.size) { "CLAD: lectura fuera de rango" }
        return buf[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = u8() != 0

    fun u16(): Int {
        val a = u8(); val b = u8()
        return a or (b shl 8)
    }

    fun u32(): Long {
        val a = u8().toLong(); val b = u8().toLong(); val c = u8().toLong(); val d = u8().toLong()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    fun u64(): Long {
        var r = 0L
        for (i in 0 until 8) r = r or (u8().toLong() shl (8 * i))
        return r
    }

    fun raw(n: Int): ByteArray {
        check(pos + n <= buf.size) { "CLAD: lectura fuera de rango ($n bytes, quedan $remaining)" }
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun stringU8(): String = String(raw(u8()), Charsets.UTF_8)
    fun stringU16(): String = String(raw(u16()), Charsets.UTF_8)
    fun bytesU8(): ByteArray = raw(u8())
    fun rest(): ByteArray = raw(remaining)
}
