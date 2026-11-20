package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a usage window stands against a steady pace through it. */
@Serializable
data class UsagePace(
    @SerialName("line_percent") val linePercent: Double,
    @SerialName("delta_points") val deltaPoints: Double,
    val level: UsagePaceLevel,
)

@Serializable
data class UsageWindow(
    val utilization: Double,
    @SerialName("resets_at") val resetsAt: String? = null,
    val pace: UsagePace? = null,
)

/** A weekly cap that applies to one model rather than the whole plan. */
@Serializable
data class ScopedUsageWindow(
    val model: String,
    val utilization: Double,
    @SerialName("resets_at") val resetsAt: String? = null,
    val pace: UsagePace? = null,
)

/** `GET /api/usage`; every field is absent while the server has no recent report. */
@Serializable
data class Usage(
    @SerialName("five_hour") val fiveHour: UsageWindow? = null,
    @SerialName("seven_day") val sevenDay: UsageWindow? = null,
    @SerialName("seven_day_models") val sevenDayModels: List<ScopedUsageWindow> = emptyList(),
    @SerialName("reported_at") val reportedAt: String? = null,
)
