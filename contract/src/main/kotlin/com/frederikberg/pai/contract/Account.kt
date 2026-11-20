package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One namespace as its member sees it. */
@Serializable
data class NamespaceSummary(
    val slug: String,
    @SerialName("display_name") val displayName: String,
)

/**
 * `GET /api/me`. [role] tolerates a value this app does not know: it is read through
 * [UserRole.isKnown], and an unknown one is treated as the least privileged, a guest.
 */
@Serializable
data class MeResponse(
    val identity: String,
    val role: UserRole,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("allowed_session_ids") val allowedSessionIds: List<String> = emptyList(),
    val namespaces: List<NamespaceSummary> = emptyList(),
)

/** One person's own settings (`/api/settings/me`), the same on every device they sign in from. */
@Serializable
data class UserSettings(
    @SerialName("recording_mode") val recordingMode: VoiceSttMode,
    @SerialName("playback_voice_id") val playbackVoiceId: String? = null,
    @SerialName("playback_speed") val playbackSpeed: Double,
    @SerialName("email_notifications") val emailNotifications: Boolean,
    @SerialName("alarm_ring_interval_s") val alarmRingIntervalS: Int,
    @SerialName("alarm_ring_duration_s") val alarmRingDurationS: Int,
    @SerialName("location_sign_in_id") val locationSignInId: String? = null,
)

/** One signed-in device or machine identity of the caller; [current] marks the one making the request. */
@Serializable
data class SignIn(
    val id: String,
    val kind: SignInKind,
    val name: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val active: Boolean = false,
    val current: Boolean = false,
    @SerialName("agent_slug") val agentSlug: String? = null,
)
