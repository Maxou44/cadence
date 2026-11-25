package io.opendomyos.cadence.program

/**
 * Petit parseur JSON sans dépendance (les fichiers programmes font < 600 Ko).
 * Renvoie Map<String, Any?>, List<Any?>, String, Double, Boolean ou null.
 */
object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON tronqué" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Caractère inattendu '$c' à $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "Littéral invalide à $i" }; i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> error("',' ou ']' attendu à ${i - 1}") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i++] == ':') { "':' attendu à ${i - 1}" }
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> error("',' ou '}' attendu à ${i - 1}") }
            }
        }
    }
}
