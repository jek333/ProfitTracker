package com.jek.sq5monitor.core

import com.jek.sq5monitor.core.obd.Dtc
import com.jek.sq5monitor.core.obd.DtcDescriptions
import com.jek.sq5monitor.core.obd.MonitorStatus
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Quantity
import com.jek.sq5monitor.core.obd.SignalInputs
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.core.obd.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecodingTest {

    private fun decode(pid: Int, vararg bytes: Int) = Pids[pid]!!.decode(bytes)

    @Test
    fun `decodes J1979 formulas`() {
        assertEquals(1726.0, decode(Pids.RPM, 0x1A, 0xF8), 0.001)
        assertEquals(90.0, decode(Pids.COOLANT_TEMP, 130), 0.001)
        assertEquals(100.0, decode(Pids.THROTTLE, 255), 0.001)
        assertEquals(-64.0, decode(Pids.TIMING_ADVANCE, 0), 0.001)
        assertEquals(12.5, decode(Pids.TIMING_ADVANCE, 153), 0.001)
        assertEquals(-100.0, decode(Pids.STFT_B1, 0), 0.001)
        assertEquals(0.0, decode(Pids.STFT_B1, 128), 0.001)
        assertEquals(655.35, decode(Pids.MAF, 0xFF, 0xFF), 0.001)
        assertEquals(14.2, decode(Pids.MODULE_VOLTAGE, 0x37, 0x78), 0.001)
        assertEquals(1.0, decode(Pids.LAMBDA_COMMANDED, 0x80, 0x00), 0.001)
        assertEquals(520.0, decode(Pids.CAT_TEMP_B1S1, 0x15, 0xE0), 0.001)
    }

    @Test
    fun `decodes supported pid bitmaps`() {
        // Classic example: BE 1F A8 13 -> 01 03 04 05 06 07 0C 0D 0E 0F 10 11 13 15 1C 1F 20
        val supported = Pids.decodeSupported(0x00, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), supported)
        assertEquals(setOf(0x41, 0x60), Pids.decodeSupported(0x40, intArrayOf(0x80, 0x00, 0x00, 0x01)))
    }

    @Test
    fun `decodes dtc bytes`() {
        assertEquals("P0301", Dtc.fromBytes(0x03, 0x01)!!.code)
        assertEquals("C0035", Dtc.fromBytes(0x40, 0x35)!!.code)
        assertEquals("B1234", Dtc.fromBytes(0x92, 0x34)!!.code)
        assertEquals("U0101", Dtc.fromBytes(0xC1, 0x01)!!.code)
        assertEquals("P2A00", Dtc.fromBytes(0x2A, 0x00)!!.code)
        assertNull(Dtc.fromBytes(0, 0))
    }

    @Test
    fun `parses CAN mode 03 reply with count byte`() {
        val codes = Dtc.parseResponse(intArrayOf(0x43, 0x02, 0x04, 0x56, 0x03, 0x01))
        assertEquals(listOf("P0456", "P0301"), codes.map { it.code })
        assertTrue(Dtc.parseResponse(intArrayOf(0x43, 0x00)).isEmpty())
    }

    @Test
    fun `parses legacy mode 03 reply without count byte`() {
        val codes = Dtc.parseResponse(intArrayOf(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00))
        assertEquals(listOf("P0133"), codes.map { it.code })
    }

    @Test
    fun `describes known and unknown codes`() {
        assertEquals("Cylinder 4 misfire detected", DtcDescriptions.describe("P0304"))
        assertEquals("Ignition coil C primary/secondary circuit", DtcDescriptions.describe("P0353"))
        assertTrue(DtcDescriptions.isKnown("P0420"))
        assertFalse(DtcDescriptions.isKnown("P1234"))
        assertEquals("Manufacturer-specific fuel and air metering fault", DtcDescriptions.describe("P1234"))
        assertEquals("Generic network communication fault", DtcDescriptions.describe("U0299"))
        assertEquals("Manufacturer-specific body (airbags, lighting, comfort) fault", DtcDescriptions.describe("B1001"))
    }

    @Test
    fun `decodes readiness monitors for a spark ignition engine`() {
        // MIL on, 2 codes; misfire/fuel/components supported & complete; catalyst, evap, O2, O2 heater
        // supported; evap incomplete.
        val status = MonitorStatus.decode(intArrayOf(0x82, 0x07, 0x65, 0x04))
        assertTrue(status.milOn)
        assertEquals(2, status.dtcCount)
        assertFalse(status.compressionIgnition)
        assertEquals(
            listOf("Misfire", "Fuel system", "Components", "Catalyst", "Evaporative system", "Oxygen sensor", "Oxygen sensor heater"),
            status.monitors.map { it.name },
        )
        assertEquals(listOf("Evaporative system"), status.incomplete.map { it.name })
    }

    @Test
    fun `converts units for display`() {
        assertEquals("194", Units.display(90.0, Quantity.TEMPERATURE, UnitSystem.IMPERIAL, 0).text)
        assertEquals("°F", Units.display(90.0, Quantity.TEMPERATURE, UnitSystem.IMPERIAL, 0).unit)
        assertEquals("62", Units.display(100.0, Quantity.SPEED, UnitSystem.IMPERIAL, 0).text)
        assertEquals("14.5", Units.display(100.0, Quantity.PRESSURE, UnitSystem.IMPERIAL, 0).text)
        assertEquals("23.5", Units.display(10.0, Quantity.FUEL_ECONOMY, UnitSystem.IMPERIAL, 1).text)
        assertEquals("100 km/h", Units.display(100.0, Quantity.SPEED, UnitSystem.METRIC, 0).toString())
    }

    @Test
    fun `derived signals compute from their inputs`() {
        val boost = Signals[Signals.BOOST]!!
        assertEquals(115.0, boost.compute(SignalInputs(mapOf(Pids.INTAKE_MAP to 216.0, Pids.BAROMETRIC to 101.0), null))!!, 0.001)
        assertNull(boost.compute(SignalInputs(mapOf(Pids.INTAKE_MAP to 216.0), null)))

        val economy = Signals[Signals.FUEL_ECONOMY]!!
        // 20 g/s at 100 km/h: 20/14.7*3600/745 = 6.575 L/h -> 6.575 L/100km
        assertEquals(6.575, economy.compute(SignalInputs(mapOf(Pids.MAF to 20.0, Pids.SPEED to 100.0), null))!!, 0.01)
        assertNull(economy.compute(SignalInputs(mapOf(Pids.MAF to 5.0, Pids.SPEED to 0.0), null)))
    }

    @Test
    fun `available signals depend on supported pids`() {
        val ids = Signals.available(setOf(Pids.RPM, Pids.INTAKE_MAP, Pids.BAROMETRIC)).map { it.id }.toSet()
        assertTrue(Signals.BOOST in ids)
        assertTrue(Signals.BATTERY in ids)
        assertTrue(Signals.pidId(Pids.RPM) in ids)
        assertFalse(Signals.FUEL_ECONOMY in ids)
        assertFalse(Signals.pidId(Pids.OIL_TEMP) in ids)
    }

    @Test
    fun `default dashboard only references existing signals`() {
        Signals.defaultDashboard.forEach { assertTrue(it, Signals[it] != null) }
        assertEquals(Signals.all.size, Signals.all.map { it.id }.toSet().size)
    }
}
