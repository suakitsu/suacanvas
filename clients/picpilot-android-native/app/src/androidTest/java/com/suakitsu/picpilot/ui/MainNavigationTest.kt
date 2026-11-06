package com.suakitsu.picpilot.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.suakitsu.picpilot.ui.theme.PicPilotTheme
import org.junit.Rule
import org.junit.Test

class MainNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactLayoutHasOneSetOfFivePrimaryDestinations() {
        compose.setContent {
            PicPilotTheme {
                CompactPrimaryNavigationBar(currentRoute = "workspace", onNavigate = {})
            }
        }
        compose.onAllNodesWithText("工作台").assertCountEquals(1)
        compose.onAllNodesWithText("电商制图").assertCountEquals(1)
        compose.onAllNodesWithText("商品视频").assertCountEquals(1)
        compose.onAllNodesWithText("AI 创作").assertCountEquals(1)
        compose.onAllNodesWithText("更多").assertCountEquals(1)
    }
}
