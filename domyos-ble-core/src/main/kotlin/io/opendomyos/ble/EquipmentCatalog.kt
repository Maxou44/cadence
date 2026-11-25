package io.opendomyos.ble

/** Type d'appareil déduit du nom Bluetooth annoncé (règles du SDK d'origine). */
enum class EquipmentKind { BIKE, ELLIPTICAL, TREADMILL, ROWER, UNKNOWN;

    companion object {
        /**
         * domyos-bike-*, domyos-biking-*, domyos-eb* → vélo ; domyos-el-* → elliptique ;
         * domyos-tc-* → tapis ; domyos-row-*, domyos-wr*, pdNNN → rameur.
         */
        fun fromAdvertisedName(name: String?): EquipmentKind {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                n.startsWith("domyos-bike-") || n.startsWith("domyos-biking-") || n.startsWith("domyos-eb") -> BIKE
                n.startsWith("domyos-el-") -> ELLIPTICAL
                n.startsWith("domyos-tc-") -> TREADMILL
                n.startsWith("domyos-row-") || n.startsWith("domyos-wr") || Regex("^pd\\d+$").matches(n) -> ROWER
                else -> UNKNOWN
            }
        }
    }
}

enum class Protocol { DOMYOS, FTMS }

/**
 * Modèle connu, identifié par l'ID renvoyé par la console (GetEquipmentId, ou « Model Number »
 * du Device Information Service en FTMS). Extrait de la table `DCEquipmentTypes` de l'app 5.1.4.
 *
 * @param protocols protocoles annoncés par Decathlon, dans l'ordre de préférence de l'app.
 */
data class KnownModel(val name: String, val kind: EquipmentKind, val equipmentIds: Set<Int>, val protocols: List<Protocol>)

object EquipmentCatalog {
    private val D = listOf(Protocol.DOMYOS)
    private val F = listOf(Protocol.FTMS)
    private val FD = listOf(Protocol.FTMS, Protocol.DOMYOS)
    private val DF = listOf(Protocol.DOMYOS, Protocol.FTMS)
    private fun m(name: String, kind: EquipmentKind, p: List<Protocol>, vararg ids: Int) = KnownModel(name, kind, ids.toSet(), p)

    val models: List<KnownModel> = listOf(
        // Vélos
        m("Training Bike 900", EquipmentKind.BIKE, F, 8806819),
        m("Challenge Bike", EquipmentKind.BIKE, F, 8607949, 8862828),
        m("Initial Bike", EquipmentKind.BIKE, F, 8578613, 8578634),
        m("EB 140 B", EquipmentKind.BIKE, FD, 8872850),
        m("EB 520", EquipmentKind.BIKE, D, 8640779, 8652259),
        m("EB 520 B", EquipmentKind.BIKE, FD, 8872851),
        m("EB 900", EquipmentKind.BIKE, D, 3200001, 3200002),
        m("EB 900 B", EquipmentKind.BIKE, FD, 8872852),
        m("Bike 500", EquipmentKind.BIKE, D, 2100003, 2100004, 2100005, 2100006),
        m("E-Seat / E-Fold", EquipmentKind.BIKE, D, 8368168, 8369246),
        m("E-Seat B", EquipmentKind.BIKE, D, 966114),
        m("E-Fold B", EquipmentKind.BIKE, D, 966119),
        m("E-Energy (vélo)", EquipmentKind.BIKE, D, 8363074, 8369237),
        // Elliptiques
        m("Elliptique 100", EquipmentKind.ELLIPTICAL, F, 8903327),
        m("Elliptique 300", EquipmentKind.ELLIPTICAL, F, 8607939),
        m("Elliptique 520 (2022)", EquipmentKind.ELLIPTICAL, D, 968153, 8607938),
        m("Elliptique 540", EquipmentKind.ELLIPTICAL, D, 8607943, 8607944),
        m("Elliptique 700", EquipmentKind.ELLIPTICAL, D, 8607932, 8580899),
        m("Elliptique 900", EquipmentKind.ELLIPTICAL, D, 3200003, 3200004),
        m("Elliptique 500/520 / E-Shape", EquipmentKind.ELLIPTICAL, D, 8368167, 8369244),
        m("E-Essential+", EquipmentKind.ELLIPTICAL, D, 2100001, 2100002),
        m("E-Energy (elliptique)", EquipmentKind.ELLIPTICAL, D, 8369247, 8369248),
        // Tapis
        m("Slim Run", EquipmentKind.TREADMILL, F, 8800625),
        m("Run 700", EquipmentKind.TREADMILL, F, 8603734, 8603735),
        m("Run 500", EquipmentKind.TREADMILL, F, 8542707, 8542708),
        m("TS100", EquipmentKind.TREADMILL, F, 8756473),
        m("T900D", EquipmentKind.TREADMILL, DF, 8754269, 8754274, 8772616),
        m("Run Initial", EquipmentKind.TREADMILL, DF, 8734772, 207007),
        m("Walk 900", EquipmentKind.TREADMILL, DF, 8405294, 8405266, 8405295),
        m("Run 100E", EquipmentKind.TREADMILL, DF, 8607158, 8607156, 8759370, 8796094),
        m("T900", EquipmentKind.TREADMILL, D, 9000001, 9000002),
        m("T540", EquipmentKind.TREADMILL, D, 5400001, 5400002),
        m("Incline Run", EquipmentKind.TREADMILL, D, 324129, 324336),
        m("E-Intense Run", EquipmentKind.TREADMILL, D, 8389706, 8389707),
        m("Climb", EquipmentKind.TREADMILL, D, 8396836),
        m("E-Shape Booster", EquipmentKind.TREADMILL, D, 84202, 8346893),
        // Rameurs
        m("Training Row", EquipmentKind.ROWER, F, 8804464),
        m("Wood Row", EquipmentKind.ROWER, F, 8604435),
        m("Water Rower", EquipmentKind.ROWER, F, 8783011),
        m("R500 SP", EquipmentKind.ROWER, D, 8600216, 8607947),
        m("R500", EquipmentKind.ROWER, D, 3100001, 3100002),
    )

    fun findByEquipmentId(id: Int?): KnownModel? = id?.let { i -> models.firstOrNull { i in it.equipmentIds } }

    /**
     * Modèles dont la résistance FTMS doit être multipliée par l'incrément annoncé dans
     * « Supported Resistance Level Range » (`hasResistanceNotHandledByFTMS` du SDK).
     */
    val FTMS_RESISTANCE_SCALED_IDS: Set<Int> = setOf(8607949, 8862828, 8806819)
}
