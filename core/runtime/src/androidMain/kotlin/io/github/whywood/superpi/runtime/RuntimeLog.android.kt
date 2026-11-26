package io.github.whywood.superpi.runtime

import android.util.Log

actual object RuntimeLog {
    @Volatile
    private var customSink: RuntimeLogSink? = null

    actual fun setSink(sink: RuntimeLogSink?) {
        customSink = sink
    }

    actual fun info(tag: String, message: String) {
        customSink?.let { it.info(tag, message); return }
        runCatching { Log.i(tag, message) }
    }

    actual fun warn(tag: String, message: String, error: Throwable?) {
        customSink?.let { it.warn(tag, message, error); return }
        runCatching { Log.w(tag, message, error) }
    }

    actual fun error(tag: String, message: String, error: Throwable?) {
        customSink?.let { it.error(tag, message, error); return }
        runCatching { Log.e(tag, message, error) }
    }
}
