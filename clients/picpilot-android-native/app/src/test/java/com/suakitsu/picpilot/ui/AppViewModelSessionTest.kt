package com.suakitsu.picpilot.ui

import com.suakitsu.picpilot.core.network.ApiResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelSessionTest {
    @Test
    fun transientFailuresKeepTheLocalSession() {
        listOf(
            "NETWORK_ERROR",
            "HTTP_500",
            "HTTP_502",
            "HTTP_503",
            "REQUEST_FAILED",
            "RATE_LIMITED",
        ).forEach { code ->
            assertFalse(code, ApiResult.Failure("temporary", code).invalidatesSession())
        }
    }

    @Test
    fun explicitAuthenticationFailuresInvalidateTheSession() {
        listOf(
            "HTTP_401",
            "UNAUTHORIZED",
            "AUTH_REQUIRED",
            "TOKEN_EXPIRED",
            "AUTH_TOKEN_EXPIRED",
            "JWT_EXPIRED",
            "INVALID_TOKEN",
            "INVALID_ACCESS_TOKEN",
        ).forEach { code ->
            assertTrue(code, ApiResult.Failure("expired", code.lowercase()).invalidatesSession())
        }
    }

    @Test
    fun forbiddenDoesNotEraseTheSession() {
        assertFalse(ApiResult.Failure("forbidden", "HTTP_403").invalidatesSession())
    }
}
