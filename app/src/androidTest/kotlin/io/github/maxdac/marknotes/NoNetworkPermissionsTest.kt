package io.github.maxdac.marknotes

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** This edition is offline only: it requests no network permissions and still launches without them. */
@RunWith(AndroidJUnit4::class)
class NoNetworkPermissionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchesWithoutNetworkPermissions() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        )
        context.appContainer.settings.setGettingStartedConsumed(true)
        ActivityScenario.launch(MainActivity::class.java).use { }
    }
}
