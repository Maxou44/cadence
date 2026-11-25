package io.opendomyos.ble.domyos

/**
 * Données temps réel renvoyées par la commande GetInfoValue (0xAC), interrogée ~toutes les 300 ms.
 * Interprétation « vélo / elliptique » (la plus courante).
 */
data class DomyosInfoValue(
    /** Compteur générique (tours de pédale / foulées), u16. */
    val count: Int,
    val powerWatts: Int,
    val speedKmh: Float,
    val cadenceRpm: Int,
    /** Calories cumulées de la séance en cours (kcal). */
    val sessionKcal: Int,
    /** Distance cumulée de la séance en cours (km, résolution 0,1). */
    val sessionDistanceKm: Float,
    /** Niveau de résistance actuel (1..32 selon le modèle). */
    val resistanceLevel: Int,
    /** Code erreur console (0 = aucune). */
    val errorNumber: Int,
    /** L'utilisateur tient les capteurs cardio / « tap » sur l'appareil. */
    val tapOnEquipment: Boolean,
    /** Fréquence cardiaque mesurée par la console (capteurs mains / ceinture analogique), bpm. */
    val heartRateBpm: Int,
    val sessionAverageSpeedKmh: Float,
    /** Dernier bouton pressé sur la console, voir [BikeButton]. */
    val pressedButton: Int,
    /** Vitesse du ventilateur 0..5. */
    val fanSpeedLevel: Int,
    /** État start/pause/stop de la console, voir [HotKey]. */
    val hotKeyStatus: Int,
    /** Trame brute (utile pour d'autres types d'appareils ou le débogage). */
    val raw: ByteArray,
) {
    val button: BikeButton? get() = BikeButton.fromCode(pressedButton)
    val hotKey: HotKey? get() = HotKey.fromCode(hotKeyStatus)

    override fun equals(other: Any?): Boolean = other is DomyosInfoValue && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()

    companion object {
        /** Parse une réponse complète `F0 BC …` de 26 octets. */
        fun parse(f: ByteArray): DomyosInfoValue = DomyosInfoValue(
            count = f.u16be(2),
            powerWatts = f.u16be(4),
            speedKmh = f.u16be(6) / 10f,
            cadenceRpm = f.u16be(8),
            sessionKcal = f.u16be(10),
            sessionDistanceKm = f.u16be(12) / 10f,
            resistanceLevel = f.u8(14),
            errorNumber = f.u8(15),
            tapOnEquipment = f.u8(16) != 0,
            heartRateBpm = f.u16be(17),
            sessionAverageSpeedKmh = f.u16be(19) / 10f,
            // f[21] : non utilisé par l'application d'origine
            pressedButton = f.u8(22),
            fanSpeedLevel = f.u8(23),
            hotKeyStatus = f.u8(24),
            raw = f.copyOf(),
        )
    }
}

/** Démarrer / mettre en pause / arrêter la séance sur la console (commande 0xC8). */
enum class HotKey(val code: Int) {
    PAUSE(0), START(1), STOP(2);

    companion object {
        fun fromCode(code: Int): HotKey? = entries.firstOrNull { it.code == code }
    }
}

/** Boutons physiques d'une console vélo, remontés dans [DomyosInfoValue.pressedButton]. */
enum class BikeButton(val code: Int) {
    GENERAL_1(1), GENERAL_2(2), GENERAL_3(3), GENERAL_4(4),
    QUIT(5), START(6), PROGRAM(7),
    LOAD_PLUS(8), LOAD_MINUS(9),
    FAN_MINUS(10), FAN_PLUS(11);

    companion object {
        fun fromCode(code: Int): BikeButton? = entries.firstOrNull { it.code == code }
    }
}

/** Couleur de la LED cardio (champ de SetInfoValue). */
enum class HeartRateLedColor(val code: Int) {
    OFF(0), BLUE(1), GREEN(2), YELLOW(3), ORANGE(4), RED(5)
}

/** Valeurs de SetSessionData (0xC4). Seule CLEAR (3) est utilisée par l'app d'origine. */
object SessionData {
    /** Remet à zéro les cumuls de séance de la console (distance, kcal, compteur). */
    const val CLEAR: Int = 3
}

/**
 * Paramètres de SetInfoValue (0xAD). Chaque champ `null` = « ne pas modifier » (envoyé en FF/FFFF).
 */
data class DomyosSetInfo(
    /** 1..32 (borne réelle selon le modèle). */
    val resistanceLevel: Int? = null,
    /** Puissance cible 0..1000 W (mode watts constants, si la console le gère). */
    val targetWatts: Int? = null,
    /** Vitesse 0..25,4 km/h (tapis). */
    val speedKmh: Float? = null,
    /** Inclinaison -20..+20 % (tapis / elliptique). */
    val inclinePercent: Float? = null,
    val heartRateLedColor: HeartRateLedColor? = null,
    /** LED Bluetooth de la console allumée/éteinte. */
    val bluetoothLed: Boolean? = null,
) {
    fun validate() {
        resistanceLevel?.let { require(it in 1..32) { "resistanceLevel $it hors de 1..32" } }
        targetWatts?.let { require(it in 0..1000) { "targetWatts $it hors de 0..1000" } }
        speedKmh?.let { require(it in 0f..25.4f) { "speedKmh $it hors de 0..25.4" } }
        inclinePercent?.let { require(it in -20f..20f) { "inclinePercent $it hors de -20..20" } }
    }

    /** Encodage d'origine : 0 → 0 ; positif → 1000 + x*10 ; négatif → |x|*10. */
    internal fun encodedIncline(): Int? = inclinePercent?.let {
        when {
            it == 0f -> 0
            it > 0f -> (1000 + it * 10f).toInt()
            else -> (-it * 10f).toInt()
        }
    }
}

/** Informations statiques de la console. */
data class DomyosConsoleInfo(
    val equipmentId: Int?,
    val firmwareVersion: String?,
    val serialNumber: String?,
    val usageHours: Int?,
    val cumulativeKm: Int?,
    val bluetoothVersion: Int?,
)

/** Résultat d'entraînement mémorisé par la console pour un utilisateur (0xE0). */
data class DomyosWorkoutResult(
    val user: Int,
    val totalTimeMinutes: Int,
    val totalDistanceKm: Float,
    val totalKcal: Int,
    val averageSpeedKmh: Float,
    val averageBpm: Int,
)
