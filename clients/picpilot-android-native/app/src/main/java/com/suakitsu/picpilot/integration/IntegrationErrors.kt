package com.suakitsu.picpilot.integration

import com.suakitsu.picpilot.core.network.ApiResult
import kotlinx.coroutines.CancellationException

class IntegrationApiException(
    message: String,
    val code: String,
) : IllegalStateException(message)

class ServerQuoteChangedException(
    val authoritativeCredits: Int,
) : IllegalStateException("服务端价格已更新为 $authoritativeCredits 积分，请重新确认后提交")

class SubmissionUnknownException : IllegalStateException(
    "提交结果暂时无法确认，请先到历史记录核对，避免重复扣费",
)

internal fun <T> ApiResult<T>.valueOrThrow(): T = when (this) {
    is ApiResult.Success -> value
    is ApiResult.Failure -> throw IntegrationApiException(message, code)
}

internal fun ApiResult.Failure.isAmbiguousSubmission(): Boolean =
    code == "NETWORK_ERROR" || code == "HTTP_502" || code == "HTTP_503" || code == "HTTP_504"

internal suspend inline fun <T> integrationResult(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
