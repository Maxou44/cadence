package io.opendomyos.cadence.program

/** Catégories telles que numérotées dans les fichiers Decathlon (`idCategorie`). */
enum class ProgramCategory(val id: Int, val label: String) {
    HEALTH(0, "Santé"),
    CALORIES(1, "Calories"),
    ENDURANCE(2, "Endurance"),
    INTERVAL(3, "Fractionné"),
    POWER(10, "Puissance"),
    WEIGHT_LOSS(11, "Perte de poids"),
    RECOVERY(12, "Récupération"),
    STAY_FIT(13, "Forme"),
    STRENGTH(14, "Renforcement"),
    OTHER(-1, "Autres");

    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OTHER }
}

/** Point de la courbe : à partir de `progress` (0..1 de la durée), résistance `value`. */
data class ProgramPoint(
    val progress: Float,
    val value: Int,
    val rpmMin: Int? = null,
    val rpmMax: Int? = null,
    val instruction: String? = null,
)

/** Palier constant (déduit des points), utilisé par le lecteur et l'affichage. */
data class Segment(
    val start: Float,
    val end: Float,
    val resistance: Int,
    val rpmMin: Int?,
    val rpmMax: Int?,
    val instruction: String?,
) {
    val length get() = end - start
}

data class Program(
    /** Identifiant stable (fichier + idProgram) pour favoris et historique. */
    val key: String,
    val idProgram: String,
    val title: String,
    val category: ProgramCategory,
    val consoleIds: Set<Int>,
    val durationSec: Int,
    val level: Int?,
    val style: String?,
    val points: List<ProgramPoint>,
) {
    val segments: List<Segment> by lazy { buildSegments(points) }
    val maxResistance: Int get() = segments.maxOfOrNull { it.resistance } ?: 1
    val minResistance: Int get() = segments.minOfOrNull { it.resistance } ?: 1
    val hasCadenceTargets: Boolean get() = segments.any { it.rpmMin != null }

    /** Index du palier actif pour une progression 0..1. */
    fun segmentIndexAt(progress: Float): Int {
        var idx = 0
        for (i in segments.indices) if (segments[i].start <= progress + 1e-6f) idx = i else break
        return idx
    }

    companion object {
        /**
         * Règle de lecture de l'app d'origine : la valeur active est celle du DERNIER point
         * dont `progress` ≤ avancement. Plusieurs points au même `progress` décrivent un
         * front vertical : seul le dernier compte. Le point final (progress = 1) marque la fin.
         */
        fun buildSegments(points: List<ProgramPoint>): List<Segment> {
            if (points.isEmpty()) return emptyList()
            val out = ArrayList<Segment>()
            for (i in points.indices) {
                val p = points[i]
                val end = if (i + 1 < points.size) points[i + 1].progress else 1f
                if (end - p.progress <= 1e-6f) continue
                val s = Segment(p.progress, end, p.value, p.rpmMin, p.rpmMax, p.instruction)
                val last = out.lastOrNull()
                // fusion des paliers identiques consécutifs (plus lisible à l'écran)
                if (last != null && last.resistance == s.resistance && last.rpmMin == s.rpmMin &&
                    last.rpmMax == s.rpmMax && last.instruction == s.instruction && kotlin.math.abs(last.end - s.start) < 1e-6f
                ) out[out.size - 1] = last.copy(end = s.end) else out += s
            }
            if (out.isEmpty()) out += Segment(0f, 1f, points.last().value, points.last().rpmMin, points.last().rpmMax, points.last().instruction)
            if (out.last().end < 1f) out[out.size - 1] = out.last().copy(end = 1f)
            return out
        }
    }
}
