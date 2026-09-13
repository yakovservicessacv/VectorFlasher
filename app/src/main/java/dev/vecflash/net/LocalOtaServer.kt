package dev.vecflash.net

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP diminuto que corre dentro de la app.
 *
 * Vector siempre descarga el OTA el mismo, por URL: nunca se le puede "empujar" un
 * archivo. Asi que para instalar sin internet, el celular sirve el .ota ya descargado
 * y le pasamos al robot una URL apuntando al propio telefono.
 *
 * Basta con que el robot y el celular esten en la misma red (vale el hotspot del celular).
 */
class LocalOtaServer(private val rootDir: File) {

    companion object {
        private const val TAG = "LocalOtaServer"
        const val PORT = 8787

        /** IP del telefono en la red local, que es la que tiene que alcanzar el robot. */
        fun localIpAddress(): String? {
            try {
                for (iface in NetworkInterface.getNetworkInterfaces()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo leer la IP local", e)
            }
            return null
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get() && serverSocket?.isClosed == false) return true
        stop()
        return try {
            // ServerSocket(PORT) ya hace el bind, y despues reuseAddress no sirve
            // de nada: hay que crearlo sin enlazar, marcarlo y enlazar a mano. Si no,
            // reabrir tras un cierre reciente falla por el TIME_WAIT del puerto.
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(PORT))
            serverSocket = socket
            running.set(true)
            pool.execute { acceptLoop(socket) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo abrir el puerto $PORT", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignorar
        }
        serverSocket = null
    }

    /**
     * Se pide a si mismo el archivo para confirmar que el servidor responde de
     * verdad antes de mandarle la URL al robot.
     */
    fun selfTest(fileName: String): Boolean {
        return try {
            val conn = (java.net.URL("http://127.0.0.1:$PORT/${encode(fileName)}")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 3000
                readTimeout = 3000
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** URL que hay que mandarle al robot para este archivo. */
    fun urlFor(fileName: String): String? {
        val ip = localIpAddress() ?: return null
        return "http://$ip:$PORT/${encode(fileName)}"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                pool.execute { handle(client) }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "fallo al aceptar conexion", e)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { sock ->
                val input = sock.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return

                var rangeStart = 0L
                var rangeEnd = -1L
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isBlank()) break
                    if (header.startsWith("Range:", ignoreCase = true)) {
                        val spec = header.substringAfter("=", "").trim()
                        val parts = spec.split("-")
                        rangeStart = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        rangeEnd = parts.getOrNull(1)?.toLongOrNull() ?: -1L
                    }
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return
                val name = decode(path.removePrefix("/").substringBefore('?'))

                val out = sock.getOutputStream()

                if (name.isBlank() || name.contains("..") || name.contains('/')) {
                    respondError(out, 400, "Bad Request")
                    return
                }

                val file = File(rootDir, name)
                if (!file.isFile) {
                    respondError(out, 404, "Not Found")
                    return
                }

                val total = file.length()
                val from = rangeStart.coerceIn(0, if (total == 0L) 0 else total - 1)
                val to = if (rangeEnd in from until total) rangeEnd else total - 1
                val length = to - from + 1
                val partial = from != 0L || to != total - 1

                val status = if (partial) "206 Partial Content" else "200 OK"
                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Length: $length\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (partial) append("Content-Range: bytes $from-$to/$total\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray())

                if (method.equals("HEAD", ignoreCase = true)) {
                    out.flush()
                    return
                }

                streamFile(file, from, length, out)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "error sirviendo peticion", e)
        }
    }

    private fun streamFile(file: File, from: Long, length: Long, out: OutputStream) {
        file.inputStream().use { stream ->
            var skipped = 0L
            while (skipped < from) {
                val n = stream.skip(from - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val want = if (remaining < buffer.size) remaining.toInt() else buffer.size
                val read = stream.read(buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun respondError(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun encode(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (safe.indexOf(c) >= 0) sb.append(c)
            else sb.append(String.format("%%%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun decode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.code)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
