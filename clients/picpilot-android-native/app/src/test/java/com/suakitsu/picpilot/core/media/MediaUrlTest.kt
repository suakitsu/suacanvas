package com.suakitsu.picpilot.core.media

import com.suakitsu.picpilot.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlTest {
    @Test
    fun sameOriginPublicMediaIsSubmittedAsCanonicalPath() {
        assertEquals(
            "/api/public/files/projects/example/reference.jpg",
            mediaReferenceForApi("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/public/files/projects/example/reference.jpg"),
        )
    }

    @Test
    fun externalReferenceUrlRemainsAbsolute() {
        assertEquals(
            "https://images.example.test/reference.jpg",
            mediaReferenceForApi("https://images.example.test/reference.jpg"),
        )
    }
}
