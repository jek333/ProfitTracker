package com.jek.sq5monitor.core.uds

import com.jek.sq5monitor.core.obd.EcuDtcs
import com.jek.sq5monitor.core.obd.MonitorStatus
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Plain-text report in the spirit of a VCDS auto-scan log, for sharing with a mechanic or forum. */
object DiagnosticReport {

    fun build(
        vin: String?,
        adapter: String?,
        modules: List<ModuleScanResult>,
        obdCodes: List<EcuDtcs> = emptyList(),
        monitorStatus: MonitorStatus? = null,
        timestamp: ZonedDateTime = ZonedDateTime.now(),
    ): String = buildString {
        appendLine("SQ5 Monitor diagnostic report")
        appendLine(timestamp.format(DateTimeFormatter.RFC_1123_DATE_TIME))
        vin?.let { appendLine("VIN: $it") }
        adapter?.let { appendLine("Adapter: $it") }
        appendLine()

        if (monitorStatus != null || obdCodes.isNotEmpty()) {
            appendLine("== OBD-II ==")
            monitorStatus?.let { status ->
                appendLine("Check engine light: ${if (status.milOn) "ON" else "off"}")
                val incomplete = status.incomplete.map { it.name }
                appendLine("Readiness: " + if (incomplete.isEmpty()) "all monitors complete" else "incomplete: ${incomplete.joinToString()}")
            }
            obdCodes.forEach { group ->
                group.codes.forEach { appendLine("${group.ecuName} [${group.kind.label}] ${it.code} - ${it.description}") }
            }
            appendLine()
        }

        if (modules.isNotEmpty()) {
            val found = modules.filterIsInstance<ModuleScanResult.Found>()
            val faults = found.sumOf { it.dtcs.size }
            appendLine("== Module scan: ${found.size} modules found, $faults fault(s) ==")
            modules.forEach { result ->
                val module = result.module
                when (result) {
                    is ModuleScanResult.NotFound -> appendLine("${module.label}: not fitted / no response")
                    is ModuleScanResult.Failed -> appendLine("${module.label}: error - ${result.error}")
                    is ModuleScanResult.Found -> {
                        val status = when {
                            result.dtcError != null -> "faults unreadable (${result.dtcError})"
                            result.dtcs.isEmpty() -> "OK"
                            else -> "${result.dtcs.size} fault(s)"
                        }
                        appendLine("${module.label}: $status")
                        result.identification.forEach { (label, value) -> appendLine("    $label: $value") }
                        result.dtcs.forEach { dtc ->
                            appendLine("    ${dtc.display} - ${dtc.description}")
                            appendLine("        ${dtc.statusText}")
                        }
                    }
                }
            }
        }
    }.trimEnd() + "\n"
}
