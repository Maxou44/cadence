package io.opendomyos.ble.android

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.opendomyos.ble.ExerciseBike
import io.opendomyos.ble.Protocol
import kotlinx.coroutines.CoroutineScope

/** Une connexion active : la liaison GATT + le vélo initialisé. */
class BikeConnection(val link: AndroidBleLink, val bike: ExerciseBike) {
    suspend fun close() {
        bike.close()
        link.disconnect()
    }
}

/**
 * Raccourci Android : connecte la console, choisit le protocole et lance le polling.
 *
 * ```kotlin
 * val conn = DomyosConnector.connect(context, discovered.device, lifecycleScope)
 * conn.bike.data.collect { ui.render(it) }
 * ```
 */
object DomyosConnector {
    suspend fun connect(
        context: Context,
        device: BluetoothDevice,
        scope: CoroutineScope,
        preferred: Protocol? = null,
        clearSession: Boolean = true,
        config: AndroidBleLink.Config = AndroidBleLink.Config(),
        log: (String) -> Unit = {},
    ): BikeConnection {
        val link = AndroidBleLink(context.applicationContext, device, config, log)
        link.connect()
        return try {
            BikeConnection(link, ExerciseBike.open(link, scope, preferred, clearSession, log))
        } catch (e: Throwable) {
            link.disconnect()
            throw e
        }
    }
}
