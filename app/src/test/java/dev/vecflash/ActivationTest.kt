package dev.vecflash

import dev.vecflash.data.OtaStorage
import dev.vecflash.rts.CladReader
import dev.vecflash.rts.CladWriter
import dev.vecflash.rts.Rts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationTest {

    /**
     * El cliente oficial manda RtsCloudSessionRequest_5(token, "", ""): token con
     * longitud uint16 y luego dos cadenas vacias de longitud uint8.
     */
    @Test
    fun `el mensaje de activacion sigue el formato del cliente oficial`() {
        val token = "abc123"
        val env = Rts.cloudSessionRequest(5, token)

        assertEquals(0x04, env[0].toInt())
        assertEquals(5, env[1].toInt())
        assertEquals(Rts.TAG_CLOUD_SESSION_REQUEST, env[2].toInt())

        val r = CladReader(env, 3)
        assertEquals(token, r.stringU16())
        assertEquals("", r.stringU8())
        assertEquals("", r.stringU8())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `la longitud del token va en dos bytes little endian`() {
        val token = "x".repeat(300)
        val env = Rts.cloudSessionRequest(6, token)
        // tras el sobre de 3 bytes: 300 = 0x012C -> 2C 01
        assertEquals(0x2C, env[3].toInt() and 0xFF)
        assertEquals(0x01, env[4].toInt() and 0xFF)
        assertEquals(token, CladReader(env, 3).stringU16())
    }

    @Test
    fun `se parsea la respuesta de activacion`() {
        val payload = CladWriter().bool(true).u8(0).stringU16("guid-1234").build()
        val msg = Rts.parse(Rts.envelope(5, Rts.TAG_CLOUD_SESSION_RESPONSE, payload))
        assertTrue(msg is Rts.Incoming.CloudSession)
        val cs = msg as Rts.Incoming.CloudSession
        assertTrue(cs.success)
        assertEquals(0, cs.statusCode)
        assertEquals("guid-1234", cs.clientTokenGuid)
    }

    @Test
    fun `una activacion fallida se lee como tal`() {
        val payload = CladWriter().bool(false).u8(7).stringU16("").build()
        val cs = Rts.parse(Rts.envelope(5, Rts.TAG_CLOUD_SESSION_RESPONSE, payload))
                as Rts.Incoming.CloudSession
        assertTrue(!cs.success)
        assertEquals(7, cs.statusCode)
    }

    /**
     * El mismo nombre de archivo existe en varios stacks (1.6-rebuild esta en dos),
     * asi que el nombre local tiene que distinguirlos o se pisan al descargar.
     */
    @Test
    fun `el nombre local no colisiona entre stacks`() {
        val a = OtaStorage.localName("custom firmware", "1.6-rebuild")
        val b = OtaStorage.localName("oskr custom firmware", "1.6-rebuild")
        assertNotEquals(a, b)
        assertEquals("custom-firmware__1.6-rebuild", a)
    }

    @Test
    fun `los guiones del id del stack sobreviven al nombre local`() {
        // "escape-pod" ya lleva guion: no se puede reconstruir el id invirtiendo
        // la sustitucion, por eso el stack se guarda aparte y no se deduce del nombre.
        assertEquals("escape-pod__2.0.1.6076ep.ota",
            OtaStorage.localName("escape-pod", "2.0.1.6076ep.ota"))
        assertEquals("modified-escape-pod__2.0.1.6085ep.ota",
            OtaStorage.localName("modified escape-pod", "2.0.1.6085ep.ota"))
    }
}
