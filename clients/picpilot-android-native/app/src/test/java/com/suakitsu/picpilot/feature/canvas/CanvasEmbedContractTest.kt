package com.suakitsu.picpilot.feature.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEmbedContractTest {
    @Test
    fun activeCanvasRemainsVisibleWhenWebReadinessSignalIsLate() {
        assertTrue(NATIVE_CANVAS_CSS.contains(".canvas-lab-frame.is-active"))
        assertTrue(NATIVE_CANVAS_CSS.contains("opacity:1!important"))
        assertTrue(NATIVE_CANVAS_CSS.contains("visibility:visible!important"))
        assertTrue(NATIVE_CANVAS_CSS.contains("pointer-events:auto!important"))
    }

    @Test
    fun embeddedCanvasKeepsItsProgressStateVisibleUntilTheFrameIsReady() {
        assertFalse(NATIVE_CANVAS_CSS.contains(".canvas-lab-loading{display:none!important}"))
    }

    @Test
    fun hiddenWebToolbarCannotCollapseTheEmbeddedCanvasGridRow() {
        assertTrue(NATIVE_CANVAS_CSS.contains(".canvas-lab{display:block!important;position:relative!important}"))
        assertTrue(NATIVE_CANVAS_CSS.contains(".canvas-lab-frame-wrap{position:absolute!important;inset:0!important"))
        assertTrue(NATIVE_CANVAS_CSS.contains("height:var(--picpilot-native-height,100vh)!important"))
        assertTrue(NATIVE_CANVAS_CSS.contains("min-height:var(--picpilot-native-height,100vh)!important"))
    }

    @Test
    fun embeddedCanvasUsesTheVisibleViewportWithoutAReservedScrollbarStrip() {
        assertTrue(NATIVE_CANVAS_CSS.contains("height:var(--picpilot-native-height,100vh)!important"))
        assertTrue(NATIVE_CANVAS_CSS.contains("overflow:hidden!important;scrollbar-gutter:auto!important"))
    }
}
