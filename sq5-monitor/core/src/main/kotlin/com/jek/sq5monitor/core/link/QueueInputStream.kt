package com.jek.sq5monitor.core.link

import java.io.InputStream

/**
 * An [InputStream] fed by a producer (BLE notifications, the simulator) instead of a socket.
 * [available] is exact, as required by [com.jek.sq5monitor.core.elm.Elm327].
 */
class QueueInputStream : InputStream() {
    private val lock = Object()
    private val bytes = ArrayDeque<Byte>()
    private var closed = false

    fun push(data: ByteArray) = synchronized(lock) {
        data.forEach { bytes.addLast(it) }
        lock.notifyAll()
    }

    override fun available(): Int = synchronized(lock) { bytes.size }

    override fun read(): Int = synchronized(lock) {
        while (bytes.isEmpty()) {
            if (closed) return -1
            lock.wait()
        }
        bytes.removeFirst().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        if (len == 0) return 0
        while (bytes.isEmpty()) {
            if (closed) return -1
            lock.wait()
        }
        var n = 0
        while (n < len && bytes.isNotEmpty()) b[off + n++] = bytes.removeFirst()
        n
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
