package dev.vecflash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Catalogo de firmwares, consultado en varios sitios a la vez.
 *
 * Fuentes vivas comprobadas: websetup.skittle.dev (con su API y su inventory.json)
 * y ota.pvic.xyz, que no tiene API sino un listado de directorios y aporta cosas que
 * no estan en skittle, como las builds 2.1.0.x. websetup.froggitti.net no se lista
 * aparte porque redirige a skittle. Los CDN de Anki y wire.my.to estan caidos.
 *
 * Se consultan todas en paralelo y se fusiona lo que conteste cada una: un archivo
 * presente en dos sitios queda con espejo de reserva.
 */
object FirmwareCatalog {

    data class Source(
        val id: String,
        val label: String,
        val listEndpoint: String?,
        val inventoryUrl: String?,
        val fileBase: String,
        /** Para espejos que son un simple listado de directorios: subdir -> stack. */
        val directories: Map<String, String>? = null
    )

    val SOURCES = listOf(
        Source(
            id = "skittle",
            label = "websetup.skittle.dev",
            listEndpoint = "https://websetup.skittle.dev/firmware",
            inventoryUrl = null,
            fileBase = "http://websetup.skittle.dev:8000/static/firmware"
        ),
        Source(
            id = "inventory",
            label = "inventory.json",
            listEndpoint = null,
            inventoryUrl = "https://websetup.skittle.dev/static/data/inventory.json",
            fileBase = ""
        ),
        /**
         * Espejo distinto de verdad: no tiene API, es un listado de directorios.
         * Trae cosas que no estan en skittle, como las builds 2.1.0.x.
         * (websetup.froggitti.net no se lista aparte: redirige a skittle.)
         */
        Source(
            id = "pvic",
            label = "ota.pvic.xyz",
            listEndpoint = null,
            inventoryUrl = null,
            fileBase = "https://ota.pvic.xyz",
            directories = mapOf(
                "dev" to "pvic dev",
                "oskr" to "pvic oskr",
                "ddl" to "pvic ddl",
                "unlock" to "pvic unlock",
                "weird" to "pvic weird",
                "dvt2" to "pvic dvt2",
                "dvt3" to "pvic dvt3"
            )
        )
    )

    data class Firmware(
        val name: String,
        val url: String,
        /** Otras direcciones para el mismo archivo, por si la primera falla. */
        val mirrors: List<String> = emptyList()
    ) {
        val allUrls: List<String> get() = listOf(url) + mirrors
    }

    data class Stack(
        val id: String,
        val label: String,
        val description: String,
        val firmwares: List<Firmware>
    )

    data class RefreshResult(
        val stacks: List<Stack>,
        val sourcesOk: List<String>,
        val sourcesFailed: List<String>
    )

    private val STACK_ORDER = listOf(
        "custom firmware", "oskr custom firmware", "unstable custom firmware",
        "beta", "prod", "escape-pod", "modified escape-pod", "utility"
    )

    private val LABELS = mapOf(
        "pvic dev" to "DEV 2.1.x",
        "pvic oskr" to "OSKR 2.1.x",
        "pvic ddl" to "DDL",
        "pvic unlock" to "UNLOCK",
        "pvic weird" to "EXPERIMENTAL",
        "pvic dvt2" to "DVT2 (prototipo)",
        "pvic dvt3" to "DVT3 (prototipo)",
        "custom firmware" to "CUSTOM FIRMWARE",
        "oskr custom firmware" to "OSKR CUSTOM FIRMWARE",
        "unstable custom firmware" to "UNSTABLE CUSTOM FIRMWARE",
        "beta" to "BETA",
        "prod" to "PROD",
        "escape-pod" to "ESCAPE-POD",
        "modified escape-pod" to "MODIFIED ESCAPE-POD",
        "utility" to "UTILITY"
    )

    val DESCRIPTION_KEYS = mapOf(
        "custom firmware" to "stack_desc_custom",
        "oskr custom firmware" to "stack_desc_oskr",
        "unstable custom firmware" to "stack_desc_unstable",
        "beta" to "stack_desc_beta",
        "prod" to "stack_desc_prod",
        "escape-pod" to "stack_desc_escapepod",
        "modified escape-pod" to "stack_desc_modep",
        "utility" to "stack_desc_utility"
    )

