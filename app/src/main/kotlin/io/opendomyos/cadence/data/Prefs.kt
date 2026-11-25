package io.opendomyos.cadence.data

import android.content.Context
import io.opendomyos.cadence.program.MiniJson

data class SavedDevice(val address: String, val name: String)

data class HistoryEntry(
    val dateMs: Long,
    val programKey: String?,
    val title: String,
    val durationSec: Int,
    val distanceKm: Float,
    val kcal: Int,
    val avgPower: Int,
    val maxPower: Int,
    val avgCadence: Int,
    val avgHeartRate: Int,
    val completed: Boolean,
)

/** Persistance simple : appareil appairé, favoris, statistiques de programmes, historique. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cadence", Context.MODE_PRIVATE)

    var device: SavedDevice?
        get() = sp.getString("dev_addr", null)?.let { SavedDevice(it, sp.getString("dev_name", null) ?: it) }
        set(v) { sp.edit().apply { if (v == null) { remove("dev_addr"); remove("dev_name") } else { putString("dev_addr", v.address); putString("dev_name", v.name) } }.apply() }

    /** Dernier ID console connu (pour filtrer les programmes sans être connecté). */
    var lastEquipmentId: Int?
        get() = sp.getInt("eq_id", -1).takeIf { it >= 0 }
        set(v) { sp.edit().putInt("eq_id", v ?: -1).apply() }

    var autoPause: Boolean
        get() = sp.getBoolean("auto_pause", true)
        set(v) { sp.edit().putBoolean("auto_pause", v).apply() }

    var favorites: Set<String>
        get() = sp.getStringSet("favs", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favs", v).apply() }

    fun toggleFavorite(key: String): Boolean {
        val f = favorites.toMutableSet()
        val now = if (key in f) { f.remove(key); false } else { f.add(key); true }
        favorites = f
        return now
    }

    fun playCount(key: String) = sp.getInt("plays_$key", 0)
    fun lastPlayed(key: String) = sp.getLong("last_$key", 0)

    fun addHistory(e: HistoryEntry) {
        e.programKey?.let { k -> sp.edit().putInt("plays_$k", playCount(k) + 1).putLong("last_$k", e.dateMs).apply() }
        val list = (listOf(e) + history()).take(200)
        sp.edit().putString("history", encode(list)).apply()
    }

    fun history(): List<HistoryEntry> = runCatching {
        (MiniJson.parse(sp.getString("history", "[]")!!) as List<*>).filterIsInstance<Map<*, *>>().map { m ->
            fun n(k: String) = (m[k] as? Number) ?: 0
            HistoryEntry(
                n("d").toLong(), m["k"] as? String, m["t"] as? String ?: "", n("s").toInt(), n("km").toFloat(), n("kc").toInt(),
                n("p").toInt(), n("pm").toInt(), n("c").toInt(), n("hr").toInt(), m["ok"] == true,
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<HistoryEntry>): String = list.joinToString(",", "[", "]") { e ->
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        "{\"d\":${e.dateMs},\"k\":${e.programKey?.let(::q) ?: "null"},\"t\":${q(e.title)},\"s\":${e.durationSec}," +
            "\"km\":${e.distanceKm},\"kc\":${e.kcal},\"p\":${e.avgPower},\"pm\":${e.maxPower},\"c\":${e.avgCadence}," +
            "\"hr\":${e.avgHeartRate},\"ok\":${e.completed}}"
    }
}
