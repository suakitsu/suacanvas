package com.suakitsu.picpilot.feature.video

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(uris: List<Uri>, maxAllowed: Int): VideoImageReadResult = withContext(Dispatchers.IO) {
        val unique = uris.distinctBy(Uri::toString)
        val errors = mutableListOf<String>()
        if (unique.size > maxAllowed) {
            errors += "当前模型最多支持 $maxAllowed 张参考图，已保留前 $maxAllowed 张"
        }
        val images = unique.take(maxAllowed).mapNotNull { uri ->
            runCatching { inspect(uri) }
                .onFailure { errors += (it.message ?: "图片读取失败，请重新选择") }
                .getOrNull()
        }
        VideoImageReadResult(images, errors.distinct())
    }

    private fun inspect(uri: Uri): VideoReferenceImage {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val metadata = resolver.queryMetadata(uri)
        val sizeBytes = metadata.sizeBytes.takeIf { it >= 0L } ?: resolver.measureSize(uri)
        val sniffedMime = resolver.sniffMime(uri)
        val mimeType = normalizeMime(
            provided = resolver.getType(uri),
            displayName = metadata.displayName,
            sniffed = sniffedMime,
        ) ?: throw IllegalArgumentException("${metadata.displayName}：不支持该文件格式")

        if (sizeBytes > MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("${metadata.displayName}：单张图片不能超过 30 MB")
        }

        val (width, height) = resolver.readDimensions(uri)
        if (mimeType in HEIF_MIMES && sniffedMime !in HEIF_MIMES) {
            throw IllegalArgumentException("${metadata.displayName}：HEIC/HEIF 图片损坏或无法解析")
        }
        if (mimeType !in HEIF_MIMES && (width <= 0 || height <= 0)) {
            throw IllegalArgumentException("${metadata.displayName}：图片损坏或无法解析")
        }
        if (width > MAX_PIXEL_EDGE || height > MAX_PIXEL_EDGE ||
            width.toLong() * height.toLong() > MAX_TOTAL_PIXELS
        ) {
            throw IllegalArgumentException("${metadata.displayName}：图片像素过大")
        }

        return VideoReferenceImage(
            localId = UUID.randomUUID().toString(),
            uri = uri,
            displayName = metadata.displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
        )
    }
}

private data class ContentMetadata(val displayName: String, val sizeBytes: Long)

private fun ContentResolver.queryMetadata(uri: Uri): ContentMetadata {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "参考图"
    var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                name = cursor.getString(index)?.takeIf(String::isNotBlank) ?: name
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                if (!cursor.isNull(index)) size = cursor.getLong(index)
            }
        }
    }
    return ContentMetadata(name, size)
}

private fun ContentResolver.sniffMime(uri: Uri): String? = openInputStream(uri)?.use { stream ->
    val header = ByteArray(16)
    val count = stream.read(header)
    when {
        count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
        count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "image/png"
        count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT)
            when {
                brand.startsWith("hei") || brand.startsWith("hev") -> "image/heic"
                brand.startsWith("mif") || brand.startsWith("msf") -> "image/heif"
                else -> null
            }
        }
        else -> null
    }
}

private fun normalizeMime(provided: String?, displayName: String, sniffed: String?): String? {
    if (sniffed in SUPPORTED_IMAGE_MIMES) return sniffed
    val normalized = provided?.lowercase(Locale.ROOT)?.substringBefore(';')
    if (normalized in SUPPORTED_IMAGE_MIMES) return normalized
    return when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> null
    }
}

private fun ContentResolver.readDimensions(uri: Uri): Pair<Int, Int> = openInputStream(uri)?.use { stream ->
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(stream, null, options)
    options.outWidth to options.outHeight
} ?: (0 to 0)

private fun ContentResolver.measureSize(uri: Uri): Long = openInputStream(uri)?.use { stream ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_IMAGE_BYTES) return@use total
    }
    total
} ?: throw IllegalArgumentException("图片读取失败，请重新选择")

private val PNG_MAGIC = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)
private val HEIF_MIMES = setOf("image/heic", "image/heif")
private val SUPPORTED_IMAGE_MIMES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
)
private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
private const val MAX_PIXEL_EDGE = 16_384
private const val MAX_TOTAL_PIXELS = 80_000_000L
