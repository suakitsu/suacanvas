package com.suakitsu.picpilot.feature.video

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoPendingSubmissionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(fingerprint: String): String {
        val storedFingerprint = preferences.getString(KEY_FINGERPRINT, "")
        val storedKey = preferences.getString(KEY_IDEMPOTENCY, "")
        if (storedFingerprint == fingerprint && !storedKey.isNullOrBlank()) return storedKey

        val newKey = "android-video:${UUID.randomUUID()}"
        preferences.edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putString(KEY_IDEMPOTENCY, newKey)
            .apply()
        return newKey
    }

    @Synchronized
    fun hasPending(fingerprint: String): Boolean =
        preferences.getString(KEY_FINGERPRINT, "") == fingerprint &&
            !preferences.getString(KEY_IDEMPOTENCY, "").isNullOrBlank()

    @Synchronized
    fun clearIfMatches(idempotencyKey: String) {
        if (preferences.getString(KEY_IDEMPOTENCY, "") == idempotencyKey) {
            preferences.edit().remove(KEY_FINGERPRINT).remove(KEY_IDEMPOTENCY).apply()
        }
    }

    companion object {
        private const val PREFERENCES = "picpilot_video_pending_v1"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_IDEMPOTENCY = "idempotency_key"
    }
}

internal fun videoRequestFingerprint(request: VideoCreateRequest): String {
    val stable = buildString {
        append(request.title.trim()).append('\n')
        append(request.prompt.trim()).append('\n')
        append(request.originalPrompt.trim()).append('\n')
        append(request.modelCode).append('|')
        append(request.resolution).append('|')
        append(request.durationSeconds).append('|')
        append(request.aspectRatio).append('|')
        append(request.generateAudio).append('|')
        append(request.seed ?: "").append('\n')
        request.references.forEach { image ->
            append(image.uri).append('|')
            append(image.displayName).append('|')
            append(image.sizeBytes).append('|')
            append(image.mimeType).append('|')
            append(image.width).append('x').append(image.height).append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(stable.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
