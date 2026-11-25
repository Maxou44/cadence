package io.opendomyos.ble

import java.util.UUID

/**
 * Tous les UUID GATT utilisés par les consoles Domyos (E Connected).
 *
 * Deux familles coexistent sur les consoles :
 *  - le protocole propriétaire Domyos, transporté sur le service « Transparent UART »
 *    du module Microchip/ISSC (BM70/RN4870) ;
 *  - le standard Bluetooth SIG « Fitness Machine Service » (FTMS) sur les consoles récentes.
 */
object BleUuids {
    private fun sig16(short: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", short))

    /** Descripteur CCCD standard (activation notifications/indications). */
    val CCCD: UUID = sig16(0x2902)

    // ---- Protocole propriétaire Domyos (Microchip Transparent UART) ----
    val DOMYOS_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
    /** Console -> téléphone (notify). */
    val DOMYOS_NOTIFY: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
    /** Téléphone -> console (write). */
    val DOMYOS_WRITE: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")

    // ---- FTMS ----
    val FTMS_SERVICE: UUID = sig16(0x1826)
    val FTMS_MACHINE_FEATURE: UUID = sig16(0x2ACC)
    val FTMS_TREADMILL_DATA: UUID = sig16(0x2ACD)
    val FTMS_CROSS_TRAINER_DATA: UUID = sig16(0x2ACE)
    val FTMS_ROWER_DATA: UUID = sig16(0x2AD1)
    val FTMS_INDOOR_BIKE_DATA: UUID = sig16(0x2AD2)
    val FTMS_TRAINING_STATUS: UUID = sig16(0x2AD3)
    val FTMS_SUPPORTED_SPEED_RANGE: UUID = sig16(0x2AD4)
    val FTMS_SUPPORTED_INCLINATION_RANGE: UUID = sig16(0x2AD5)
    val FTMS_SUPPORTED_RESISTANCE_RANGE: UUID = sig16(0x2AD6)
    val FTMS_SUPPORTED_HEART_RATE_RANGE: UUID = sig16(0x2AD7)
    val FTMS_SUPPORTED_POWER_RANGE: UUID = sig16(0x2AD8)
    val FTMS_CONTROL_POINT: UUID = sig16(0x2AD9)
    val FTMS_MACHINE_STATUS: UUID = sig16(0x2ADA)

    // ---- Device Information ----
    val DEVICE_INFORMATION_SERVICE: UUID = sig16(0x180A)
    val DIS_MODEL_NUMBER: UUID = sig16(0x2A24)
    val DIS_SERIAL_NUMBER: UUID = sig16(0x2A25)
    val DIS_FIRMWARE_REVISION: UUID = sig16(0x2A26)
    val DIS_HARDWARE_REVISION: UUID = sig16(0x2A27)
    val DIS_SOFTWARE_REVISION: UUID = sig16(0x2A28)
    val DIS_MANUFACTURER_NAME: UUID = sig16(0x2A29)

    // ---- User Data Service (utilisé par l'app pour poids/taille/âge en FTMS) ----
    val USER_DATA_SERVICE: UUID = sig16(0x181C)
    val UDS_AGE: UUID = sig16(0x2A80)
    val UDS_GENDER: UUID = sig16(0x2A8C)
    val UDS_HEIGHT: UUID = sig16(0x2A8E)
    val UDS_WEIGHT: UUID = sig16(0x2A98)
    val UDS_USER_INDEX: UUID = sig16(0x2A9A)
    val UDS_USER_CONTROL_POINT: UUID = sig16(0x2A9F)
}
