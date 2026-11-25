package io.opendomyos.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.opendomyos.ble.BleException
import io.opendomyos.ble.BleUuids
import io.opendomyos.ble.EquipmentKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredEquipment(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val rssi: Int,
    val kind: EquipmentKind,
    /** L'annonce contient le service FTMS (0x1826). */
    val advertisesFtms: Boolean,
)

/**
 * Scan BLE des consoles Domyos. Comme le SDK d'origine, le scan n'est pas filtré côté
 * contrôleur (beaucoup de consoles n'annoncent pas d'UUID) ; on filtre sur le nom
 * (`Domyos-…`) ou la présence du service FTMS.
 *
 * Permissions : BLUETOOTH_SCAN (Android 12+, avec `neverForLocation` possible),
 * ACCESS_FINE_LOCATION avant Android 12.
 */
@SuppressLint("MissingPermission")
class DomyosScanner(private val context: Context) {

    /**
     * @param includeAnyFtms remonter aussi les appareils FTMS non-Domyos (home-trainers tiers).
     */
    fun scan(includeAnyFtms: Boolean = false): Flow<DiscoveredEquipment> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw BleException("Pas d'adaptateur Bluetooth")
        if (!adapter.isEnabled) throw BleException("Bluetooth désactivé")
        val scanner = adapter.bluetoothLeScanner ?: throw BleException("Scanner BLE indisponible")
        val seen = HashSet<String>()
        val ftmsUuid = ParcelUuid(BleUuids.FTMS_SERVICE)

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name
                val ftms = result.scanRecord?.serviceUuids?.contains(ftmsUuid) == true
                val kind = EquipmentKind.fromAdvertisedName(name)
                val isDomyos = kind != EquipmentKind.UNKNOWN || (name?.lowercase()?.startsWith("domyos") == true)
                if (!isDomyos && !(includeAnyFtms && ftms)) return
                if (!seen.add(device.address)) return
                trySend(DiscoveredEquipment(device, name, device.address, result.rssi, kind, ftms))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

            override fun onScanFailed(errorCode: Int) { close(BleException("Échec du scan BLE (code $errorCode)")) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(ScanFilter.Builder().build()), settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
