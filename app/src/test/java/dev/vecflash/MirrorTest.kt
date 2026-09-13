package dev.vecflash

import dev.vecflash.data.FirmwareCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ota.pvic.xyz no tiene API: hay que leer el autoindex HTML del directorio.
 * Este es el HTML real que devuelve, recortado.
 */
class MirrorTest {

    private val realListing = """
        <!doctype html>
        <meta name="viewport" content="width=device-width">
        <pre>
        <a href="../">../</a>
        <a href="2.1.0.1.ota">2.1.0.1.ota</a>
        <a href="2.1.0.10.ota">2.1.0.10.ota</a>
        <a href="2.1.0.16.ota">2.1.0.16.ota</a>
        <a href="cozmoware/">cozmoware/</a>
        <a href="test.txt">test.txt</a>
        </pre>
    """.trimIndent()

    @Test
    fun `saca los ota del autoindex y descarta lo demas`() {
        val names = FirmwareCatalog.parseDirectoryListing(realListing)
        assertEquals(listOf("2.1.0.1.ota", "2.1.0.10.ota", "2.1.0.16.ota"), names)
    }

    @Test
    fun `ignora subdirectorios y el enlace al padre`() {
        val names = FirmwareCatalog.parseDirectoryListing(realListing)
        assertTrue(names.none { it.contains("/") })
        assertTrue(names.none { it == "test.txt" })
    }

    @Test
    fun `no repite si el mismo archivo aparece dos veces`() {
        val html = """<a href="a.ota">a.ota</a><a href="a.ota">otra vez</a>"""
        assertEquals(listOf("a.ota"), FirmwareCatalog.parseDirectoryListing(html))
    }

    @Test
    fun `un listado sin ota devuelve lista vacia`() {
        assertEquals(0, FirmwareCatalog.parseDirectoryListing("<pre><a href=\"x/\">x/</a></pre>").size)
        assertEquals(0, FirmwareCatalog.parseDirectoryListing("").size)
    }

    @Test
    fun `estan configuradas las tres fuentes`() {
        val ids = FirmwareCatalog.SOURCES.map { it.id }
        assertTrue(ids.contains("skittle"))
        assertTrue(ids.contains("inventory"))
        assertTrue(ids.contains("pvic"))
    }

    @Test
    fun `pvic se consulta por directorios y no por API`() {
        val pvic = FirmwareCatalog.SOURCES.first { it.id == "pvic" }
        assertEquals(null, pvic.listEndpoint)
        assertEquals(null, pvic.inventoryUrl)
        assertTrue((pvic.directories ?: emptyMap()).isNotEmpty())
        // las 2.1.0.x estan en dev y oskr: es lo que aporta este espejo
        assertTrue(pvic.directories!!.containsKey("dev"))
        assertTrue(pvic.directories!!.containsKey("oskr"))
    }

    @Test
    fun `las URL de pvic van por https`() {
        val pvic = FirmwareCatalog.SOURCES.first { it.id == "pvic" }
        assertTrue(pvic.fileBase.startsWith("https://"))
    }
}
