package io.github.whywood.superpi.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class MemoryDreamScheduler(private val context: Context) {
    fun syncSchedule(intervalHours: Int, replace: Boolean = false) {
        runCatching {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (intervalHours <= 0) {
                manager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<MemoryDreamWorker>(
                intervalHours.toLong().coerceAtLeast(1L),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { error ->
            Diagnostics.log(context, "memory: schedule dream worker failed", error)
        }
    }

    private companion object {
        private const val UNIQUE_WORK_NAME = "superpi_memory_dream"
    }
}
