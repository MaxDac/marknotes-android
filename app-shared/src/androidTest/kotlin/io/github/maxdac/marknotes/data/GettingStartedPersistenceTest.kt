package io.github.maxdac.marknotes.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GettingStartedPersistenceTest {
    @Test
    fun failedCommitDoesNotPublishConsumptionOrModifyStoredState() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = base.getSharedPreferences("guide-write-failure-test", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor =
                        object : SharedPreferences.Editor by preferences.edit() {
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
                            override fun commit(): Boolean = false
                        }
                }
        }
        val settings = AppSettings(context, dynamicColorSupported = false)
        assertFalse(settings.gettingStartedConsumed.value)
        assertFalse(settings.setGettingStartedConsumed(true))
        assertFalse(settings.gettingStartedConsumed.value)
        assertFalse(AppSettings(context, dynamicColorSupported = false).gettingStartedConsumed.value)
    }
}
