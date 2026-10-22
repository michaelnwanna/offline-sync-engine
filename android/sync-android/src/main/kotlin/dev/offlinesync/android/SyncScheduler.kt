package dev.offlinesync.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

interface SyncWorkerHost {
    suspend fun openSyncSession(scopeId: String): SyncWorkerSession?
}

interface SyncWorkerSession : AutoCloseable {
    val queue: MutationQueue
    val runner: SyncRunner
}

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun requestSync(scopeId: String): Operation {
        validateScope(scopeId)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SCOPE_ID to scopeId))
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(scopeId))
            .build()
        return workManager.enqueueUniqueWork("${tag(scopeId)}:run", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun start(scopeId: String): Operation {
        validateScope(scopeId)
        val periodic = PeriodicWorkRequestBuilder<SyncWakeWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(SCOPE_ID to scopeId))
            .setConstraints(connected())
            .addTag(tag(scopeId))
            .build()
        return workManager.enqueueUniquePeriodicWork("${tag(scopeId)}:periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun cancel(scopeId: String): Operation {
        validateScope(scopeId)
        return workManager.cancelAllWorkByTag(tag(scopeId))
    }

    internal fun wakeAt(scopeId: String, atMs: Long, nowMs: Long = System.currentTimeMillis()): Operation {
        validateScope(scopeId)
        val delay = if (atMs > nowMs) atMs - nowMs else 0
        val request = OneTimeWorkRequestBuilder<SyncWakeWorker>()
            .setInputData(workDataOf(SCOPE_ID to scopeId))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(connected())
            .addTag(tag(scopeId))
            .build()
        return workManager.enqueueUniqueWork("${tag(scopeId)}:deadline", ExistingWorkPolicy.REPLACE, request)
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun tag(scopeId: String) = "offline-sync:$scopeId"

    private fun validateScope(scopeId: String) {
        require(scopeId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }
}

internal const val SCOPE_ID = "offlineSyncScope"

class SyncWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString(SCOPE_ID) ?: return Result.failure()
        SyncScheduler(applicationContext).requestSync(scope).await()
        return Result.success()
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString(SCOPE_ID) ?: return Result.failure()
        val host = applicationContext as? SyncWorkerHost ?: return Result.failure()
        val session = host.openSyncSession(scope) ?: return Result.success()
        session.use {
            if (session.queue.isBackgroundSyncPaused()) return Result.success()
            val result = session.runner.run()
            val scheduler = SyncScheduler(applicationContext)
            return when (result.outcome) {
                SyncRunOutcome.AUTHENTICATION_REQUIRED -> Result.success()
                SyncRunOutcome.ACTION_REQUIRED -> {
                    session.queue.pauseBackgroundSync()
                    Result.failure(workDataOf("errorCode" to result.errorCode))
                }
                SyncRunOutcome.RETRY, SyncRunOutcome.STALE_RESPONSE -> Result.retry()
                SyncRunOutcome.COMPLETE, SyncRunOutcome.MORE_WORK -> {
                    val deadline = if (result.outcome == SyncRunOutcome.MORE_WORK) System.currentTimeMillis()
                        else session.queue.nextWakeAtMs()
                    if (deadline != null) scheduler.wakeAt(scope, deadline).await()
                    Result.success()
                }
            }
        }
    }
}