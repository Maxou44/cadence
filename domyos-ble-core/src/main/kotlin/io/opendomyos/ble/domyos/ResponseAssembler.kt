package io.opendomyos.ble.domyos

/**
 * Reconstitue une réponse Domyos à partir des notifications BLE.
 *
 * La console envoie ses réponses par paquets de 20 octets max (MTU par défaut) ; une réponse
 * de 26 octets arrive donc en deux notifications, et une notification peut commencer par des
 * octets résiduels. On cherche la séquence `F0 <opcode attendu>`, on attend la longueur
 * prévue, puis on valide le checksum. En cas de checksum invalide on reprend la recherche
 * un octet plus loin (comportement plus robuste que le SDK d'origine, compatible avec lui).
 */
class ResponseAssembler(private val expectedOpcode: Int, private val expectedLength: Int) {
    private var buffer = ByteArray(0)

    /** Ajoute un paquet reçu ; renvoie la trame complète et valide dès qu'elle est disponible. */
    fun feed(chunk: ByteArray): ByteArray? {
        buffer += chunk
        while (true) {
            val start = findStart()
            if (start < 0) {
                // garder un éventuel F0 final qui pourrait être le début de la réponse
                buffer = if (buffer.isNotEmpty() && buffer.last().toInt() and 0xFF == DomyosFrame.HEADER)
                    byteArrayOf(buffer.last()) else ByteArray(0)
                return null
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < expectedLength) return null
            val candidate = buffer.copyOfRange(0, expectedLength)
            if (DomyosFrame.isValidResponse(candidate, expectedOpcode)) {
                buffer = buffer.copyOfRange(expectedLength, buffer.size)
                return candidate
            }
            buffer = buffer.copyOfRange(1, buffer.size) // faux départ : on avance
        }
    }

    fun reset() { buffer = ByteArray(0) }

    private fun findStart(): Int {
        for (i in 0 until buffer.size - 1) {
            if ((buffer[i].toInt() and 0xFF) == DomyosFrame.HEADER && (buffer[i + 1].toInt() and 0xFF) == expectedOpcode) return i
        }
        return -1
    }
}
