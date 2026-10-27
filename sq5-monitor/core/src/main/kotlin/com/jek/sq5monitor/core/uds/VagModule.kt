package com.jek.sq5monitor.core.uds

/**
 * A control module reachable through the diagnostic gateway.
 *
 * @property address the two-digit VAG address shown in VCDS/ODIS/OBDeleven (e.g. "01" = engine).
 * @property requestId CAN id the tester sends to.
 * @property responseId CAN id the module answers on.
 */
data class VagModule(
    val address: String,
    val name: String,
    val requestId: Int,
    val responseId: Int,
) {
    val label: String get() = "$address $name"
}

/**
 * UDS-on-CAN diagnostic addresses used by VAG MLBevo/MQB vehicles, which includes the 2018 SQ5 (FY).
 *
 * Except for the powertrain (0x7E0/0x7E8, 0x7E1/0x7E9) VAG answers on request id + 0x6A.
 * Equipment varies by car, so modules that are not fitted simply do not answer during a scan.
 */
object VagModules {
    private fun module(address: String, name: String, requestId: Int, responseId: Int = requestId + 0x6A) =
        VagModule(address, name, requestId, responseId)

    val ENGINE = module("01", "Engine electronics", 0x7E0, 0x7E8)
    val TRANSMISSION = module("02", "Transmission electronics", 0x7E1, 0x7E9)

    val all: List<VagModule> = listOf(
        ENGINE,
        TRANSMISSION,
        module("03", "Brake electronics", 0x713),
        module("08", "Air conditioning", 0x746),
        module("09", "Central electrics", 0x70E),
        module("13", "Adaptive cruise control", 0x757),
        module("15", "Airbag", 0x715),
        module("16", "Steering column electronics", 0x70C),
        module("17", "Instrument cluster", 0x714),
        module("19", "Data bus diagnostic interface (gateway)", 0x710),
        module("42", "Door electronics, driver side", 0x74A),
        module("44", "Steering assistance", 0x712),
        module("52", "Door electronics, passenger side", 0x74B),
        module("5F", "Information electronics (MMI)", 0x773),
        module("76", "Park assist", 0x70A),
    )

    fun byAddress(address: String): VagModule? = all.firstOrNull { it.address.equals(address, ignoreCase = true) }
}
