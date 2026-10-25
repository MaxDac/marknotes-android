package io.github.maxdac.marknotes

import android.view.WindowManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.security.DeviceAuth
import io.github.maxdac.marknotes.ui.lock.LOCKED_TITLE
import io.github.maxdac.marknotes.ui.lock.UNLOCK_TEXT
import io.github.maxdac.marknotes.ui.settings.NO_SCREEN_LOCK_TEXT
import io.github.maxdac.marknotes.ui.settings.SECURE_SCREEN_TEXT
import io.github.maxdac.marknotes.ui.settings.TURN_ON_APP_LOCK_TEXT
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The optional app lock and FLAG_SECURE, driven through [MainActivity]. */
@RunWith(AndroidJUnit4::class)
class AppLockFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val notesDir: File
        get() = File(context.filesDir, "notes")

    @Before
    fun setUp() {
        container.settings.setGettingStartedConsumed(true)
        notesDir.deleteRecursively()
        reset()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        reset()
        notesDir.deleteRecursively()
    }

    private fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.settings.setAppLockEnabled(false)
            container.settings.setSecureScreen(false)
            container.appLock.onEnabledChanged()
        }
    }

    private fun secureFlagSet(): Boolean {
        var flags = 0
        scenario.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun openPrivacySettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(SECURE_SCREEN_TEXT).performScrollTo()
    }

    @Test
    fun lockIsOffByDefaultAndNeedsAScreenLock() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertFalse(secureFlagSet())
        openPrivacySettings()
        val turnOn = compose.onNodeWithText(TURN_ON_APP_LOCK_TEXT)
        if (DeviceAuth.isDeviceSecure(context)) {
            turnOn.assertIsEnabled()
        } else {
            turnOn.assertIsNotEnabled()
            compose.onNodeWithText(NO_SCREEN_LOCK_TEXT).assertExists()
        }
        compose.onNodeWithText(SECURE_SCREEN_TEXT).assertIsOff()
    }

    @Test
    fun secureScreenSettingAppliesFlagSecure() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.settings.setSecureScreen(true) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(secureFlagSet())

        openPrivacySettings()
        compose.onNodeWithText(SECURE_SCREEN_TEXT).assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(container.settings.secureScreen.value)
        assertFalse(secureFlagSet())

        compose.onNodeWithText(SECURE_SCREEN_TEXT).performClick()
        compose.waitForIdle()
        assertTrue(secureFlagSet())
    }

    @Test
    fun lockScreenHidesTheAppAndKeepsTheScreenOnUnlock() {
        notesDir.mkdirs()
        File(notesDir, "Secret.md").writeText("hidden text")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Secret")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Secret").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("hidden text")).fetchSemanticsNodes().isNotEmpty()
        }

        // Locking from inside the app ("Lock now", timeout, screen off) shows the lock screen without a prompt.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.settings.setAppLockEnabled(true)
            container.appLock.lock()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(LOCKED_TITLE)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(UNLOCK_TEXT).assertExists()
        assertEquals(0, compose.onAllNodes(hasText("hidden text")).fetchSemanticsNodes().size)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.appLock.beginAuthentication()
            container.appLock.endAuthentication(success = true)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(LOCKED_TITLE)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("hidden text").assertExists()
    }
}
