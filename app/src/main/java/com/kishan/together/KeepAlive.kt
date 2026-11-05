package com.kishan.together

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Restarts the status service if the phone killed it. Runs every 15 minutes (the shortest Android allows). */
class KeepAlive(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (Repo.configured) runCatching { PresenceService.start(applicationContext) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "keepalive", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<KeepAlive>(15, TimeUnit.MINUTES).build(),
        )

        fun kick(ctx: Context) = WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<KeepAlive>().setInitialDelay(2, TimeUnit.SECONDS).build()
        )
    }
}
