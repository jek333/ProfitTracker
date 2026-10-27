package com.jek.sq5monitor.core

import com.jek.sq5monitor.core.log.CsvLog
import com.jek.sq5monitor.core.monitor.AlertEvaluator
import com.jek.sq5monitor.core.monitor.AlertSeverity
import com.jek.sq5monitor.core.monitor.LiveSample
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.UnitSystem
import com.jek.sq5monitor.core.sim.SimulatedElm327
import com.jek.sq5monitor.core.vehicle.Vin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class VinAndAlertsTest {

    @Test
    fun `computes the standard check digit example`() {
        assertEquals('X', Vin.checkDigit("1M8GDM9AXKP042788"))
        assertTrue(Vin.decode("1M8GDM9AXKP042788").checkDigitValid)
    }

    @Test
    fun `decodes a 2018 SQ5 VIN`() {
        val info = Vin.decode(SimulatedElm327.DEMO_VIN)
        assertEquals("Audi AG (SUV)", info.manufacturer)
        assertEquals("Q5 / SQ5 (FY, 2nd generation)", info.model)
        assertEquals(2018, info.modelYear)
        assertTrue(info.checkDigitValid)
    }

    @Test
    fun `rejects malformed VINs`() {
        assertFalse(Vin.isValidFormat("WA1C4AFY5J200000"))
        assertFalse(Vin.isValidFormat("WA1C4AFYOJ2000001")) // letter O is not allowed
        assertFalse(Vin.decode("WA1C4AFY9J2000001").checkDigitValid)
    }

    private val coolant = Signals.pidId(Pids.COOLANT_TEMP)
    private val rpm = Signals.pidId(Pids.RPM)

    @Test
    fun `no alerts for a healthy warm engine`() {
        val alerts = AlertEvaluator().evaluate(mapOf(coolant to 95.0, rpm to 800.0, Signals.BATTERY to 14.1), milOn = false)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `alerts on overheating, check engine light and charging`() {
        val alerts = AlertEvaluator().evaluate(mapOf(coolant to 118.0, rpm to 2000.0, Signals.BATTERY to 12.3), milOn = true)
        assertEquals(setOf("mil", "coolant", "battery_charge"), alerts.map { it.id }.toSet())
        assertEquals(AlertSeverity.CRITICAL, alerts.first { it.id == "coolant" }.severity)
    }

    @Test
    fun `low resting voltage only warns with the engine off`() {
        val evaluator = AlertEvaluator()
        assertEquals(listOf("battery_low"), evaluator.evaluate(mapOf(rpm to 0.0, Signals.BATTERY to 11.8), false).map { it.id })
        assertTrue(evaluator.evaluate(mapOf(rpm to 0.0, Signals.BATTERY to 12.5), false).isEmpty())
    }

    @Test
    fun `csv log writes header and converted rows`() {
        val out = StringWriter()
        val signals = listOf(Signals[Signals.BOOST]!!, Signals[coolant]!!)
        val log = CsvLog(out, signals, UnitSystem.IMPERIAL)
        log.writeHeader()
        log.append(LiveSample(1_700_000_000_000, mapOf(Signals.BOOST to 103.42, coolant to 90.0), false, 10.0))
        log.append(LiveSample(1_700_000_000_500, mapOf(coolant to 91.0), false, 10.0))
        val lines = out.toString().trim().lines()
        assertEquals("timestamp,elapsed_s,Boost (psi),Coolant (°F)", lines[0])
        assertEquals("2023-11-14T22:13:20Z,0.000,15.0,194", lines[1])
        assertEquals("2023-11-14T22:13:20.500Z,0.500,,196", lines[2])
    }
}
