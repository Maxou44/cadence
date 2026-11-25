package io.opendomyos.ble

import io.opendomyos.ble.domyos.DomyosFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Simulateur de console Domyos (vélo) implémentant [BleLink].
 * - protocole Domyos : répond aux trames, découpe en paquets de 20 octets, peut ajouter
 *   des octets parasites et ignorer des requêtes (pour tester timeouts/renvois) ;
 * - FTMS : features, plages, Control Point avec indications, Indoor Bike Data.
 */
class FakeConsole(
    val withDomyos: Boolean = true,
    val withFtms: Boolean = false,
    val equipmentIdBcd: String = "0008640779", // EB 520
    val modelNumber: String = "8806819",
) : BleLink {
    val written = mutableListOf<Pair<UUID, ByteArray>>()
    val notified = mutableListOf<UUID>()
    var dropNextRequests = 0
    var garbagePrefix = byteArrayOf()

    // état simulé
    var resistance = 1; var fan = 0; var hotKey = 0; var watts = 120; var rpm = 75
    var speedX10 = 254; var kcal = 37; var distX10 = 52; var hr = 0; var pressedButton = 0
    var cleared = false; var beep = -1
    var ftmsResistanceRaw = -1; var ftmsPower = -1; var ftmsControl = false; var ftmsStarted = false

    private val _n = MutableSharedFlow<GattNotification>(extraBufferCapacity = 256)
    override val notifications: SharedFlow<GattNotification> = _n

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = when (service) {
        BleUuids.DOMYOS_SERVICE -> withDomyos
        BleUuids.FTMS_SERVICE -> withFtms && characteristic in setOf(
            BleUuids.FTMS_MACHINE_FEATURE, BleUuids.FTMS_INDOOR_BIKE_DATA, BleUuids.FTMS_CONTROL_POINT,
            BleUuids.FTMS_MACHINE_STATUS, BleUuids.FTMS_SUPPORTED_RESISTANCE_RANGE, BleUuids.FTMS_SUPPORTED_POWER_RANGE)
        BleUuids.DEVICE_INFORMATION_SERVICE -> withFtms
        else -> false
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID, indication: Boolean) {
        notified += characteristic
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray = when (characteristic) {
        // features : cadence, distance, résistance, énergie, HR, temps écoulé, puissance ; cibles : résistance + puissance
        BleUuids.FTMS_MACHINE_FEATURE -> le32(0b0100_1110_1000_0110) + le32(0b1100)
        BleUuids.FTMS_SUPPORTED_RESISTANCE_RANGE -> le16(10) + le16(320) + le16(10) // 1.0..32.0 pas 1.0
        BleUuids.FTMS_SUPPORTED_POWER_RANGE -> le16(25) + le16(1000) + le16(5)
        BleUuids.DIS_MODEL_NUMBER -> modelNumber.toByteArray()
        else -> error("read inattendu $characteristic")
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
        written += characteristic to value
        when (characteristic) {
            BleUuids.DOMYOS_WRITE -> onDomyos(value)
            BleUuids.FTMS_CONTROL_POINT -> onFtmsControl(value)
            else -> error("write inattendu $characteristic")
        }
    }

    // ---------------- Domyos ----------------
    private suspend fun onDomyos(req: ByteArray) {
        require(DomyosFrame.checksum(req, req.size - 1) == req.last()) { "checksum requête invalide" }
        require(req[0] == 0xF0.toByte())
        if (dropNextRequests > 0) { dropNextRequests--; return }
        val op = req[1].toInt() and 0xFF
        val payload: ByteArray = when (op) {
            0xC9 -> equipmentIdBcd.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            0xAC -> infoPayload()
            0xAD -> { if (req[10] != (-1).toByte()) resistance = req[10].toInt() and 0xFF
                      val w = ((req[15].toInt() and 0xFF) shl 8) or (req[16].toInt() and 0xFF); if (w != 0xFFFF) watts = w
                      req.copyOfRange(2, 22) }
            0xC8 -> { hotKey = req[2].toInt(); byteArrayOf(req[2]) }
            0xC4 -> { if (req[2].toInt() == 3) { cleared = true; kcal = 0; distX10 = 0 }; byteArrayOf(0) }
            0xCA -> { fan = req[2].toInt(); byteArrayOf(req[2]) }
            0xAF -> { beep = req[2].toInt(); byteArrayOf(req[2]) }
            0xA3 -> byteArrayOf(2, 7, 0, 0)
            0xA4 -> byteArrayOf(19, 3, 0x39, 0x00, 0x30, 0, 0, 0, 0, 0)
            0xA5 -> byteArrayOf(0x01, 0x2C, 0, 0)
            0xAB -> byteArrayOf(0x04, 0xD2.toByte(), 0, 0, 0)
            0xA6 -> byteArrayOf(5)
            0xCB, 0xCD -> req.copyOfRange(2, 26)
            0xE0 -> byteArrayOf(req[2], 0, 45, 0, 152.toByte(), 1, 44, 0, 203.toByte(), 128.toByte())
            else -> return // opcode inconnu : pas de réponse
        }
        val resp = DomyosFrame.build(DomyosFrame.responseOpcode(op), payload)
        val stream = garbagePrefix + resp
        stream.toList().chunked(20).forEach { _n.emit(GattNotification(BleUuids.DOMYOS_NOTIFY, it.toByteArray())) }
    }

    private fun infoPayload(): ByteArray {
        val p = ByteArray(23)
        // indices absolus dans la trame : on écrit à (abs-2) dans le payload
        fun w16(abs: Int, v: Int) { p[abs - 2] = (v shr 8).toByte(); p[abs - 1] = v.toByte() }
        fun w8(abs: Int, v: Int) { p[abs - 2] = v.toByte() }
        w16(2, 1234); w16(4, watts); w16(6, speedX10); w16(8, rpm); w16(10, kcal); w16(12, distX10)
        w8(14, resistance); w8(15, 0); w8(16, 1); w16(17, hr); w16(19, 231); w8(22, pressedButton); w8(23, fan); w8(24, hotKey)
        return p
    }

    // ---------------- FTMS ----------------
    private suspend fun onFtmsControl(req: ByteArray) {
        val op = req[0].toInt() and 0xFF
        val result = when (op) {
            0x00 -> { ftmsControl = true; 1 }
            else -> if (!ftmsControl) 5 else when (op) {
                0x04 -> { ftmsResistanceRaw = req[1].toInt() and 0xFF; 1 }
                0x05 -> { ftmsPower = (req[1].toInt() and 0xFF) or (req[2].toInt() shl 8); 1 }
                0x07 -> { ftmsStarted = true; 1 }
                0x08 -> { ftmsStarted = false; 1 }
                else -> 2
            }
        }
        _n.emit(GattNotification(BleUuids.FTMS_CONTROL_POINT, byteArrayOf(0x80.toByte(), op.toByte(), result.toByte())))
    }

    /** Envoie une Indoor Bike Data : vitesse 25,40 km/h, cadence 80 rpm, distance 1234 m, résistance 8, 150 W, HR 0, 600 s. */
    suspend fun emitIndoorBikeData() {
        val flags = (1 shl 2) or (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 9) or (1 shl 11)
        val v = le16(flags) + le16(2540) + le16(160) + byteArrayOf(0xD2.toByte(), 0x04, 0) + le16(8) + le16(150) + byteArrayOf(0) + le16(600)
        _n.emit(GattNotification(BleUuids.FTMS_INDOOR_BIKE_DATA, v))
    }

    companion object {
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }
}
