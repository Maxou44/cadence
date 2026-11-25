package io.opendomyos.ble.ftms

/**
 * Décodeurs des caractéristiques FTMS (Bluetooth SIG, Fitness Machine Service v1.0).
 * Tout est en little-endian.
 */
internal class LeReader(private val b: ByteArray) {
    var pos = 0
    val remaining get() = b.size - pos
    fun u8() = (b[pos++].toInt() and 0xFF)
    fun u16() = u8() or (u8() shl 8)
    fun s16() = u16().toShort().toInt()
    fun u24() = u8() or (u8() shl 8) or (u8() shl 16)
    fun u32() = u16().toLong() or (u16().toLong() shl 16)
}

/**
 * Indoor Bike Data (0x2AD2). Les champs absents de la notification valent null.
 * Une mesure peut être répartie sur plusieurs notifications (bit « More Data ») :
 * utiliser [mergedWith] pour cumuler.
 */
data class IndoorBikeData(
    val speedKmh: Float? = null,
    val averageSpeedKmh: Float? = null,
    val cadenceRpm: Float? = null,
    val averageCadenceRpm: Float? = null,
    val totalDistanceMeters: Int? = null,
    val resistanceLevel: Int? = null,
    val powerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val totalKcal: Int? = null,
    val kcalPerHour: Int? = null,
    val kcalPerMinute: Int? = null,
    val heartRateBpm: Int? = null,
    val metabolicEquivalent: Float? = null,
    val elapsedSeconds: Int? = null,
    val remainingSeconds: Int? = null,
) {
    fun mergedWith(newer: IndoorBikeData) = IndoorBikeData(
        speedKmh = newer.speedKmh ?: speedKmh,
        averageSpeedKmh = newer.averageSpeedKmh ?: averageSpeedKmh,
        cadenceRpm = newer.cadenceRpm ?: cadenceRpm,
        averageCadenceRpm = newer.averageCadenceRpm ?: averageCadenceRpm,
        totalDistanceMeters = newer.totalDistanceMeters ?: totalDistanceMeters,
        resistanceLevel = newer.resistanceLevel ?: resistanceLevel,
        powerWatts = newer.powerWatts ?: powerWatts,
        averagePowerWatts = newer.averagePowerWatts ?: averagePowerWatts,
        totalKcal = newer.totalKcal ?: totalKcal,
        kcalPerHour = newer.kcalPerHour ?: kcalPerHour,
        kcalPerMinute = newer.kcalPerMinute ?: kcalPerMinute,
        heartRateBpm = newer.heartRateBpm ?: heartRateBpm,
        metabolicEquivalent = newer.metabolicEquivalent ?: metabolicEquivalent,
        elapsedSeconds = newer.elapsedSeconds ?: elapsedSeconds,
        remainingSeconds = newer.remainingSeconds ?: remainingSeconds,
    )

    companion object {
        fun parse(value: ByteArray): IndoorBikeData {
            val r = LeReader(value)
            val flags = r.u16()
            fun has(bit: Int) = flags and (1 shl bit) != 0
            // bit 0 « More Data » est inversé : 0 => vitesse instantanée présente
            val speed = if (!has(0)) r.u16() / 100f else null
            val avgSpeed = if (has(1)) r.u16() / 100f else null
            val cadence = if (has(2)) r.u16() / 2f else null
            val avgCadence = if (has(3)) r.u16() / 2f else null
            val distance = if (has(4)) r.u24() else null
            val resistance = if (has(5)) r.s16() else null
            val power = if (has(6)) r.s16() else null
            val avgPower = if (has(7)) r.s16() else null
            var kcal: Int? = null; var kcalH: Int? = null; var kcalM: Int? = null
            if (has(8)) { kcal = r.u16(); kcalH = r.u16(); kcalM = r.u8() }
            val hr = if (has(9)) r.u8() else null
            val met = if (has(10)) r.u8() / 10f else null
            val elapsed = if (has(11)) r.u16() else null
            val remaining = if (has(12)) r.u16() else null
            return IndoorBikeData(
                speed, avgSpeed, cadence, avgCadence, distance, resistance, power, avgPower,
                kcal?.takeIf { it != 0xFFFF }, kcalH?.takeIf { it != 0xFFFF }, kcalM?.takeIf { it != 0xFF },
                hr, met, elapsed, remaining,
            )
        }
    }
}

