package dev.vecflash.ble

/**
 * Framing multiparte de Vector sobre BLE.
 *
 * Cada paquete = 1 byte de cabecera + payload.
 *   bits 7..6 -> estado multiparte
 *   bits 5..0 -> tamano del payload
 * El tamano maximo de paquete es 20 bytes, o sea 19 de payload.
 */
object BleFraming {
    const val MAX_PACKET = 20

    const val MSG_CONTINUE = 0b00
    const val MSG_END = 0b01
    const val MSG_START = 0b10
    const val MSG_SOLO = 0b11

    fun headerByte(multipart: Int, size: Int): Byte =
        (((multipart shl 6) or (size and 0x3F)) and 0xFF).toByte()

    fun multipartOf(header: Byte): Int = (header.toInt() and 0xFF) shr 6
    fun sizeOf(header: Byte): Int = header.toInt() and 0x3F

    /** Parte un mensaje completo en paquetes listos para escribir en la caracteristica. */
    fun split(msg: ByteArray, maxSize: Int = MAX_PACKET): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        if (msg.size < maxSize) {
            packets.add(packet(MSG_SOLO, msg, 0, msg.size))
            return packets
        }
        var remaining = msg.size
        while (remaining > 0) {
            val offset = msg.size - remaining
            when {
                remaining == msg.size -> {
                    val n = maxSize - 1
                    packets.add(packet(MSG_START, msg, offset, n))
                    remaining -= n
                }
                remaining < maxSize -> {
                    packets.add(packet(MSG_END, msg, offset, remaining))
                    remaining = 0
                }
                else -> {
                    val n = maxSize - 1
                    packets.add(packet(MSG_CONTINUE, msg, offset, n))
                    remaining -= n
                }
            }
        }
        return packets
    }

    private fun packet(multipart: Int, src: ByteArray, offset: Int, len: Int): ByteArray {
        val p = ByteArray(len + 1)
        p[0] = headerByte(multipart, len)
        System.arraycopy(src, offset, p, 1, len)
        return p
    }
}

/**
 * Reensambla paquetes entrantes en mensajes completos.
 *
 * Informa de cada descarte: un solo paquete perdido en mitad de una rafaga larga
 * (el escaneo Wi-Fi son 25+ paquetes) tira el mensaje entero, y sin traza es
 * imposible saber que paso.
 */
class BleAssembler(
    private val onMessage: (ByteArray) -> Unit,
    private val onDiagnostic: ((String) -> Unit)? = null
) {

    private var expecting = BleFraming.MSG_START
    private var buffer = java.io.ByteArrayOutputStream()
    private var packetsInMessage = 0

    fun reset() {
        expecting = BleFraming.MSG_START
        buffer = java.io.ByteArrayOutputStream()
        packetsInMessage = 0
    }

    fun receive(raw: ByteArray) {
        if (raw.isEmpty()) return

        val declared = BleFraming.sizeOf(raw[0])
        if (declared != raw.size - 1) {
            onDiagnostic?.invoke("ble drop: dice $declared bytes pero trae ${raw.size - 1}")
            reset()
            return
        }

        when (BleFraming.multipartOf(raw[0])) {
            BleFraming.MSG_SOLO -> {
                if (expecting == BleFraming.MSG_CONTINUE) {
                    onDiagnostic?.invoke("ble drop: llego SOLO con un mensaje a medias")
                }
                reset()
                onMessage(raw.copyOfRange(1, raw.size))
            }

            BleFraming.MSG_START -> {
                if (expecting == BleFraming.MSG_CONTINUE) {
                    onDiagnostic?.invoke("ble drop: llego START con un mensaje a medias")
                }
                buffer = java.io.ByteArrayOutputStream()
                buffer.write(raw, 1, raw.size - 1)
                packetsInMessage = 1
                expecting = BleFraming.MSG_CONTINUE
            }

            BleFraming.MSG_CONTINUE -> {
                if (expecting != BleFraming.MSG_CONTINUE) {
                    onDiagnostic?.invoke("ble drop: CONTINUE sin START previo")
                    reset()
                    return
                }
                buffer.write(raw, 1, raw.size - 1)
                packetsInMessage++
            }

            BleFraming.MSG_END -> {
                if (expecting != BleFraming.MSG_CONTINUE) {
                    onDiagnostic?.invoke("ble drop: END sin START previo")
                    reset()
                    return
                }
                buffer.write(raw, 1, raw.size - 1)
                packetsInMessage++
                val msg = buffer.toByteArray()
                val n = packetsInMessage
                reset()
                onDiagnostic?.invoke("ble rx ${msg.size}B en $n paquetes")
                onMessage(msg)
            }
        }
    }
}
