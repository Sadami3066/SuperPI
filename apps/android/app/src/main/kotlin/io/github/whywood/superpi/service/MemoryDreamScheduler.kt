package io.github.whywood.superpi.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class MemoryDreamScheduler(private val context: Context) {
    fun ensureScheduled() {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<MemoryDreamWorker>(REPEAT_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { error ->
            Diagnostics.log(context, "memory: schedule dream worker failed", error)
        }
    }

    private companion object {
        private const val UNIQUE_WORK_NAME = "superpi_memory_dream"
        private const val REPEAT_HOURS = 24L
    }
}
