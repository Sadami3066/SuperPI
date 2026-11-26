package io.github.whywood.superpi.runtime

interface RuntimeLogSink {
    fun info(tag: String, message: String)

    fun warn(tag: String, message: String, error: Throwable? = null)

    fun error(tag: String, message: String, error: Throwable? = null)
}

expect object RuntimeLog {
    fun setSink(sink: RuntimeLogSink?)

    fun info(tag: String, message: String)

    fun warn(tag: String, message: String, error: Throwable? = null)

    fun error(tag: String, message: String, error: Throwable? = null)
}

fun logInfo(tag: String, message: String) {
    RuntimeLog.info(tag, message)
}

fun logWarn(tag: String, message: String, error: Throwable? = null) {
    RuntimeLog.warn(tag, message, error)
}

fun logError(tag: String, message: String, error: Throwable? = null) {
    RuntimeLog.error(tag, message, error)
}
