package io.github.maxdac.marknotes.ui.browser

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxdac.marknotes.sync.FolderRemovalNotice
import io.github.maxdac.marknotes.sync.SyncHooks
import io.github.maxdac.marknotes.sync.SyncUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inert hooks; [DrawerSection] is left at its default. */
private open class InertHooks : SyncHooks {
    override val available = false
    override val state: StateFlow<SyncUiState> = MutableStateFlow(SyncUiState())
    override val providerNames = ""
    override val aboutNotices = emptyList<String>()

    override fun syncIfConnected() = Unit

    override fun syncNow() = Unit

    override fun acknowledgeFolderNotice(notice: FolderRemovalNotice) = Unit

    override fun onActivityResumed() = Unit

    override fun watchNetwork(activity: ComponentActivity) = Unit

    override fun onForeground(foreground: Boolean) = Unit

    override fun onActivityFinishing() = Unit

    override fun updateReconnectPolicy(enabled: Boolean, unlocked: Boolean) = Unit

    override suspend fun deleteAllLocalData() = Unit
}

@RunWith(AndroidJUnit4::class)
class FolderDrawerSectionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(hooks: SyncHooks) = rule.setContent {
        val navController = rememberNavController()
        FolderDrawer(listOf("a"), current = "", inSemantics = true, drawerSection = {
            with(hooks) { DrawerSection(navController) }
        }, onSelect = {})
    }

    /** The default [SyncHooks.DrawerSection] (what the libre app uses) renders nothing in the drawer. */
    @Test
    fun defaultHookRendersNothing() {
        show(InertHooks())
        rule.onNodeWithText("All notes").assertIsDisplayed()
        rule.onNodeWithText("Fake section").assertDoesNotExist()
    }

    @Test
    fun hookSectionRendersInDrawer() {
        show(object : InertHooks() {
            @Composable
            override fun ColumnScope.DrawerSection(navController: NavController) {
                Text("Fake section")
            }
        })
        rule.onNodeWithText("All notes").assertIsDisplayed()
        rule.onNodeWithText("Fake section").assertIsDisplayed()
    }
}
