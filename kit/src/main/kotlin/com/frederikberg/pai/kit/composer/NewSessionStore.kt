package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.contract.SessionModelsResponse
import com.frederikberg.pai.contract.SessionType
import com.frederikberg.pai.kit.api.getSessionModels
import com.frederikberg.pai.kit.api.getSessionTypes
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CatalogState {
    data object Loading : CatalogState

    data class Failed(
        val message: String,
    ) : CatalogState

    data class Loaded(
        val types: List<SessionType>,
        val models: SessionModelsResponse?,
    ) : CatalogState
}

/**
 * What the new-session screen offers: the session types this person may start and the models with their
 * effort levels, as the server lists them for them. The models list is a refinement, so a failed read of
 * it leaves the types usable with only "Default" to pick.
 */
class NewSessionStore(
    private val api: ApiClient,
) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** The no-memory switch belongs to the creation in hand, never to the synced draft, so another device opening New Session cannot turn it off unseen. */
    private val _noMemory = MutableStateFlow(false)
    val noMemory: StateFlow<Boolean> = _noMemory.asStateFlow()

    fun setNoMemory(on: Boolean) {
        _noMemory.value = on
    }

    suspend fun load() {
        val types =
            try {
                api.getSessionTypes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _state.value = CatalogState.Failed(e.userMessage)
                return
            }
        val models =
            try {
                api.getSessionModels()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                null
            }
        _state.value = CatalogState.Loaded(types, models)
    }
}
