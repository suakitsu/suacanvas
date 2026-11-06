package com.suakitsu.picpilot.feature.detail

import android.content.Context
import com.suakitsu.picpilot.PicPilotApplication
import com.suakitsu.picpilot.core.media.resolveMediaUrl

internal object DetailMediaActions {
    fun download(
        context: Context,
        url: String,
        suggestedName: String,
        mimeType: String,
    ): Boolean {
        val resolved = resolveMediaUrl(url)
        val app = context.applicationContext as? PicPilotApplication ?: return false
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return false
        app.downloadMedia(resolved, suggestedName, mimeType)
        return true
    }

    fun shareRemote(context: Context, url: String, mimeType: String): Boolean {
        val resolved = resolveMediaUrl(url)
        val app = context.applicationContext as? PicPilotApplication ?: return false
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return false
        app.shareMedia(resolved, if (mimeType.startsWith("video/")) "PicPilot 视频" else "PicPilot 图片", mimeType)
        return true
    }
}
