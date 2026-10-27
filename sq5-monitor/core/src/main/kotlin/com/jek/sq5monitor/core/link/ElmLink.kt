package com.jek.sq5monitor.core.link

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A raw byte pipe to an ELM327-compatible adapter (Bluetooth SPP, Wi-Fi TCP or the simulator).
 *
 * Implementations only move bytes; all protocol handling lives in [com.jek.sq5monitor.core.elm.Elm327].
 * [InputStream.available] must report buffered bytes accurately because the ELM reader polls it to
 * enforce timeouts without blocking forever on transports (such as Bluetooth sockets) that do not
 * support read timeouts.
 */
interface ElmLink : Closeable {
    /** Human readable description, e.g. "OBDII (00:1D:A5:68:98:8B)". */
    val description: String
    val input: InputStream
    val output: OutputStream
}

/** [ElmLink] over an arbitrary stream pair, closing [resource] when the link is closed. */
class StreamElmLink(
    override val description: String,
    override val input: InputStream,
    override val output: OutputStream,
    private val resource: Closeable,
) : ElmLink {
    override fun close() = resource.close()
}
