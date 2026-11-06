package com.suakitsu.picpilot.feature.commerce

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

class CommerceImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(uris: List<Uri>): ImageReadResult = withContext(Dispatchers.IO) {
        val uniqueUris = uris.distinctBy(Uri::toString).take(MAX_REFERENCE_IMAGES)
        val images = mutableListOf<CommerceReferenceImage>()
        val errors = mutableListOf<String>()

        uniqueUris.forEach { uri ->
            runCatching { inspect(uri) }
                .onSuccess(images::add)
                .onFailure { error ->
                    errors += error.message ?: "图片读取失败"
                }
        }
        ImageReadResult(images = images, errors = errors.distinct())
    }

    private fun inspect(uri: Uri): CommerceReferenceImage {
        val resolver = context.contentResolver
        val metadata = resolver.queryMetadata(uri)
        val sniffedMime = resolver.sniffMime(uri)
        val mimeType = normalizeMime(
            resolver.getType(uri),
            metadata.displayName,
            sniffedMime,
        ) ?: throw CommerceImageException("${metadata.displayName}：不支持该文件格式")

        if (metadata.sizeBytes > MAX_IMAGE_BYTES) {
            throw CommerceImageException("${metadata.displayName}：单张图片不能超过 30 MB")
        }

        val dimensions = resolver.readDimensions(uri)
        if (mimeType != "image/heic" && mimeType != "image/heif" &&
            (dimensions.first <= 0 || dimensions.second <= 0)
        ) {
            throw CommerceImageException("${metadata.displayName}：图片损坏或无法解析")
        }
        if (dimensions.first > MAX_PIXEL_EDGE || dimensions.second > MAX_PIXEL_EDGE ||
            dimensions.first.toLong() * dimensions.second.toLong() > MAX_TOTAL_PIXELS
        ) {
            throw CommerceImageException("${metadata.displayName}：图片像素过大")
        }

        return CommerceReferenceImage(
            localId = UUID.randomUUID().toString(),
            uri = uri.toString(),
            displayName = metadata.displayName,
            mimeType = mimeType,
            sizeBytes = metadata.sizeBytes,
            width = dimensions.first,
            height = dimensions.second,
        )
    }
}

data class ImageReadResult(
    val images: List<CommerceReferenceImage>,
    val errors: List<String>,
)

private data class ContentMetadata(
    val displayName: String,
    val sizeBytes: Long,
)

private class CommerceImageException(message: String) : IllegalArgumentException(message)

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
    return ContentMetadata(displayName = name, sizeBytes = size)
}

private fun ContentResolver.sniffMime(uri: Uri): String? = openInputStream(uri)?.use { stream ->
    val header = ByteArray(16)
    val count = stream.read(header)
    when {
        count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
        count >= 8 && header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x1A.toByte(),
                0x0A.toByte(),
            ),
        ) -> "image/png"
        count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
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
    val providedNormalized = provided?.lowercase(Locale.ROOT)?.substringBefore(';')
    if (providedNormalized in SUPPORTED_IMAGE_MIMES) return providedNormalized
    return when (displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
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

private val SUPPORTED_IMAGE_MIMES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
    "image/heif",
)

const val MAX_REFERENCE_IMAGES = 10
private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
private const val MAX_PIXEL_EDGE = 16_384
private const val MAX_TOTAL_PIXELS = 80_000_000L
