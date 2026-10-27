package com.jek.sq5monitor.core

import com.jek.sq5monitor.core.link.QueueInputStream
import com.jek.sq5monitor.core.monitor.LiveSample
import com.jek.sq5monitor.core.monitor.SignalHistory
import com.jek.sq5monitor.core.obd.Dtc
import com.jek.sq5monitor.core.obd.DtcKind
import com.jek.sq5monitor.core.obd.EcuDtcs
import com.jek.sq5monitor.core.obd.MonitorStatus
import com.jek.sq5monitor.core.uds.DiagnosticReport
import com.jek.sq5monitor.core.uds.ModuleScanResult
import com.jek.sq5monitor.core.uds.UdsDtc
import com.jek.sq5monitor.core.uds.VagModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HelpersTest {

    @Test
    fun `signal history keeps min, max and a bounded history`() {
        val history = SignalHistory(capacity = 3)
        listOf(5.0, 1.0, 9.0, 4.0).forEachIndexed { i, v -> history.record(LiveSample(i.toLong(), mapOf("x" to v), null, 0.0)) }
        val stats = history.snapshot().getValue("x")
        assertEquals(1.0, stats.min, 0.0)
        assertEquals(9.0, stats.max, 0.0)
        assertEquals(4.0, stats.latest, 0.0)
        assertEquals(listOf(1.0, 9.0, 4.0), stats.history)
    }

    @Test
    fun `queue input stream reports availability and end of stream`() {
        val stream = QueueInputStream()
        assertEquals(0, stream.available())
        stream.push("OK>".toByteArray())
        assertEquals(3, stream.available())
        val buffer = ByteArray(8)
        assertEquals(3, stream.read(buffer, 0, buffer.size))
        assertEquals("OK>", String(buffer, 0, 3))
        stream.close()
        assertEquals(-1, stream.read())
    }

    @Test
    fun `diagnostic report summarises modules and obd codes`() {
        val report = DiagnosticReport.build(
            vin = "WA1C4AFY5J2000001",
            adapter = "Demo",
            modules = listOf(
                ModuleScanResult.Found(VagModules.ENGINE, mapOf("System name" to "J623"), listOf(UdsDtc(0x045600, 0x28))),
                ModuleScanResult.NotFound(VagModules.byAddress("13")!!),
            ),
            obdCodes = listOf(EcuDtcs(0x7E8, DtcKind.STORED, listOf(Dtc("P0456")))),
            monitorStatus = MonitorStatus.decode(intArrayOf(0x81, 0x07, 0x65, 0x04)),
            timestamp = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC),
        )
        assertTrue(report, report.contains("VIN: WA1C4AFY5J2000001"))
        assertTrue(report, report.contains("Check engine light: ON"))
        assertTrue(report, report.contains("Readiness: incomplete: Evaporative system"))
        assertTrue(report, report.contains("Engine [Stored] P0456 - Evaporative emission system leak detected (very small leak)"))
        assertTrue(report, report.contains("== Module scan: 1 modules found, 1 fault(s) =="))
        assertTrue(report, report.contains("01 Engine electronics: 1 fault(s)"))
        assertTrue(report, report.contains("    P0456 00 - Evaporative emission system leak detected (very small leak)"))
        assertTrue(report, report.contains("        Intermittent / stored, confirmed"))
        assertTrue(report, report.contains("13 Adaptive cruise control: not fitted / no response"))
    }
}
