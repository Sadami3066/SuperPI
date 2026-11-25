package io.github.whywood.superpi.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.whywood.superpi.SuperPIApplication
import io.github.whywood.superpi.runtime.memory.ConsolidationOutcome
import kotlinx.coroutines.CancellationException

class MemoryDreamWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SuperPIApplication ?: return Result.success()
        return try {
            app.graph.credentials.preloadAll()
            when (app.graph.memoryAgent.runConsolidationFromWorker()) {
                is ConsolidationOutcome.Done,
                ConsolidationOutcome.Busy,
                ConsolidationOutcome.Paused,
                ConsolidationOutcome.NoModel,
                is ConsolidationOutcome.Skipped,
                -> Result.success()

                is ConsolidationOutcome.Failed -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Diagnostics.log(applicationContext, "memory: background dream worker failed", error)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private companion object {
        private const val MAX_RETRIES = 2
    }
}
