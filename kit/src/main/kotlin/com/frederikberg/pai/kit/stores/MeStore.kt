package com.frederikberg.pai.kit.stores

import com.frederikberg.pai.contract.MeResponse
import com.frederikberg.pai.kit.api.getMe
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who is signed in and what they may see, from `GET /api/me`. */
class MeStore(
    private val api: ApiClient,
) {
    private val _me = MutableStateFlow<MeResponse?>(null)
    val me: StateFlow<MeResponse?> = _me.asStateFlow()

    private val _capabilities = MutableStateFlow(Capabilities.Unknown)
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Keeps the last answer when a reload fails, so a dropped connection does not take screens away mid-use. */
    suspend fun load() {
        try {
            val answer = api.getMe()
            _me.value = answer
            _capabilities.value = Capabilities.of(answer)
            _errorMessage.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _errorMessage.value = e.userMessage
        }
    }

    /** Forgets who was signed in, on sign-out. */
    fun clear() {
        _me.value = null
        _capabilities.value = Capabilities.Unknown
        _errorMessage.value = null
    }
}
