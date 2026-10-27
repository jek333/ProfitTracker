package com.jek.sq5monitor.core.isotp

/** A complete (reassembled) ISO-TP message from one ECU. */
class EcuMessage(val ecuId: Int, val payload: IntArray) {
    override fun toString(): String =
        "%03X: %s".format(ecuId, payload.joinToString(" ") { "%02X".format(it) })
}

/**
 * ISO 15765-2 (ISO-TP) reassembly of the frames printed by an ELM327 with headers enabled.
 *
 * The ELM327 handles flow control itself; it simply prints every frame it receives. This class turns
 * single frames and first/consecutive frame sequences back into complete messages, keeping replies
 * from different ECUs apart even when they are interleaved.
 */
object IsoTp {

    fun assemble(lines: List<String>): List<EcuMessage> = assembleFrames(lines.mapNotNull(CanFrame::parse))

    fun assembleFrames(frames: List<CanFrame>): List<EcuMessage> {
        val messages = mutableListOf<EcuMessage>()
        val pending = mutableMapOf<Int, Pending>()

        for (frame in frames) {
            val data = frame.data
            if (data.isEmpty()) continue
            val pci = data[0]
            when (pci shr 4) {
                0x0 -> { // Single frame
                    val length = pci and 0x0F
                    if (length in 1..data.size - 1) {
                        messages += EcuMessage(frame.id, data.copyOfRange(1, 1 + length))
                    }
                }
                0x1 -> { // First frame
                    if (data.size < 2) continue
                    val length = ((pci and 0x0F) shl 8) or data[1]
                    val state = Pending(length)
                    state.append(data, from = 2)
                    pending[frame.id] = state
                }
                0x2 -> { // Consecutive frame
                    val state = pending[frame.id] ?: continue
                    if ((pci and 0x0F) != state.nextSequence) {
                        pending.remove(frame.id) // lost a frame; the message is unusable
                        continue
                    }
                    state.nextSequence = (state.nextSequence + 1) and 0x0F
                    state.append(data, from = 1)
                }
                else -> Unit // Flow control or garbage
            }
            pending[frame.id]?.takeIf { it.isComplete }?.let {
                messages += EcuMessage(frame.id, it.payload())
                pending.remove(frame.id)
            }
        }
        return messages
    }

    private class Pending(val length: Int) {
        private val buffer = ArrayList<Int>(length)
        var nextSequence = 1
        val isComplete: Boolean get() = buffer.size >= length

        fun append(data: IntArray, from: Int) {
            for (i in from until data.size) {
                if (buffer.size < length) buffer += data[i]
            }
        }

        fun payload(): IntArray = buffer.toIntArray()
    }
}
