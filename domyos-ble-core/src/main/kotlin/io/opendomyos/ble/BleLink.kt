package io.opendomyos.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Une notification/indication reçue d'une caractéristique. */
class GattNotification(val characteristic: UUID, val value: ByteArray)

/**
 * Abstraction minimale d'une connexion GATT déjà établie et dont les services sont découverts.
 *
 * Le cœur du protocole ne dépend que de cette interface : l'implémentation Android
 * ([io.opendomyos.ble.android.AndroidBleLink]) l'adapte à `BluetoothGatt`, et les tests
 * l'implémentent avec un simulateur de console. Toute plateforme (iOS, desktop…) peut
 * fournir la sienne.
 *
 * Toutes les opérations suspendues doivent être sérialisées par l'implémentation
 * (une seule opération GATT à la fois) et lever une exception en cas d'échec.
 */
interface BleLink {
    /** Vrai si la caractéristique existe sur l'appareil connecté. */
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** Écrit une valeur. Les valeurs plus longues que MTU-3 doivent être gérées (long write ou MTU élevé). */
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean = true)

    suspend fun read(service: UUID, characteristic: UUID): ByteArray

    /** Active les notifications (ou indications si [indication]) en écrivant le CCCD. */
    suspend fun enableNotifications(service: UUID, characteristic: UUID, indication: Boolean = false)

    /** Flux chaud de toutes les notifications/indications reçues. */
    val notifications: Flow<GattNotification>
}

class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)
