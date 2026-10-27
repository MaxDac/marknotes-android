package io.github.maxdac.marknotes

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.data.ThemeMode
import io.github.maxdac.marknotes.security.DeviceAuth
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.sync.connections
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.NoteScreen
import io.github.maxdac.marknotes.ui.browser.BrowserScreen
import io.github.maxdac.marknotes.ui.conflicts.ConflictsScreen
import io.github.maxdac.marknotes.ui.drives.AddDriveScreen
import io.github.maxdac.marknotes.ui.help.GettingStartedHost
import io.github.maxdac.marknotes.ui.help.HelpScreen
import io.github.maxdac.marknotes.ui.help.MarkdownHelpScreen
import io.github.maxdac.marknotes.ui.importing.ImportScreen
import io.github.maxdac.marknotes.ui.importing.SharedContent
import io.github.maxdac.marknotes.ui.lock.LockScreen
import io.github.maxdac.marknotes.ui.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** The latest content shared with the app; [shareRequests] counts new shares to open the import screen for. */
    private val sharedContent = MutableStateFlow<SharedContent?>(null)
    private val shareRequests = MutableStateFlow(0)

    /** The last unlock error shown on the lock screen. */
    private val unlockError = MutableStateFlow<CharSequence?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedContent.value = SharedContent.from(intent)
        if (savedInstanceState == null) {
            // Sync once per launch; no periodic polling.
            appContainer.syncManager.syncIfConnected()
            if (sharedContent.value != null) shareRequests.value++
        }
        val settings = appContainer.settings
        val appLock = appContainer.appLock
        applySecureScreen()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { lockWhenIdle() } }
        watchNetwork()
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val color by settings.themeColor.collectAsStateWithLifecycle()
            val locked by appLock.locked.collectAsStateWithLifecycle()
            val error by unlockError.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_WINDOW else LIGHT_WINDOW))
            }
            MarkNotesTheme(mode = mode, color = color) {
                val focusManager = LocalFocusManager.current
                LaunchedEffect(locked) {
                    // Nothing typed while locked may reach the editor below the lock screen.
                    if (locked) focusManager.clearFocus(force = true)
                }
                Box(Modifier.fillMaxSize()) {
                    // Kept composed while locked, so unlocking returns to the same screen.
                    Box(if (locked) Modifier.clearAndSetSemantics {} else Modifier) {
                        MarkNotesNavHost(sharedContent, shareRequests, unlocked = !locked, onFinish = ::finish)
                    }
                    if (locked) LockDialog(dark = dark, error = error)
                }
            }
        }
    }

    /** The lock screen, in a window of its own so it also covers the dialogs and menus open when locking. */
    @Composable
    private fun LockDialog(dark: Boolean, error: CharSequence?) {
        Dialog(
            onDismissRequest = { moveTaskToBack(true) },
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, it.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            LockScreen(error = error, onUnlock = ::promptUnlock)
        }
    }

    /** Applies the "Hide notes in Recents and block screenshots" setting to the window. */
    private fun applySecureScreen() {
        // Collected on Main.immediate, so the flag is set before the first frame.
        lifecycleScope.launch {
            appContainer.settings.secureScreen.collect { secure ->
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    /**
     * Feeds the network state to "Sync when reconnected" while the activity exists, so a reconnect seen in the
     * background syncs once the app is back in the foreground. Nothing syncs while the app is in the background.
     */
    private fun watchNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val reconnect = appContainer.syncManager.reconnect
        lifecycleScope.launch { connectivity.connections().collect { reconnect.update(online = it) } }
    }

    /** Locks the app after the idle timeout while it is in the foreground. */
    private suspend fun lockWhenIdle() {
        val settings = appContainer.settings
        val appLock = appContainer.appLock
        combine(settings.appLockEnabled, settings.lockTimeout, appLock.locked, ::Triple)
            .collectLatest { (enabled, timeout, locked) ->
                if (!enabled || locked || timeout.millis <= 0) return@collectLatest
                do {
                    delay(appLock.millisUntilIdleLock().coerceAtLeast(MIN_IDLE_CHECK_MILLIS))
                } while (!appLock.checkIdle())
            }
    }

    private fun promptUnlock() {
        val appLock = appContainer.appLock
        if (!appLock.locked.value || !appLock.beginAuthentication()) return
        unlockError.value = null
        DeviceAuth.authenticate(this, getString(R.string.unlock_prompt_title, getString(R.string.app_name))) {
                success,
                error
            ->
            appLock.endAuthentication(success)
            unlockError.value = error
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        appContainer.appLock.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        val settings = appContainer.settings
        if (settings.appLockEnabled.value && !DeviceAuth.isDeviceSecure(this)) {
            // Without a screen lock there is no fallback credential: turn the app lock off and say so.
            settings.setAppLockEnabled(false)
            appContainer.appLock.onEnabledChanged()
            Toast.makeText(this, APP_LOCK_TURNED_OFF_TEXT, Toast.LENGTH_LONG).show()
        }
        appContainer.appLock.onStart()
        appContainer.syncManager.reconnect.update(foreground = true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            appContainer.appLock.onStop()
            appContainer.syncManager.reconnect.update(foreground = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The next launch syncs anyway; a reconnect seen before it must not add a second sync.
        if (isFinishing) appContainer.syncManager.reconnect.reset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        SharedContent.from(intent)?.let {
            setIntent(intent)
            sharedContent.value = it
            shareRequests.value++
        }
    }

    override fun onResume() {
        super.onResume()
        // Completes a pending browser-based sign-in (Dropbox, ...) when returning from the browser.
        appContainer.syncManager.onActivityResumed()
        if (appContainer.appLock.consumePromptRequest()) promptUnlock()
    }
}

const val APP_LOCK_TURNED_OFF_TEXT = "The app lock was turned off because this device no longer has a screen lock."
private const val MIN_IDLE_CHECK_MILLIS = 1_000L

// Window background behind the Compose content, matching the theme surfaces so nothing flashes white.
private const val DARK_WINDOW = 0xFF141218.toInt()
private const val LIGHT_WINDOW = 0xFFFEF7FF.toInt()

object Routes {
    const val BROWSER = "browser?path={path}"
    const val EDITOR = "editor?path={path}"
    const val SETTINGS = "settings"
    const val SIGN_IN = "sign_in/{provider}"
    const val CONFLICTS = "conflicts"
    const val ADD_DRIVE = "add_drive"
    const val IMPORT = "import"
    const val HELP = "help"
    const val MARKDOWN_HELP = "help/markdown"

    fun browser(path: String) = "browser?path=${Uri.encode(path)}"
    fun editor(path: String) = "editor?path=${Uri.encode(path)}"
    fun signIn(provider: DriveProvider) = "sign_in/${provider.name}"
}

@Composable
private fun MarkNotesNavHost(
    sharedContent: StateFlow<SharedContent?>,
    shareRequests: StateFlow<Int>,
    unlocked: Boolean,
    onFinish: () -> Unit
) {
    val navController = rememberNavController()
    val container = LocalContext.current.appContainer
    val backStackEntry by navController.currentBackStackEntryAsState()
    val guideConsumed by container.settings.gettingStartedConsumed.collectAsStateWithLifecycle()
    var replayGuide by rememberSaveable { mutableStateOf(false) }
    var handledShare by rememberSaveable { mutableIntStateOf(0) }
    val pathArg = listOf(
        navArgument("path") {
            type = NavType.StringType
            defaultValue = ""
        }
    )
    val shareRequest by shareRequests.collectAsStateWithLifecycle()
    LaunchedEffect(shareRequest) {
        if (shareRequest > 0) {
            // A new share replaces an import screen that is still open.
            navController.popBackStack(Routes.IMPORT, inclusive = true)
            navController.navigate(Routes.IMPORT)
            handledShare = shareRequest
        }
    }
    val guideDestination = backStackEntry?.destination?.route in setOf(Routes.BROWSER, Routes.HELP)
    val guidePending = (!guideConsumed || replayGuide) &&
        (guideDestination || backStackEntry == null) && shareRequest <= handledShare
    NavHost(
        navController = navController,
        startDestination = Routes.BROWSER,
        // Opaque themed backdrop, so screen transitions never show the window behind (white in dark mode).
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .then(if (guidePending) Modifier.clearAndSetSemantics {} else Modifier)
    ) {
        composable(Routes.BROWSER, arguments = pathArg) { entry ->
            BrowserScreen(
                folder = entry.arguments?.getString("path").orEmpty(),
                onOpenFolder = { navController.navigate(Routes.browser(it)) },
                onJumpToFolder = { path ->
                    val root = navController.graph.startDestinationId
                    if (path.isEmpty()) {
                        navController.popBackStack(root, inclusive = false)
                    } else {
                        navController.navigate(Routes.browser(path)) {
                            popUpTo(root)
                            launchSingleTop = true
                        }
                    }
                },
                onOpenNote = { navController.navigate(Routes.editor(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.EDITOR, arguments = pathArg) { entry ->
            NoteScreen(
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.navigateUp() },
                onOpenNote = { navController.navigate(Routes.editor(it)) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAddDrive = { navController.navigate(Routes.ADD_DRIVE) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onBack = { navController.navigateUp() },
                onOpenHelp = { navController.navigate(Routes.HELP) },
                // Editors of deleted notes must not stay open: back to the (now empty) note list.
                onLocalDataDeleted = {
                    navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
                }
            )
        }
        composable(Routes.ADD_DRIVE) {
            AddDriveScreen(
                onProviderSignIn = { navController.navigate(Routes.signIn(it)) },
                onDone = { navController.popBackStack(Routes.ADD_DRIVE, inclusive = true) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.SIGN_IN, arguments = listOf(navArgument("provider") { type = NavType.StringType })) { entry ->
            val name = entry.arguments?.getString("provider")
            val provider = DriveProvider.entries.firstOrNull { it.name == name }
            val screen = provider?.let { LocalContext.current.appContainer.connectors[it]?.signInScreen }
            if (screen == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                screen({ navController.navigateUp() }, { navController.navigateUp() })
            }
        }
        composable(Routes.CONFLICTS) {
            ConflictsScreen(onBack = { navController.navigateUp() })
        }
        composable(Routes.IMPORT) {
            val content by sharedContent.collectAsStateWithLifecycle()
            val current = content
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ImportScreen(
                    content = current,
                    onSaved = { path ->
                        navController.navigate(Routes.editor(path)) {
                            popUpTo(Routes.IMPORT) { inclusive = true }
                        }
                    },
                    // Return to the app the content was shared from.
                    onCancel = onFinish
                )
            }
        }
        composable(Routes.HELP) {
            HelpScreen(
                onReplay = { replayGuide = true },
                onMarkdown = { navController.navigate(Routes.MARKDOWN_HELP) },
                onBack = { navController.navigateUp() }
            )
        }
        composable(Routes.MARKDOWN_HELP) {
            MarkdownHelpScreen(onBack = { navController.navigateUp() })
        }
    }
    val providers = container.connectors.values.filter { it.unavailableReason == null }
        .joinToString(", ") { it.provider.displayName }
        .ifEmpty { stringResource(R.string.guide_no_providers) }
    GettingStartedHost(
        settings = container.settings,
        replay = replayGuide,
        onReplayFinished = { replayGuide = false },
        unlocked = unlocked,
        safeDestination = guideDestination,
        pendingShare = shareRequest > handledShare,
        providers = providers,
        onMarkdown = { navController.navigate(Routes.MARKDOWN_HELP) }
    )
}
