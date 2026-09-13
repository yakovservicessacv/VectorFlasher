package dev.vecflash.rts

/*
 * Protocolo RTS de Vector.
 *
 * Sobre exterior de cada mensaje:
 *   0x04 | version de conexion 2..6 | tag del mensaje | payload
 *
 * El handshake inicial es la excepcion: 0x01 | version uint32 LE  (5 bytes).
 */
object Rts {

    const val EXT_COMMS_RTS_CONNECTION = 0x04
    const val HANDSHAKE_TAG = 0x01
    const val HANDSHAKE_SIZE = 5

    // Tags de mensaje. Coinciden en las versiones 2..6 para todo lo que usamos aqui.
    const val TAG_CONN_REQUEST = 0x01
    const val TAG_CONN_RESPONSE = 0x02
    const val TAG_NONCE_MESSAGE = 0x03
    const val TAG_CHALLENGE_MESSAGE = 0x04
    const val TAG_CHALLENGE_SUCCESS = 0x05
    const val TAG_WIFI_CONNECT_REQUEST = 0x06
    const val TAG_WIFI_CONNECT_RESPONSE = 0x07
    const val TAG_WIFI_IP_REQUEST = 0x08
    const val TAG_WIFI_IP_RESPONSE = 0x09
    const val TAG_STATUS_REQUEST = 0x0a
    const val TAG_STATUS_RESPONSE = 0x0b
    const val TAG_WIFI_SCAN_REQUEST = 0x0c
    const val TAG_WIFI_SCAN_RESPONSE = 0x0d
    const val TAG_OTA_UPDATE_REQUEST = 0x0e
    const val TAG_OTA_UPDATE_RESPONSE = 0x0f
    const val TAG_CANCEL_PAIRING = 0x10
    const val TAG_FORCE_DISCONNECT = 0x11
    const val TAG_ACK = 0x12
    const val TAG_SSH_REQUEST = 0x15
    const val TAG_OTA_CANCEL_REQUEST = 0x17
    const val TAG_CLOUD_SESSION_REQUEST = 0x1d
    const val TAG_CLOUD_SESSION_RESPONSE = 0x1e

    const val CONN_TYPE_FIRST_TIME_PAIR = 0x00
    const val CONN_TYPE_RECONNECTION = 0x01

