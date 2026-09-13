package dev.vecflash.data

import android.content.Context
import java.io.File

/**
 * Los OTA guardados dentro de los datos de la app.
 *
 * Antes esto se apoyaba en DownloadManager y en "existe el archivo y pesa algo".
 * Eso mentia: DownloadManager crea el archivo al arrancar y lo va llenando, asi que
 * una descarga a medias (o fallida) se veia como guardada.
 *
 * Ahora manda un indice explicito: solo cuenta como guardado lo que esta en el
 * indice Y existe en disco con el tamano exacto que se registro. Mientras baja,
 * el archivo vive como ".part" y jamas se confunde con uno completo.
 */
class OtaStorage(context: Context) {

    private val prefs = context.getSharedPreferences("ota_index", Context.MODE_PRIVATE)

    /** Datos internos de la app: se van con la desinstalacion y no los ve nadie mas. */
    val dir: File = File(context.filesDir, "ota").apply { mkdirs() }

    companion object {
        private const val KEY_INDEX = "index"
        const val PART_SUFFIX = ".part"

        /**
         * Un mismo nombre aparece en varios stacks (1.6-rebuild esta en dos), asi que
         * el archivo local lleva el stack delante. El id real se guarda en el indice:
         * no se deduce del nombre, porque "escape-pod" ya lleva guion.
         */
        fun localName(stackId: String, fileName: String): String =
            stackId.replace(' ', '-') + "__" + fileName
    }

    // ---------------- indice ----------------
    // La logica vive en OtaIndex, que no depende de Android y si se puede probar.

    private fun readIndex(): List<OtaIndex.Entry> =
        OtaIndex.decode(prefs.getString(KEY_INDEX, null))

    private fun writeIndex(entries: List<OtaIndex.Entry>) {
        prefs.edit().putString(KEY_INDEX, OtaIndex.encode(entries)).apply()
    }

    /**
     * Solo lo que esta en el indice y ademas existe con el tamano registrado.
     * De paso limpia las entradas cuyo archivo desaparecio o quedo a medias.
     */
    fun stored(): List<OtaIndex.Entry> {
        val index = readIndex()
        val valid = OtaIndex.validate(index, dir)
        if (valid.size != index.size) writeIndex(valid)
        return valid
    }

    fun storedNames(): Set<String> = stored().map { it.localName }.toSet()

    fun isStored(stackId: String, fileName: String): Boolean =
        stored().any { it.stackId == stackId && it.fileName == fileName }

    fun entryFor(stackId: String, fileName: String): OtaIndex.Entry? =
        stored().firstOrNull { it.stackId == stackId && it.fileName == fileName }

    fun totalBytes(): Long = stored().sumOf { e -> File(dir, e.localName).length() }

    // ---------------- archivos ----------------

    fun fileFor(stackId: String, fileName: String): File =
        File(dir, localName(stackId, fileName))

    fun partFileFor(stackId: String, fileName: String): File =
        File(dir, localName(stackId, fileName) + PART_SUFFIX)

    fun record(stackId: String, fileName: String, url: String, size: Long) {
        writeIndex(
            OtaIndex.with(
                readIndex(),
                OtaIndex.Entry(stackId, fileName, url, size, System.currentTimeMillis())
            )
        )
    }

    fun delete(stackId: String, fileName: String) {
        fileFor(stackId, fileName).delete()
        partFileFor(stackId, fileName).delete()
        writeIndex(OtaIndex.without(readIndex(), stackId, fileName))
    }

    fun deleteAll() {
        (dir.listFiles() ?: emptyArray()).forEach { it.delete() }
        writeIndex(emptyList())
    }

    /** Restos de descargas interrumpidas, por si hay que hacer sitio. */
    fun clearPartials() {
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(PART_SUFFIX) }
            .forEach { it.delete() }
    }
}
