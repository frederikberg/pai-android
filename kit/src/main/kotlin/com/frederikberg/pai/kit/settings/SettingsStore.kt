package com.frederikberg.pai.kit.settings

import com.frederikberg.pai.contract.SignIn
import com.frederikberg.pai.kit.api.getUserSettings
import com.frederikberg.pai.kit.api.listSignIns
import com.frederikberg.pai.kit.api.revokeSignIn
import com.frederikberg.pai.kit.api.setEmailNotifications
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A part of the settings screen that comes from the server. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Failed(
        val message: String,
    ) : Loadable<Nothing>

    data class Loaded<T>(
        val value: T,
    ) : Loadable<T>
}

/**
 * What the settings screen reads from and writes to the server: whether e-mail goes out beside a push, and
 * the list of devices signed in to this account. Each write applies to the screen only once the server
 * has taken it, so a switch never shows a state the server does not hold.
 */
class SettingsStore(
    private val api: ApiClient,
) {
    private val _emailNotifications = MutableStateFlow<Loadable<Boolean>>(Loadable.Loading)
    val emailNotifications: StateFlow<Loadable<Boolean>> = _emailNotifications.asStateFlow()

    private val _signIns = MutableStateFlow<Loadable<List<SignIn>>>(Loadable.Loading)
    val signIns: StateFlow<Loadable<List<SignIn>>> = _signIns.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** What the last write that failed said, in words; null when there is nothing to say. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    suspend fun load() {
        loadEmail()
        loadSignIns()
    }

    private suspend fun loadEmail() {
        try {
            _emailNotifications.value = Loadable.Loaded(api.getUserSettings().emailNotifications)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (_emailNotifications.value !is Loadable.Loaded) _emailNotifications.value = Loadable.Failed(e.userMessage)
        }
    }

    private suspend fun loadSignIns() {
        try {
            _signIns.value = Loadable.Loaded(api.listSignIns().filter { it.revokedAt == null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (_signIns.value !is Loadable.Loaded) _signIns.value = Loadable.Failed(e.userMessage)
        }
    }

    suspend fun setEmailNotifications(enabled: Boolean) {
        try {
            _emailNotifications.value = Loadable.Loaded(api.setEmailNotifications(enabled).emailNotifications)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = e.userMessage
        }
    }

    /** Signs another of the person's devices out; this phone ends its own sign-in through sign-out, never through here. */
    suspend fun revoke(id: String) {
        try {
            api.revokeSignIn(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = e.userMessage
            return
        }
        loadSignIns()
    }
}