    /** Construye el sobre completo de un mensaje saliente. */
    fun envelope(version: Int, tag: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = EXT_COMMS_RTS_CONNECTION.toByte()
        out[1] = version.toByte()
        out[2] = tag.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    fun handshake(version: Int): ByteArray =
        CladWriter().u8(HANDSHAKE_TAG).u32(version.toLong()).build()

    fun isHandshake(data: ByteArray): Boolean =
        data.size == HANDSHAKE_SIZE && (data[0].toInt() and 0xFF) == HANDSHAKE_TAG

    fun handshakeVersion(data: ByteArray): Int =
        CladReader(data, 1).u32().toInt()

    // ---------- mensajes salientes ----------

    fun connResponse(version: Int, connType: Int, publicKey: ByteArray): ByteArray =
        envelope(version, TAG_CONN_RESPONSE, CladWriter().u8(connType).raw(publicKey).build())

    fun ack(version: Int, ackedTag: Int): ByteArray =
        envelope(version, TAG_ACK, CladWriter().u8(ackedTag).build())

    fun challengeReply(version: Int, number: Long): ByteArray =
        envelope(version, TAG_CHALLENGE_MESSAGE, CladWriter().u32(number).build())

    fun statusRequest(version: Int): ByteArray = envelope(version, TAG_STATUS_REQUEST)

    fun wifiScanRequest(version: Int): ByteArray = envelope(version, TAG_WIFI_SCAN_REQUEST)

    fun wifiIpRequest(version: Int): ByteArray = envelope(version, TAG_WIFI_IP_REQUEST)

    fun wifiConnectRequest(
        version: Int, ssid: String, password: String,
        authType: Int, hidden: Boolean, timeoutSeconds: Int = 15
    ): ByteArray = envelope(
        version, TAG_WIFI_CONNECT_REQUEST,
        CladWriter()
            .stringU8(strToHex(ssid))
            .stringU8(password)
            .u8(timeoutSeconds)
            .u8(authType)
            .bool(hidden)
            .build()
    )

    fun otaUpdateRequest(version: Int, url: String): ByteArray =
        envelope(version, TAG_OTA_UPDATE_REQUEST, CladWriter().stringU8(url).build())

    fun otaCancelRequest(version: Int): ByteArray = envelope(version, TAG_OTA_CANCEL_REQUEST)

    /**
     * El paso "ACTIVATE" del flujo de escape-pod / wire-pod.
     *
     * El cliente oficial manda clientName y appId vacios, asi que hacemos igual.
     * Con firmware de pod el token no se valida contra la nube de Anki: lo atiende
     * el servidor local, por eso se puede activar sin cuenta.
     */
    fun cloudSessionRequest(version: Int, sessionToken: String): ByteArray =
        envelope(
            version, TAG_CLOUD_SESSION_REQUEST,
            CladWriter().stringU16(sessionToken).stringU8("").stringU8("").build()
        )

    fun sshRequest(version: Int, authorizedKey: String): ByteArray =
        envelope(version, TAG_SSH_REQUEST, CladWriter().stringU8(authorizedKey).build())

    // ---------- mensajes entrantes ----------

    sealed class Incoming {
        data class Handshake(val version: Int) : Incoming()
        class ConnRequest(val publicKey: ByteArray) : Incoming()
        class Nonce(val toRobotNonce: ByteArray, val toDeviceNonce: ByteArray) : Incoming()
        data class Challenge(val number: Long) : Incoming()
        object ChallengeSuccess : Incoming()
        data class Ack(val ackedTag: Int) : Incoming()
        data class Status(
            val ssid: String, val wifiState: Int, val accessPoint: Boolean,
            val bleState: Int, val batteryState: Int, val version: String,
            val esn: String, val otaInProgress: Boolean,
            val hasOwner: Boolean, val isCloudAuthed: Boolean
        ) : Incoming()
        data class WifiScan(val statusCode: Int, val networks: List<WifiNetwork>) : Incoming()
        data class WifiConnect(val ssid: String, val wifiState: Int, val connectResult: Int) : Incoming()
        data class WifiIp(val hasIpV4: Boolean, val ipV4: String) : Incoming()
        data class OtaProgress(val status: Int, val current: Long, val expected: Long) : Incoming()
        data class CloudSession(
            val success: Boolean, val statusCode: Int, val clientTokenGuid: String
        ) : Incoming()
        object CancelPairing : Incoming()
        object ForceDisconnect : Incoming()
        class Unknown(val tag: Int, val payload: ByteArray) : Incoming()
    }

    data class WifiNetwork(
        val ssid: String, val authType: Int, val signalStrength: Int,
        val hidden: Boolean, val provisioned: Boolean
    )

    /** Decodifica un mensaje ya desencriptado y desensamblado. */
    fun parse(data: ByteArray): Incoming? {
        if (data.isEmpty()) return null
        if (isHandshake(data)) return Incoming.Handshake(handshakeVersion(data))
        if (data.size < 3) return null
        if ((data[0].toInt() and 0xFF) != EXT_COMMS_RTS_CONNECTION) return null

        val version = data[1].toInt() and 0xFF
        val tag = data[2].toInt() and 0xFF
        val r = CladReader(data, 3)

        return try {
            when (tag) {
                TAG_CONN_REQUEST -> Incoming.ConnRequest(r.raw(32))
                TAG_NONCE_MESSAGE -> Incoming.Nonce(r.raw(24), r.raw(24))
                TAG_CHALLENGE_MESSAGE -> Incoming.Challenge(r.u32())
                TAG_CHALLENGE_SUCCESS -> Incoming.ChallengeSuccess
                TAG_ACK -> Incoming.Ack(r.u8())
                TAG_CANCEL_PAIRING -> Incoming.CancelPairing
                TAG_FORCE_DISCONNECT -> Incoming.ForceDisconnect
                TAG_OTA_UPDATE_RESPONSE -> Incoming.OtaProgress(r.u8(), r.u64(), r.u64())
                TAG_STATUS_RESPONSE -> parseStatus(r)
                TAG_WIFI_SCAN_RESPONSE -> parseWifiScan(r, version)
                TAG_WIFI_CONNECT_RESPONSE -> Incoming.WifiConnect(hexToStr(r.stringU8()), r.u8(), r.u8())
                TAG_WIFI_IP_RESPONSE -> parseWifiIp(r)
                TAG_CLOUD_SESSION_RESPONSE ->
                    Incoming.CloudSession(r.bool(), r.u8(), r.stringU16())
                else -> Incoming.Unknown(tag, data.copyOfRange(3, data.size))
            }
        } catch (e: Exception) {
            Incoming.Unknown(tag, data.copyOfRange(3, data.size))
        }
    }

    /**
     * El status cambia de campos entre versiones de RTS, asi que leemos de forma
     * defensiva: lo que no venga se queda con su valor por defecto.
     */
    private fun parseStatus(r: CladReader): Incoming.Status {
        var ssid = ""
        var wifiState = 0
        var accessPoint = false
        var bleState = 0
        var batteryState = 0
        var version = ""
        var esn = ""
        var ota = false
        var hasOwner = false
        var cloud = false
        try {
            ssid = hexToStr(r.stringU8())
            wifiState = r.u8()
            accessPoint = r.bool()
            bleState = r.u8()
            batteryState = r.u8()
            version = r.stringU8()
            esn = r.stringU8()
            ota = r.bool()
            hasOwner = r.bool()
            cloud = r.bool()
        } catch (e: Exception) {
            // Version antigua de RTS: nos quedamos con los campos que si llegaron.
        }
        return Incoming.Status(
            ssid, wifiState, accessPoint, bleState, batteryState,
            version, esn, ota, hasOwner, cloud
        )
    }

    /**
     * El formato cambia con la version: RtsWifiScanResult_2 (solo RTS v2) no trae
     * el campo "provisioned". Leerlo de mas desincroniza el resto de la lista.
     *
     * Si aun asi falla a mitad, devolvemos las redes que si se pudieron leer en vez
     * de perder la respuesta entera.
     */
    private fun parseWifiScan(r: CladReader, version: Int): Incoming.WifiScan {
        val hasProvisioned = version >= 3
        val statusCode = r.u8()
        val count = r.u8()
        val list = ArrayList<WifiNetwork>(count)
        try {
            for (i in 0 until count) {
                val authType = r.u8()
                val signal = r.u8()
                val ssid = hexToStr(r.stringU8())
                val hidden = r.bool()
                val provisioned = if (hasProvisioned) r.bool() else false
                list.add(WifiNetwork(ssid, authType, signal, hidden, provisioned))
            }
        } catch (e: Exception) {
            // lista truncada: nos quedamos con lo leido
        }
        return Incoming.WifiScan(statusCode, list)
    }

    private fun parseWifiIp(r: CladReader): Incoming.WifiIp {
        val hasIpV4 = r.bool()
        val ipBytes = r.raw(4)
        val ip = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return Incoming.WifiIp(hasIpV4, ip)
    }

    // ---------- utilidades ----------

    /** Vector transmite los SSID como cadena hexadecimal, no como texto plano. */
    fun hexToStr(hex: String): String {
        if (hex.length % 2 != 0) return hex
        return try {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            hex
        }
    }

    /**
     * Vector compara el SSID en hexadecimal contra lo que vio al escanear, y el
     * cliente oficial lo genera en MAYUSCULAS. En minusculas no encuentra la red
     * y la conexion se queda sin hacer nada.
     */
    fun strToHex(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString("") { String.format("%02X", it) }

    fun wifiStateName(state: Int): String = when (state) {
        0 -> "Sin conexion"
        1 -> "Conectado (sin internet)"
        2 -> "Conectado"
        3 -> "Conectando"
        else -> "Desconocido ($state)"
    }

    fun authTypeName(auth: Int): String = when (auth) {
        0 -> "Abierta"
        1 -> "WEP"
        2 -> "WEP shared"
        3 -> "IEEE8021X"
        4 -> "WPA-PSK"
        5 -> "WPA2-PSK"
        6 -> "WPA2-EAP"
        else -> "Auth $auth"
    }

    // Status del OtaUpdateResponse.
    // El robot contesta 0/1 nada mas recibir la peticion, antes de empezar a
    // descargar: no son fallos, son "aun no arranca".
    const val OTA_UNKNOWN = 0
    const val OTA_IDLE = 1
    const val OTA_IN_PROGRESS = 2
    const val OTA_COMPLETED = 3

    fun otaStatusName(status: Int): String = when (status) {
        OTA_IN_PROGRESS -> "Descargando e instalando"
        OTA_COMPLETED -> "Completado"
        else -> "Error o estado inesperado ($status)"
    }
}
