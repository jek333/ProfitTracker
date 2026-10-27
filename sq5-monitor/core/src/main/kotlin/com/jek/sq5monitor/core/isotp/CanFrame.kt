package com.jek.sq5monitor.core.isotp

/** One CAN frame as printed by the ELM327 with headers on (`ATH1`), e.g. `7E8 06 41 0D 32 AA AA AA`. */
class CanFrame(val id: Int, val data: IntArray) {
    override fun toString(): String =
        "%03X %s".format(id, data.joinToString(" ") { "%02X".format(it) })

    companion object {
        private val HEX_LINE = Regex("^[0-9A-Fa-f ]+$")

        /**
         * Parses a header-on reply line. Accepts both spaced (`7E8 03 41 0D 32`) and compact
         * (`7E803410D32`) output with 11-bit (3 hex digit) or 29-bit (8 hex digit) identifiers.
         * Returns null for anything that is not a frame (status text, `NO DATA`, ...).
         */
        fun parse(line: String): CanFrame? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !HEX_LINE.matches(trimmed)) return null
            val tokens = trimmed.split(Regex("\\s+"))
            val (header, bytes) = if (tokens.size > 1) {
                // 29-bit headers are printed as four separate bytes: 18 DA F1 10.
                if (tokens[0].length == 2 && tokens.size >= 5 && tokens.take(4).all { it.length == 2 }) {
                    tokens.take(4).joinToString("") to tokens.drop(4)
                } else {
                    tokens[0] to tokens.drop(1)
                }
            } else {
                val compact = tokens[0]
                val headerLength = if (compact.length % 2 == 1) 3 else 8
                if (compact.length <= headerLength) return null
                compact.substring(0, headerLength) to compact.substring(headerLength).chunked(2)
            }
            if (header.length != 3 && header.length != 8) return null
            if (bytes.isEmpty() || bytes.any { it.length != 2 }) return null
            return CanFrame(header.toInt(16), IntArray(bytes.size) { bytes[it].toInt(16) })
        }
    }
}
