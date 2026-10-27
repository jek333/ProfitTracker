package com.jek.sq5monitor.core.link

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Connects to Wi-Fi ELM327 adapters, which almost universally listen on 192.168.0.10:35000. */
object TcpElmLink {
    const val DEFAULT_HOST = "192.168.0.10"
    const val DEFAULT_PORT = 35000

    /** Blocking; call from an IO dispatcher. */
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int = 5_000): ElmLink {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return StreamElmLink("Wi-Fi $host:$port", socket.getInputStream(), socket.getOutputStream(), socket)
    }
}
