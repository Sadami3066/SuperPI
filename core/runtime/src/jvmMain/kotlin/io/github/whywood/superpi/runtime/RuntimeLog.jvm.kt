package io.github.whywood.superpi.runtime

import java.time.Instant

actual object RuntimeLog {
    @Volatile
    private var customSink: RuntimeLogSink? = null

    actual fun setSink(sink: RuntimeLogSink?) {
        customSink = sink
    }

    actual fun info(tag: String, message: String) {
        customSink?.let { it.info(tag, message); return }
        if (System.getProperty("superpi.verboseLog") == "true") {
            System.err.println("${Instant.now()} I/$tag: $message")
        }
    }

    actual fun warn(tag: String, message: String, error: Throwable?) {
        customSink?.let { it.warn(tag, message, error); return }
        if (System.getProperty("superpi.verboseLog") == "true") {
            System.err.println("${Instant.now()} W/$tag: $message")
            error?.printStackTrace(System.err)
        }
    }

    actual fun error(tag: String, message: String, error: Throwable?) {
        customSink?.let { it.error(tag, message, error); return }
        System.err.println("${Instant.now()} E/$tag: $message")
        error?.printStackTrace(System.err)
    }
}