    /** Catalogo incrustado: las listas salen al instante y sin internet. */
    private val BUNDLED: Map<String, List<String>> = mapOf(
        "custom firmware" to listOf(
            "1.6-rebuild", "ClaudOS", "Viccyware", "WireOS", "purplOS", "redOS"
        ),
        "oskr custom firmware" to listOf("1.6-rebuild", "WireOS", "purplOS"),
        "unstable custom firmware" to listOf(
            "1.4-Rebuild-DEV.ota", "1.6-rebuild-indev", "1.6-rebuild-indev-oskr",
            "ClaudOStest-3.0.1.9d.ota", "NeoOS.ota", "viccyware-samsung.ota"
        ),
        "beta" to listOf(
            "0.9.0.ota", "0.10.ota", "0.11.ota", "0.11.19-froggitti.ota",
            "0.12.1433.ota", "0.13.1526.ota", "0.14.1615.ota", "cozmoware.ota"
        ),
        "prod" to listOf(
            "1.0.0.1741.ota", "1.0.1.1768.ota", "1.0.2.1804.ota", "1.1.0.2106.ota",
            "1.1.1.2107.ota", "1.2.1.2343.ota", "1.2.2.2353.ota", "1.2.3.2506.ota",
            "1.3.0.2510.ota", "1.4.1.2806.ota", "1.5.0.3009.ota", "1.6.0.3331.ota",
            "1.7.0.3410.ota", "1.7.0.3412.ota", "1.8.0.6021.ota", "1.8.1.6051.ota",
            "2.0.0.6074.ota", "2.0.1.6076.ota", "2.0.1.6077.ota", "2.0.1.6078.ota",
            "2.0.1.6079.ota", "2.0.1.6080.ota", "2.0.1.6082.ota", "2.0.1.6083.ota",
            "2.0.1.6084.ota", "2.0.1.6085.ota", "2.0.1.6086.ota", "2.0.1.6090.ota",
            "2.0.1.6091.ota", "2.0.1.6092.ota", "2.0.1.6093.ota"
        ),
        "escape-pod" to listOf(
            "1.7.2.6014ep.ota", "1.7.3.6016ep.ota", "1.8.1.6051ep.ota", "2.0.1.6076ep.ota"
        ),
        "modified escape-pod" to listOf(
            "1.4.1.2806ep.ota", "1.6.0.3331ep.ota", "2.0.1.6082ep.ota",
            "2.0.1.6085ep.ota", "2.0.1.6086ep.ota"
        ),
        "utility" to listOf(
            "Revert-To-Prod.ota", "Unlock-Prod-OSKR.ota", "Unlock-Prod-PVT.ota",
            "Unlock-Prod.ota", "Upgrade-Unlock-u-skittle-dev.ota",
            "Upgrade-Unlock.ota", "WireOS-Recovery-Installer.ota"
        )
    )

    private val PRIMARY = SOURCES.first { it.id == "skittle" }

    fun bundled(): List<Stack> = STACK_ORDER.map { id ->
        Stack(
            id = id,
            label = LABELS[id] ?: id.uppercase(),
            description = DESCRIPTION_KEYS[id] ?: "",
            firmwares = (BUNDLED[id] ?: emptyList())
                .map { Firmware(it, fileUrl(PRIMARY, id, it)) }
        )
    }

    /** Consulta las tres fuentes en paralelo y fusiona lo que conteste cada una. */
    suspend fun refresh(): RefreshResult = coroutineScope {
        val jobs = SOURCES.map { src -> async(Dispatchers.IO) { src to querySource(src) } }
        val results = jobs.map { it.await() }

        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        // stack -> nombre de archivo -> lista de URLs (una por fuente)
        val merged = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()

        for ((src, catalog) in results) {
            if (catalog == null || catalog.isEmpty()) {
                failed.add(src.label)
                continue
            }
            ok.add(src.label)
            for ((stackId, files) in catalog) {
                val byName = merged.getOrPut(stackId) { LinkedHashMap() }
                for ((fileName, url) in files) {
                    byName.getOrPut(fileName) { mutableListOf() }.add(url)
                }
            }
        }

        // Si no contesto nadie, nos quedamos con lo incrustado.
        if (merged.isEmpty()) {
            return@coroutineScope RefreshResult(bundled(), ok, failed)
        }

        val order = STACK_ORDER + merged.keys.filter { it !in STACK_ORDER }
        val stacks = order.mapNotNull { stackId ->
            val byName = merged[stackId] ?: return@mapNotNull null
            if (byName.isEmpty()) return@mapNotNull null
            Stack(
                id = stackId,
                label = LABELS[stackId] ?: stackId.uppercase(),
                description = DESCRIPTION_KEYS[stackId] ?: "",
                firmwares = byName.map { (name, urls) ->
                    Firmware(name, urls.first(), urls.drop(1))
                }
            )
        }
        RefreshResult(stacks, ok, failed)
    }

