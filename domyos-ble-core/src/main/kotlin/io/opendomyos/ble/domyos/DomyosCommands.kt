package io.opendomyos.ble.domyos

/**
 * Une commande du protocole Domyos : la trame à envoyer, la réponse attendue et son décodage.
 *
 * @param responseLength longueur totale attendue de la réponse (en-tête et checksum compris).
 * @param retries nombre de renvois après un timeout (valeurs reprises du SDK d'origine).
 */
class DomyosCommand<out T>(
    val name: String,
    val opcode: Int,
    val payload: ByteArray,
    val responseLength: Int,
    val retries: Int = 0,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val parser: (ByteArray) -> T,
) {
    val request: ByteArray = DomyosFrame.build(opcode, payload)
    val responseOpcode: Int = DomyosFrame.responseOpcode(opcode)

    fun parse(response: ByteArray): T = parser(response)

    override fun toString() = "$name(${request.toHex()})"

    companion object {
        /** Timeout par tentative utilisé par le SDK d'origine (2,2 s). */
        const val DEFAULT_TIMEOUT_MS = 2200L
    }
}

/**
 * Catalogue des commandes utiles pour un vélo/elliptique/rameur Domyos.
 *
 * Les commandes de mise à jour firmware (0xC5 + trames 0x55 0x55…), de redémarrage (0xA8)
 * et du menu SAV (0xA0/0xC6) existent dans le SDK mais ne sont volontairement pas exposées :
 * une erreur peut rendre la console inutilisable.
 */
object DomyosCommands {
    private fun op(v: Int) = v

    /** 0xC9 – identifiant du modèle (ex. 8806819). Renvoie null si non numérique. */
    fun getEquipmentId() = DomyosCommand("GetEquipmentId", op(0xC9), ByteArray(0), 8) { f ->
        // 5 octets affichés en hexadécimal puis lus comme un nombre décimal (codage BCD).
        val hex = (2..6).joinToString("") { String.format("%02X", f.u8(it)) }
        hex.toIntOrNull()
    }

    /** 0xAC – mesures temps réel. */
    fun getInfoValue() = DomyosCommand("GetInfoValue", op(0xAC), ByteArray(0), 26) { DomyosInfoValue.parse(it) }

    /** 0xAD – consigne de résistance / watts / vitesse / inclinaison / LEDs. Retry ×1. */
    fun setInfoValue(p: DomyosSetInfo): DomyosCommand<Unit> {
        p.validate()
        val speed = p.speedKmh?.let { (it * 10f).toInt() } ?: 0xFFFF
        val incline = p.encodedIncline() ?: 0xFFFF
        val watts = p.targetWatts ?: 0xFFFF
        val payload = byteArrayOf(
            -1, -1,
            (speed shr 8).toByte(), speed.toByte(),
            -1, -1, -1, -1,
            (p.resistanceLevel ?: 0xFF).toByte(),
            -1, -1,
            (incline shr 8).toByte(), incline.toByte(),
            (watts shr 8).toByte(), watts.toByte(),
            (p.heartRateLedColor?.code ?: 0xFF).toByte(),
            (p.bluetoothLed?.let { if (it) 1 else 0 } ?: 0xFF).toByte(),
            -1, -1, -1,
        )
        return DomyosCommand("SetInfoValue", op(0xAD), payload, 23, retries = 1) { }
    }

    /** 0xC8 – start / pause / stop. Retry ×4 comme l'original. */
    fun setHotKey(key: HotKey) =
        DomyosCommand("SetHotKey", op(0xC8), byteArrayOf(key.code.toByte()), 4, retries = 4) { }

    /** 0xC4 – données de séance ; [SessionData.CLEAR] remet les cumuls à zéro. */
    fun setSessionData(value: Int = SessionData.CLEAR) =
        DomyosCommand("SetSessionData", op(0xC4), byteArrayOf(value.toByte()), 4) { }

    /** 0xCA – ventilateur 0..5. */
    fun setFanSpeed(level: Int): DomyosCommand<Unit> {
        require(level in 0..5) { "fan level $level hors de 0..5" }
        return DomyosCommand("SetFanSpeed", op(0xCA), byteArrayOf(level.toByte()), 4) { }
    }

