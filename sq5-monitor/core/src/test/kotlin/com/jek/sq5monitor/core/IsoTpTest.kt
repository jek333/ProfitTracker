package com.jek.sq5monitor.core

import com.jek.sq5monitor.core.isotp.CanFrame
import com.jek.sq5monitor.core.isotp.IsoTp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTpTest {

    @Test
    fun `parses spaced and compact 11-bit frames`() {
        val spaced = CanFrame.parse("7E8 06 41 00 BE 3F A8 13")!!
        assertEquals(0x7E8, spaced.id)
        assertArrayEquals(intArrayOf(0x06, 0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), spaced.data)

        val compact = CanFrame.parse("7E803410D32")!!
        assertEquals(0x7E8, compact.id)
        assertArrayEquals(intArrayOf(0x03, 0x41, 0x0D, 0x32), compact.data)
    }

    @Test
    fun `parses 29-bit frames`() {
        val frame = CanFrame.parse("18 DA F1 10 03 41 0D 32")!!
        assertEquals(0x18DAF110, frame.id)
        assertArrayEquals(intArrayOf(0x03, 0x41, 0x0D, 0x32), frame.data)
    }

    @Test
    fun `ignores non-frame lines`() {
        assertNull(CanFrame.parse("NO DATA"))
        assertNull(CanFrame.parse("OK"))
        assertNull(CanFrame.parse("SEARCHING..."))
        assertNull(CanFrame.parse(""))
    }

    @Test
    fun `single frame is truncated to its PCI length, ignoring padding`() {
        val messages = IsoTp.assemble(listOf("7E8 03 41 0D 32 AA AA AA AA"))
        assertEquals(1, messages.size)
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), messages[0].payload)
    }

    @Test
    fun `reassembles a multi-frame VIN reply`() {
        val lines = listOf(
            "7E8 10 14 49 02 01 57 41 31",
            "7E8 21 43 34 41 46 59 35 4A",
            "7E8 22 32 30 30 30 30 30 31",
        )
        val message = IsoTp.assemble(lines).single()
        assertEquals(0x14, message.payload.size)
        val vin = message.payload.drop(3).map { it.toChar() }.joinToString("")
        assertEquals("WA1C4AFY5J2000001", vin)
    }

    @Test
    fun `keeps interleaved ECUs apart`() {
        val lines = listOf(
            "7E8 10 0A 41 00 BE 3F A8 13",
            "7E9 06 41 00 80 00 00 00",
            "7E8 21 20 80 00 00 01 AA AA",
        )
        val messages = IsoTp.assemble(lines)
        assertEquals(listOf(0x7E9, 0x7E8), messages.map { it.ecuId })
        assertArrayEquals(intArrayOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13, 0x20, 0x80, 0x00, 0x00), messages[1].payload)
    }

    @Test
    fun `drops a message with a missing consecutive frame`() {
        val lines = listOf(
            "7E8 10 14 49 02 01 57 41 31",
            "7E8 22 32 30 30 30 30 30 31",
        )
        assertEquals(0, IsoTp.assemble(lines).size)
    }
}
