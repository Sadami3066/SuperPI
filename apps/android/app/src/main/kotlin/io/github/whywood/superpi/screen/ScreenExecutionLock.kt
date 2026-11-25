package io.github.whywood.superpi.screen

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ScreenExecutionLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
