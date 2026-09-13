package dev.vecflash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Descarga los OTA a los datos de la app.
 *
 * Dos reglas que evitan el problema de antes:
 *  - mientras baja, el archivo es un ".part"; solo al terminar se renombra
 *  - el indice se toca al final, asi que nada aparece como guardado hasta que lo esta
 *
 * Si se corta, se reanuda con Range en vez de empezar de cero: son archivos de
 * cientos de MB.
 */
class OtaDownloader(private val storage: OtaStorage) {

    companion object {
        /** Cabecera tar del primer miembro de todo OTA de Vector. */
        private val OTA_SIGNATURE = "manifest.ini".toByteArray(Charsets.US_ASCII)

        /**
         * Comprueba que el archivo es realmente un OTA de Vector.
         *
         * Un .ota es un tar cuyo primer miembro se llama "manifest.ini", asi que los
         * 12 primeros bytes son siempre esos, seguidos del relleno a cero del campo
         * de nombre. Verificado en varias versiones y en las dos fuentes.
         *
         * Pilla los tres desastres habituales antes de perder 40 minutos flasheando:
         * una pagina de error HTML guardada como .ota cuando un espejo se cae, una
         * descarga cortada, y el archivo equivocado.
         */
        fun looksLikeVectorOta(file: File): Boolean {
            if (!file.isFile || file.length() < 512) return false
            return try {
                file.inputStream().use { input ->
                    val head = ByteArray(OTA_SIGNATURE.size + 1)
                    if (input.read(head) != head.size) return false
                    OTA_SIGNATURE.indices.all { head[it] == OTA_SIGNATURE[it] } &&
                        head[OTA_SIGNATURE.size] == 0.toByte()
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    data class Progress(
        val stackId: String,
        val fileName: String,
        val downloaded: Long,
        val total: Long,
        val failed: Boolean = false
    ) {
        val fraction: Float
            get() = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    suspend fun download(
        stackId: String,
        fileName: String,
        url: String,
        onProgress: (Long, Long) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        val target = storage.fileFor(stackId, fileName)
        val part = storage.partFileFor(stackId, fileName)

        try {
            var existing = if (part.isFile) part.length() else 0L

            var conn = open(url, existing)
            var code = conn.responseCode

            // Si el servidor ignora el Range, hay que empezar de cero.
            if (existing > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                part.delete()
                existing = 0
                conn = open(url, 0)
                code = conn.responseCode
            }

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                return@withContext Result.failure(IllegalStateException("HTTP $code"))
            }

            val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val total = if (remaining > 0) existing + remaining else -1L

            RandomAccessFile(part, "rw").use { out ->
                out.seek(existing)
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // No inundamos la UI: un aviso cada ~400 ms.
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 400) {
                            onProgress(done, total)
                            lastReport = now
                        }
                    }
                    onProgress(done, total)
                }
            }
            conn.disconnect()

            if (total > 0 && part.length() != total) {
                return@withContext Result.failure(
                    IllegalStateException("incompleto: ${part.length()} de $total")
                )
            }

            // Antes de renombrar: si no es un OTA, la descarga no vale de nada.
            if (!looksLikeVectorOta(part)) {
                part.delete()
                return@withContext Result.failure(
                    IllegalStateException("el archivo no es un OTA de Vector")
                )
            }

            target.delete()
            if (!part.renameTo(target)) {
                return@withContext Result.failure(IllegalStateException("no se pudo renombrar"))
            }

            val size = target.length()
            storage.record(stackId, fileName, url, size)
            Result.success(size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun open(url: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }

    /** Tamano que anuncia el servidor, para saber si lo guardado quedo viejo. */
    suspend fun remoteSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
            }
            val len = if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
            conn.disconnect()
            len
        } catch (e: Exception) {
            -1L
        }
    }

    fun cleanupPartial(stackId: String, fileName: String) {
        File(storage.dir, OtaStorage.localName(stackId, fileName) + OtaStorage.PART_SUFFIX).delete()
    }
}