/** Fitness Machine Feature (0x2ACC). */
data class FtmsFeatures(val machineFeatures: Long, val targetSettingFeatures: Long) {
    fun supportsMachine(bit: Int) = machineFeatures and (1L shl bit) != 0L
    fun supportsTarget(bit: Int) = targetSettingFeatures and (1L shl bit) != 0L

    val canSetSpeed get() = supportsTarget(0)
    val canSetInclination get() = supportsTarget(1)
    val canSetResistance get() = supportsTarget(2)
    val canSetPower get() = supportsTarget(3)
    val canSimulateIndoorBike get() = supportsTarget(13)

    companion object {
        fun parse(v: ByteArray): FtmsFeatures { val r = LeReader(v); return FtmsFeatures(r.u32(), r.u32()) }
    }
}

/** Plage supportée (0x2AD4..0x2AD8) : min / max / incrément, déjà mis à l'échelle. */
data class FtmsRange(val min: Float, val max: Float, val increment: Float) {
    companion object {
        /** Résistance (0x2AD6) et inclinaison (0x2AD5) : s16, s16, u16, résolution 0,1. */
        fun parseTenths(v: ByteArray): FtmsRange { val r = LeReader(v); return FtmsRange(r.s16() / 10f, r.s16() / 10f, r.u16() / 10f) }
        /** Puissance (0x2AD8) : s16, s16, u16 en watts. */
        fun parsePower(v: ByteArray): FtmsRange { val r = LeReader(v); return FtmsRange(r.s16().toFloat(), r.s16().toFloat(), r.u16().toFloat()) }
        /** Vitesse (0x2AD4) : u16 ×3, résolution 0,01 km/h. */
        fun parseSpeed(v: ByteArray): FtmsRange { val r = LeReader(v); return FtmsRange(r.u16() / 100f, r.u16() / 100f, r.u16() / 100f) }
    }
}

/** Opcodes du Fitness Machine Control Point (0x2AD9). */
object FtmsOpCode {
    const val REQUEST_CONTROL = 0x00
    const val RESET = 0x01
    const val SET_TARGET_SPEED = 0x02
    const val SET_TARGET_INCLINATION = 0x03
    const val SET_TARGET_RESISTANCE = 0x04
    const val SET_TARGET_POWER = 0x05
    const val SET_TARGET_HEART_RATE = 0x06
    const val START_OR_RESUME = 0x07
    const val STOP_OR_PAUSE = 0x08
    const val SET_INDOOR_BIKE_SIMULATION = 0x11
    const val RESPONSE_CODE = 0x80

    const val PARAM_STOP = 0x01
    const val PARAM_PAUSE = 0x02
}

enum class FtmsResult(val code: Int) {
    SUCCESS(1), NOT_SUPPORTED(2), INVALID_PARAMETER(3), OPERATION_FAILED(4), CONTROL_NOT_PERMITTED(5), UNKNOWN(-1);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN }
}

