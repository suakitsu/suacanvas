package com.suakitsu.picpilot.core.media

import com.suakitsu.picpilot.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val picPilotBaseUrl: HttpUrl by lazy { BuildConfig.API_BASE_URL.toHttpUrl() }

fun resolveMediaUrl(value: String): String {
    val candidate = value.trim()
    if (candidate.isBlank()) return candidate
    if (candidate.toHttpUrlOrNull() != null || candidate.startsWith("content://") ||
        candidate.startsWith("file://") || candidate.startsWith("data:")
    ) {
        return candidate
    }
    return picPilotBaseUrl.resolve(candidate)?.toString()
        ?: picPilotBaseUrl.resolve(candidate.removePrefix("/"))?.toString()
        ?: candidate
}

/** Converts a displayed absolute PicPilot media URL back to the canonical API-owned path. */
fun mediaReferenceForApi(value: String): String {
    val candidate = value.trim()
    val url = candidate.toHttpUrlOrNull() ?: return candidate
    return if (isPicPilotOrigin(url) && url.encodedPath.startsWith("/api/public/files/")) {
        url.encodedPath
    } else {
        candidate
    }
}

fun resolveMediaModel(value: Any?): Any? = when (value) {
    is String -> resolveMediaUrl(value)
    else -> value
}

fun isPicPilotOrigin(url: HttpUrl): Boolean =
    url.scheme == picPilotBaseUrl.scheme &&
        url.host == picPilotBaseUrl.host &&
        url.port == picPilotBaseUrl.port
