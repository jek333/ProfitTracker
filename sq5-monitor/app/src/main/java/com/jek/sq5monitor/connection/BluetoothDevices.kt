package com.jek.sq5monitor.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Bluetooth device the user might pick as their OBD adapter. */
data class AdapterDevice(
    val address: String,
    val name: String,
    val ble: Boolean,
    val bonded: Boolean,
) {
    /** Names used by common ELM327-compatible adapters, so they can be listed first. */
    val likelyObd: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    companion object {
        private val OBD_NAME_HINTS = listOf(
            "OBD", "ELM", "V-LINK", "VLINK", "vLinker", "Vgate", "iCar", "Veepeak", "OBDLink", "KONNWEI", "Carista",
            "LELink", "Android-Vlink", "IOS-Vlink",
        )
    }
}

/** Thin wrapper over the Android Bluetooth APIs used by the connect screen. */
class BluetoothDevices(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Runtime permissions needed to list and connect to adapters (and scan for BLE ones). */
    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Listing paired devices and connecting only needs CONNECT; scanning is optional. */
    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<AdapterDevice> {
        if (!hasConnectPermission()) return emptyList()
        val devices = try {
            adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            emptySet()
        }
        return devices.map { it.toAdapterDevice(bonded = true) }
            .sortedWith(compareByDescending<AdapterDevice> { it.likelyObd }.thenBy { it.name.lowercase() })
    }

    /** Scans for BLE adapters (which usually are not paired). Cancelling the collection stops the scan. */
    @SuppressLint("MissingPermission")
    fun scanLe(): Flow<AdapterDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasScanPermission()) {
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: safeName(device) ?: return
                trySend(AdapterDevice(device.address, name, ble = true, bonded = false))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed (error $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Bluetooth turned off or permission revoked mid-scan; nothing left to stop.
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun remoteDevice(address: String): BluetoothDevice =
        adapter?.getRemoteDevice(address) ?: throw IllegalStateException("Bluetooth is not available on this phone")

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toAdapterDevice(bonded: Boolean) = AdapterDevice(
        address = address,
        name = safeName(this) ?: address,
        ble = type == BluetoothDevice.DEVICE_TYPE_LE,
        bonded = bonded,
    )
}
