package io.opendomyos.cadence.session

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.opendomyos.ble.BikeData
import io.opendomyos.ble.EquipmentKind
import io.opendomyos.ble.Protocol
import io.opendomyos.ble.android.BikeConnection
import io.opendomyos.ble.android.DiscoveredEquipment
import io.opendomyos.ble.android.DomyosConnector
import io.opendomyos.ble.android.DomyosScanner
import io.opendomyos.ble.android.LinkState
import io.opendomyos.ble.domyos.HotKey
import io.opendomyos.cadence.data.HistoryEntry
import io.opendomyos.cadence.data.Prefs
import io.opendomyos.cadence.data.SavedDevice
import io.opendomyos.cadence.program.Program
import io.opendomyos.cadence.program.ProgramPlayer
import io.opendomyos.cadence.program.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** État de la liaison avec le vélo. */
sealed class Conn {
    object Idle : Conn()
    data class Connecting(val name: String) : Conn()
    data class Connected(val name: String, val model: String?, val protocol: Protocol, val equipmentId: Int?) : Conn()
    data class Reconnecting(val name: String, val attempt: Int) : Conn()
    data class Failed(val message: String) : Conn()
}

data class WorkoutStats(
    val distanceKm: Float = 0f,
    val kcal: Int = 0,
    val avgPower: Int = 0,
    val maxPower: Int = 0,
    val avgCadence: Int = 0,
    val avgHeartRate: Int = 0,
)

/** Instantané de la séance pour l'UI et la notification. */
data class WorkoutState(
    val program: Program?,
    val elapsedMs: Long,
    val durationMs: Long?,
    val progress: Float,
    val segmentIndex: Int,
    val segment: Segment?,
    val nextSegment: Segment?,
    val segmentRemainingMs: Long,
    val targetResistance: Int,
    val nextResistance: Int?,
    val userOffset: Int,
    val paused: Boolean,
    /** En attente de pédalage (début de séance ou arrêt de pédalage). */
    val waitingForPedaling: Boolean,
    val finished: Boolean,
    val stats: WorkoutStats,
) {
    val running get() = !paused && !waitingForPedaling && !finished
    val title get() = program?.title ?: "Séance libre"
}

/**
 * Cerveau de l'app, indépendant de l'UI : appairage, connexion/reconnexion, lecture des
 * programmes, statistiques. Vit dans l'Application ; le [WorkoutService] le garde en vie
 * en arrière-plan.
 */
