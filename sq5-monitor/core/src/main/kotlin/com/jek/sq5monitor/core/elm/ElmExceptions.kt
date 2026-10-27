package com.jek.sq5monitor.core.elm

import java.io.IOException

/** Base class for adapter level failures. */
open class ElmException(message: String) : IOException(message)

/** The adapter did not answer with a `>` prompt in time. Usually means the link dropped. */
class ElmTimeoutException(command: String, timeoutMs: Long) :
    ElmException("Adapter did not respond to \"$command\" within $timeoutMs ms")

/** The adapter answered with an error such as `CAN ERROR` or `UNABLE TO CONNECT`. */
class ElmErrorException(val command: String, val error: String) :
    ElmException("Adapter error for \"$command\": $error")
