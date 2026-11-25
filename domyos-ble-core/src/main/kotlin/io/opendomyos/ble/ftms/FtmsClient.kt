package io.opendomyos.ble.ftms

import io.opendomyos.ble.BleLink
import io.opendomyos.ble.BleUuids
import io.opendomyos.ble.EquipmentCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class FtmsControlException(val opCode: Int, val result: FtmsResult?) :
    Exception("Commande FTMS 0x%02X refusée : %s".format(opCode, result ?: "timeout"))

/**
 * Client FTMS pour vélo d'intérieur (Indoor Bike Data), avec les particularités du SDK Domyos :
 *  - la résistance est envoyée en u8 résolution 0,1 ;
 *  - pour certains modèles ([EquipmentCatalog.FTMS_RESISTANCE_SCALED_IDS]) le niveau demandé
 *    est multiplié par l'incrément de « Supported Resistance Level Range ».
 */
class FtmsClient(
    private val link: BleLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val controlMutex = Mutex()
    private val controlResponses = Channel<FtmsControlResponse>(Channel.UNLIMITED)

    private val _bikeData = MutableStateFlow<IndoorBikeData?>(null)
    val bikeData: StateFlow<IndoorBikeData?> = _bikeData.asStateFlow()

    private val _status = MutableSharedFlow<FtmsMachineStatus>(extraBufferCapacity = 16)
    val status: SharedFlow<FtmsMachineStatus> = _status.asSharedFlow()

    var features: FtmsFeatures? = null; private set
    var resistanceRange: FtmsRange? = null; private set
    var powerRange: FtmsRange? = null; private set
    /** « Model Number » du Device Information Service, converti en entier si possible. */
    var equipmentId: Int? = null; private set
    private var hasControl = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.collect { n ->
                when (n.characteristic) {
                    BleUuids.FTMS_INDOOR_BIKE_DATA -> runCatching { IndoorBikeData.parse(n.value) }
                        .onSuccess { d -> _bikeData.value = _bikeData.value?.mergedWith(d) ?: d }
                        .onFailure { log("Indoor Bike Data illisible : ${it.message}") }
                    BleUuids.FTMS_MACHINE_STATUS -> {
                        val s = FtmsMachineStatus.parse(n.value)
                        if (s is FtmsMachineStatus.ControlPermissionLost) hasControl = false
                        _status.tryEmit(s)
                    }
                    BleUuids.FTMS_CONTROL_POINT -> FtmsControlResponse.parse(n.value)?.let { controlResponses.trySend(it) }
                }
            }
        }
    }

    private fun has(c: java.util.UUID) = link.hasCharacteristic(BleUuids.FTMS_SERVICE, c)

    suspend fun initialize(requestControl: Boolean = true) {
        if (has(BleUuids.FTMS_MACHINE_FEATURE)) features = FtmsFeatures.parse(link.read(BleUuids.FTMS_SERVICE, BleUuids.FTMS_MACHINE_FEATURE))
        if (has(BleUuids.FTMS_SUPPORTED_RESISTANCE_RANGE))
            resistanceRange = FtmsRange.parseTenths(link.read(BleUuids.FTMS_SERVICE, BleUuids.FTMS_SUPPORTED_RESISTANCE_RANGE))
        if (has(BleUuids.FTMS_SUPPORTED_POWER_RANGE))
            powerRange = FtmsRange.parsePower(link.read(BleUuids.FTMS_SERVICE, BleUuids.FTMS_SUPPORTED_POWER_RANGE))
        if (link.hasCharacteristic(BleUuids.DEVICE_INFORMATION_SERVICE, BleUuids.DIS_MODEL_NUMBER)) {
            val model = runCatching { String(link.read(BleUuids.DEVICE_INFORMATION_SERVICE, BleUuids.DIS_MODEL_NUMBER)).trim { it <= ' ' } }.getOrNull()
            equipmentId = model?.toIntOrNull()
            log("FTMS model number = $model")
        }
        log("FTMS features=$features résistance=$resistanceRange puissance=$powerRange")

        if (has(BleUuids.FTMS_INDOOR_BIKE_DATA)) link.enableNotifications(BleUuids.FTMS_SERVICE, BleUuids.FTMS_INDOOR_BIKE_DATA)
        if (has(BleUuids.FTMS_MACHINE_STATUS)) link.enableNotifications(BleUuids.FTMS_SERVICE, BleUuids.FTMS_MACHINE_STATUS)
        if (has(BleUuids.FTMS_CONTROL_POINT)) {
            link.enableNotifications(BleUuids.FTMS_SERVICE, BleUuids.FTMS_CONTROL_POINT, indication = true)
            if (requestControl) runCatching { ensureControl() }.onFailure { log("Request Control refusé : ${it.message}") }
        }
    }

    /** Envoie une requête au Control Point et attend l'indication de réponse correspondante. */
    suspend fun control(request: ByteArray, timeoutMs: Long = 3000): FtmsResult = controlMutex.withLock {
        val op = request[0].toInt() and 0xFF
        while (controlResponses.tryReceive().isSuccess) { /* purge */ }
        link.write(BleUuids.FTMS_SERVICE, BleUuids.FTMS_CONTROL_POINT, request, withResponse = true)
        val resp = withTimeoutOrNull(timeoutMs) {
            var r: FtmsControlResponse
            do { r = controlResponses.receive() } while (r.requestOpCode != op)
            r
        }
        if (resp?.result != FtmsResult.SUCCESS) throw FtmsControlException(op, resp?.result)
        FtmsResult.SUCCESS
    }

    private suspend fun ensureControl() {
        if (hasControl) return
        control(FtmsControl.requestControl())
        hasControl = true
    }

    private suspend fun controlled(request: ByteArray): FtmsResult {
        ensureControl()
        return try {
            control(request)
        } catch (e: FtmsControlException) {
            if (e.result != FtmsResult.CONTROL_NOT_PERMITTED) throw e
            hasControl = false
            ensureControl()
            control(request)
        }
    }

    suspend fun start() = controlled(FtmsControl.startOrResume())
    suspend fun pause() = controlled(FtmsControl.pause())
    suspend fun stop() = controlled(FtmsControl.stop())
    suspend fun reset() = controlled(FtmsControl.reset())

    /** Niveau de résistance tel qu'affiché par la console (ex. 1..32). */
    suspend fun setResistance(level: Float): FtmsResult {
        val scale = if (equipmentId in EquipmentCatalog.FTMS_RESISTANCE_SCALED_IDS) resistanceRange?.increment ?: 1f else 1f
        var value = level * scale
        resistanceRange?.let { r -> if (r.max > r.min) value = value.coerceIn(r.min, r.max) }
        return controlled(FtmsControl.targetResistance(value))
    }

    suspend fun setTargetPower(watts: Int): FtmsResult {
        var w = watts
        powerRange?.let { r -> if (r.max > r.min) w = w.coerceIn(r.min.toInt(), r.max.toInt()) }
        return controlled(FtmsControl.targetPower(w))
    }

    suspend fun setSimulation(gradePercent: Float, windMps: Float = 0f, crr: Float = 0.004f, cw: Float = 0.51f) =
        controlled(FtmsControl.indoorBikeSimulation(windMps, gradePercent, crr, cw))
}
