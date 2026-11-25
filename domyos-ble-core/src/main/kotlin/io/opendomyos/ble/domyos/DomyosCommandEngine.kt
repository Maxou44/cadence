package io.opendomyos.ble.domyos

import io.opendomyos.ble.BleLink
import io.opendomyos.ble.BleUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class DomyosTimeoutException(command: String, attempts: Int) :
    Exception("Pas de réponse valide à $command après $attempts tentative(s)")

/**
 * Exécute les commandes Domyos une par une (requête → réponse), comme le SDK d'origine :
 * une seule commande en vol, timeout de 2,2 s par tentative, renvois selon la commande.
 *
 * Le verrou [Mutex] est équitable (FIFO) : les commandes de l'application s'intercalent
 * naturellement avec le polling GetInfoValue.
 */
class DomyosCommandEngine(
    private val link: BleLink,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        // UNDISPATCHED : l'abonnement est actif avant le retour du constructeur.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications
                .filter { it.characteristic == BleUuids.DOMYOS_NOTIFY }
                .collect { incoming.trySend(it.value) }
        }
    }

    /** Vrai si aucune commande n'est en cours. */
    val isIdle: Boolean get() = !mutex.isLocked

    suspend fun <T> execute(command: DomyosCommand<T>): T = mutex.withLock {
        val attempts = command.retries + 1
        repeat(attempts) { attempt ->
            drainStale()
            val assembler = ResponseAssembler(command.responseOpcode, command.responseLength)
            log("→ $command" + if (attempt > 0) " (renvoi $attempt)" else "")
            link.write(BleUuids.DOMYOS_SERVICE, BleUuids.DOMYOS_WRITE, command.request, withResponse = true)
            val frame = withTimeoutOrNull(command.timeoutMs) {
                var result: ByteArray? = null
                while (result == null) result = assembler.feed(incoming.receive())
                result
            }
            if (frame != null) {
                log("← ${command.name} ${frame.toHex()}")
                return@withLock command.parse(frame)
            }
            log("⏱ timeout ${command.name}")
        }
        throw DomyosTimeoutException(command.name, attempts)
    }

    private fun drainStale() {
        while (incoming.tryReceive().isSuccess) { /* on jette les octets orphelins */ }
    }
}