/** Encodage des requêtes du Control Point. */
object FtmsControl {
    fun requestControl() = byteArrayOf(FtmsOpCode.REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(FtmsOpCode.RESET.toByte())
    fun startOrResume() = byteArrayOf(FtmsOpCode.START_OR_RESUME.toByte())
    fun stop() = byteArrayOf(FtmsOpCode.STOP_OR_PAUSE.toByte(), FtmsOpCode.PARAM_STOP.toByte())
    fun pause() = byteArrayOf(FtmsOpCode.STOP_OR_PAUSE.toByte(), FtmsOpCode.PARAM_PAUSE.toByte())

    /** Résistance : u8, résolution 0,1 (c'est aussi l'encodage du SDK Domyos). */
    fun targetResistance(level: Float): ByteArray {
        val raw = Math.round(level * 10f).coerceIn(0, 255)
        return byteArrayOf(FtmsOpCode.SET_TARGET_RESISTANCE.toByte(), raw.toByte())
    }

    /** Puissance : s16 LE, en watts. */
    fun targetPower(watts: Int) = byteArrayOf(FtmsOpCode.SET_TARGET_POWER.toByte(), watts.toByte(), (watts shr 8).toByte())

    /** Inclinaison : s16 LE, résolution 0,1 %. */
    fun targetInclination(percent: Float): ByteArray {
        val raw = Math.round(percent * 10f)
        return byteArrayOf(FtmsOpCode.SET_TARGET_INCLINATION.toByte(), raw.toByte(), (raw shr 8).toByte())
    }

    /** Vitesse : u16 LE, résolution 0,01 km/h. */
    fun targetSpeed(kmh: Float): ByteArray {
        val raw = Math.round(kmh * 100f)
        return byteArrayOf(FtmsOpCode.SET_TARGET_SPEED.toByte(), raw.toByte(), (raw shr 8).toByte())
    }

    /**
     * Simulation (type Zwift) : vent m/s (0,001), pente % (0,01), Crr (0,0001), Cw kg/m (0,01).
     */
    fun indoorBikeSimulation(windMps: Float, gradePercent: Float, crr: Float = 0.004f, cw: Float = 0.51f): ByteArray {
        val wind = Math.round(windMps * 1000f); val grade = Math.round(gradePercent * 100f)
        return byteArrayOf(
            FtmsOpCode.SET_INDOOR_BIKE_SIMULATION.toByte(),
            wind.toByte(), (wind shr 8).toByte(), grade.toByte(), (grade shr 8).toByte(),
            Math.round(crr * 10000f).toByte(), Math.round(cw * 100f).toByte(),
        )
    }
}

/** Réponse du Control Point : `80 <opcode requête> <résultat>`. */
data class FtmsControlResponse(val requestOpCode: Int, val result: FtmsResult) {
    companion object {
        fun parse(v: ByteArray): FtmsControlResponse? =
            if (v.size >= 3 && (v[0].toInt() and 0xFF) == FtmsOpCode.RESPONSE_CODE)
                FtmsControlResponse(v[1].toInt() and 0xFF, FtmsResult.of(v[2].toInt() and 0xFF))
            else null
    }
}

/** Événements « Fitness Machine Status » (0x2ADA) les plus utiles. */
sealed class FtmsMachineStatus {
    object Reset : FtmsMachineStatus()
    data class StoppedOrPaused(val paused: Boolean) : FtmsMachineStatus()
    object StoppedBySafetyKey : FtmsMachineStatus()
    object StartedOrResumed : FtmsMachineStatus()
    data class TargetResistanceChanged(val level: Float) : FtmsMachineStatus()
    data class TargetPowerChanged(val watts: Int) : FtmsMachineStatus()
    object ControlPermissionLost : FtmsMachineStatus()
    data class Other(val opCode: Int, val raw: ByteArray) : FtmsMachineStatus()

    companion object {
        fun parse(v: ByteArray): FtmsMachineStatus {
            if (v.isEmpty()) return Other(-1, v)
            val r = LeReader(v)
            return when (val op = r.u8()) {
                0x01 -> Reset
                0x02 -> StoppedOrPaused(paused = r.remaining > 0 && r.u8() == FtmsOpCode.PARAM_PAUSE)
                0x03 -> StoppedBySafetyKey
                0x04 -> StartedOrResumed
                0x07 -> if (r.remaining >= 1) TargetResistanceChanged(r.u8() / 10f) else Other(op, v)
                0x08 -> if (r.remaining >= 2) TargetPowerChanged(r.s16()) else Other(op, v)
                0xFF -> ControlPermissionLost
                else -> Other(op, v)
            }
        }
    }
}
