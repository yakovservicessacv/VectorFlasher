package dev.vecflash

import dev.vecflash.data.OtaDownloader
import dev.vecflash.data.OtaIndex
import dev.vecflash.data.OtaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(name: String, size: Long, stack: String = "prod") =
        OtaIndex.Entry(stack, name, "http://x/$name", size, 1L)

    private fun writeFile(dir: File, localName: String, size: Int): File {
        val f = File(dir, localName)
        f.writeBytes(ByteArray(size))
        return f
    }

    @Test
    fun `el indice sobrevive el viaje de ida y vuelta`() {
        val entries = listOf(
            entry("2.0.1.6093.ota", 184_381_440L),
            entry("WireOS", 161_832_960L, "custom firmware")
        )
        val decoded = OtaIndex.decode(OtaIndex.encode(entries))
        assertEquals(2, decoded.size)
        assertEquals("2.0.1.6093.ota", decoded[0].fileName)
        assertEquals(184_381_440L, decoded[0].size)
        assertEquals("custom firmware", decoded[1].stackId)
    }

    @Test
    fun `un indice corrupto o vacio no revienta`() {
        assertEquals(0, OtaIndex.decode(null).size)
        assertEquals(0, OtaIndex.decode("").size)
        assertEquals(0, OtaIndex.decode("{no es json").size)
        assertEquals(0, OtaIndex.decode("[{\"sin\":\"campos\"}]").size)
    }

    /**
     * Este es EL bug que se reporto: aparecia un firmware como guardado sin estarlo.
     * Lo causaba dar por bueno cualquier archivo que existiera y pesara algo, y
     * una descarga a medias cumple las dos cosas.
     */
    @Test
    fun `una descarga a medias no cuenta como guardada`() {
        val dir = tmp.newFolder("ota")
        val e = entry("2.0.1.6093.ota", 184_381_440L)
        // el archivo existe y pesa algo, pero le falta casi todo
        writeFile(dir, e.localName, 4096)

        assertEquals(0, OtaIndex.validate(listOf(e), dir).size)
    }

    @Test
    fun `solo cuenta cuando el tamano cuadra exactamente`() {
        val dir = tmp.newFolder("ota")
        val e = entry("chico.ota", 1024L)
        writeFile(dir, e.localName, 1024)

        assertEquals(1, OtaIndex.validate(listOf(e), dir).size)

        // un byte de mas tampoco vale
        writeFile(dir, e.localName, 1025)
        assertEquals(0, OtaIndex.validate(listOf(e), dir).size)
    }

    @Test
    fun `si el archivo desaparece la entrada se cae`() {
        val dir = tmp.newFolder("ota")
        val e = entry("fantasma.ota", 100L)
        assertEquals(0, OtaIndex.validate(listOf(e), dir).size)
    }

    @Test
    fun `un part nunca se confunde con el archivo final`() {
        val dir = tmp.newFolder("ota")
        val e = entry("bajando.ota", 5000L)
        // solo existe el temporal, con el peso final por casualidad
        writeFile(dir, e.localName + OtaStorage.PART_SUFFIX, 5000)

        assertEquals(0, OtaIndex.validate(listOf(e), dir).size)
    }

    @Test
    fun `anadir la misma entrada la reemplaza en vez de duplicarla`() {
        val a = entry("WireOS", 100L, "custom firmware")
        val b = entry("WireOS", 200L, "custom firmware")
        val list = OtaIndex.with(OtaIndex.with(emptyList(), a), b)
        assertEquals(1, list.size)
        assertEquals(200L, list[0].size)
    }

    @Test
    fun `el mismo archivo en dos stacks son entradas distintas`() {
        val a = entry("1.6-rebuild", 100L, "custom firmware")
        val b = entry("1.6-rebuild", 100L, "oskr custom firmware")
        val list = OtaIndex.with(OtaIndex.with(emptyList(), a), b)
        assertEquals(2, list.size)
        assertTrue(list[0].localName != list[1].localName)
    }

    @Test
    fun `quitar solo borra el del stack indicado`() {
        val a = entry("1.6-rebuild", 100L, "custom firmware")
        val b = entry("1.6-rebuild", 100L, "oskr custom firmware")
        val list = OtaIndex.without(listOf(a, b), "custom firmware", "1.6-rebuild")
        assertEquals(1, list.size)
        assertEquals("oskr custom firmware", list[0].stackId)
    }

    @Test
    fun `validate limpia lo invalido y conserva lo bueno`() {
        val dir = tmp.newFolder("ota")
        val bueno = entry("bueno.ota", 2048L)
        val medias = entry("medias.ota", 900_000L)
        val ausente = entry("ausente.ota", 300L)
        writeFile(dir, bueno.localName, 2048)
        writeFile(dir, medias.localName, 1234)

        val valid = OtaIndex.validate(listOf(bueno, medias, ausente), dir)
        assertEquals(1, valid.size)
        assertEquals("bueno.ota", valid[0].fileName)
    }

    /**
     * Regresion: "Instalar desde el celular" pasaba el nombre local ya prefijado, y
     * fileFor() le volvia a anteponer el stack. La ruta quedaba con el prefijo dos
     * veces, el archivo no aparecia y la app decia "no hay nada guardado" teniendo
     * tres. El prefijo se aplica UNA sola vez, en fileFor.
     */
    @Test
    fun `el prefijo del stack no se puede aplicar dos veces`() {
        val once = OtaStorage.localName("custom firmware", "Viccyware")
        assertEquals("custom-firmware__Viccyware", once)

        val twice = OtaStorage.localName("custom firmware", once)
        assertNotEquals(once, twice)
        assertEquals("custom-firmware__custom-firmware__Viccyware", twice)
    }

    // ---------- validacion del formato .ota ----------

    /** Un .ota es un tar cuyo primer miembro se llama manifest.ini. */
    private fun fakeOta(dir: File, name: String, size: Int = 1024): File {
        val f = File(dir, name)
        val body = ByteArray(size)
        val sig = "manifest.ini".toByteArray(Charsets.US_ASCII)
        sig.copyInto(body)
        f.writeBytes(body)
        return f
    }

    @Test
    fun `un ota de verdad se reconoce`() {
        val dir = tmp.newFolder("ota")
        assertTrue(OtaDownloader.looksLikeVectorOta(fakeOta(dir, "bueno.ota")))
    }

    /** Cuando un espejo se cae, lo que se descarga es una pagina de error. */
    @Test
    fun `una pagina de error HTML no cuela como ota`() {
        val dir = tmp.newFolder("ota")
        val f = File(dir, "malo.ota")
        f.writeBytes(("<html><head><title>404 Not Found</title></head><body>" +
            "nginx".repeat(200) + "</body></html>").toByteArray())
        assertTrue(!OtaDownloader.looksLikeVectorOta(f))
    }

    @Test
    fun `un archivo cortado no cuela`() {
        val dir = tmp.newFolder("ota")
        val f = File(dir, "corto.ota")
        f.writeBytes("manifest.ini".toByteArray(Charsets.US_ASCII))
        assertTrue("menos de 512 bytes no puede ser un tar", !OtaDownloader.looksLikeVectorOta(f))
    }

    @Test
    fun `un nombre parecido pero distinto no cuela`() {
        val dir = tmp.newFolder("ota")
        val f = File(dir, "casi.ota")
        val body = ByteArray(1024)
        "manifest.txt".toByteArray(Charsets.US_ASCII).copyInto(body)
        f.writeBytes(body)
        assertTrue(!OtaDownloader.looksLikeVectorOta(f))
    }

    @Test
    fun `hace falta el relleno a cero tras el nombre`() {
        val dir = tmp.newFolder("ota")
        val f = File(dir, "sinnul.ota")
        val body = ByteArray(1024) { 65 }
        "manifest.ini".toByteArray(Charsets.US_ASCII).copyInto(body)
        // el byte 12 es 'A', no un cero: el campo de nombre del tar no acaba ahi
        assertTrue(!OtaDownloader.looksLikeVectorOta(f))
    }

    @Test
    fun `un archivo que no existe no cuela`() {
        val dir = tmp.newFolder("ota")
        assertTrue(!OtaDownloader.looksLikeVectorOta(File(dir, "no-esta.ota")))
    }
}
