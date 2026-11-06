package com.suakitsu.picpilot.core.network

import com.suakitsu.picpilot.core.model.ApiEnvelope
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val message: String, val code: String = "REQUEST_FAILED") : ApiResult<Nothing>
}

suspend fun <T> apiCall(block: suspend () -> ApiEnvelope<T>): ApiResult<T> = try {
    val response = block()
    val data = response.data
    if (response.success && data != null) ApiResult.Success(data)
    else ApiResult.Failure(response.message.ifBlank { "服务暂时不可用，请稍后重试" }, response.code)
} catch (_: SocketTimeoutException) {
    ApiResult.Failure("请求超时，请检查网络后重试", "NETWORK_TIMEOUT")
} catch (_: UnknownHostException) {
    ApiResult.Failure("当前网络不可用，请检查网络连接", "NETWORK_UNAVAILABLE")
} catch (_: ConnectException) {
    ApiResult.Failure("无法连接 PicPilot 服务，请稍后重试", "CONNECTION_FAILED")
} catch (_: SSLException) {
    ApiResult.Failure("安全连接失败，请检查系统时间和网络", "TLS_ERROR")
} catch (_: IOException) {
    ApiResult.Failure("网络连接中断，请稍后重试", "NETWORK_ERROR")
} catch (error: HttpException) {
    error.toApiFailure()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    ApiResult.Failure("客户端未能处理本次响应，请刷新后重试", "CLIENT_RESPONSE_ERROR")
}

internal fun HttpException.toApiFailure(): ApiResult.Failure {
    val payload = runCatching { response()?.errorBody()?.string() }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let { body -> runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() }
    val serverMessage = payload?.get("message")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val serverCode = payload?.get("code")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val status = code()
    return ApiResult.Failure(
        message = serverMessage.takeIf(::isSafeServerMessage) ?: defaultHttpMessage(status),
        code = serverCode.ifBlank { "HTTP_$status" },
    )
}

private fun defaultHttpMessage(status: Int): String = when (status) {
    400, 422 -> "请求内容有误，请检查后重试"
    401 -> "登录状态已失效，请重新登录"
    403 -> "当前操作没有权限"
    404 -> "请求的内容不存在或已被删除"
    408 -> "请求超时，请稍后重试"
    409 -> "当前状态已变化，请刷新后重试"
    413 -> "文件过大，请压缩后重试"
    429 -> "操作过于频繁，请稍后再试"
    502, 503, 504 -> "服务连接暂时繁忙（HTTP $status），请稍后重试"
    in 500..599 -> "服务器处理失败（HTTP $status），请稍后重试"
    else -> "请求失败（HTTP $status），请稍后重试"
}

private fun isSafeServerMessage(message: String): Boolean =
    message.isNotBlank() &&
        message.length <= 160 &&
        !message.contains('\n') &&
        !message.contains("exception", ignoreCase = true) &&
        !message.contains("stack", ignoreCase = true) &&
        !message.contains('{') &&
        !message.contains('<')
