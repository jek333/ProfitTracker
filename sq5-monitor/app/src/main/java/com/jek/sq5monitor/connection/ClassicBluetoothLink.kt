package com.jek.sq5monitor.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.jek.sq5monitor.core.link.ElmLink
import com.jek.sq5monitor.core.link.StreamElmLink
import java.io.IOException
import java.util.UUID

/** Bluetooth Classic adapters expose the ELM327 as a serial port (SPP / RFCOMM). */
object ClassicBluetoothLink {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Blocking; call from an IO dispatcher. Tries a secure socket first, then insecure (some clones need it). */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, name: String): ElmLink {
        val factories: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var lastError: IOException? = null
        for (create in factories) {
            val socket = try {
                create()
            } catch (e: IOException) {
                lastError = e
                continue
            }
            try {
                socket.connect()
                return StreamElmLink("$name (Bluetooth)", socket.inputStream, socket.outputStream, socket)
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw IOException(
            "Could not open a connection to $name. Check that the adapter is plugged in, the ignition is on " +
                "and no other app is connected to it.",
            lastError,
        )
    }
}
