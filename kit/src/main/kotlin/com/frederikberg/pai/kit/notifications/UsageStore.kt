package com.frederikberg.pai.kit.notifications

import com.frederikberg.pai.contract.Usage
import com.frederikberg.pai.contract.UsagePaceLevel
import com.frederikberg.pai.contract.UsageWindow
import com.frederikberg.pai.kit.api.getUsage
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** One window of the plan's usage as a screen draws it. */
data class UsageRow(
    /** `five_hour`, `seven_day`, or the model a scoped cap names. */
    val kind: Kind,
    val model: String?,
    /** 0 to 100, rounded: the bar's fill. */
    val percent: Int,
    val resetsAt: String?,
    val paceLevel: UsagePaceLevel?,
) {
    enum class Kind { FiveHour, SevenDay, SevenDayModel }
}

sealed interface UsageState {
    data object Loading : UsageState

    data class Failed(
        val message: String,
    ) : UsageState

    /** [rows] is empty while the server has no recent report. */
    data class Loaded(
        val rows: List<UsageRow>,
        val reportedAt: String?,
    ) : UsageState
}

/** The plan's usage windows, as the server last had them reported. */
class UsageStore(
    private val api: ApiClient,
) {
    private val _state = MutableStateFlow<UsageState>(UsageState.Loading)
    val state: StateFlow<UsageState> = _state.asStateFlow()

    suspend fun load() {
        try {
            _state.value = of(api.getUsage())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // A report already on screen stays; only a first load has nothing to fall back to.
            if (_state.value !is UsageState.Loaded) _state.value = UsageState.Failed(e.userMessage)
        }
    }

    companion object {
        fun of(usage: Usage): UsageState.Loaded {
            fun row(
                kind: UsageRow.Kind,
                window: UsageWindow?,
                model: String? = null,
            ) = window?.let {
                UsageRow(kind, model, it.utilization.roundToInt().coerceIn(0, 100), it.resetsAt, it.pace?.level)
            }
            val scoped =
                usage.sevenDayModels.map {
                    UsageRow(
                        UsageRow.Kind.SevenDayModel,
                        it.model,
                        it.utilization.roundToInt().coerceIn(0, 100),
                        it.resetsAt,
                        it.pace?.level,
                    )
                }
            return UsageState.Loaded(
                listOfNotNull(
                    row(UsageRow.Kind.FiveHour, usage.fiveHour),
                    row(UsageRow.Kind.SevenDay, usage.sevenDay),
                ) + scoped,
                usage.reportedAt,
            )
        }
    }
}
