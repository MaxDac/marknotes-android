package io.github.maxdac.marknotes.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/** App accent colour. [DYNAMIC] follows the wallpaper (Material You) and needs Android 12+. */
enum class ThemeColor(val label: String) {
    DYNAMIC("Dynamic"),
    BLUE("Blue"),
    TEAL("Teal"),
    GREEN("Green"),
    LIME("Lime"),
    AMBER("Amber"),
    ORANGE("Orange"),
    RED("Red"),
    PURPLE("Purple")
}

/** How long the app can stay unused before the app lock locks it; [IMMEDIATELY] locks when you leave the app. */
enum class LockTimeout(val minutes: Int, val label: String) {
    IMMEDIATELY(0, "Immediately"),
    ONE_MINUTE(1, "1 minute"),
    FIVE_MINUTES(5, "5 minutes"),
    FIFTEEN_MINUTES(15, "15 minutes");

    val millis: Long get() = minutes * 60_000L

    companion object {
        val DEFAULT = FIVE_MINUTES
    }
}

/** Small non-secret preferences. */
class AppSettings(context: Context, private val dynamicColorSupported: Boolean) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Folder of the legacy single Proton Drive setup; migrated into the Proton drive account. */
    val legacyRemoteFolder: String
        get() = prefs.getString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER) ?: DEFAULT_REMOTE_FOLDER

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastSyncSummary: String
        get() = prefs.getString(KEY_LAST_SYNC_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, value).apply()

    /**
     * Forgets the sync state kept in the settings (last sync, legacy folder), when the local data is deleted.
     * Settings about the app itself (theme, device id) are kept.
     */
    fun clearSyncState() {
        prefs.edit().remove(KEY_LAST_SYNC).remove(KEY_LAST_SYNC_SUMMARY).remove(KEY_REMOTE_FOLDER).apply()
    }

    /** Stable random id of this installation, written to the drive manifests. */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private val _themeMode = MutableStateFlow(
        enumValueOrNull<ThemeMode>(prefs.getString(KEY_THEME_MODE, null)) ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _themeColor = MutableStateFlow(
        enumValueOrNull<ThemeColor>(prefs.getString(KEY_THEME_COLOR, null))
            ?.takeIf { it != ThemeColor.DYNAMIC || dynamicColorSupported }
            ?: if (dynamicColorSupported) ThemeColor.DYNAMIC else ThemeColor.BLUE
    )
    val themeColor: StateFlow<ThemeColor> = _themeColor

    /** Colours offered in the settings; Dynamic needs Android 12+. */
    val availableColors: List<ThemeColor>
        get() = ThemeColor.entries.filter { it != ThemeColor.DYNAMIC || dynamicColorSupported }

    private val _chordSequencesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CHORD_SEQUENCES, false))

    /**
     * Whether chord sequences can be created and opened in the chord editor. Off by default and kept on this device
     * only (never synced); it doesn't change how notes are detected, parsed or synced.
     */
    val chordSequencesEnabled: StateFlow<Boolean> = _chordSequencesEnabled

    fun setChordSequencesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHORD_SEQUENCES, enabled).apply()
        _chordSequencesEnabled.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setThemeColor(color: ThemeColor) {
        prefs.edit().putString(KEY_THEME_COLOR, color.name).apply()
        _themeColor.value = color
    }

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))

    /** Whether opening the app asks for biometrics or the device credential (see `security/AppLock`). */
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled

    private val _lockTimeout = MutableStateFlow(
        enumValueOrNull<LockTimeout>(prefs.getString(KEY_LOCK_TIMEOUT, null)) ?: LockTimeout.DEFAULT
    )
    val lockTimeout: StateFlow<LockTimeout> = _lockTimeout

    private val _secureScreen = MutableStateFlow(prefs.getBoolean(KEY_SECURE_SCREEN, false))

    /** Whether the window is FLAG_SECURE: hidden in Recents, no screenshots or screen recording. */
    val secureScreen: StateFlow<Boolean> = _secureScreen

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLockEnabled.value = enabled
    }

    fun setLockTimeout(timeout: LockTimeout) {
        prefs.edit().putString(KEY_LOCK_TIMEOUT, timeout.name).apply()
        _lockTimeout.value = timeout
    }

    fun setSecureScreen(secure: Boolean) {
        prefs.edit().putBoolean(KEY_SECURE_SCREEN, secure).apply()
        _secureScreen.value = secure
    }

    companion object {
        const val DEFAULT_REMOTE_FOLDER = "MarkNotes"
        private const val KEY_REMOTE_FOLDER = "remote_folder"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_CHORD_SEQUENCES = "chord_sequences_enabled"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_LOCK_TIMEOUT = "lock_timeout"
        private const val KEY_SECURE_SCREEN = "secure_screen"

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
