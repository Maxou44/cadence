package io.opendomyos.cadence.program

/**
 * Lecteur de programme, indépendant d'Android et du Bluetooth (le temps est injecté).
 *
 * Reprend le comportement de l'app d'origine :
 *  - avancement = temps de pédalage / durée cible ;
 *  - la résistance envoyée change à chaque nouveau palier ;
 *  - si l'utilisateur modifie la résistance (app ou boutons de la console), l'écart est
 *    conservé et appliqué aux paliers suivants (« offset cumulé »), borné à [minRes, maxRes].
 *
 * Sans programme (`program == null`) : mode libre, résistance manuelle.
 */
class ProgramPlayer(
    val program: Program?,
    val minRes: Int = 1,
    val maxRes: Int = 32,
    startResistance: Int = 4,
) {
    var elapsedMs: Long = 0; private set
    var userOffset: Int = 0; private set
    private var manualResistance = startResistance.coerceIn(minRes, maxRes)

    val durationMs: Long? = program?.let { it.durationSec * 1000L }

    val progress: Float get() = durationMs?.let { (elapsedMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    val finished: Boolean get() = durationMs != null && elapsedMs >= durationMs

    val segmentIndex: Int get() = program?.segmentIndexAt(progress) ?: -1

    val currentSegment: Segment? get() = program?.segments?.getOrNull(segmentIndex)

    val nextSegment: Segment? get() = program?.segments?.getOrNull(segmentIndex + 1)

    /** Temps restant dans le palier courant. */
    val segmentRemainingMs: Long
        get() {
            val d = durationMs ?: return 0
            val seg = currentSegment ?: return 0
            return ((seg.end * d) - elapsedMs).toLong().coerceAtLeast(0)
        }

    val remainingMs: Long get() = durationMs?.let { (it - elapsedMs).coerceAtLeast(0) } ?: 0

    /** Résistance à appliquer maintenant. */
    val targetResistance: Int
        get() = currentSegment?.let { (it.resistance + userOffset).coerceIn(minRes, maxRes) } ?: manualResistance

    /** Résistance prévue pour un palier donné (offset utilisateur inclus). */
    fun resistanceFor(seg: Segment) = (seg.resistance + userOffset).coerceIn(minRes, maxRes)

    /**
     * Avance le temps de pédalage.
     * @return true si la résistance cible a changé (il faut l'envoyer à la console).
     */
    fun advance(deltaMs: Long): Boolean {
        if (deltaMs <= 0 || finished) return false
        val before = targetResistance
        elapsedMs += deltaMs
        durationMs?.let { if (elapsedMs > it) elapsedMs = it }
        return targetResistance != before
    }

    /** +/- depuis l'app. Renvoie la nouvelle cible. */
    fun adjust(delta: Int): Int {
        val seg = currentSegment
        if (seg == null) manualResistance = (manualResistance + delta).coerceIn(minRes, maxRes)
        else userOffset = ((seg.resistance + userOffset + delta).coerceIn(minRes, maxRes)) - seg.resistance
        return targetResistance
    }

    /** La console signale une résistance différente (boutons Load+/Load−) : on adopte l'écart. */
    fun adoptConsoleResistance(level: Int) {
        val seg = currentSegment
        if (seg == null) manualResistance = level.coerceIn(minRes, maxRes)
        else userOffset = level.coerceIn(minRes, maxRes) - seg.resistance
    }

    /** Reprise après reconnexion / restauration. */
    fun restore(elapsedMs: Long, userOffset: Int) {
        this.elapsedMs = elapsedMs.coerceAtLeast(0); this.userOffset = userOffset
    }
}
