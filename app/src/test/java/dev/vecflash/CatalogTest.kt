package dev.vecflash

import dev.vecflash.data.FirmwareCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    private val stacks = FirmwareCatalog.bundled()

    @Test
    fun `estan los ocho stacks y en el orden de websetup`() {
        assertEquals(
            listOf(
                "custom firmware",
                "oskr custom firmware",
                "unstable custom firmware",
                "beta",
                "prod",
                "escape-pod",
                "modified escape-pod",
                "utility"
            ),
            stacks.map { it.id }
        )
    }

    @Test
    fun `ningun stack se queda vacio`() {
        stacks.forEach { assertTrue("${it.id} esta vacio", it.firmwares.isNotEmpty()) }
    }

    @Test
    fun `prod trae todo el historial hasta 2_0_1`() {
        val prod = stacks.first { it.id == "prod" }
        assertEquals(31, prod.firmwares.size)
        assertTrue(prod.firmwares.any { it.name == "1.0.0.1741.ota" })
        assertTrue(prod.firmwares.any { it.name == "2.0.1.6093.ota" })
    }

    @Test
    fun `el catalogo completo tiene 70 firmwares`() {
        // 6 + 3 + 6 + 8 + 31 + 4 + 5 + 7
        assertEquals(70, stacks.sumOf { it.firmwares.size })
    }

    /**
     * RtsOtaUpdateRequest codifica la longitud de la URL en un uint8: si alguna
     * entrada pasara de 255 caracteres, el flasheo fallaria al enviarse.
     */
    @Test
    fun `ninguna URL pasa del limite de 255 del protocolo`() {
        stacks.forEach { stack ->
            stack.firmwares.forEach { fw ->
                assertTrue(
                    "URL demasiado larga (${fw.url.length}): ${fw.url}",
                    fw.url.length <= 255
                )
            }
        }
    }

    @Test
    fun `los espacios del nombre del stack se codifican como percent-20`() {
        val custom = stacks.first { it.id == "custom firmware" }
        val url = custom.firmwares.first().url
        assertTrue(url.contains("custom%20firmware"))
        assertTrue("no debe usar '+' para los espacios", !url.contains("+"))
    }

    @Test
    fun `las URL apuntan al servidor de firmware de websetup`() {
        stacks.forEach { stack ->
            stack.firmwares.forEach { fw ->
                assertTrue(
                    fw.url,
                    fw.url.startsWith("http://websetup.skittle.dev:8000/static/firmware/")
                )
            }
        }
    }

    @Test
    fun `las entradas sin extension ota tambien se conservan`() {
        val custom = stacks.first { it.id == "custom firmware" }
        assertTrue(custom.firmwares.any { it.name == "WireOS" })
        assertTrue(custom.firmwares.any { it.name == "purplOS" })
    }

    @Test
    fun `utility incluye las herramientas de desbloqueo`() {
        val utility = stacks.first { it.id == "utility" }
        assertTrue(utility.firmwares.any { it.name == "Unlock-Prod.ota" })
        assertTrue(utility.firmwares.any { it.name == "Revert-To-Prod.ota" })
    }
}
