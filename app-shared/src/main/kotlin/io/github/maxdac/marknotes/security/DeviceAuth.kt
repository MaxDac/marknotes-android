package io.github.maxdac.marknotes.security

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode

/**
 * Biometric prompt with the device credential (PIN, pattern or password) as fallback, through the framework
 * [BiometricPrompt]: no extra dependency and no FragmentActivity.
 *
 * The app lock is only a privacy lock over the UI, not protection against a rooted or compromised device or
 * privileged hooking, so the prompt is deliberately not bound to a Keystore CryptoObject: notes are plain files
 * in app storage (as on desktop and in the shared drive), so whoever can hook this callback can already read them
 * directly, and a key would protect nothing. A CryptoObject would also require BIOMETRIC_STRONG (dropping weak
 * face unlock) and, on API 29, rule out the device-credential fallback.
 */
object DeviceAuth {
    /** Whether the device has a screen lock; the app lock needs one (it is the fallback for biometrics). */
    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Shows the prompt. [onResult] runs on the main thread with whether it succeeded and, on failure, an error to
     * show (null when the user just cancelled it).
     */
    fun authenticate(context: Context, title: String, onResult: (success: Boolean, error: CharSequence?) -> Unit) {
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onResult(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                if (!cancelled) context.appContainer.diagnostics.report(DiagnosticCode.AUTHENTICATION, null)
                onResult(false, if (cancelled) null else errString ?: "Authentication failed")
            }
            // onAuthenticationFailed (a fingerprint or face that doesn't match) keeps the prompt open.
        }
        try {
            builder.build().authenticate(CancellationSignal(), context.mainExecutor, callback)
        } catch (e: IllegalArgumentException) {
            context.appContainer.diagnostics.report(DiagnosticCode.AUTHENTICATION, e)
            onResult(false, e.message ?: "Authentication is not available")
        } catch (e: SecurityException) {
            context.appContainer.diagnostics.report(DiagnosticCode.AUTHENTICATION, e)
            onResult(false, e.message ?: "Authentication is not available")
        }
    }
}
