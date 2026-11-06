package com.suakitsu.picpilot.integration.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

data class UploadDescriptor(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val declaredSize: Long = -1L,
    val order: Int,
)

interface UploadContentSource {
    fun open(uri: String): InputStream
    fun contentLength(uri: String): Long?
    fun mimeType(uri: String): String?
    fun displayName(uri: String): String?
}

class ContentResolverUploadSource(
    private val resolver: ContentResolver,
) : UploadContentSource {
    override fun open(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri))
        ?: throw FileNotFoundException("Selected image is no longer readable")

    override fun contentLength(uri: String): Long? {
        val parsed = Uri.parse(uri)
        resolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
            if (descriptor.length >= 0L) return descriptor.length
        }
        return query(parsed, OpenableColumns.SIZE)?.toLongOrNull()
    }

    override fun mimeType(uri: String): String? = resolver.getType(Uri.parse(uri))

    override fun displayName(uri: String): String? = query(Uri.parse(uri), OpenableColumns.DISPLAY_NAME)

    private fun query(uri: Uri, column: String): String? = resolver.query(
        uri,
        arrayOf(column),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getColumnIndex(column)
            .takeIf { it >= 0 }
            ?.let(cursor::getString)
    }
}

class OrderedMultipartFactory(
    private val source: UploadContentSource,
) {
    fun text(name: String, value: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, value)

    fun repeatedText(name: String, values: List<String>): List<MultipartBody.Part> =
        values.map { text(name, it) }

    fun imageParts(
        fieldName: String,
        uploads: List<UploadDescriptor>,
        maxFiles: Int = 10,
        maxTotalBytes: Long = 30L * 1024L * 1024L,
    ): List<MultipartBody.Part> {
        require(uploads.isNotEmpty()) { "Please select at least one image" }
        require(uploads.size <= maxFiles) { "A maximum of $maxFiles images is supported" }

        val ordered = uploads.sortedBy(UploadDescriptor::order)
        val resolved = ordered.map { descriptor ->
            val size = source.contentLength(descriptor.uri)?.takeIf { it >= 0L }
                ?: descriptor.declaredSize.takeIf { it >= 0L }
                ?: measureSize(descriptor.uri, maxTotalBytes)
            require(size != 0L) { "The selected image is empty" }
            require(size <= maxTotalBytes) {
                "The selected images exceed the ${(maxTotalBytes / 1024L / 1024L)} MB upload limit"
            }
            ResolvedUpload(
                descriptor = descriptor,
                size = size,
                filename = safeFilename(
                    descriptor.displayName.ifBlank { source.displayName(descriptor.uri).orEmpty() },
                    descriptor.uri,
                ),
                mimeType = safeImageMime(
                    descriptor.mimeType.ifBlank { source.mimeType(descriptor.uri).orEmpty() },
                    descriptor.displayName,
                    descriptor.uri,
                ),
            )
        }
        var totalBytes = 0L
        resolved.forEach { upload ->
            require(totalBytes <= maxTotalBytes - upload.size) {
                "The selected images exceed the ${(maxTotalBytes / 1024L / 1024L)} MB upload limit"
            }
            totalBytes += upload.size
        }
        require(totalBytes <= maxTotalBytes) {
            "The selected images exceed the ${(maxTotalBytes / 1024L / 1024L)} MB upload limit"
        }
        return resolved.map { upload ->
            MultipartBody.Part.createFormData(
                fieldName,
                upload.filename,
                StreamingContentRequestBody(source, upload.descriptor.uri, upload.mimeType, upload.size),
            )
        }
    }

    private data class ResolvedUpload(
        val descriptor: UploadDescriptor,
        val size: Long,
        val filename: String,
        val mimeType: MediaType,
    )

    private fun measureSize(uri: String, maxBytes: Long): Long {
        var total = 0L
        source.open(uri).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (total <= maxBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
        }
        return total
    }
}

private class StreamingContentRequestBody(
    private val source: UploadContentSource,
    private val uri: String,
    private val mediaType: MediaType,
    private val length: Long,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        source.open(uri).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        }
    }
}

private fun safeFilename(name: String, uri: String): String {
    val fallback = uri.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
    return name.ifBlank { fallback }
        .replace(Regex("[\\r\\n\\\"\\\\]"), "_")
        .take(120)
        .ifBlank { "image" }
}

private fun safeImageMime(candidate: String, name: String, uri: String): MediaType {
    val normalized = candidate.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = (name.ifBlank { uri }).substringBefore('?').substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
    val inferred = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "application/octet-stream"
    }
    val allowed = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif",
    )
    val resolved = when {
        normalized in allowed -> normalized
        normalized.isBlank() || normalized == "application/octet-stream" || normalized == "image/*" -> inferred
        else -> inferred
    }
    return resolved.toMediaType()
}
