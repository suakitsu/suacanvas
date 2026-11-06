package com.suakitsu.picpilot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestContractTest {
    @Test
    fun appDoesNotClaimEverySuakitsuWebLink() {
        val manifest = manifestText()

        assertFalse(manifest.contains("android:autoVerify=\"true\""))
        assertFalse(manifest.contains("android:host=\"suakitsu.com\""))
    }

    @Test
    fun explicitPicPilotSchemeRemainsAvailable() {
        val manifest = manifestText()

        assertTrue(manifest.contains("android:scheme=\"picpilot\""))
        assertTrue(manifest.contains("android:host=\"open\""))
    }

    private fun manifestText(): String {
        val candidates = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AndroidManifest.xml was not found from ${File(".").absolutePath}")
    }
}