    /** 0xAF – bip sonore de la console. */
    fun setBeep(on: Boolean) =
        DomyosCommand("SetBeep", op(0xAF), byteArrayOf(if (on) 1 else 0), 4) { }

    /** 0xCB – zones d'affichage 1..6. Retry ×1. */
    fun setDisplayZones(page: DisplayPage) =
        DomyosCommand("SetDisplayZones", op(0xCB), page.encode(), 27, retries = 1) { }

    /** 0xCD – zones d'affichage 7..12. Retry ×1. */
    fun setDisplayZonesSecondArea(page: DisplayPage) =
        DomyosCommand("SetDisplayZonesSecondArea", op(0xCD), page.encode(), 27, retries = 1) { }

    /** 0xCC – éteindre des zones 1..6 (true = éteindre, false = inchangé). */
    fun setDisplayZonesOff(off: BooleanArray) = zonesOff("SetDisplayZonesOff", 0xCC, off)

    /** 0xCE – éteindre des zones 7..12. */
    fun setDisplayZonesOffSecondArea(off: BooleanArray) = zonesOff("SetDisplayZonesOffSecondArea", 0xCE, off)

    private fun zonesOff(name: String, opcode: Int, off: BooleanArray): DomyosCommand<Unit> {
        require(off.size == 6)
        return DomyosCommand(name, op(opcode), ByteArray(6) { if (off[it]) 1 else -1 }, 9) { }
    }

    /** 0xA3 – version firmware console « M.m.0 ». */
    fun getFirmwareVersion() = DomyosCommand("GetVersion", op(0xA3), ByteArray(0), 7) { f ->
        "${f.u8(2)}.${f.u8(3)}.0"
    }

    /** 0xA4 – numéro de série (format d'origine %02d%02d%05d, ordre d'octets d'origine conservé). */
    fun getSerialNumber() = DomyosCommand("GetSerialNumber", op(0xA4), ByteArray(0), 13) { f ->
        val n = f.u8(4) or (f.u8(5) shl 16) or (f.u8(6) shl 8)
        String.format("%02d%02d%05d", f.u8(2), f.u8(3), n)
    }

    /** 0xA5 – heures d'utilisation. */
    fun getUsageHours() = DomyosCommand("GetUsageHour", op(0xA5), ByteArray(0), 7) { it.u16be(2) }

    /** 0xAB – kilométrage cumulé console. */
    fun getCumulativeKm() = DomyosCommand("GetCumulativeKM", op(0xAB), ByteArray(0), 8) { it.u16be(2) }

    /** 0xA6 – version du module Bluetooth. */
    fun getBluetoothVersion() = DomyosCommand("GetBluetoothVersion", op(0xA6), ByteArray(0), 4) { it.u8(2) }

    /** 0xC7 – historique des codes erreur (non nuls). */
    fun getErrorHistory() = DomyosCommand("GetErrorHistoricList", op(0xC7), ByteArray(0), 13) { f ->
        (2 until f.size - 1).map { f.u8(it) }.filter { it > 0 }
    }

    /** 0xE0 – résultat d'entraînement mémorisé pour l'utilisateur [user]. */
    fun getWorkoutResult(user: Int) =
        DomyosCommand("GetWorkoutResult", op(0xE0), byteArrayOf(user.toByte()), 13) { f ->
            DomyosWorkoutResult(
                user = f.u8(2),
                totalTimeMinutes = f.u16be(3),
                totalDistanceKm = f.u16be(5) / 10f,
                totalKcal = f.u16be(7),
                averageSpeedKmh = f.u16be(9) / 10f,
                averageBpm = f.u8(11),
            )
        }

    /** 0xE1 – efface le résultat mémorisé de l'utilisateur [user]. */
    fun resetWorkoutResult(user: Int) =
        DomyosCommand("ResetWorkoutResult", op(0xE1), byteArrayOf(user.toByte()), 4) { }

    /**
     * 0xA1 – active (true) ou désactive le service FTMS de la console (consoles bi-protocole).
     * Renvoie la valeur confirmée par la console.
     */
    fun setFtmsEnabled(enabled: Boolean) =
        DomyosCommand("SetFTMS", op(0xA1), byteArrayOf(if (enabled) 1 else 0), 4) { it.u8(2) }
}
