package com.detox.app.presentation.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.detox.app.R
import com.detox.app.ui.theme.DetoxTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Stage 1 now states the session length under the hero — every open is a countdown
 * (`OverlayManager.startSessionTimer`) and the remaining-opens number alone never said so.
 *
 * Overlays are always dark (they render over an arbitrary app), so there is only one variant to
 * check; the PNG is written for the visual pass.
 */
class SessionIntentionOverlayRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(sessionMinutes: Int) {
        composeRule.setContent {
            DetoxTheme(darkTheme = true) {
                SessionIntentionOverlay(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    contextHeader = "🔥 12 day streak",
                    opensUsed = 2,
                    maxOpens = 5,
                    sessionMinutes = sessionMinutes,
                    motivationText = null,
                    onYes = {},
                    onNo = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "session-overlay").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun showsSessionLengthUnderTheHero() {
        show(sessionMinutes = 3)
        composeRule
            .onNodeWithText(context.getString(R.string.overlay_v2_label_session_length, 3))
            .assertIsDisplayed()
        capture("stage1-3min")
    }

    /** No length known (0) ⇒ the line must not claim one. */
    @Test
    fun withoutASessionLength_showsNoLine() {
        show(sessionMinutes = 0)
        composeRule
            .onNodeWithText(context.getString(R.string.overlay_v2_label_session_length, 0))
            .assertDoesNotExist()
        capture("stage1-no-length")
    }
}
