package com.jek.sq5monitor.core.obd

/** Which OBD-II trouble code list to read. */
enum class DtcKind(val mode: Int, val label: String) {
    STORED(0x03, "Stored"),
    PENDING(0x07, "Pending"),
    PERMANENT(0x0A, "Permanent"),
}

/** An SAE J2012 trouble code such as `P0301`. */
data class Dtc(val code: String) {
    val description: String get() = DtcDescriptions.describe(code)

    companion object {
        private const val LETTERS = "PCBU"

        /** Decodes the two-byte SAE form. Returns null for the all-zero filler entry. */
        fun fromBytes(a: Int, b: Int): Dtc? {
            if (a == 0 && b == 0) return null
            val letter = LETTERS[a shr 6]
            val first = (a shr 4) and 0x03
            return Dtc("%c%d%X%02X".format(letter, first, a and 0x0F, b))
        }

        /**
         * Parses a mode 03/07/0A reply payload (starting with the positive response byte).
         * On CAN the second byte is the number of codes; older protocols omit it.
         */
        fun parseResponse(payload: IntArray): List<Dtc> {
            if (payload.size < 2) return emptyList()
            val count = payload[1]
            val start = if ((payload.size - 2) == count * 2) 2 else 1
            return (start until payload.size - 1 step 2).mapNotNull { fromBytes(payload[it], payload[it + 1]) }
        }
    }
}
