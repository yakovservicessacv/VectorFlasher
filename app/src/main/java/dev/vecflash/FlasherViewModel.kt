package dev.vecflash

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.vecflash.data.FirmwareCatalog
import dev.vecflash.data.OtaDownloader
import dev.vecflash.data.OtaIndex
import dev.vecflash.data.OtaStorage
import dev.vecflash.net.LocalOtaServer
import dev.vecflash.rts.VectorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlasherViewModel(app: Application) : AndroidViewModel(app) {

    val session = VectorSession(app)
    val storage = OtaStorage(app)
    private val downloader = OtaDownloader(storage)
    private val localServer = LocalOtaServer(storage.dir)

    private val prefs = app.getSharedPreferences("vecflash", Context.MODE_PRIVATE)

    private val _stacks = MutableStateFlow(FirmwareCatalog.bundled())
    val stacks: StateFlow<List<FirmwareCatalog.Stack>> = _stacks.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Fuentes que contestaron en la ultima consulta, para poder mostrarlo. */
    private val _sourcesOk = MutableStateFlow<List<String>>(emptyList())
    val sourcesOk: StateFlow<List<String>> = _sourcesOk.asStateFlow()

    private val _guideSeen = MutableStateFlow(prefs.getBoolean(KEY_GUIDE_SEEN, false))
    val guideSeen: StateFlow<Boolean> = _guideSeen.asStateFlow()

    private val _offlineAsked = MutableStateFlow(prefs.getBoolean(KEY_OFFLINE_ASKED, false))
    val offlineAsked: StateFlow<Boolean> = _offlineAsked.asStateFlow()

    /** Descargas en curso: nombre local -> progreso. */
    private val _downloads = MutableStateFlow<Map<String, OtaDownloader.Progress>>(emptyMap())
    val downloads: StateFlow<Map<String, OtaDownloader.Progress>> = _downloads.asStateFlow()

    private val _stored = MutableStateFlow(storage.stored())
    val stored: StateFlow<List<OtaIndex.Entry>> = _stored.asStateFlow()

    private val _storedBytes = MutableStateFlow(storage.totalBytes())
    val storedBytes: StateFlow<Long> = _storedBytes.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _localServerError = MutableStateFlow<String?>(null)
    val localServerError: StateFlow<String?> = _localServerError.asStateFlow()

    private val jobs = HashMap<String, Job>()

    companion object {
        private const val KEY_GUIDE_SEEN = "guide_seen"
        private const val KEY_OFFLINE_ASKED = "offline_asked"
    }

    init {
        // Restos de descargas cortadas: no ocupan sitio para nada.
        storage.clearPartials()
        refreshCatalog(thenCheckUpdates = true)
    }

    // ---------------- catalogo ----------------

    fun refreshCatalog(thenCheckUpdates: Boolean = false) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val result = FirmwareCatalog.refresh()
                if (result.stacks.any { it.firmwares.isNotEmpty() }) _stacks.value = result.stacks
                _sourcesOk.value = result.sourcesOk
            } catch (e: Exception) {
                // nos quedamos con el catalogo incrustado
            }
            _refreshing.value = false
            if (thenCheckUpdates) checkStoredForUpdates()
        }
    }

    /**
     * Al abrir la app: compara lo guardado en el celular con lo que anuncian las
     * fuentes. Los firmwares de comunidad (WireOS, purplOS...) no cambian de nombre
     * cuando se actualizan, asi que lo que se compara es el tamano del archivo.
     */
    fun checkStoredForUpdates() {
        viewModelScope.launch {
            val entries = storage.stored()
            if (entries.isEmpty()) return@launch

            var updated = 0
            for (entry in entries) {
                val fw = findFirmware(entry.stackId, entry.fileName) ?: continue
                var newSize = -1L
                var newUrl = entry.url
                for (url in fw.allUrls) {
                    val size = downloader.remoteSize(url)
                    if (size > 0) { newSize = size; newUrl = url; break }
                }
                if (newSize > 0 && entry.size > 0 && newSize != entry.size) {
                    updated++
                    startDownload(entry.stackId, entry.fileName, newUrl)
                }
            }
            _updateStatus.value = if (updated > 0) "updated:$updated" else null
        }
    }

    private fun findFirmware(stackId: String, fileName: String): FirmwareCatalog.Firmware? =
        _stacks.value.firstOrNull { it.id == stackId }?.firmwares?.firstOrNull { it.name == fileName }

    fun dismissUpdateStatus() { _updateStatus.value = null }

    // ---------------- primera vez ----------------

    fun markGuideSeen() {
        prefs.edit().putBoolean(KEY_GUIDE_SEEN, true).apply()
        _guideSeen.value = true
    }

    fun showGuideAgain() { _guideSeen.value = false }

    fun markOfflineAsked() {
        prefs.edit().putBoolean(KEY_OFFLINE_ASKED, true).apply()
        _offlineAsked.value = true
    }

    // ---------------- descargas ----------------

    fun enqueue(stackId: String, firmware: FirmwareCatalog.Firmware) =
        startDownload(stackId, firmware.name, firmware.url)

    fun enqueueAll(selection: List<Pair<String, FirmwareCatalog.Firmware>>) {
        selection.forEach { (stackId, fw) -> startDownload(stackId, fw.name, fw.url) }
    }

    private fun startDownload(stackId: String, fileName: String, url: String) {
        val key = OtaStorage.localName(stackId, fileName)
        if (jobs[key]?.isActive == true) return

        jobs[key] = viewModelScope.launch {
            updateProgress(key, OtaDownloader.Progress(stackId, fileName, 0, -1))
            val result = downloader.download(stackId, fileName, url) { done, total ->
                updateProgress(key, OtaDownloader.Progress(stackId, fileName, done, total))
            }
            result
                .onSuccess { refreshStored() }
                .onFailure {
                    updateProgress(key, OtaDownloader.Progress(stackId, fileName, 0, -1, failed = true))
                }
            // El fallo se deja un momento en pantalla y luego se limpia.
            if (result.isSuccess) removeProgress(key)
            jobs.remove(key)
        }
    }

    fun cancelDownload(stackId: String, fileName: String) {
        val key = OtaStorage.localName(stackId, fileName)
        jobs.remove(key)?.cancel()
        downloader.cleanupPartial(stackId, fileName)
        removeProgress(key)
        refreshStored()
    }

    fun deleteStored(stackId: String, fileName: String) {
        cancelDownload(stackId, fileName)
        storage.delete(stackId, fileName)
        refreshStored()
    }

    fun deleteAllStored() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _downloads.value = emptyMap()
        storage.deleteAll()
        refreshStored()
    }

    fun isStored(stackId: String, fileName: String): Boolean =
        _stored.value.any { it.stackId == stackId && it.fileName == fileName }

    private fun updateProgress(key: String, p: OtaDownloader.Progress) {
        _downloads.value = _downloads.value.toMutableMap().apply { put(key, p) }
    }

    private fun removeProgress(key: String) {
        _downloads.value = _downloads.value.toMutableMap().apply { remove(key) }
    }

    private fun refreshStored() {
        _stored.value = storage.stored()
        _storedBytes.value = storage.totalBytes()
    }

    // ---------------- instalar desde el celular ----------------

    fun clearLocalServerError() { _localServerError.value = null }

    /**
     * Instalar desde el celular solo funciona si Vector puede alcanzar al telefono
     * por HTTP: el robot SIEMPRE descarga el, nunca se le empuja el archivo. Antes
     * de mandarle nada comprobamos las condiciones, porque si falla alguna el OTA
     * se queda en 0% sin decir por que.
     */
    fun installFromPhone(stackId: String, fileName: String) {
        val ctx = getApplication<Application>()
        _localServerError.value = null

        // Todo esto toca disco y red: no puede correr en el hilo principal.
        // (El selfTest lo hacia, Android lanzaba NetworkOnMainThreadException y el
        //  preflight fallaba siempre con "no se pudo abrir el servidor local".)
        viewModelScope.launch {
            val file = storage.fileFor(stackId, fileName)

            // 1. el archivo esta completo y es un OTA de verdad
            val valid = withContext(Dispatchers.IO) {
                file.isFile && file.length() > 0L && OtaDownloader.looksLikeVectorOta(file)
            }
            if (!valid) {
                _localServerError.value = ctx.getString(R.string.offline_missing, fileName)
                return@launch
            }

            // 2. Vector tiene Wi-Fi (si no, no puede llegar a ningun sitio)
            val wifiState = session.status.value?.wifiState
            if (wifiState != null && wifiState != 1 && wifiState != 2) {
                _localServerError.value = ctx.getString(R.string.offline_robot_no_wifi)
                return@launch
            }

            // 3. el telefono tiene direccion en la red local
            val phoneIp = withContext(Dispatchers.IO) { LocalOtaServer.localIpAddress() }
            if (phoneIp == null) {
                _localServerError.value = ctx.getString(R.string.offline_no_ip)
                return@launch
            }

            // 4. el servidor arranca y se contesta a si mismo
            val serverOk = withContext(Dispatchers.IO) {
                localServer.start() && localServer.selfTest(file.name)
            }
            if (!serverOk) {
                _localServerError.value = ctx.getString(R.string.offline_server_error)
                return@launch
            }

            // 5. mismo segmento de red que el robot, si ya sabemos su IP
            val robotIp = session.robotIp.value
            if (robotIp != null && !sameSubnet(phoneIp, robotIp)) {
                _localServerError.value =
                    ctx.getString(R.string.offline_other_network, robotIp, phoneIp)
                return@launch
            }
            if (robotIp == null) session.requestIp()

            val url = withContext(Dispatchers.IO) { localServer.urlFor(file.name) }
            if (url == null) {
                _localServerError.value = ctx.getString(R.string.offline_no_ip)
                return@launch
            }
            session.startOta(url)
        }
    }

    /** Comparacion simple de /24: suficiente para una red domestica. */
    private fun sameSubnet(a: String, b: String): Boolean {
        val x = a.split(".")
        val y = b.split(".")
        if (x.size != 4 || y.size != 4) return true
        return x[0] == y[0] && x[1] == y[1] && x[2] == y[2]
    }

    override fun onCleared() {
        super.onCleared()
        localServer.stop()
        session.disconnect()
    }
}
