package io.opendomyos.ble.domyos

/**
 * Format de trame du protocole propriétaire Domyos.
 *
 * ```
 * Requête  : F0 <op>        <payload…> <checksum>
 * Réponse  : F0 <op + 0x10> <payload…> <checksum>
 * checksum = somme de tous les octets précédents, modulo 256
 * ```
 * Les entiers multi-octets sont en big-endian. La valeur « tout à 1 » (FF / FFFF)
 * signifie « ne pas modifier » dans les commandes d'écriture.
 */
object DomyosFrame {
    const val HEADER: Int = 0xF0

    fun checksum(bytes: ByteArray, length: Int = bytes.size): Byte {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return sum.toByte()
    }

    /** Construit une trame complète `F0 op payload… cs`. */
    fun build(opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = HEADER.toByte()
        out[1] = opcode.toByte()
        payload.copyInto(out, 2)
        out[out.size - 1] = checksum(out, out.size - 1)
        return out
    }

    /** Opcode de réponse attendu pour un opcode de requête. */
    fun responseOpcode(requestOpcode: Int): Int = (requestOpcode + 0x10) and 0xFF

    /** Vérifie l'en-tête, l'opcode et le checksum d'une réponse complète. */
    fun isValidResponse(frame: ByteArray, expectedOpcode: Int): Boolean =
        frame.size >= 3 &&
            (frame[0].toInt() and 0xFF) == HEADER &&
            (frame[1].toInt() and 0xFF) == expectedOpcode &&
            checksum(frame, frame.size - 1) == frame[frame.size - 1]
}

/** Lecture big-endian d'une trame de réponse (indices absolus dans la trame). */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
