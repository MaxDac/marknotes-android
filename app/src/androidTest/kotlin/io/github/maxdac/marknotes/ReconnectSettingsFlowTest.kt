package io.github.maxdac.marknotes

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.ui.settings.SYNC_ON_RECONNECT_SWITCH_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The optional "Sync when reconnected" switch in Settings, driven through [MainActivity]. */
@RunWith(AndroidJUnit4::class)
class ReconnectSettingsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        container.settings.setGettingStartedConsumed(true)
        reset()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        reset()
    }

    private fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.settings.setSyncOnReconnect(false)
            container.settings.setAppLockEnabled(false)
            container.appLock.onEnabledChanged()
        }
    }

    @Test
    fun theSwitchIsOffByDefaultAndPersistsAcrossRecreation() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag(SYNC_ON_RECONNECT_SWITCH_TAG).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag(SYNC_ON_RECONNECT_SWITCH_TAG).assertIsOn()
        assertTrue(container.settings.syncOnReconnect.value)
        scenario.recreate()
        compose.onNodeWithTag(SYNC_ON_RECONNECT_SWITCH_TAG).performScrollTo().assertIsOn()
    }
}
