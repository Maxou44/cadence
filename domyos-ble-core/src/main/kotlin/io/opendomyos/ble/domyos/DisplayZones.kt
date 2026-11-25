package io.opendomyos.ble.domyos

/**
 * Pilotage de l'afficheur de la console (commandes 0xCB / 0xCD / 0xCC / 0xCE).
 *
 * L'afficheur est découpé en 12 zones : 1..6 (« zone principale », 0xCB) et 7..12
 * (« seconde zone », 0xCD). Chaque zone s'encode sur 4 octets :
 * `type, var1 (u16 BE), var2`. Une zone laissée à [DisplayZone.Unchanged] est envoyée FF FF FF FF.
 *
 * Correspondance utilisée par l'app d'origine pour un vélo :
 *  zone 1 = affichage principal, 2 = vitesse, 3 = cardio, 4 = cadence, 5 = kcal,
 *  6 = résistance, 7 = distance.
 */
sealed class DisplayZone {
    internal abstract fun encode(): ByteArray

    /** Ne pas toucher à cette zone. */
    object Unchanged : DisplayZone() {
        override fun encode() = byteArrayOf(-1, -1, -1, -1)
    }

    /**
     * Nombre. [decimals] : 0, 1 ou 2 (la zone 1 n'accepte que 0 ou 2).
     * La valeur transmise est `value * 10^decimals`, bornée à 0..65534.
     */
    data class Number(val value: Float, val decimals: Int = 0) : DisplayZone() {
        init { require(decimals in 0..2) }
        override fun encode(): ByteArray {
            val scaled = when (decimals) { 0 -> value; 1 -> value * 10f; else -> value * 100f }
            return zone(TYPE_ARITHMETIC, scaled.toInt().coerceIn(0, 0xFFFE), decimals)
        }
    }

    /** Durée mm:ss affichée comme un nombre (minutes*100 + secondes) — zones 2..12. */
    data class Time(val minutes: Int, val seconds: Int) : DisplayZone() {
        override fun encode() = zone(TYPE_ARITHMETIC, (minutes * 100 + seconds).coerceIn(0, 0xFFFE), 0)
    }

    /** Chaîne prédéfinie du firmware, désignée par son index. */
    data class Text(val stringIndex: Int) : DisplayZone() {
        override fun encode() = zone(TYPE_STRING, stringIndex, 0xFF)
    }

    /** Horloge mm:ss native — zone 1 uniquement. */
    data class Clock(val minutes: Int, val seconds: Int) : DisplayZone() {
        override fun encode() = zone(TYPE_CLOCK, minutes * 256 + seconds, 0xFF)
    }

    companion object {
        const val TYPE_ARITHMETIC = 1
        const val TYPE_STRING = 2
        const val TYPE_CLOCK = 3

        private fun zone(type: Int, var1: Int, var2: Int) =
            byteArrayOf(type.toByte(), (var1 shr 8).toByte(), var1.toByte(), var2.toByte())
    }
}

/** Les 6 zones d'une page d'affichage. */
data class DisplayPage(
    val z1: DisplayZone = DisplayZone.Unchanged,
    val z2: DisplayZone = DisplayZone.Unchanged,
    val z3: DisplayZone = DisplayZone.Unchanged,
    val z4: DisplayZone = DisplayZone.Unchanged,
    val z5: DisplayZone = DisplayZone.Unchanged,
    val z6: DisplayZone = DisplayZone.Unchanged,
) {
    internal fun encode(): ByteArray = listOf(z1, z2, z3, z4, z5, z6).fold(ByteArray(0)) { acc, z -> acc + z.encode() }
}
