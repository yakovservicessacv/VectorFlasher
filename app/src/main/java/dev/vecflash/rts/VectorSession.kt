package dev.vecflash.rts

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.vecflash.ble.VectorBle
import dev.vecflash.R
import dev.vecflash.crypto.VectorCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Maquina de estados completa del pairing de Vector.
 *
 * Secuencia real, en orden:
 *   1. el robot manda el handshake  -> respondemos con la misma version
 *   2. el robot manda RtsConnRequest con su clave publica
 *   3. respondemos RtsConnResponse con la nuestra -> Vector muestra el PIN en la cara
 *   4. el robot manda los nonces
 *   5. el usuario teclea el PIN -> derivamos claves y mandamos el ACK **sin cifrar**
 *      (a partir de ese ACK, todo el trafico va cifrado)
 *   6. challenge / challenge-success -> sesion autorizada
 */
class VectorSession(context: Context) {

    private val appContext = context.applicationContext
    val ble = VectorBle(context)
    private val crypto = VectorCrypto()

    // Red de seguridad: si el robot no contesta, la UI no puede quedarse colgada.
    private val timeouts = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        if (_wifiScanning.value) {
            _wifiScanning.value = false
            logLine("wifi scan timed out")
        }
    }
    private val connectTimeout = Runnable {
        if (_wifiConnecting.value) {
            _wifiConnecting.value = false
            logLine("wifi connect timed out")
        }
    }

    /**
     * Si el OTA no avanza nada en un minuto, casi siempre es que el robot no
     * alcanza la URL. Marcarlo permite explicarlo en pantalla.
     */
    private val otaStallCheck = object : Runnable {
        override fun run() {
            val s = _ota.value
            if (s.active && !s.finished) {
                val quiet = System.currentTimeMillis() - (if (otaLastTime > 0) otaLastTime else otaStartedAt)
                if (quiet > 60_000 && !s.stalled) {
                    _ota.value = s.copy(stalled = true)
                    logLine("ota stalled: sin avance en 60s")
                }
                timeouts.postDelayed(this, 15_000)
            }
        }
    }

    private fun s(id: Int): String = appContext.getString(id)
    private fun s(id: Int, arg: Any): String = appContext.getString(id, arg)

    sealed class Phase {
        object Idle : Phase()
        object Connecting : Phase()
        object Handshaking : Phase()
        object NeedPin : Phase()
        object Authenticating : Phase()
        object Ready : Phase()
        data class Failed(val message: String) : Phase()
    }

    data class OtaState(
        val active: Boolean = false,
        val status: Int = 0,
        val current: Long = 0,
        val expected: Long = 0,
        val finished: Boolean = false,
        val error: String? = null,
        /** Velocidad medida entre dos avisos de progreso del robot. */
        val bytesPerSecond: Long = 0,
        /** Lleva un rato sin avanzar: casi siempre es que no alcanza el servidor. */
        val stalled: Boolean = false
    ) {
        val fraction: Float
            get() = if (expected > 0) (current.toDouble() / expected.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

        /** Segundos restantes, o -1 si aun no hay velocidad medida. */
        val etaSeconds: Long
            get() = if (bytesPerSecond > 0 && expected > current)
                        (expected - current) / bytesPerSecond
                    else -1L

        /**
         * Vector descarga a 30-80 KB/s si su stack esta corriendo, y a varios MB/s
         * desde el modo recuperacion. Por debajo de este umbral conviene avisar.
         */
        val isSlow: Boolean
            get() = bytesPerSecond in 1 until 150_000
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _devices = MutableStateFlow<List<VectorBle.Discovered>>(emptyList())
    val devices: StateFlow<List<VectorBle.Discovered>> = _devices.asStateFlow()

    private val _status = MutableStateFlow<Rts.Incoming.Status?>(null)
    val status: StateFlow<Rts.Incoming.Status?> = _status.asStateFlow()

    private val _networks = MutableStateFlow<List<Rts.WifiNetwork>>(emptyList())
    val networks: StateFlow<List<Rts.WifiNetwork>> = _networks.asStateFlow()

    private val _wifiResult = MutableStateFlow<Rts.Incoming.WifiConnect?>(null)
    val wifiResult: StateFlow<Rts.Incoming.WifiConnect?> = _wifiResult.asStateFlow()

    private val _ota = MutableStateFlow(OtaState())
    val ota: StateFlow<OtaState> = _ota.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _wifiConnecting = MutableStateFlow(false)
    val wifiConnecting: StateFlow<Boolean> = _wifiConnecting.asStateFlow()

    private val _wifiScanning = MutableStateFlow(false)
    val wifiScanning: StateFlow<Boolean> = _wifiScanning.asStateFlow()

    private val _cloudSession = MutableStateFlow<Rts.Incoming.CloudSession?>(null)
    val cloudSession: StateFlow<Rts.Incoming.CloudSession?> = _cloudSession.asStateFlow()

    private val _robotIp = MutableStateFlow<String?>(null)
    val robotIp: StateFlow<String?> = _robotIp.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    var connectedName: String = ""
        private set

    // --- estado del protocolo ---
    private var rtsVersion = 5
    private var ownKeys: VectorCrypto.KeyPair? = null
    private var remotePublicKey: ByteArray? = null
    private var encryptNonce: ByteArray? = null
    private var decryptNonce: ByteArray? = null
    private var encryptKey: ByteArray? = null
    private var decryptKey: ByteArray? = null
    private var encrypted = false

    // Para medir la velocidad del OTA entre avisos de progreso.
    private var otaLastBytes = 0L
    private var otaLastTime = 0L
    private var otaStartedAt = 0L

    init {
        ble.onScanResult = { d ->
            val current = _devices.value
            if (current.none { it.device.address == d.device.address }) {
                _devices.value = current + d
            }
        }
        ble.onError = { msg -> fail(msg) }
        ble.onConnectionState = { connected ->
            if (!connected) {
                if (_phase.value is Phase.Ready || _phase.value is Phase.Authenticating) {
                    logLine("disconnected")
                }
                resetProtocol()
                if (_phase.value !is Phase.Failed) _phase.value = Phase.Idle
            }
        }
        ble.onReady = {
            logLine("BLE ready, waiting for handshake")
            _phase.value = Phase.Handshaking
        }
        ble.onMessage = { raw -> handleRaw(raw) }
        ble.onDiagnostic = { line -> logLine(line) }
    }

    // ---------------- API publica ----------------

    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true
        logLine("scanning for service fee3")
        ble.startScan()
    }

    fun stopScan() {
        _scanning.value = false
        ble.stopScan()
    }

    fun connect(d: VectorBle.Discovered) {
        stopScan()
        connectedName = d.name
        resetProtocol()
        _phase.value = Phase.Connecting
        logLine("connecting to ${d.name}")
        ble.connect(d.device)
    }

    fun disconnect() {
        ble.disconnect()
        resetProtocol()
        _phase.value = Phase.Idle
    }

    /** Paso 5: el usuario teclea el PIN que Vector muestra en la cara. */
    fun submitPin(pin: String) {
        val keys = ownKeys
        val remote = remotePublicKey
        if (keys == null || remote == null) {
            fail(s(R.string.err_no_keys))
            return
        }
        try {
            val session = crypto.clientSessionKeys(keys, remote, pin)
            decryptKey = session.rx
            encryptKey = session.tx

            // El ACK del nonce viaja SIN cifrar; el cifrado empieza justo despues.
            sendPlain(Rts.ack(rtsVersion, Rts.TAG_NONCE_MESSAGE))
            encrypted = true

            _phase.value = Phase.Authenticating
            logLine("pin applied, waiting for challenge")
        } catch (e: Exception) {
            fail(s(R.string.err_crypto_state))
        }
    }

    fun requestStatus() = sendRts(Rts.statusRequest(rtsVersion))

    fun scanWifi() {
        _wifiScanning.value = true
        logLine("wifi scan requested")
        timeouts.removeCallbacks(scanTimeout)
        timeouts.postDelayed(scanTimeout, 30_000)
        sendRts(Rts.wifiScanRequest(rtsVersion))
    }

    /**
     * Ojo con "hidden": el cliente oficial lo manda SIEMPRE en false, aunque el
     * escaneo diga lo contrario. Algunos routers marcan la red como oculta sin
     * serlo, y mandar true hace que Vector no la encuentre.
     */
    fun connectWifi(ssid: String, password: String, authType: Int, hidden: Boolean = false) {
        _wifiResult.value = null
        _wifiConnecting.value = true
        timeouts.removeCallbacks(connectTimeout)
        timeouts.postDelayed(connectTimeout, 35_000)
        logLine("wifi connect: $ssid auth=$authType hex=${Rts.strToHex(ssid)}")
        sendRts(Rts.wifiConnectRequest(rtsVersion, ssid, password, authType, hidden = false))
    }

    /** Lo importante: mandar la URL del OTA para que Vector la descargue e instale. */
    fun startOta(url: String) {
        if (url.length > 255) {
            fail(s(R.string.err_url_too_long))
            return
        }
        _ota.value = OtaState(active = true)
        otaLastBytes = 0L
        otaLastTime = 0L
        otaStartedAt = System.currentTimeMillis()
        timeouts.removeCallbacks(otaStallCheck)
        timeouts.postDelayed(otaStallCheck, 15_000)
        logLine("ota start: $url")
        sendRts(Rts.otaUpdateRequest(rtsVersion, url))
    }

    fun cancelOta() {
        timeouts.removeCallbacks(otaStallCheck)
        logLine("ota cancel")
        sendRts(Rts.otaCancelRequest(rtsVersion))
        _ota.value = OtaState()
    }

    /**
     * El paso ACTIVATE. Con firmware de pod el token no lo valida la nube de Anki,
     * lo atiende el servidor local, por eso funciona sin cuenta.
     */
    fun activate(sessionToken: String) {
        _cloudSession.value = null
        logLine("activate: sending session token")
        sendRts(Rts.cloudSessionRequest(rtsVersion, sessionToken))
    }

    fun requestIp() {
        logLine("asking robot for its ip")
        sendRts(Rts.wifiIpRequest(rtsVersion))
    }

    fun clearError() {
        if (_phase.value is Phase.Failed) _phase.value = Phase.Idle
    }

    // ---------------- interno ----------------

    private fun resetProtocol() {
        timeouts.removeCallbacks(otaStallCheck)
        ownKeys = null
        remotePublicKey = null
        encryptNonce = null
        decryptNonce = null
        encryptKey = null
        decryptKey = null
        encrypted = false
        _status.value = null
        _networks.value = emptyList()
        _wifiResult.value = null
        timeouts.removeCallbacks(scanTimeout)
        timeouts.removeCallbacks(connectTimeout)
        _wifiConnecting.value = false
        _wifiScanning.value = false
        _ota.value = OtaState()
        _cloudSession.value = null
        _robotIp.value = null
    }

    private fun handleRaw(raw: ByteArray) {
        val plain = if (encrypted) {
            val nonce = decryptNonce
            val key = decryptKey
            if (nonce == null || key == null) {
                fail(s(R.string.err_crypto_state))
                return
            }
            val d = crypto.decrypt(raw, nonce, key)
            if (d == null) {
                fail(s(R.string.err_bad_pin))
                return
            }
            VectorCrypto.incrementNonce(nonce)
            d
        } else raw

        val msg = Rts.parse(plain) ?: return
        handleMessage(msg)
    }

    private fun handleMessage(msg: Rts.Incoming) {
        when (msg) {
            is Rts.Incoming.Handshake -> {
                rtsVersion = msg.version
                logLine("handshake RTS v${msg.version}")
                if (msg.version < 2 || msg.version > 6) {
                    fail(s(R.string.err_unsupported_version, msg.version))
                    return
                }
                sendPlain(Rts.handshake(msg.version))
            }

            is Rts.Incoming.ConnRequest -> {
                remotePublicKey = msg.publicKey
                val keys = crypto.generateKeyPair()
                ownKeys = keys
                sendPlain(Rts.connResponse(rtsVersion, Rts.CONN_TYPE_FIRST_TIME_PAIR, keys.publicKey))
                logLine("keys exchanged, PIN shown on face")
            }

            is Rts.Incoming.Nonce -> {
                encryptNonce = msg.toRobotNonce.copyOf()
                decryptNonce = msg.toDeviceNonce.copyOf()
                _phase.value = Phase.NeedPin
            }

            is Rts.Incoming.Challenge -> {
                sendRts(Rts.challengeReply(rtsVersion, msg.number + 1))
            }

            is Rts.Incoming.ChallengeSuccess -> {
                _phase.value = Phase.Ready
                logLine("encrypted session authorized")
                requestStatus()
            }

            is Rts.Incoming.Status -> {
                _status.value = msg
            }

            is Rts.Incoming.WifiScan -> {
                timeouts.removeCallbacks(scanTimeout)
                _wifiScanning.value = false
                _networks.value = msg.networks.sortedByDescending { it.signalStrength }
                logLine("wifi scan: status=${msg.statusCode} ${msg.networks.size} redes")
            }

            is Rts.Incoming.WifiConnect -> {
                timeouts.removeCallbacks(connectTimeout)
                _wifiConnecting.value = false
                _wifiResult.value = msg
                logLine("wifi connect -> state=${msg.wifiState} result=${msg.connectResult}")
                requestStatus()
            }

            is Rts.Incoming.OtaProgress -> {
                when (msg.status) {
                    // El robot acusa recibo antes de bajar nada: seguimos esperando.
                    Rts.OTA_UNKNOWN, Rts.OTA_IDLE -> _ota.value = OtaState(
                        active = true, status = msg.status,
                        current = msg.current, expected = msg.expected
                    )

                    Rts.OTA_IN_PROGRESS -> {
                        val now = System.currentTimeMillis()
                        var rate = _ota.value.bytesPerSecond
                        if (otaLastTime > 0) {
                            val dt = now - otaLastTime
                            val db = msg.current - otaLastBytes
                            if (dt > 400 && db > 0) {
                                val sample = db * 1000 / dt
                                // Suavizado: los avisos llegan irregulares.
                                rate = if (rate > 0) (rate * 2 + sample) / 3 else sample
                            }
                        }
                        if (otaLastTime == 0L || msg.current > otaLastBytes) {
                            otaLastBytes = msg.current
                            otaLastTime = now
                        }
                        _ota.value = OtaState(
                            active = true, status = msg.status,
                            current = msg.current, expected = msg.expected,
                            bytesPerSecond = rate
                        )
                    }
                    Rts.OTA_COMPLETED -> {
                        _ota.value = OtaState(
                            active = false, status = msg.status,
                            current = msg.current, expected = msg.expected, finished = true
                        )
                        logLine("ota complete, robot rebooting")
                    }
                    else -> {
                        _ota.value = OtaState(
                            active = false, status = msg.status,
                            error = "status ${msg.status}"
                        )
                        logLine("ota failed, status=${msg.status}")
                    }
                }
            }

            is Rts.Incoming.CancelPairing -> {
                logLine("robot cancelled pairing")
                disconnect()
            }

            is Rts.Incoming.ForceDisconnect -> {
                logLine("robot forced disconnect")
                disconnect()
            }

            is Rts.Incoming.CloudSession -> {
                _cloudSession.value = msg
                logLine("activate result: success=${msg.success} status=${msg.statusCode}")
                requestStatus()
            }

            is Rts.Incoming.Ack -> Unit

            is Rts.Incoming.WifiIp -> {
                _robotIp.value = if (msg.hasIpV4) msg.ipV4 else null
                logLine("robot ip ${msg.ipV4}")
            }
            is Rts.Incoming.Unknown -> {
                logLine("tag 0x${Integer.toHexString(msg.tag)} no manejado, ${msg.payload.size}B")
                // Si el robot contesto al escaneo pero no supimos leerlo, al menos
                // que la UI no se quede colgada esperando.
                if (msg.tag == Rts.TAG_WIFI_SCAN_RESPONSE) {
                    timeouts.removeCallbacks(scanTimeout)
                    _wifiScanning.value = false
                }
                if (msg.tag == Rts.TAG_WIFI_CONNECT_RESPONSE) {
                    timeouts.removeCallbacks(connectTimeout)
                    _wifiConnecting.value = false
                }
            }
        }
    }

    private fun sendPlain(data: ByteArray) = ble.send(data)

    private fun sendRts(data: ByteArray) {
        if (!encrypted) {
            sendPlain(data)
            return
        }
        val nonce = encryptNonce
        val key = encryptKey
        if (nonce == null || key == null) {
            fail(s(R.string.err_crypto_state))
            return
        }
        val cipher = crypto.encrypt(data, nonce, key)
        VectorCrypto.incrementNonce(nonce)
        ble.send(cipher)
    }

    private fun fail(message: String) {
        logLine("error: $message")
        _phase.value = Phase.Failed(message)
    }

    private fun logLine(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}
