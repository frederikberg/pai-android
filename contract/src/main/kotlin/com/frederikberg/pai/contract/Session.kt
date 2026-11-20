package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One answer option of a [Blocker]; [key] is what answering sends back. */
@Serializable
data class BlockerOption(
    val key: String,
    val label: String,
)

/** A prompt the session is parked on, answerable by option key. */
@Serializable
data class Blocker(
    val kind: BlockerKind,
    val question: String = "",
    val options: List<BlockerOption> = emptyList(),
)

/** What a session has running: live subagents and background shells or monitors. */
@Serializable
data class ActivityCounts(
    val agents: Int = 0,
    val tasks: Int = 0,
)

/** A session waiting for the person to unlock the secrets it was refused. */
@Serializable
data class SecretPrompt(
    val at: String,
    val names: List<String> = emptyList(),
    val vault4: List<String> = emptyList(),
    val reason: String? = null,
)

/** One session as `GET /api/sessions` and the single-session routes answer. */
@Serializable
data class Session(
    val id: String,
    @SerialName("session_type") val sessionType: String,
    val model: String? = null,
    @SerialName("live_model") val liveModel: String? = null,
    @SerialName("pending_model") val pendingModel: String? = null,
    @SerialName("ultrafast_model") val ultrafastModel: String? = null,
    val thinking: String? = null,
    @SerialName("no_memory") val noMemory: Boolean = false,
    @SerialName("closed_unexpectedly") val closedUnexpectedly: Boolean = false,
    val status: SessionStatus,
    val state: SessionState? = null,
    val blocker: Blocker? = null,
    @SerialName("turn_state") val turnState: TurnState? = null,
    @SerialName("display_state") val displayState: DisplayState? = null,
    @SerialName("secret_grantable") val secretGrantable: Boolean = false,
    @SerialName("secret_prompt") val secretPrompt: SecretPrompt? = null,
    val title: String? = null,
    @SerialName("title_locked") val titleLocked: Boolean = false,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    @SerialName("initial_message") val initialMessage: String? = null,
    @SerialName("session_tokens") val sessionTokens: Long = 0,
    @SerialName("activity_counts") val activityCounts: ActivityCounts? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    @SerialName("idle_timeout_minutes") val idleTimeoutMinutes: Int? = null,
    @SerialName("effective_idle_timeout_minutes") val effectiveIdleTimeoutMinutes: Int? = null,
    @SerialName("cse_id") val cseId: String? = null,
    @SerialName("transcript_path") val transcriptPath: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
    @SerialName("working_dir") val workingDir: String? = null,
    val agent: String? = null,
    val kind: SessionKind? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("subagent_name") val subagentName: String? = null,
    @SerialName("subagent_type") val subagentType: String? = null,
    @SerialName("subagent_model") val subagentModel: String? = null,
    @SerialName("subagent_description") val subagentDescription: String? = null,
    @SerialName("remote_control") val remoteControl: Boolean = false,
    val discovered: Boolean = false,
    @SerialName("source_missing") val sourceMissing: Boolean = false,
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("claude_version") val claudeVersion: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("phase_id") val phaseId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("phase_name") val phaseName: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("read_position_message_id") val readPositionMessageId: Long? = null,
    @SerialName("read_position_offset_px") val readPositionOffsetPx: Double? = null,
    @SerialName("read_position_at_bottom") val readPositionAtBottom: Boolean? = null,
    @SerialName("read_position_saved_at") val readPositionSavedAt: String? = null,
    @SerialName("transferred_to_session_id") val transferredToSessionId: String? = null,
    @SerialName("transferred_at") val transferredAt: String? = null,
    val namespace: String? = null,
)

/**
 * What `GET /api/sessions/search` adds to a [Session] row: the row itself decodes as a [Session]
 * from the same object, this carries the ranking. A semantic hit has a tier (1 likely, 2 less
 * likely); a fuzzy hit has neither.
 */
@Serializable
data class SessionSearchRanking(
    val score: Double? = null,
    val tier: Int? = null,
)

/** The kinds a session type picker offers (`GET /api/session-types`). */
@Serializable
data class SessionType(
    val id: String,
    val name: String,
    val icon: String,
    @SerialName("working_dir") val workingDir: String? = null,
)

/** One entry of the model picker. */
@Serializable
data class SessionModelInfo(
    val id: String,
    @SerialName("effort_levels") val effortLevels: List<String> = emptyList(),
    val label: String,
    val engine: SessionEngine,
)

/** A model an API session may pick. */
@Serializable
data class ApiSessionModelInfo(
    val id: String,
    val provider: String,
    @SerialName("effort_levels") val effortLevels: List<String> = emptyList(),
)

/** What an API session may pick for itself, and what it runs on when it picks nothing. */
@Serializable
data class ApiSessionCatalog(
    @SerialName("default_model") val defaultModel: String,
    @SerialName("default_thinking") val defaultThinking: String,
    val models: List<ApiSessionModelInfo> = emptyList(),
)

/** `GET /api/session-models`. */
@Serializable
data class SessionModelsResponse(
    val models: List<SessionModelInfo> = emptyList(),
    @SerialName("fast_default_model") val fastDefaultModel: String,
    @SerialName("fast_default_thinking") val fastDefaultThinking: String,
    val ultrafast: ApiSessionCatalog,
    val mailverdict: ApiSessionCatalog,
)

/** One skill Claude Code lists for an environment. */
@Serializable
data class SkillEntry(
    val name: String,
    val description: String = "",
)

/** `GET /api/skills`: what a composer's environment lists; empty, never absent. */
@Serializable
data class SkillListing(
    val skills: List<SkillEntry> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
)
