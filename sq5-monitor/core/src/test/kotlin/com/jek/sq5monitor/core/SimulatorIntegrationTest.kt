package com.jek.sq5monitor.core

import com.jek.sq5monitor.core.elm.Elm327
import com.jek.sq5monitor.core.elm.ElmException
import com.jek.sq5monitor.core.elm.ElmResponse
import com.jek.sq5monitor.core.elm.ElmTimeoutException
import com.jek.sq5monitor.core.link.StreamElmLink
import com.jek.sq5monitor.core.monitor.LiveMonitor
import com.jek.sq5monitor.core.obd.DtcKind
import com.jek.sq5monitor.core.obd.ObdClient
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.sim.SimulatedElm327
import com.jek.sq5monitor.core.uds.ModuleScanResult
import com.jek.sq5monitor.core.uds.ModuleScanner
import com.jek.sq5monitor.core.uds.UdsClient
import com.jek.sq5monitor.core.uds.UdsDids
import com.jek.sq5monitor.core.uds.UdsNegativeResponseException
import com.jek.sq5monitor.core.uds.VagModules
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Drives the real protocol stack against the simulated adapter and car. */
class SimulatorIntegrationTest {

    /** Fixed clock: 12 s into the drive cycle, i.e. mid-way through a full-throttle pull. */
    private var now = 1_000_000L
    private val sim = SimulatedElm327(clock = { now })
    private val elm = Elm327(sim, pollIntervalMs = 1)
    private val obd = ObdClient(elm)
    private val uds = UdsClient(elm)

    private fun connected(block: suspend () -> Unit) = runBlocking {
        val info = elm.initialize()
        assertEquals("ELM327 v1.5", info.version)
        assertEquals("ISO 15765-4 (CAN 11/500)", info.protocol)
        block()
    }

    @Test
    fun `discovers supported pids across bitmaps`() = connected {
        val supported = obd.supportedPids()
        assertTrue(Pids.RPM in supported)
        assertTrue(Pids.OIL_TEMP in supported) // lives in the 0x41-0x60 bitmap
        assertFalse(0x20 in supported)
        assertFalse(Pids.FUEL_RATE in supported)
    }

