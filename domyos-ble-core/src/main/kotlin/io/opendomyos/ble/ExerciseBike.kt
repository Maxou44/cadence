package io.opendomyos.ble

import io.opendomyos.ble.domyos.DomyosProtocolClient
import io.opendomyos.ble.domyos.HotKey
import io.opendomyos.ble.ftms.FtmsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Mesures unifiées, quel que soit le protocole. Les champs inconnus valent null. */
data class BikeData(
    val protocol: Protocol,
    val speedKmh: Float?,
    val cadenceRpm: Float?,
    val powerWatts: Int?,
    val resistanceLevel: Int?,
    val heartRateBpm: Int?,
    val distanceKm: Float?,
    val kcal: Int?,
    /** Fourni par FTMS uniquement ; en protocole Domyos, c'est à l'app de chronométrer. */
    val elapsedSeconds: Int?,
    /** Domyos uniquement. */
    val fanSpeedLevel: Int? = null,
    /** Domyos uniquement : état start/pause/stop de la console. */
    val consoleState: HotKey? = null,
    /** Domyos uniquement : code erreur console (0 = OK). */
    val errorNumber: Int? = null,
)

/**
 * Point d'entrée principal : choisit le protocole, initialise la console et expose
 * un flux de [BikeData] et des commandes communes.
 *
 * ```kotlin
 * val bike = ExerciseBike.open(link, scope)
 * bike.data.collect { println("${it?.powerWatts} W, ${it?.cadenceRpm} rpm") }
 * bike.setResistance(8)
 * ```
 * Les fonctions spécifiques sont accessibles via [domyos] (afficheur, ventilateur, bip…)
 * ou [ftms] (simulation de pente, plages supportées…).
 */
class ExerciseBike private constructor(
    val protocol: Protocol,
    val domyos: DomyosProtocolClient?,
    val ftms: FtmsClient?,
    private val scope: CoroutineScope,
) {
    private val _data = MutableStateFlow<BikeData?>(null)
    val data: StateFlow<BikeData?> = _data.asStateFlow()

    val equipmentId: Int? get() = domyos?.consoleInfo?.equipmentId ?: ftms?.equipmentId
    val model: KnownModel? get() = EquipmentCatalog.findByEquipmentId(equipmentId)

    private fun bind() {
        domyos?.let { d ->
            scope.launch {
                d.live.filterNotNull().collect { v ->
                    _data.value = BikeData(
                        protocol = Protocol.DOMYOS,
                        speedKmh = v.speedKmh,
                        cadenceRpm = v.cadenceRpm.toFloat(),
                        powerWatts = v.powerWatts,
                        resistanceLevel = v.resistanceLevel,
                        heartRateBpm = v.heartRateBpm.takeIf { it > 0 },
                        distanceKm = v.sessionDistanceKm,
                        kcal = v.sessionKcal,
                        elapsedSeconds = null,
                        fanSpeedLevel = v.fanSpeedLevel,
                        consoleState = v.hotKey,
                        errorNumber = v.errorNumber,
                    )
                }
            }
        }
        ftms?.let { f ->
            scope.launch {
                f.bikeData.filterNotNull().collect { v ->
                    _data.value = BikeData(
                        protocol = Protocol.FTMS,
                        speedKmh = v.speedKmh,
                        cadenceRpm = v.cadenceRpm,
                        powerWatts = v.powerWatts,
                        resistanceLevel = v.resistanceLevel,
                        heartRateBpm = v.heartRateBpm?.takeIf { it > 0 },
                        distanceKm = v.totalDistanceMeters?.let { it / 1000f },
                        kcal = v.totalKcal,
                        elapsedSeconds = v.elapsedSeconds,
                    )
                }
            }
        }
    }

    suspend fun setResistance(level: Int) {
        domyos?.setResistance(level) ?: ftms!!.setResistance(level.toFloat())
    }

    suspend fun setTargetPower(watts: Int) {
        domyos?.setTargetWatts(watts) ?: ftms!!.setTargetPower(watts)
    }

    suspend fun start() { domyos?.start() ?: ftms!!.start() }
    suspend fun pause() { domyos?.pause() ?: ftms!!.pause() }
    suspend fun stop() { domyos?.stop() ?: ftms!!.stop() }

    /** Arrête le polling et les collecteurs. La déconnexion GATT reste à la charge du [BleLink]. */
    fun close() {
        domyos?.stopPolling()
        scope.cancel()
    }

    companion object {
        /**
         * Initialise la console sur une liaison GATT connectée (services découverts).
         *
         * @param preferred protocole souhaité ; par défaut le protocole Domyos est préféré quand
         *   il est disponible (plus complet : afficheur, ventilateur, boutons), sinon FTMS.
         * @param clearSession remise à zéro des cumuls de la console (protocole Domyos).
         */
        suspend fun open(
            link: BleLink,
            parentScope: CoroutineScope,
            preferred: Protocol? = null,
            clearSession: Boolean = true,
            log: (String) -> Unit = {},
        ): ExerciseBike {
            val hasDomyos = link.hasCharacteristic(BleUuids.DOMYOS_SERVICE, BleUuids.DOMYOS_NOTIFY) &&
                link.hasCharacteristic(BleUuids.DOMYOS_SERVICE, BleUuids.DOMYOS_WRITE)
            val hasFtms = link.hasCharacteristic(BleUuids.FTMS_SERVICE, BleUuids.FTMS_INDOOR_BIKE_DATA) ||
                link.hasCharacteristic(BleUuids.FTMS_SERVICE, BleUuids.FTMS_CONTROL_POINT)
            val protocol = when {
                preferred == Protocol.FTMS && hasFtms -> Protocol.FTMS
                preferred == Protocol.DOMYOS && hasDomyos -> Protocol.DOMYOS
                hasDomyos -> Protocol.DOMYOS
                hasFtms -> Protocol.FTMS
                else -> throw BleException("Ni le service Domyos ni FTMS n'ont été trouvés")
            }
            log("Protocole choisi : $protocol (domyos=$hasDomyos, ftms=$hasFtms)")
            val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]))
            val bike = when (protocol) {
                Protocol.DOMYOS -> {
                    val d = DomyosProtocolClient(link, scope, log)
                    ExerciseBike(protocol, d, null, scope).also { it.bind(); d.initialize(clearSession = clearSession) }
                }
                Protocol.FTMS -> {
                    val f = FtmsClient(link, scope, log)
                    ExerciseBike(protocol, null, f, scope).also { it.bind(); f.initialize() }
                }
            }
            bike.model?.let { log("Modèle reconnu : ${it.name}") }
            return bike
        }
    }
}
