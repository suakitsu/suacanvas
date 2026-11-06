package com.suakitsu.picpilot

import java.net.URI

object PicPilotUrlPolicy {
    private const val PRIMARY_HOST = "suakitsu.com"
    private val embeddedOAuthHosts = setOf("github.com")

    fun isPicPilotUrl(rawUrl: String): Boolean {
        val uri = parse(rawUrl) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host == PRIMARY_HOST || host.endsWith(".$PRIMARY_HOST"))
    }

    fun shouldStayInApp(rawUrl: String): Boolean {
        val uri = parse(rawUrl) ?: return rawUrl == "about:blank"
        val host = uri.host?.lowercase() ?: return false
        return isPicPilotUrl(rawUrl) ||
            (uri.scheme.equals("https", ignoreCase = true) && host in embeddedOAuthHosts)
    }

    fun isAuthenticatedSurface(rawUrl: String): Boolean {
        if (!isPicPilotUrl(rawUrl)) return false
        val path = parse(rawUrl)?.path.orEmpty()
        return path != "/" &&
            !path.startsWith("/login") &&
            !path.startsWith("/register") &&
            !path.startsWith("/forgot-password") &&
            !path.startsWith("/auth/")
    }

    private fun parse(rawUrl: String): URI? = runCatching { URI(rawUrl) }.getOrNull()
}