    @Test
    fun `reads many pids using multi-pid requests`() = connected {
        now += 12_000
        val pids = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.INTAKE_MAP, Pids.BAROMETRIC, Pids.THROTTLE, Pids.OIL_TEMP, Pids.MAF)
        val values = obd.readValues(pids)
        assertEquals(pids.toSet(), values.keys)
        assertTrue("rpm ${values[Pids.RPM]}", values.getValue(Pids.RPM) in 2800.0..6200.0)
        assertTrue("boost", values.getValue(Pids.INTAKE_MAP) > values.getValue(Pids.BAROMETRIC) + 50)
        assertEquals(101.0, values.getValue(Pids.BAROMETRIC), 0.001)
    }

    @Test
    fun `reads the vin`() = connected {
        assertEquals(SimulatedElm327.DEMO_VIN, obd.readVin())
    }

    @Test
    fun `reads and clears obd trouble codes`() = connected {
        val stored = obd.readDtcs(DtcKind.STORED)
        assertEquals(listOf(0x7E8, 0x7E9), stored.map { it.ecuId })
        assertEquals(listOf("P0456"), stored.first { it.ecuName == "Engine" }.codes.map { it.code })
        assertTrue(stored.first { it.ecuName == "Transmission" }.codes.isEmpty())

        val status = obd.monitorStatus()!!
        assertTrue(status.milOn)
        assertEquals(listOf("Evaporative system"), status.incomplete.map { it.name })

        assertTrue(obd.clearDtcs())
        assertTrue(obd.readDtcs(DtcKind.STORED).all { it.codes.isEmpty() })
        val afterClear = obd.monitorStatus()!!
        assertFalse(afterClear.milOn)
        assertEquals(4, afterClear.incomplete.size) // all non-continuous monitors reset
    }

    @Test
    fun `reads identification and dtcs from a module over uds`() = connected {
        val gateway = VagModules.byAddress("19")!!
        uds.withModule(gateway) {
            assertEquals("J533 GATEWAY", readDidText(UdsDids.SYSTEM_NAME))
            assertEquals(SimulatedElm327.DEMO_VIN, readDidText(UdsDids.VIN)) // multi-frame with flow control
            assertEquals(null, readDidText(0x1234)) // NRC 0x31 -> unsupported
            val dtcs = readDtcs()
            assertEquals(listOf("U0101 00"), dtcs.map { it.display })
            assertFalse(dtcs.single().active)
            assertTrue(dtcs.single().confirmed)
            try {
                request(0x2E, 0xF1, 0x90) // writes are not supported by the simulated module
                fail("expected negative response")
            } catch (e: UdsNegativeResponseException) {
                assertEquals(0x11, e.code)
            }
        }
        // Functional addressing is restored afterwards, so OBD-II keeps working.
        assertNotNull(obd.readValues(listOf(Pids.RPM))[Pids.RPM])
    }

    @Test
    fun `scans all modules like an auto-scan`() = connected {
        val results = ModuleScanner(uds).scan().toList()
        assertEquals(VagModules.all.size, results.size)
        assertEquals(results.size, results.last().done)
        val byAddress = results.associate { it.result.module.address to it.result }
        assertTrue(byAddress["13"] is ModuleScanResult.NotFound)
        val engine = byAddress["01"] as ModuleScanResult.Found
        assertEquals("J623 ENGINE", engine.identification["System name"])
        assertEquals(listOf("P0456 00"), engine.dtcs.map { it.display })
        val gateway = byAddress["19"] as ModuleScanResult.Found
        assertEquals(1, gateway.dtcs.size)
        val abs = byAddress["03"] as ModuleScanResult.Found
        assertTrue(abs.dtcs.isEmpty())

        uds.withModule(VagModules.byAddress("19")!!) { clearDtcs() }
        val rescanned = ModuleScanner(uds).scanModule(VagModules.byAddress("19")!!) as ModuleScanResult.Found
        assertTrue(rescanned.dtcs.isEmpty())
    }

    @Test
    fun `live monitor emits derived and slow signals`() = connected {
        now += 12_000
        val signals = listOf(Signals[Signals.BOOST]!!, Signals[Signals.BATTERY]!!, Signals[Signals.pidId(Pids.COOLANT_TEMP)]!!, Signals[Signals.pidId(Pids.RPM)]!!)
        val samples = LiveMonitor(elm, obd).samples(signals, minIntervalMs = 0, slowEvery = 3).take(4).toList()
        val first = samples.first()
        assertTrue(first.values.keys.containsAll(signals.map { it.id }))
        assertTrue("boost ${first.values[Signals.BOOST]}", first.values.getValue(Signals.BOOST) > 50)
        assertEquals(14.1, first.values.getValue(Signals.BATTERY), 0.1)
        assertEquals(true, first.milOn)
        // Slow values are carried over between slow cycles.
        assertTrue(samples.all { it.values.containsKey(Signals.pidId(Pids.COOLANT_TEMP)) })
    }

    @Test
    fun `initialisation fails clearly when the ignition is off`() = runBlocking {
        sim.ignitionOn = false
        try {
            elm.initialize()
            fail("expected failure")
        } catch (e: ElmException) {
            assertTrue(e.message!!.contains("ignition"))
        }
    }

    @Test
    fun `times out when the adapter never answers`() = runBlocking {
        val silent = object : InputStream() {
            override fun available() = 0
            override fun read() = -1
        }
        val dead = Elm327(StreamElmLink("dead", silent, ByteArrayOutputStream(), silent), pollIntervalMs = 1)
        try {
            dead.send("ATZ", timeoutMs = 50)
            fail("expected timeout")
        } catch (e: ElmTimeoutException) {
            assertTrue(e.message!!.contains("ATZ"))
        }
    }

    @Test
    fun `response parsing strips echo, searching and detects errors`() {
        val response = ElmResponse.parse("0100", "0100\rSEARCHING...\r7E8 06 41 00 BE 3F A8 13\r\r")
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13"), response.lines)
        assertEquals(null, response.error)
        assertTrue(ElmResponse.parse("0100", "NO DATA\r").isNoData)
        assertEquals("CAN ERROR", ElmResponse.parse("0100", "CAN ERROR\r").error)
        assertEquals("?", ElmResponse.parse("ATXX", "?\r").error)
    }
}
