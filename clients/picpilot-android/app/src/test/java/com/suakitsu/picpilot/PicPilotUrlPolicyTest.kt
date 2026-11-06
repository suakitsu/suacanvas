package com.suakitsu.picpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicPilotUrlPolicyTest {
    @Test
    fun acceptsOnlyHttpsPicPilotHosts() {
        assertTrue(PicPilotUrlPolicy.isPicPilotUrl("https://suakitsu.com/workspace"))
        assertTrue(PicPilotUrlPolicy.isPicPilotUrl("https://api.suakitsu.com/api/public/ping"))
        assertFalse(PicPilotUrlPolicy.isPicPilotUrl("http://suakitsu.com/workspace"))
        assertFalse(PicPilotUrlPolicy.isPicPilotUrl("https://suakitsu.com.example.org/workspace"))
    }

    @Test
    fun keepsOnlyProductAndGithubOAuthInsideWebView() {
        assertTrue(PicPilotUrlPolicy.shouldStayInApp("https://suakitsu.com/login"))
        assertTrue(PicPilotUrlPolicy.shouldStayInApp("https://github.com/login/oauth/authorize"))
        assertFalse(PicPilotUrlPolicy.shouldStayInApp("https://example.com/payment"))
    }

    @Test
    fun hidesNativeNavigationOnPublicAuthPages() {
        assertFalse(PicPilotUrlPolicy.isAuthenticatedSurface("https://suakitsu.com/"))
        assertFalse(PicPilotUrlPolicy.isAuthenticatedSurface("https://suakitsu.com/login"))
        assertFalse(PicPilotUrlPolicy.isAuthenticatedSurface("https://suakitsu.com/auth/github/callback"))
        assertTrue(PicPilotUrlPolicy.isAuthenticatedSurface("https://suakitsu.com/workspace"))
    }
}
