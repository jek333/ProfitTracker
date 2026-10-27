package com.jek.sq5monitor.core.uds

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Outcome of scanning one module. */
sealed interface ModuleScanResult {
    val module: VagModule

    data class Found(
        override val module: VagModule,
        /** Identification fields keyed by label (see [UdsDids.identification]). */
        val identification: Map<String, String>,
        val dtcs: List<UdsDtc>,
        /** Set when the module answered but refused the DTC request. */
        val dtcError: String? = null,
    ) : ModuleScanResult

    data class NotFound(override val module: VagModule) : ModuleScanResult

    data class Failed(override val module: VagModule, val error: String) : ModuleScanResult
}

/** Progress of an "auto-scan" like VCDS/OBDeleven: one result per module as it completes. */
data class ScanProgress(val done: Int, val total: Int, val result: ModuleScanResult)

class ModuleScanner(private val uds: UdsClient) {

    fun scan(modules: List<VagModule> = VagModules.all): Flow<ScanProgress> = flow {
        modules.forEachIndexed { index, module ->
            emit(ScanProgress(index + 1, modules.size, scanModule(module)))
        }
    }

    suspend fun scanModule(module: VagModule): ModuleScanResult = try {
        uds.withModule(module) {
            // The system name doubles as the presence check; an absent module throws here.
            val identification = linkedMapOf<String, String>()
            for ((did, label) in UdsDids.identification) {
                readDidText(did)?.let { identification[label] = it }
            }
            try {
                ModuleScanResult.Found(module, identification, readDtcs())
            } catch (e: UdsNegativeResponseException) {
                ModuleScanResult.Found(module, identification, emptyList(), e.message)
            }
        }
    } catch (e: UdsNoResponseException) {
        ModuleScanResult.NotFound(module)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ModuleScanResult.Failed(module, e.message ?: e.javaClass.simpleName)
    }
}
