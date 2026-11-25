package io.opendomyos.ble.domyos

import io.opendomyos.ble.BleLink
import io.opendomyos.ble.BleUuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Client du protocole propriétaire Domyos sur une liaison déjà connectée.
 *
 * Séquence d'initialisation reproduite de l'app d'origine :
 *  1. activation des notifications sur 49535343-1E4D… ;
 *  2. GetEquipmentId (2 essais) pour identifier le modèle ;
 *  3. lecture des infos console (version, série, heures, km, version BT) ;
 *  4. SetSessionData(CLEAR) pour remettre les cumuls de séance à zéro (optionnel) ;
 *  5. polling GetInfoValue toutes les 300 ms.
 */
class DomyosProtocolClient(
    private val link: BleLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    val engine = DomyosCommandEngine(link, scope, log)

    private val _live = MutableStateFlow<DomyosInfoValue?>(null)
    /** Dernières mesures reçues (mis à jour à chaque poll). */
    val live: StateFlow<DomyosInfoValue?> = _live.asStateFlow()

    private val _buttons = MutableSharedFlow<BikeButton>(extraBufferCapacity = 16)
    /** Appuis sur les boutons de la console (émis quand la valeur « dernier bouton » change). */
    val buttons: SharedFlow<BikeButton> = _buttons.asSharedFlow()

    private val _hotKeys = MutableSharedFlow<HotKey>(extraBufferCapacity = 16)
    /** Changements d'état start/pause/stop initiés depuis la console. */
    val hotKeys: SharedFlow<HotKey> = _hotKeys.asSharedFlow()

    private val _consecutiveFailures = MutableStateFlow(0)
    /** Nombre de polls consécutifs sans réponse (lien dégradé si > 3). */
    val consecutiveFailures: StateFlow<Int> = _consecutiveFailures.asStateFlow()

    var consoleInfo: DomyosConsoleInfo? = null
        private set

    private var pollJob: Job? = null

    suspend fun <T> execute(command: DomyosCommand<T>): T = engine.execute(command)

    /**
     * @param clearSession remet à zéro distance/kcal de la console (comme l'app d'origine).
     * @param readConsoleInfo lit version, numéro de série, etc. (≈ 5 allers-retours).
     */
    suspend fun initialize(clearSession: Boolean = true, readConsoleInfo: Boolean = true, startPolling: Boolean = true): DomyosConsoleInfo {
        link.enableNotifications(BleUuids.DOMYOS_SERVICE, BleUuids.DOMYOS_NOTIFY)
        val id = runCatching { execute(DomyosCommands.getEquipmentId()) }
            .recoverCatching { execute(DomyosCommands.getEquipmentId()) }
            .getOrNull()
        log("Equipment ID = $id")

        suspend fun <T> optional(c: DomyosCommand<T>): T? =
            if (!readConsoleInfo) null else runCatching { execute(c) }.onFailure { log("${c.name} ignoré : ${it.message}") }.getOrNull()

        val info = DomyosConsoleInfo(
            equipmentId = id,
            firmwareVersion = optional(DomyosCommands.getFirmwareVersion()),
            serialNumber = optional(DomyosCommands.getSerialNumber()),
            usageHours = optional(DomyosCommands.getUsageHours()),
            cumulativeKm = optional(DomyosCommands.getCumulativeKm()),
            bluetoothVersion = optional(DomyosCommands.getBluetoothVersion()),
        )
        consoleInfo = info
        if (clearSession) runCatching { clearSession() }.onFailure { log("Clear session ignoré : ${it.message}") }
        if (startPolling) startPolling()
        return info
    }

    fun startPolling(intervalMs: Long = 300) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    onInfo(execute(DomyosCommands.getInfoValue()))
                    _consecutiveFailures.value = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _consecutiveFailures.value += 1
                    log("Poll échoué (${_consecutiveFailures.value}) : ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private fun onInfo(v: DomyosInfoValue) {
        val previous = _live.value
        _live.value = v
        if (previous != null) {
            if (v.pressedButton != previous.pressedButton) v.button?.let { _buttons.tryEmit(it) }
            if (v.hotKeyStatus != previous.hotKeyStatus) v.hotKey?.let { _hotKeys.tryEmit(it) }
        }
    }

    // ---------- Commandes de haut niveau ----------

    /** Niveau de résistance 1..32 (la borne haute dépend du modèle, souvent 16 ou 32). */
    suspend fun setResistance(level: Int) = execute(DomyosCommands.setInfoValue(DomyosSetInfo(resistanceLevel = level)))

    /** Mode watts constants (si la console le gère). */
    suspend fun setTargetWatts(watts: Int) = execute(DomyosCommands.setInfoValue(DomyosSetInfo(targetWatts = watts)))

    suspend fun setInfo(params: DomyosSetInfo) = execute(DomyosCommands.setInfoValue(params))

    suspend fun start() = execute(DomyosCommands.setHotKey(HotKey.START))
    suspend fun pause() = execute(DomyosCommands.setHotKey(HotKey.PAUSE))
    suspend fun stop() = execute(DomyosCommands.setHotKey(HotKey.STOP))

    suspend fun clearSession() = execute(DomyosCommands.setSessionData(SessionData.CLEAR))
    suspend fun setFanSpeed(level: Int) = execute(DomyosCommands.setFanSpeed(level))
    suspend fun setBeep(on: Boolean) = execute(DomyosCommands.setBeep(on))
    suspend fun setHeartRateLed(color: HeartRateLedColor) = execute(DomyosCommands.setInfoValue(DomyosSetInfo(heartRateLedColor = color)))

    suspend fun setDisplay(page: DisplayPage) = execute(DomyosCommands.setDisplayZones(page))
    suspend fun setDisplaySecondArea(page: DisplayPage) = execute(DomyosCommands.setDisplayZonesSecondArea(page))

    suspend fun getWorkoutResult(user: Int) = execute(DomyosCommands.getWorkoutResult(user))
    suspend fun getErrorHistory() = execute(DomyosCommands.getErrorHistory())
}
