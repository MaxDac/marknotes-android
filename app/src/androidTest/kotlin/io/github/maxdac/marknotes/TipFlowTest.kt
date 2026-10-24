package io.github.maxdac.marknotes

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.settings.SettingsScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TipFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val tipLabel get() = context.getString(R.string.leave_a_tip)

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun tipIsReachableThroughSettingsWithoutOpeningBrowserAndBackStillWorks() {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        val monitor = Instrumentation.ActivityMonitor(
            filter,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true
        )
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText(tipLabel).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.tip_description)).assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
            compose.onNodeWithText(tipLabel).performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            assertEquals(0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun tapOpensOnlyTheExactSupportUrlWithoutUserData() {
        val intents = mutableListOf<Intent>()
        showSettings { intents.add(it) }
        compose.onNodeWithText(tipLabel).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(intents.isEmpty())

        compose.onNodeWithText(tipLabel).performClick()
        compose.runOnIdle {
            assertEquals(1, intents.size)
            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://ko-fi.com/maxdac", intent.dataString)
            assertNull(intent.extras)
            assertNull(intent.clipData)
            assertNull(intent.categories)
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertNull(intent.selector)
            assertNull(intent.type)
            assertEquals(0, intent.flags)
        }
    }

    @Test
    fun noBrowserShowsVisibleError() {
        showSettings { throw ActivityNotFoundException("No browser") }
        compose.onNodeWithText(tipLabel).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tip_no_browser)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tipLabel).assertExists()
    }

    @Test
    fun blockedBrowserShowsVisibleErrorAndCanRetry() {
        var blocked = true
        var launches = 0
        showSettings {
            if (blocked) throw SecurityException("Blocked by device policy")
            launches++
        }
        compose.onNodeWithText(tipLabel).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tip_browser_blocked)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { blocked = false }
        compose.onNodeWithText(tipLabel).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.tip_browser_blocked)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, launches) }
    }

    // Override only the platform launch boundary; production SettingsScreen and its error UI stay unchanged.
    private fun showSettings(launch: (Intent) -> Unit) {
        scenario.onActivity { activity ->
            val recordingContext = object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) = launch(intent)
            }
            activity.setContent {
                CompositionLocalProvider(LocalContext provides recordingContext) {
                    MarkNotesTheme {
                        SettingsScreen(onAddDrive = {}, onOpenConflicts = {}, onBack = {})
                    }
                }
            }
        }
    }
}