@SuppressLint("MissingPermission")
class SessionManager(private val context: Context, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + io.opendomyos.cadence.util.LogErrors)

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val connection: StateFlow<Conn> = _conn.asStateFlow()

    private val _live = MutableStateFlow<BikeData?>(null)
    val live: StateFlow<BikeData?> = _live.asStateFlow()

    private val _workout = MutableStateFlow<WorkoutState?>(null)
    val workout: StateFlow<WorkoutState?> = _workout.asStateFlow()

    private val _scan = MutableStateFlow<List<DiscoveredEquipment>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredEquipment>> = _scan.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _summary = MutableStateFlow<HistoryEntry?>(null)
    /** Résumé de la dernière séance terminée (affiché puis effacé par l'UI). */
    val summary: StateFlow<HistoryEntry?> = _summary.asStateFlow()

    private var bikeConn: BikeConnection? = null
    /** Collecteurs liés à la connexion courante (annulés à la déconnexion). */
    private var connJob: Job? = null
    private var connectJob: Job? = null
    private var scanJob: Job? = null
    private var userDisconnect = false

    // séance
    private var player: ProgramPlayer? = null
    private var tickJob: Job? = null
    private var paused = false
    private var waiting = true
    private var zeroCadenceSince = 0L
    private var startedAtMs = 0L
    private var distanceBase = 0f
    private var kcalBase = 0
    private var powerSum = 0.0; private var cadenceSum = 0.0; private var hrSum = 0.0
    private var movingSamples = 0; private var hrSamples = 0; private var maxPower = 0
    private var lastCommanded = -1
    private var lastCommandAt = 0L
    private var mismatchCount = 0
    private val resistanceChannel = Channel<Int>(Channel.CONFLATED)

    val bike get() = bikeConn?.bike
    val equipmentId: Int? get() = (bikeConn?.bike?.equipmentId) ?: prefs.lastEquipmentId

    init {
        // Envoi des résistances : on ne garde que la dernière demande (appuis rapides sur +/−).
        scope.launch {
            for (level in resistanceChannel) {
                val b = bike ?: continue
                try {
                    b.setResistance(level)
                    lastCommanded = level; lastCommandAt = SystemClock.elapsedRealtime(); mismatchCount = 0
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    _messages.tryEmit("Résistance non appliquée : ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ appairage / scan

    fun startScan() {
        if (scanJob?.isActive == true) return
        _scan.value = emptyList()
        _scanning.value = true
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(20_000) {
                    DomyosScanner(context).scan(includeAnyFtms = true).collect { d ->
                        _scan.value = (_scan.value.filter { it.address != d.address } + d)
                            .sortedWith(compareByDescending<DiscoveredEquipment> { it.kind == EquipmentKind.BIKE }.thenByDescending { it.rssi })
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Échec du scan")
            } finally { _scanning.value = false }
        }
    }

    fun stopScan() { scanJob?.cancel(); _scanning.value = false }

    fun pair(d: DiscoveredEquipment) {
        stopScan()
        prefs.device = SavedDevice(d.address, d.name ?: d.address)
        connect()
    }

    fun forgetDevice() {
        disconnect()
        prefs.device = null
        prefs.lastEquipmentId = null
    }

    // ------------------------------------------------------------------ connexion

    /** Connecte l'appareil appairé ; démarre le service pour tenir la liaison en arrière-plan. */
    fun connect() {
        val saved = prefs.device ?: return
        if (connectJob?.isActive == true || _conn.value is Conn.Connected) return
        userDisconnect = false
        _conn.value = Conn.Connecting(saved.name)
        startService()
        connectJob = scope.launch { connectLoop(saved, initial = true) }
    }

    private suspend fun connectLoop(saved: SavedDevice, initial: Boolean) {
        var attempt = 0
        while (scope.isActive && !userDisconnect) {
            attempt++
            _conn.value = if (initial && attempt == 1) Conn.Connecting(saved.name) else Conn.Reconnecting(saved.name, attempt)
            try {
                val device = remoteDevice(saved.address)
                val c = DomyosConnector.connect(
                    context, device, scope,
                    clearSession = _workout.value == null,
                    log = { android.util.Log.d("Cadence", it) },
                )
                onConnected(saved, c)
                return
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w("Cadence", "connexion échouée", e)
                if (initial && attempt >= 3 && _workout.value == null) {
                    _conn.value = Conn.Failed("Vélo introuvable. Est-il allumé et à portée ?")
                    return
                }
                delay((2_000L * attempt).coerceAtMost(10_000))
            }
        }
    }

    private fun remoteDevice(address: String): BluetoothDevice =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address)

    private fun onConnected(saved: SavedDevice, c: BikeConnection) {
        bikeConn = c
        val b = c.bike
        b.equipmentId?.let { prefs.lastEquipmentId = it }
        _conn.value = Conn.Connected(saved.name, b.model?.name, b.protocol, b.equipmentId)
        lastCommanded = -1
        connJob?.cancel()
        val job = SupervisorJob(scope.coroutineContext[Job])
        connJob = job
        val cs = CoroutineScope(scope.coroutineContext + job)
        cs.launch { b.data.collect { d -> if (d != null) onData(d) } }
        b.domyos?.let { d -> cs.launch { d.hotKeys.collect { onConsoleHotKey(it) } } }
        cs.launch {
            c.link.state.first { it == LinkState.DISCONNECTED }
            onLinkLost()
        }
        // Reprise d'une séance après reconnexion : on renvoie l'état courant.
        player?.let { p ->
            scope.launch {
                runCatching { if (!paused) b.start() }
                resistanceChannel.trySend(p.targetResistance)
            }
            _messages.tryEmit("Vélo reconnecté")
        }
    }

    private fun onLinkLost() {
        val c = bikeConn ?: return
        bikeConn = null
        connJob?.cancel()
        c.bike.close()
        _live.value = null
        if (userDisconnect) { _conn.value = Conn.Idle; return }
        val saved = prefs.device ?: run { _conn.value = Conn.Idle; return }
        _messages.tryEmit("Connexion perdue, reconnexion…")
        connectJob = scope.launch { connectLoop(saved, initial = false) }
    }

    fun disconnect() {
        userDisconnect = true
        if (_workout.value != null) stopWorkout(save = true)
        connectJob?.cancel()
        connJob?.cancel()
        val c = bikeConn
        bikeConn = null
        scope.launch { runCatching { c?.close() } }
        _live.value = null
        _conn.value = Conn.Idle
    }

    // ------------------------------------------------------------------ séance

    fun startWorkout(program: Program?) {
        val b = bike ?: run { _messages.tryEmit("Connectez d'abord le vélo"); return }
        if (_workout.value != null && _workout.value?.finished == false) return
        _summary.value = null
        val range = b.ftms?.resistanceRange
        val minRes = range?.min?.toInt()?.coerceAtLeast(1) ?: 1
        val maxRes = range?.max?.toInt()?.takeIf { it > minRes } ?: 32
        player = ProgramPlayer(program, minRes, maxRes, startResistance = _live.value?.resistanceLevel ?: 4)
        paused = false; waiting = true; zeroCadenceSince = 0
        startedAtMs = System.currentTimeMillis()
        distanceBase = _live.value?.distanceKm ?: 0f
        kcalBase = _live.value?.kcal ?: 0
        powerSum = 0.0; cadenceSum = 0.0; hrSum = 0.0; movingSamples = 0; hrSamples = 0; maxPower = 0
        scope.launch {
            b.domyos?.let { d -> runCatching { d.clearSession() }.onSuccess { distanceBase = 0f; kcalBase = 0 } }
            runCatching { b.start() }
            resistanceChannel.trySend(player!!.targetResistance)
        }
        startService()
        publish()
        tickJob?.cancel()
        tickJob = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                tick(now - last)
                last = now
            }
        }
    }

    private fun tick(deltaMs: Long) {
        val p = player ?: return
        val d = _live.value
        val cadence = d?.cadenceRpm ?: 0f
        val now = SystemClock.elapsedRealtime()
        // attente / auto-pause sur le pédalage
        if (cadence > 0f) { zeroCadenceSince = 0; if (waiting) waiting = false }
        else if (!waiting && prefs.autoPause && !paused) {
            if (zeroCadenceSince == 0L) zeroCadenceSince = now
            else if (now - zeroCadenceSince > 4_000) waiting = true
        }
        val running = !paused && !waiting && !p.finished
        if (running) {
            if (p.advance(deltaMs)) resistanceChannel.trySend(p.targetResistance)
            if (d != null) {
                val w = (d.powerWatts ?: 0).coerceAtLeast(0)
                powerSum += w * deltaMs; cadenceSum += cadence * deltaMs; movingSamples += deltaMs.toInt()
                maxPower = maxOf(maxPower, w)
                d.heartRateBpm?.let { hrSum += it * deltaMs; hrSamples += deltaMs.toInt() }
            }
            if (p.finished) { finishWorkout(); return }
        }
        publish()
    }

    private fun onData(d: BikeData) {
        _live.value = d
        // Boutons Load+/Load− de la console (protocole Domyos) : on adopte l'écart.
        val p = player ?: return
        val level = d.resistanceLevel ?: return
        if (d.protocol != Protocol.DOMYOS || lastCommanded < 0) return
        if (SystemClock.elapsedRealtime() - lastCommandAt < 2_500) return
        if (level != lastCommanded && level > 0) {
            if (++mismatchCount >= 2) {
                p.adoptConsoleResistance(level)
                lastCommanded = level; mismatchCount = 0
                publish()
            }
        } else mismatchCount = 0
    }

    private fun onConsoleHotKey(k: HotKey) {
        val w = _workout.value ?: return
        when (k) {
            HotKey.PAUSE -> if (!w.paused) { paused = true; publish() }
            HotKey.START -> if (w.paused) { paused = false; publish() }
            HotKey.STOP -> if (!w.finished) stopWorkout(save = true)
        }
    }

    fun togglePause() {
        val w = _workout.value ?: return
        if (w.finished) return
        paused = !paused
        if (!paused) { waiting = false; zeroCadenceSince = 0 }
        scope.launch { runCatching { if (paused) bike?.pause() else bike?.start() } }
        publish()
    }

    fun adjustResistance(delta: Int) {
        val p = player ?: return
        resistanceChannel.trySend(p.adjust(delta))
        publish()
    }

    private fun finishWorkout() {
        tickJob?.cancel()
        scope.launch { runCatching { bike?.pause() } }
        val entry = buildEntry(completed = true)
        prefs.addHistory(entry)
        _summary.value = entry
        publish(finished = true)
        _messages.tryEmit("Programme terminé, bravo !")
    }

    /** Arrêt manuel. */
    fun stopWorkout(save: Boolean) {
        val p = player ?: return
        tickJob?.cancel()
        if (save && p.elapsedMs >= 60_000 && _workout.value?.finished != true) {
            val e = buildEntry(completed = false)
            prefs.addHistory(e)
            _summary.value = e
        }
        scope.launch { runCatching { bike?.stop() } }
        player = null
        _workout.value = null
    }

    fun dismissSummary() {
        _summary.value = null
        if (_workout.value?.finished == true) { player = null; _workout.value = null }
    }

    private fun stats(): WorkoutStats {
        val d = _live.value
        val ms = movingSamples.coerceAtLeast(1)
        return WorkoutStats(
            distanceKm = ((d?.distanceKm ?: distanceBase) - distanceBase).coerceAtLeast(0f),
            kcal = ((d?.kcal ?: kcalBase) - kcalBase).coerceAtLeast(0),
            avgPower = (powerSum / ms).toInt(),
            maxPower = maxPower,
            avgCadence = (cadenceSum / ms).toInt(),
            avgHeartRate = if (hrSamples > 0) (hrSum / hrSamples).toInt() else 0,
        )
    }

    private fun buildEntry(completed: Boolean): HistoryEntry {
        val p = player!!
        val s = stats()
        return HistoryEntry(
            startedAtMs, p.program?.key, p.program?.title ?: "Séance libre", (p.elapsedMs / 1000).toInt(),
            s.distanceKm, s.kcal, s.avgPower, s.maxPower, s.avgCadence, s.avgHeartRate, completed,
        )
    }

    private fun publish(finished: Boolean = false) {
        val p = player ?: return
        val next = p.nextSegment
        _workout.value = WorkoutState(
            program = p.program, elapsedMs = p.elapsedMs, durationMs = p.durationMs, progress = p.progress,
            segmentIndex = p.segmentIndex, segment = p.currentSegment, nextSegment = next,
            segmentRemainingMs = p.segmentRemainingMs, targetResistance = p.targetResistance,
            nextResistance = next?.let { p.resistanceFor(it) }, userOffset = p.userOffset,
            paused = paused, waitingForPedaling = waiting && !paused, finished = finished || p.finished,
            stats = stats(),
        )
    }

    private fun startService() {
        val i = Intent(context, WorkoutService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
}
