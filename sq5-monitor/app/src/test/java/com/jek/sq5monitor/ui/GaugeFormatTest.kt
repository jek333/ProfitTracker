package com.jek.sq5monitor.ui

import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaugeFormatTest {

    @Test
    fun `boost gauge converts to psi and positions the needle in canonical range`() {
        val boost = Signals[Signals.BOOST]!!
        val gauge = boost.gauge(103.42, UnitSystem.IMPERIAL, alert = false)
        assertEquals("15.0", gauge.valueText)
        assertEquals("psi", gauge.unit)
        assertEquals((103.42 - boost.min) / (boost.max - boost.min), gauge.fraction.toDouble(), 1e-4)
        assertEquals("-15", gauge.minText)
        assertEquals("22", gauge.maxText)
    }

    @Test
    fun `waiting gauge has no value`() {
        val gauge = Signals[Signals.pidId(Pids.RPM)]!!.gauge(null, UnitSystem.METRIC, alert = false)
        assertNull(gauge.valueText)
        assertEquals(0f, gauge.fraction)
        assertEquals("rpm", gauge.unit)
    }

    @Test
    fun `fuel economy in mpg uses its own scale where more is better`() {
        val economy = Signals[Signals.FUEL_ECONOMY]!!
        val gauge = economy.gauge(11.76, UnitSystem.IMPERIAL, alert = false) // ~20 mpg
        assertEquals("20.0", gauge.valueText)
        assertEquals("mpg", gauge.unit)
        assertEquals(0.5f, gauge.fraction, 0.01f)
        assertEquals("40", gauge.maxText)
    }

    @Test
    fun `temperature labels follow the unit system`() {
        val coolant = Signals[Signals.pidId(Pids.COOLANT_TEMP)]!!
        val gauge = coolant.gauge(90.0, UnitSystem.IMPERIAL, alert = true)
        assertEquals("194", gauge.valueText)
        assertEquals("°F", gauge.unit)
        assertEquals("302", gauge.maxText)
        assertEquals(true, gauge.alert)
    }
}
