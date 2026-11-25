package io.opendomyos.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import io.opendomyos.ble.BleException
import io.opendomyos.ble.BleLink
import io.opendomyos.ble.BleUuids
import io.opendomyos.ble.GattNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.util.UUID

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/**
 * Implémentation Android de [BleLink] au-dessus de `BluetoothGatt`.
 *
 * - Toutes les opérations GATT sont sérialisées (Android n'en tolère qu'une à la fois).
 * - Compatible API 26+ ; utilise les nouvelles signatures (API 33) quand elles existent.
 * - Reprend les réglages du SDK d'origine : TRANSPORT_LE, pas de négociation MTU par défaut
 *   (les trames de 23/27 octets passent en « long write »), délai avant activation des
 *   notifications (1 s dans l'original, configurable).
 *
 * Permissions requises : BLUETOOTH_CONNECT (Android 12+) ; BLUETOOTH + BLUETOOTH_ADMIN avant.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink(
    private val context: Context,
    val device: BluetoothDevice,
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) : BleLink {

    data class Config(
        val connectTimeoutMs: Long = 15_000,
        val operationTimeoutMs: Long = 5_000,
        val connectRetries: Int = 2,
        /** Pause avant chaque activation de notification (1000 ms dans le SDK d'origine). */
        val notificationEnableDelayMs: Long = 1_000,
        /** Si non nul, demande ce MTU après la connexion (ex. 247). null = comportement d'origine. */
        val requestMtu: Int? = null,
    )

    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var gatt: BluetoothGatt? = null

    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<GattNotification>(extraBufferCapacity = 512)
    override val notifications: SharedFlow<GattNotification> = _notifications.asSharedFlow()

    var mtu: Int = 23; private set

    // ------------------------------------------------------------------ connexion

    /** Connecte, découvre les services et (optionnellement) négocie le MTU. */
    suspend fun connect() {
        var lastError: Throwable? = null
        repeat(config.connectRetries + 1) { attempt ->
            try {
                connectOnce()
                return
            } catch (e: Throwable) {
                lastError = e
                log("Connexion échouée (essai ${attempt + 1}) : ${e.message}")
                closeGatt()
                delay(600)
            }
        }
        throw BleException("Impossible de se connecter à ${device.address}", lastError)
    }

    private suspend fun connectOnce() {
        _state.value = LinkState.CONNECTING
        val connected = CompletableDeferred<Unit>()
        connectDeferred = connected
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
        withTimeout(config.connectTimeoutMs) { connected.await() }

        val g = gatt ?: throw BleException("GATT fermé")
        op("discoverServices") { if (!g.discoverServices()) throw BleException("discoverServices refusé") }
        config.requestMtu?.let { wanted ->
            runCatching { op("requestMtu") { if (!g.requestMtu(wanted)) throw BleException("requestMtu refusé") } }
                .onFailure { log("MTU non négocié : ${it.message}") }
        }
        log("Connecté à ${device.address}, services : ${g.services.map { it.uuid }}")
    }

    /** Déconnexion propre : disconnect(), attente du callback (1 s max), puis close(). */
    suspend fun disconnect() {
        val g = gatt ?: return
        _state.value = LinkState.DISCONNECTING
        g.disconnect()
        withTimeoutOrNull(1_000) { state.first { it == LinkState.DISCONNECTED } }
        closeGatt()
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        _state.value = LinkState.DISCONNECTED
        pending?.completeExceptionally(BleException("Déconnecté"))
        pending = null
    }

    // ------------------------------------------------------------------ BleLink

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    private fun char(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: throw BleException("Caractéristique introuvable : $characteristic")

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
        val c = char(service, characteristic)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val g = gatt ?: throw BleException("Non connecté")
        op("write $characteristic") {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BLUETOOTH_STATUS_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
            }
            if (!ok) throw BleException("writeCharacteristic refusé")
        }
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = char(service, characteristic)
        val g = gatt ?: throw BleException("Non connecté")
        return op("read $characteristic") {
            if (!g.readCharacteristic(c)) throw BleException("readCharacteristic refusé")
        } as ByteArray
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID, indication: Boolean) {
        val c = char(service, characteristic)
        val g = gatt ?: throw BleException("Non connecté")
        if (config.notificationEnableDelayMs > 0) delay(config.notificationEnableDelayMs)
        if (!g.setCharacteristicNotification(c, true)) throw BleException("setCharacteristicNotification refusé")
        val cccd = c.getDescriptor(BleUuids.CCCD) ?: return // pas de CCCD : rien à écrire
        val v = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("CCCD $characteristic") {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, v) == BLUETOOTH_STATUS_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = v; g.writeDescriptor(cccd) }
            }
            if (!ok) throw BleException("writeDescriptor refusé")
        }
    }

    // ------------------------------------------------------------------ file d'opérations

    /** Lance une opération GATT et attend son callback ; une seule à la fois. */
    private suspend fun op(name: String, start: () -> Unit): Any? = opMutex.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            start()
            withTimeout(config.operationTimeoutMs) { d.await() }
        } catch (e: Throwable) {
            throw if (e is BleException) e else BleException("$name : ${e.message}", e)
        } finally {
            pending = null
        }
    }

    private fun finish(status: Int, result: Any?, what: String) {
        val d = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) d.complete(result)
        else d.completeExceptionally(BleException("$what : statut GATT $status"))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("Connexion : status=$status state=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = LinkState.CONNECTED
                connectDeferred?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.completeExceptionally(BleException("Déconnexion (statut $status)"))
                g.close()
                if (gatt === g) gatt = null
                _state.value = LinkState.DISCONNECTED
                pending?.completeExceptionally(BleException("Déconnecté (statut $status)"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, null, "discoverServices")

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            finish(status, newMtu, "requestMtu")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(status, null, "write")

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status, null, "writeDescriptor")

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value.copyOf(), "read")

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish(status, c.value?.copyOf() ?: ByteArray(0), "read")
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(GattNotification(c.uuid, value.copyOf()))
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) _notifications.tryEmit(GattNotification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
        }
    }

    private companion object {
        /** BluetoothStatusCodes.SUCCESS (API 31+). */
        const val BLUETOOTH_STATUS_SUCCESS = 0
    }
}
