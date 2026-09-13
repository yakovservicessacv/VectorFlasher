package dev.vecflash.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import dev.vecflash.R
import java.util.ArrayDeque
import java.util.UUID

/**
 * Transporte BLE hacia Vector.
 *
 * Los UUID salen del cliente oficial. Ojo con el detalle: en websetup los nombres
 * de las variables estan al reves respecto a su uso real, asi que aqui van
 * nombrados por lo que de verdad hacen.
 *
 *   fee3                                  -> servicio de pairing
 *   7d2a4bda-d29b-4152-b725-2491478c5cd7  -> escritura  (app  -> robot)
 *   30619f2d-0f54-41bd-a65a-7588d8c85b45  -> notify     (robot -> app)
 */
class VectorBle(private val context: Context) {

    companion object {
        private const val TAG = "VectorBle"

        val SERVICE_UUID: UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("7d2a4bda-d29b-4152-b725-2491478c5cd7")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("30619f2d-0f54-41bd-a65a-7588d8c85b45")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Vector solo acepta paquetes de 20 bytes en esta caracteristica. */
        const val PACKET_SIZE = 20

        /** Reintentos por paquete antes de darlo por imposible. */
        const val MAX_WRITE_ATTEMPTS = 5
    }

    data class Discovered(val device: BluetoothDevice, val name: String, val rssi: Int)

    var onScanResult: ((Discovered) -> Unit)? = null
    var onConnectionState: ((Boolean) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onMessage: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        mgr?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    /**
     * Las notificaciones se reensamblan en su propio hilo serie, no en el principal.
     * Con rafagas largas (el escaneo Wi-Fi) el hilo principal puede estar recomponiendo
     * y no conviene que la entrega dependa de el.
     */
    private val rxExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val assembler = BleAssembler(
        onMessage = { msg -> handler.post { onMessage?.invoke(msg) } },
        onDiagnostic = { line -> handler.post { onDiagnostic?.invoke(line) } }
    )

    private fun s(id: Int): String = context.getString(id)
    private fun s(id: Int, arg: Any): String = context.getString(id, arg)

    // Toda operacion GATT debe ir de una en una: si no, Android descarta escrituras
    // en silencio. El paquete se queda en la cabeza de la cola hasta que Android
    // confirma que se escribio: sacarlo antes hacia que una escritura fallida lo
    // perdiera, y un mensaje multiparte al que le falta un trozo llega corrupto.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private var writeAttempts = 0

    val isConnected: Boolean get() = gatt != null && writeChar != null

    // ---------------- escaneo ----------------

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onError?.invoke(s(R.string.err_bt_unavailable))
            return
        }
        stopScan()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: "Vector"
                result.device?.let { onScanResult?.invoke(Discovered(it, name, result.rssi)) }
            }

            override fun onScanFailed(errorCode: Int) {
                onError?.invoke(s(R.string.err_scan_failed, errorCode))
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: SecurityException) {
            onError?.invoke(s(R.string.err_no_permissions))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            // el adaptador pudo apagarse mientras escaneabamos
        }
    }

    // ---------------- conexion ----------------

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        assembler.reset()
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            onError?.invoke(s(R.string.err_no_permissions))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        notifyChar = null
        synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
        try {
            g.disconnect()
            g.close()
        } catch (e: Exception) {
            // ignorar
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.post { onConnectionState?.invoke(true) }
                    // Baja el intervalo de conexion: sin esto una rafaga de 30
                    // paquetes tarda muchisimo mas de lo necesario.
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (e: Exception) {
                        // no todos los telefonos lo permiten
                    }
                    // Un respiro antes de descubrir servicios evita fallos en varios telefonos.
                    handler.postDelayed({
                        try {
                            g.discoverServices()
                        } catch (e: SecurityException) {
                            onError?.invoke(s(R.string.err_no_permissions))
                        }
                    }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeChar = null
                    notifyChar = null
                    synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
                    handler.post { onConnectionState?.invoke(false) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { onError?.invoke(s(R.string.err_services, status)) }
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                handler.post { onError?.invoke(s(R.string.err_no_service)) }
                return
            }
            writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
            notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)

            if (writeChar == null || notifyChar == null) {
                handler.post { onError?.invoke(s(R.string.err_no_chars)) }
                return
            }

            writeChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            try {
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar?.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } else {
                    handler.post { onReady?.invoke() }
                }
            } catch (e: SecurityException) {
                handler.post { onError?.invoke(s(R.string.err_no_permissions)) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                handler.post { onReady?.invoke() }
            }
        }

        @Deprecated("Necesario para Android 12 y anteriores")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != NOTIFY_CHAR_UUID) return
            // ch.value devuelve el array INTERNO que Android reutiliza en cada
            // notificacion. Sin copiarlo aqui, una rafaga larga (el escaneo Wi-Fi son
            // 20+ paquetes seguidos) lo sobrescribe antes de que corra el post y el
            // reensamblado sale corrupto. Hay que copiar en el acto.
            val value = ch.value?.copyOf() ?: return
            rxExecutor.execute { assembler.receive(value) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (ch.uuid != NOTIFY_CHAR_UUID) return
            val copy = value.copyOf()
            rxExecutor.execute { assembler.receive(copy) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(writeQueue) {
                    writeQueue.poll()
                    writeAttempts = 0
                    writeBusy = false
                }
                pumpWriteQueue()
            } else {
                Log.w(TAG, "escritura fallida: $status")
                retryCurrentPacket()
            }
        }
    }

    // ---------------- envio ----------------

    /** Trocea el mensaje segun el framing de Vector y lo encola. */
    fun send(message: ByteArray) {
        val packets = BleFraming.split(message, PACKET_SIZE)
        synchronized(writeQueue) { packets.forEach { writeQueue.add(it) } }
        pumpWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWriteQueue() {
        val packet: ByteArray
        synchronized(writeQueue) {
            if (writeBusy) return
            // peek, no poll: solo sale de la cola cuando Android confirma.
            packet = writeQueue.peek() ?: return
            writeBusy = true
        }
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            synchronized(writeQueue) {
                writeQueue.clear()
                writeAttempts = 0
                writeBusy = false
            }
            return
        }
        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.value = packet
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok) retryCurrentPacket()
        } catch (e: SecurityException) {
            synchronized(writeQueue) {
                writeQueue.clear()
                writeAttempts = 0
                writeBusy = false
            }
            handler.post { onError?.invoke(s(R.string.err_no_permissions)) }
        }
    }

    /**
     * Reintenta el MISMO paquete. Si ni asi sale, se tira el mensaje entero: es
     * preferible a mandarle al robot una version a la que le falta un trozo.
     */
    private fun retryCurrentPacket() {
        val giveUp: Boolean
        synchronized(writeQueue) {
            writeBusy = false
            writeAttempts++
            giveUp = writeAttempts > MAX_WRITE_ATTEMPTS
            if (giveUp) {
                writeQueue.clear()
                writeAttempts = 0
            }
        }
        if (giveUp) {
            handler.post { onDiagnostic?.invoke("ble: escritura fallida, mensaje descartado") }
        } else {
            handler.postDelayed({ pumpWriteQueue() }, 60)
        }
    }
}
