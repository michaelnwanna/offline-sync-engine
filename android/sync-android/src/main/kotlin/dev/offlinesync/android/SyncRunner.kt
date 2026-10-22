package dev.offlinesync.android

import dev.offlinesync.core.PushRequest
import java.io.IOException

enum class SyncRunOutcome { COMPLETE, MORE_WORK, RETRY, AUTHENTICATION_REQUIRED, ACTION_REQUIRED, STALE_RESPONSE }

data class SyncRunResult(
    val outcome: SyncRunOutcome,
    val acknowledged: Int = 0,
    val pullPages: Int = 0,
    val errorCode: String? = null,
)

class SyncRunner(
    private val queue: MutationQueue,
    private val responses: SyncResponseStore,
    private val transport: SyncTransport,
) {
    suspend fun run(maxPushes: Int = 100, maxPullPages: Int = 10): SyncRunResult {
        require(maxPushes in 1..1000 && maxPullPages in 1..100)
        var acknowledged = 0
        var pages = 0
        if (queue.isAuthenticationPaused()) return SyncRunResult(SyncRunOutcome.AUTHENTICATION_REQUIRED)
        var pushLimitReached = true
        for (attempt in 0 until maxPushes) {
            if (queue.isAuthenticationPaused())
                return SyncRunResult(SyncRunOutcome.AUTHENTICATION_REQUIRED, acknowledged, pages)
            val lease = queue.claim(limit = 1).singleOrNull()
            if (lease == null) {
                pushLimitReached = false
                break
            }
            val response = try {
                transport.push(PushRequest(1, lease.mutation.replicaId, listOf(lease.mutation)))
            } catch (failure: IOException) {
                val result = classify(failure, acknowledged, pages)
                if (failure is SyncAuthenticationException) {
                    queue.pauseForAuthentication()
                    queue.fail(lease, QueueFailure.AUTHENTICATION, "AUTHENTICATION_REQUIRED")
                } else queue.fail(lease, QueueFailure.TRANSIENT, requireNotNull(result.errorCode))
                return result
            }
            val ack = response.acknowledgments.singleOrNull()
            if (ack == null || ack.mutationId != lease.mutation.mutationId) {
                queue.fail(lease, QueueFailure.TRANSIENT, "INCOMPLETE_ACKNOWLEDGMENT")
                return SyncRunResult(SyncRunOutcome.RETRY, acknowledged, pages, "INCOMPLETE_ACKNOWLEDGMENT")
            }
            val applied = try {
                responses.acknowledge(lease, ack)
            } catch (_: IllegalArgumentException) {
                queue.fail(lease, QueueFailure.TRANSIENT, "INVALID_ACKNOWLEDGMENT")
                return SyncRunResult(SyncRunOutcome.ACTION_REQUIRED, acknowledged, pages, "INVALID_ACKNOWLEDGMENT")
            }
            if (!applied) return SyncRunResult(SyncRunOutcome.STALE_RESPONSE, acknowledged, pages)
            acknowledged++
        }
        for (pageIndex in 0 until maxPullPages) {
            if (queue.isAuthenticationPaused())
                return SyncRunResult(SyncRunOutcome.AUTHENTICATION_REQUIRED, acknowledged, pages)
            val cursor = responses.pullCursor()
            val page = try {
                transport.pull(cursor)
            } catch (failure: IOException) {
                if (failure is SyncAuthenticationException) queue.pauseForAuthentication()
                return classify(failure, acknowledged, pages)
            }
            val applied = try {
                responses.applyPull(cursor, page)
            } catch (_: IllegalArgumentException) {
                return SyncRunResult(SyncRunOutcome.ACTION_REQUIRED, acknowledged, pages, "INVALID_PULL_PAGE")
            }
            if (!applied) return SyncRunResult(SyncRunOutcome.STALE_RESPONSE, acknowledged, pages)
            pages++
            if (!page.hasMore) return SyncRunResult(
                if (pushLimitReached) SyncRunOutcome.MORE_WORK else SyncRunOutcome.COMPLETE, acknowledged, pages,
            )
        }
        return SyncRunResult(SyncRunOutcome.MORE_WORK, acknowledged, pages)
    }

    private fun classify(failure: IOException, acknowledged: Int, pages: Int): SyncRunResult = when (failure) {
        is SyncAuthenticationException -> SyncRunResult(SyncRunOutcome.AUTHENTICATION_REQUIRED,
            acknowledged, pages, "AUTHENTICATION_REQUIRED")
        is SyncProtocolException -> SyncRunResult(SyncRunOutcome.ACTION_REQUIRED, acknowledged, pages, "INVALID_SYNC_MESSAGE")
        is SyncHttpException -> SyncRunResult(if (failure.retryable) SyncRunOutcome.RETRY else SyncRunOutcome.ACTION_REQUIRED,
            acknowledged, pages, "HTTP_${failure.statusCode}")
        else -> SyncRunResult(SyncRunOutcome.RETRY, acknowledged, pages, "NETWORK_ERROR")
    }
}