    /** stackId -> (nombre de archivo -> url), o null si la fuente no contesta. */
    private suspend fun querySource(src: Source): Map<String, Map<String, String>>? {
        if (src.directories != null) return queryDirectories(src)
        if (src.inventoryUrl != null) return queryInventory(src)
        if (src.listEndpoint == null) return null

        // En serie eran ocho peticiones con 10 s de timeout cada una: hasta 80 s
        // de espera al abrir la app. En paralelo tarda lo que la mas lenta.
        val out = LinkedHashMap<String, Map<String, String>>()
        coroutineScope {
            val jobs = STACK_ORDER.map { stackId ->
                async(Dispatchers.IO) { stackId to postFirmwareList(src.listEndpoint, stackId) }
            }
            for ((stackId, names) in jobs.map { it.await() }) {
                if (names.isNullOrEmpty()) continue
                out[stackId] = names.associateWith { fileUrl(src, stackId, it) }
            }
        }
        return out.ifEmpty { null }
    }

    private suspend fun postFirmwareList(endpoint: String, env: String): List<String>? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                DataOutputStream(conn.outputStream).use {
                    it.writeBytes("env=" + java.net.URLEncoder.encode(env, "UTF-8"))
                }
                val body = conn.inputStream.bufferedReader().use { r -> r.readText() }
                conn.disconnect()
                val arr = JSONObject(body).optJSONArray("message") ?: return@withContext null
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").takeIf { s -> s.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Espejos sin API: se lee el listado HTML del directorio y se sacan los
     * enlaces que acaban en .ota. Vale para cualquier nginx/Apache con autoindex.
     */
    private suspend fun queryDirectories(src: Source): Map<String, Map<String, String>>? =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, Map<String, String>>()
            for ((dir, stackId) in src.directories ?: emptyMap()) {
                val names = listDirectory(src.fileBase + "/" + dir + "/") ?: continue
                if (names.isEmpty()) continue
                out[stackId] = names.associateWith {
                    src.fileBase + "/" + dir + "/" + pathEncode(it)
                }
            }
            out.ifEmpty { null }
        }

    private val OTA_LINK = Regex("href=\"([^\"?/]+\\.ota)\"", RegexOption.IGNORE_CASE)

    /** Extrae los .ota de un autoindex HTML. Separado para poder probarlo. */
    fun parseDirectoryListing(html: String): List<String> =
        OTA_LINK.findAll(html).map { it.groupValues[1] }.distinct().toList()

    private fun listDirectory(url: String): List<String>? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { r -> r.readText() }
            conn.disconnect()
            parseDirectoryListing(body)
        } catch (e: Exception) {
            null
        }
    }

    /** inventory.json trae URLs completas, no nombres sueltos. */
    private suspend fun queryInventory(src: Source): Map<String, Map<String, String>>? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(src.inventoryUrl!!).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val body = conn.inputStream.bufferedReader().use { r -> r.readText() }
                conn.disconnect()

                val root = JSONObject(body)
                val out = LinkedHashMap<String, Map<String, String>>()
                for (key in root.keys()) {
                    val arr = root.optJSONArray(key) ?: continue
                    val stackId = INVENTORY_KEYS[key] ?: key
                    val files = LinkedHashMap<String, String>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val url = o.optString("url", "")
                        if (url.isBlank() || isUnreachable(url)) continue
                        val name = o.optString("name", url.substringAfterLast('/'))
                        files[name] = url
                    }
                    if (files.isNotEmpty()) out[stackId] = files
                }
                out.ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }

    /** inventory.json usa otros nombres de stack que el endpoint /firmware. */
    private val INVENTORY_KEYS = mapOf(
        "prod" to "prod",
        "dev" to "unstable custom firmware",
        "oskr" to "oskr custom firmware",
        "escapepod" to "escape-pod"
    )

    /**
     * Descarta lo que no se puede alcanzar desde fuera: el servidor casero del autor
     * y los CDN de Anki, que llevan apagados desde el cierre.
     */
    private fun isUnreachable(url: String): Boolean =
        Regex("""//(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.|127\.)""").containsMatchIn(url) ||
            url.contains("ota-cdn.anki.com") ||
            url.contains("ota.global.anki-services.com") ||
            url.contains("wire.my.to")

    fun fileUrl(src: Source, stackId: String, fileName: String): String =
        "${src.fileBase}/${pathEncode(stackId)}/${pathEncode(fileName)}"

    private fun pathEncode(segment: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (safe.indexOf(c) >= 0) sb.append(c)
            else sb.append(String.format("%%%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
