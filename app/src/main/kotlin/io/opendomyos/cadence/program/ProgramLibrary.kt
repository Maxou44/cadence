package io.opendomyos.cadence.program

import io.opendomyos.ble.EquipmentCatalog

/** Ensemble des programmes chargés depuis les fichiers JSON Decathlon. */
class ProgramLibrary(val programs: List<Program>) {

    /** Programmes compatibles avec un ID console ; tous si l'ID est inconnu. */
    fun forConsole(equipmentId: Int?): List<Program> {
        if (equipmentId == null) return programs
        val match = programs.filter { equipmentId in it.consoleIds }
        return match.ifEmpty { programs }
    }

    fun byKey(key: String) = programs.firstOrNull { it.key == key }

    /** Modèles disponibles (pour choisir manuellement sans console connectée). */
    fun models(): List<Pair<String, Set<Int>>> =
        programs.groupBy { it.consoleIds }.keys.map { ids ->
            val name = ids.firstNotNullOfOrNull { EquipmentCatalog.findByEquipmentId(it)?.name } ?: "Console ${ids.first()}"
            name to ids
        }.distinctBy { it.first }.sortedBy { it.first }

    companion object {
        /** @param files nom de fichier → contenu JSON */
        fun load(files: Map<String, String>): ProgramLibrary {
            val all = ArrayList<Program>()
            for ((name, text) in files.toSortedMap()) {
                val root = MiniJson.parse(text) as? Map<*, *> ?: continue
                val list = root["ProgramData"] as? List<*> ?: continue
                val byId = LinkedHashMap<String, MutableList<Map<*, *>>>()
                list.filterIsInstance<Map<*, *>>().forEach { byId.getOrPut(it["idProgram"].toString()) { ArrayList() } += it }
                for ((idProgram, variants) in byId) {
                    val first = variants.first()
                    val points = (first["dataStreams"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { p ->
                        ProgramPoint(
                            progress = (p["progress"] as Number).toFloat(),
                            value = (p["value"] as Number).toInt(),
                            rpmMin = (p["rpm_min"] as? Number)?.toInt(),
                            rpmMax = (p["rpm_max"] as? Number)?.toInt(),
                            instruction = (p["instruction"] as? String)?.takeIf { it.isNotBlank() },
                        )
                    }
                    if (points.isEmpty()) continue
                    val category = ProgramCategory.of((first["idCategorie"] as? Number)?.toInt() ?: -1)
                    all += Program(
                        key = "${name.removeSuffix(".json")}/$idProgram",
                        idProgram = idProgram,
                        title = title(first["label"], idProgram, category),
                        category = category,
                        consoleIds = variants.mapNotNull { it["idConsole"]?.toString()?.trim()?.toIntOrNull() }.toSet(),
                        durationSec = (first["valueTarget"] as? Number)?.toInt() ?: 1800,
                        level = (first["level"] as? Number)?.toInt()?.takeIf { it > 0 },
                        style = styleName(first["curveType"] as? String),
                        points = points,
                    )
                }
            }
            // Titres en double (ex. « HIIT ») : on ajoute le numéro de fin d'idProgram ou le n° PROG.
            val dup = all.groupBy { it.consoleIds.sorted().toString() + "|" + it.title }.filterValues { it.size > 1 }.values.flatten().toSet()
            val result = all.map { p ->
                if (p !in dup) p else {
                    val tail = Regex("(\\d+)\\s*$").find(p.idProgram)?.groupValues?.get(1)
                    val prog = Regex("^PROG\\s*(\\d+)", RegexOption.IGNORE_CASE).find(p.idProgram)?.groupValues?.get(1)
                    val suffix = if (tail != null && !p.title.any { it.isDigit() }) " $tail" else prog?.let { " · n°$it" } ?: ""
                    p.copy(title = p.title + suffix)
                }
            }
            return ProgramLibrary(result)
        }

        private fun title(label: Any?, idProgram: String, category: ProgramCategory): String {
            val raw = when (label) {
                is Map<*, *> -> (label["FR"] ?: label["EN"] ?: label.values.firstOrNull())?.toString()
                is String -> label
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
                ?: idProgram
            // « FRACTIONNÉ 1 » → « Fractionné 1 » ; « INTERVAL 1 » → « Interval 1 »
            return raw.lowercase().split(Regex("\\s+")).joinToString(" ") { w ->
                if (w.length <= 2 && w.any { it.isDigit() }) w.uppercase() else w.replaceFirstChar { it.titlecase() }
            }.let { if (it.equals("hiit", true)) "HIIT" else it.replace("Hiit", "HIIT") }
                .ifBlank { category.label }
        }

        private fun styleName(curve: String?) = when (curve?.lowercase()) {
            null, "" -> null
            "interval" -> "Intervalles"
            "sprint interval" -> "Sprints"
            "sweetspot" -> "Sweet spot"
            "climbing" -> "Grimpée"
            "hiit" -> "HIIT"
            else -> curve
        }
    }
}
