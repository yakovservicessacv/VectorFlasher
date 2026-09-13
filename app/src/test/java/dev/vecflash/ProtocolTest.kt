package dev.vecflash

import dev.vecflash.ble.BleAssembler
import dev.vecflash.ble.BleFraming
import dev.vecflash.rts.CladReader
import dev.vecflash.rts.CladWriter
import dev.vecflash.rts.Rts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    // ---------- framing ----------

    @Test
    fun `mensaje corto va en un solo paquete SOLO`() {
        val msg = bytes(10)
        val packets = BleFraming.split(msg)
        assertEquals(1, packets.size)
        assertEquals(BleFraming.MSG_SOLO, BleFraming.multipartOf(packets[0][0]))
        assertEquals(10, BleFraming.sizeOf(packets[0][0]))
        assertEquals(11, packets[0].size)
    }

    @Test
    fun `19 bytes todavia caben en SOLO pero 20 ya no`() {
        assertEquals(1, BleFraming.split(bytes(19)).size)
        val p20 = BleFraming.split(bytes(20))
        assertEquals(2, p20.size)
        assertEquals(BleFraming.MSG_START, BleFraming.multipartOf(p20[0][0]))
        assertEquals(19, BleFraming.sizeOf(p20[0][0]))
        assertEquals(BleFraming.MSG_END, BleFraming.multipartOf(p20[1][0]))
        assertEquals(1, BleFraming.sizeOf(p20[1][0]))
    }

    @Test
    fun `mensaje largo se parte en START CONTINUE END`() {
        val packets = BleFraming.split(bytes(50))
        assertEquals(3, packets.size)
        assertEquals(BleFraming.MSG_START, BleFraming.multipartOf(packets[0][0]))
        assertEquals(BleFraming.MSG_CONTINUE, BleFraming.multipartOf(packets[1][0]))
        assertEquals(BleFraming.MSG_END, BleFraming.multipartOf(packets[2][0]))
        assertEquals(19, BleFraming.sizeOf(packets[0][0]))
        assertEquals(19, BleFraming.sizeOf(packets[1][0]))
        assertEquals(12, BleFraming.sizeOf(packets[2][0]))
        // Ningun paquete puede pasar de 20 bytes en el aire.
        packets.forEach { assertTrue(it.size <= BleFraming.MAX_PACKET) }
    }

    @Test
    fun `partir y reensamblar devuelve el mensaje original`() {
        for (size in intArrayOf(1, 5, 19, 20, 21, 38, 39, 40, 50, 255, 512, 1000)) {
            val original = bytes(size)
            var received: ByteArray? = null
            val assembler = BleAssembler(onMessage = { received = it })
            BleFraming.split(original).forEach { assembler.receive(it) }
            assertArrayEquals("fallo con tamano $size", original, received)
        }
    }

    @Test
    fun `un paquete con tamano mentiroso se descarta sin romper el siguiente mensaje`() {
        var received: ByteArray? = null
        val assembler = BleAssembler(onMessage = { received = it })
        // cabecera que promete 10 bytes pero solo trae 3
        assembler.receive(byteArrayOf(BleFraming.headerByte(BleFraming.MSG_SOLO, 10), 1, 2, 3))
        assertEquals(null, received)

        val good = bytes(30)
        BleFraming.split(good).forEach { assembler.receive(it) }
        assertArrayEquals(good, received)
    }

    // ---------- CLAD ----------

    @Test
    fun `enteros van en little endian`() {
        val buf = CladWriter().u32(0x01020304L).build()
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), buf)
        assertEquals(0x01020304L, CladReader(buf).u32())
    }

    @Test
    fun `u64 sobrevive el viaje de ida y vuelta`() {
        val w = CladWriter()
        for (i in 0 until 8) w.u8(0)
        val zero = CladReader(w.build()).u64()
        assertEquals(0L, zero)

        val bytes = byteArrayOf(0x10, 0x20, 0, 0, 0, 0, 0, 0)
        assertEquals(0x2010L, CladReader(bytes).u64())
    }

    @Test
    fun `cadenas llevan longitud uint8 delante`() {
        val buf = CladWriter().stringU8("hola").build()
        assertEquals(4, buf[0].toInt())
        assertEquals("hola", CladReader(buf).stringU8())
    }

    // ---------- sobres RTS ----------

    @Test
    fun `el sobre lleva 0x04 version y tag`() {
        val env = Rts.otaUpdateRequest(5, "http://x.ota")
        assertEquals(0x04, env[0].toInt())
        assertEquals(5, env[1].toInt())
        assertEquals(Rts.TAG_OTA_UPDATE_REQUEST, env[2].toInt())
        assertEquals("http://x.ota".length, env[3].toInt())
    }

    @Test
    fun `el handshake son cinco bytes`() {
        val hs = Rts.handshake(5)
        assertEquals(Rts.HANDSHAKE_SIZE, hs.size)
        assertTrue(Rts.isHandshake(hs))
        assertEquals(5, Rts.handshakeVersion(hs))
    }

    @Test
    fun `se parsea el progreso del OTA`() {
        val payload = CladWriter().u8(Rts.OTA_IN_PROGRESS).u32(1000L).u32(0L).u32(4000L).u32(0L).build()
        val msg = Rts.parse(Rts.envelope(5, Rts.TAG_OTA_UPDATE_RESPONSE, payload))
        assertTrue(msg is Rts.Incoming.OtaProgress)
        val ota = msg as Rts.Incoming.OtaProgress
        assertEquals(Rts.OTA_IN_PROGRESS, ota.status)
        assertEquals(1000L, ota.current)
        assertEquals(4000L, ota.expected)
    }

    @Test
    fun `se parsea el nonce con sus dos mitades de 24`() {
        val payload = ByteArray(48) { it.toByte() }
        val msg = Rts.parse(Rts.envelope(5, Rts.TAG_NONCE_MESSAGE, payload))
        assertTrue(msg is Rts.Incoming.Nonce)
        val nonce = msg as Rts.Incoming.Nonce
        assertEquals(24, nonce.toRobotNonce.size)
        assertEquals(24, nonce.toDeviceNonce.size)
        assertEquals(0, nonce.toRobotNonce[0].toInt())
        assertEquals(24, nonce.toDeviceNonce[0].toInt())
    }

    /**
     * Regresion: el hex iba en minusculas y Vector no reconocia la red, asi que
     * conectar al Wi-Fi no hacia absolutamente nada. El cliente oficial lo manda
     * en mayusculas.
     */
    @Test
    fun `el SSID en hexadecimal va en mayusculas`() {
        assertEquals("4D6952656420353147", Rts.strToHex("MiRed 51G"))
        assertEquals("MiRed 51G", Rts.hexToStr(Rts.strToHex("MiRed 51G")))
    }

    @Test
    fun `al leer se aceptan las dos cajas de hex`() {
        assertEquals("MiRed 51G", Rts.hexToStr("4d6952656420353147"))
        assertEquals("MiRed 51G", Rts.hexToStr("4D6952656420353147"))
    }

    /**
     * Regresion: el robot contesta status 1 nada mas recibir la peticion, antes de
     * empezar a descargar. Tratarlo como fallo mostraba un error de instalacion
     * justo antes de que el OTA arrancara de verdad.
     */
    @Test
    fun `el status 1 del OTA no es un fallo`() {
        assertTrue(Rts.OTA_IDLE == 1)
        assertTrue(Rts.OTA_IDLE != Rts.OTA_IN_PROGRESS)
        assertTrue(Rts.OTA_IDLE != Rts.OTA_COMPLETED)

        val payload = CladWriter().u8(Rts.OTA_IDLE).u32(0L).u32(0L).u32(0L).u32(0L).build()
        val msg = Rts.parse(Rts.envelope(5, Rts.TAG_OTA_UPDATE_RESPONSE, payload))
        val ota = msg as Rts.Incoming.OtaProgress
        assertEquals(Rts.OTA_IDLE, ota.status)
    }

    @Test
    fun `el SSID del WifiConnectRequest va codificado en hex`() {
        val env = Rts.wifiConnectRequest(5, "Casa", "secreto", 5, false)
        val r = CladReader(env, 3)
        val hex = r.stringU8()
        assertEquals("43617361", hex)
        assertEquals("Casa", Rts.hexToStr(hex))
        assertEquals("secreto", r.stringU8())
        assertEquals(15, r.u8())   // timeout
        assertEquals(5, r.u8())    // authType
        assertEquals(false, r.bool())
    }

    // ---------- escaneo Wi-Fi ----------

    private fun scanPayload(count: Int, withProvisioned: Boolean): ByteArray {
        val w = CladWriter().u8(0).u8(count)
        for (i in 0 until count) {
            w.u8(5)                                   // authType WPA2-PSK
            w.u8(60 + i)                              // senal
            w.stringU8(Rts.strToHex("Red$i"))
            w.bool(false)                             // hidden
            if (withProvisioned) w.bool(false)
        }
        return w.build()
    }

    @Test
    fun `el escaneo de RTS v5 trae el campo provisioned`() {
        val env = Rts.envelope(5, Rts.TAG_WIFI_SCAN_RESPONSE, scanPayload(3, true))
        val scan = Rts.parse(env) as Rts.Incoming.WifiScan
        assertEquals(3, scan.networks.size)
        assertEquals("Red0", scan.networks[0].ssid)
        assertEquals("Red2", scan.networks[2].ssid)
    }

    /**
     * Regresion: en RTS v2 el resultado NO trae "provisioned". Leerlo igualmente
     * desfasaba un byte por red y el resto de la lista salia basura.
     */
    @Test
    fun `el escaneo de RTS v2 no trae provisioned y aun asi se lee entero`() {
        val env = Rts.envelope(2, Rts.TAG_WIFI_SCAN_RESPONSE, scanPayload(3, false))
        val scan = Rts.parse(env) as Rts.Incoming.WifiScan
        assertEquals(3, scan.networks.size)
        assertEquals("Red0", scan.networks[0].ssid)
        assertEquals("Red1", scan.networks[1].ssid)
        assertEquals("Red2", scan.networks[2].ssid)
    }

    @Test
    fun `una lista de redes truncada devuelve lo que si se pudo leer`() {
        val full = scanPayload(4, true)
        // cortamos a la mitad: el robot dice 4 redes pero solo llegan unas pocas
        val truncated = full.copyOf(full.size / 2)
        val env = Rts.envelope(5, Rts.TAG_WIFI_SCAN_RESPONSE, truncated)
        val scan = Rts.parse(env) as Rts.Incoming.WifiScan
        assertTrue("deberia recuperar algunas redes", scan.networks.isNotEmpty())
        assertTrue("pero no las 4", scan.networks.size < 4)
    }

    @Test
    fun `un escaneo grande sobrevive al troceado BLE`() {
        val env = Rts.envelope(5, Rts.TAG_WIFI_SCAN_RESPONSE, scanPayload(20, true))
        var received: ByteArray? = null
        val assembler = BleAssembler(onMessage = { received = it })
        BleFraming.split(env).forEach { assembler.receive(it) }

        assertArrayEquals(env, received)
        val scan = Rts.parse(received!!) as Rts.Incoming.WifiScan
        assertEquals(20, scan.networks.size)
    }
}
