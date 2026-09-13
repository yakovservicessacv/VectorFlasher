package dev.vecflash.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * El indice de OTAs guardados, sin nada de Android para poder probarlo.
 *
 * Aqui vive la regla que arregla el bug de "aparece guardado y no lo esta":
 * una entrada solo cuenta si el archivo existe Y pesa exactamente lo que se
 * registro al terminar la descarga. Un ".part" a medias nunca cuela.
 */
object OtaIndex {

    data class Entry(
        val stackId: String,
        val fileName: String,
        val url: String,
        val size: Long,
        val savedAt: Long
    ) {
        val localName: String get() = OtaStorage.localName(stackId, fileName)
    }

    fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("stack", it.stackId)
                    .put("file", it.fileName)
                    .put("url", it.url)
                    .put("size", it.size)
                    .put("at", it.savedAt)
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val stack = o.optString("stack", "")
                val file = o.optString("file", "")
                if (stack.isBlank() || file.isBlank()) return@mapNotNull null
                Entry(
                    stackId = stack,
                    fileName = file,
                    url = o.optString("url", ""),
                    size = o.optLong("size", 0),
                    savedAt = o.optLong("at", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Se queda solo con lo que de verdad esta completo en disco.
     * Un archivo a medias pesa menos de lo registrado, asi que se cae solo.
     */
    fun validate(entries: List<Entry>, dir: File): List<Entry> =
        entries.filter { e ->
            val f = File(dir, e.localName)
            f.isFile && e.size > 0 && f.length() == e.size
        }

    fun without(entries: List<Entry>, stackId: String, fileName: String): List<Entry> =
        entries.filterNot { it.stackId == stackId && it.fileName == fileName }

    fun with(entries: List<Entry>, entry: Entry): List<Entry> =
        without(entries, entry.stackId, entry.fileName) + entry
}
