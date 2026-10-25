package io.github.maxdac.marknotes.ui.drives

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.sync.SignInScreen
import io.github.maxdac.marknotes.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ReconnectDriveState(
    val account: DriveAccount? = null,
    val screen: SignInScreen? = null,
    val committing: Boolean = false,
    val error: String? = null
) {
    val busy: Boolean get() = account != null
}

/** The reconnect continuation is separate from AddDrive: it must never create or discard an account. */
class ReconnectDriveViewModel(
    private val manager: SyncManager,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : ViewModel() {
    private val _state = MutableStateFlow(ReconnectDriveState())
    val state: StateFlow<ReconnectDriveState> = _state

    fun start(account: DriveAccount, activity: ComponentActivity) {
        if (_state.value.busy) return
        val connector = manager.connector(account.provider)
        if (!connector.supportsReconnect) {
            _state.value = ReconnectDriveState(error = "Reconnect is not supported by ${account.provider.displayName}")
            return
        }
        try {
            _state.value = ReconnectDriveState(account, connector.reconnectScreen(account))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.report(DiagnosticCode.SIGN_IN, e)
            connector.cancelReconnect(account)
            _state.value = ReconnectDriveState(error = "Reconnect failed: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (_state.value.screen == null) complete(activity)
    }

    fun complete(activity: ComponentActivity) {
        val state = _state.value
        val account = state.account ?: return
        if (state.committing) return
        _state.value = state.copy(screen = null, committing = true)
        viewModelScope.launch {
            try {
                manager.reconnectDrive(activity, account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // SyncManager reported it and shows the failure as the sync message.
            } finally {
                _state.value = ReconnectDriveState()
                manager.connector(account.provider).cancelReconnect(account)
            }
        }
    }

    fun cancel() {
        val state = _state.value
        if (state.committing) return
        state.account?.let { manager.connector(it.provider).cancelReconnect(it) }
        _state.value = ReconnectDriveState()
    }

    override fun onCleared() {
        _state.value.account?.let { manager.connector(it.provider).cancelReconnect(it) }
        super.onCleared()
    }
}
