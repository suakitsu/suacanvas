package com.suakitsu.picpilot.core.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.suakitsu.picpilot.core.network.MediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class MediaFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @MediaClient private val client: OkHttpClient,
) {
    suspend fun download(
        url: String,
        suggestedName: String,
        requestedMimeType: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = fetch(url, suggestedName, requestedMimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(fetched)
                "已保存到下载/PicPilot"
            } else {
                val target = legacyDownloadFile(fetched.fileName)
                target.outputStream().use { output -> output.write(fetched.bytes) }
                "已保存到 ${target.absolutePath}"
            }
        }
    }

    suspend fun share(
        url: String,
        suggestedName: String,
        requestedMimeType: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fetched = fetch(url, suggestedName, requestedMimeType)
            val directory = File(context.cacheDir, "shared_media").apply { mkdirs() }
            pruneShareCache(directory)
            val target = File(directory, "${url.sha256().take(16)}_${fetched.fileName}")
            target.outputStream().use { output -> output.write(fetched.bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = fetched.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, fetched.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(send, "选择分享方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun fetch(url: String, suggestedName: String, requestedMimeType: String): FetchedMedia {
        val resolved = resolveMediaUrl(url)
        val uri = Uri.parse(resolved)
        if (uri.scheme == "content") {
            return fetchContentUri(uri, suggestedName, requestedMimeType)
        }
        val httpUrl = resolved.toHttpUrlOrNull() ?: throw IOException("媒体地址无效")
        if (httpUrl.scheme != "https" && httpUrl.scheme != "http") {
            throw IOException("媒体地址协议不受支持")
        }
        val response = client.newCall(Request.Builder().url(httpUrl).get().build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("媒体读取失败 (${it.code})")
            val body = it.body ?: throw IOException("媒体内容为空")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_MEDIA_BYTES) throw IOException("媒体文件过大")
            val mimeType = body.contentType()?.toString()?.substringBefore(';')
                ?.takeIf(String::isNotBlank)
                ?: requestedMimeType.takeIf(String::isNotBlank)
                ?: "application/octet-stream"
            val fileName = safeFileName(suggestedName, mimeType)
            val bytes = body.byteStream().readWithLimit(MAX_MEDIA_BYTES)
            if (bytes.isEmpty()) throw IOException("媒体内容为空")
            return FetchedMedia(bytes, fileName, mimeType)
        }
    }

    private fun fetchContentUri(uri: Uri, suggestedName: String, requestedMimeType: String): FetchedMedia {
        val resolver = context.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
        if ((metadata?.second ?: -1L) > MAX_MEDIA_BYTES) throw IOException("媒体文件过大")
        val mimeType = resolver.getType(uri)?.takeIf(String::isNotBlank)
            ?: requestedMimeType.takeIf(String::isNotBlank)
            ?: "application/octet-stream"
        val fileName = safeFileName(metadata?.first ?: suggestedName, mimeType)
        val bytes = resolver.openInputStream(uri)?.use { it.readWithLimit(MAX_MEDIA_BYTES) }
            ?: throw IOException("无法读取所选图片")
        if (bytes.isEmpty()) throw IOException("媒体内容为空")
        return FetchedMedia(bytes, fileName, mimeType)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(media: FetchedMedia) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PicPilot")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output -> output.write(media.bytes) }
                ?: throw IOException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun legacyDownloadFile(fileName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("下载目录不可用")
        val directory = File(root, "PicPilot").apply { mkdirs() }
        return File(directory, uniqueName(directory, fileName))
    }

    private fun safeFileName(name: String, mimeType: String): String {
        val clean = name.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.', '_')
            .take(72)
            .ifBlank { "picpilot_${System.currentTimeMillis()}" }
        val extension = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            else -> "bin"
        }
        val existingExtension = clean.substringAfterLast('.', "")
        return if (existingExtension.length in 2..5) clean else "$clean.$extension"
    }

    private fun uniqueName(directory: File, fileName: String): String {
        if (!File(directory, fileName).exists()) return fileName
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (extension.isBlank()) "${base}_$index" else "${base}_$index.$extension"
            if (!File(directory, candidate).exists()) return candidate
            index += 1
        }
    }

    private fun pruneShareCache(directory: File) {
        val cutoff = System.currentTimeMillis() - SHARE_CACHE_MAX_AGE_MS
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach(File::delete)
    }

    private data class FetchedMedia(val bytes: ByteArray, val fileName: String, val mimeType: String)

    private companion object {
        const val MAX_MEDIA_BYTES = 512L * 1024L * 1024L
        const val SHARE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}

private fun java.io.InputStream.readWithLimit(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("媒体文件过大")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
