package com.jek.sq5monitor.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.jek.sq5monitor.core.link.ElmLink
import com.jek.sq5monitor.core.link.QueueInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy ELM327 adapters (Vgate iCar Pro BLE, Veepeak BLE, vLinker, OBDLink CX, ...).
 *
 * They tunnel the serial stream over a vendor "UART" GATT service: one characteristic to write
 * commands to and one that notifies replies. Vendors use different UUIDs, so the pair is detected.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(
    override val description: String,
    private val gatt: BluetoothGatt,
    private val writeCharacteristic: BluetoothGattCharacteristic,
    private val callback: Callback,
) : ElmLink {

    override val input: QueueInputStream = callback.input

    private val writeType = if (writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    } else {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }

    override val output: OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) = pending.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)

        override fun flush() {
            val data = pending.toByteArray()
            pending.reset()
            var offset = 0
            while (offset < data.size) {
                if (!callback.connected) throw IOException("Bluetooth LE adapter disconnected")
                val chunk = data.copyOfRange(offset, minOf(data.size, offset + CHUNK_SIZE))
                callback.writeResults.clear()
                if (!writeChunk(chunk)) throw IOException("Bluetooth LE write failed")
                val status = callback.writeResults.poll(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (status == null && writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    throw IOException("Bluetooth LE write timed out")
                }
                if (status != null && status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth LE write error $status")
                offset += chunk.size
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(chunk: ByteArray): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(writeCharacteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        writeCharacteristic.writeType = writeType
        writeCharacteristic.value = chunk
        gatt.writeCharacteristic(writeCharacteristic)
    }

    override fun close() {
        input.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    internal class Callback : BluetoothGattCallback() {
        val input = QueueInputStream()
        val connectedSignal = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val descriptorWritten = CompletableDeferred<Int>()
        val writeResults = LinkedBlockingQueue<Int>()

        @Volatile
        var connected = false

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectedSignal.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connected = false
                connectedSignal.completeExceptionally(IOException("Bluetooth LE connection failed (status $status)"))
                input.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDiscovered.complete(Unit)
            } else {
                servicesDiscovered.completeExceptionally(IOException("Service discovery failed (status $status)"))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeResults.offer(status)
        }

        // Android 13+ delivers the value directly; the deprecated overload is only called on older versions.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            input.push(value)
        }

        @Deprecated("Used on Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { input.push(it) }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 20 // default ATT MTU (23) minus 3 bytes of header
        private const val WRITE_TIMEOUT_MS = 1_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Generic Access/Attribute, Device Information, Battery: never the data channel. */
        private val STANDARD_SERVICES = setOf("1800", "1801", "180a", "180f")

        /** Common adapter services, tried first. */
        private val PREFERRED_SERVICES = listOf("fff0", "ffe0", "18f0")

        private fun shortId(uuid: UUID): String = uuid.toString().substring(4, 8).lowercase()

        suspend fun connect(context: Context, device: BluetoothDevice, name: String): ElmLink {
            val callback = Callback()
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Could not start a Bluetooth LE connection to $name")
            try {
                withTimeout(15_000) { callback.connectedSignal.await() }
                if (!gatt.discoverServices()) throw IOException("Could not read services from $name")
                withTimeout(10_000) { callback.servicesDiscovered.await() }
                val (notify, write) = findUart(gatt.services)
                    ?: throw IOException("$name does not look like an OBD adapter (no serial service found)")
                enableNotifications(gatt, notify, callback)
                return BleLink("$name (Bluetooth LE)", gatt, write, callback)
            } catch (e: TimeoutCancellationException) {
                gatt.disconnect(); gatt.close()
                throw IOException("Timed out connecting to $name. Is the ignition on and the adapter in range?")
            } catch (e: CancellationException) {
                gatt.disconnect(); gatt.close()
                throw e
            } catch (e: Exception) {
                gatt.disconnect(); gatt.close()
                throw if (e is IOException) e else IOException(e.message ?: "Bluetooth LE error", e)
            }
        }

        @Suppress("DEPRECATION")
        private suspend fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, callback: Callback) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD) ?: return
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
            if (!started) throw IOException("Could not enable adapter notifications")
            val status = withTimeout(5_000) { callback.descriptorWritten.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Adapter rejected notifications (status $status)")
        }

        /** Finds the (notify, write) characteristic pair of the adapter's serial service. */
        private fun findUart(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val candidates = services.filterNot { shortId(it.uuid) in STANDARD_SERVICES }
                .sortedBy { PREFERRED_SERVICES.indexOf(shortId(it.uuid)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            for (service in candidates) {
                val chars = service.characteristics
                val notify = chars.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                } ?: continue
                val writable = chars.filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                // Prefer a separate write characteristic; some adapters use one characteristic for both.
                val write = writable.firstOrNull { it.uuid != notify.uuid } ?: writable.firstOrNull() ?: continue
                return notify to write
            }
            return null
        }
    }
}
