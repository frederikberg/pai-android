package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.SessionModelsResponse
import com.frederikberg.pai.contract.SessionType
import com.frederikberg.pai.contract.SkillListing
import com.frederikberg.pai.contract.Usage
import com.frederikberg.pai.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

/** The session types a new-session picker offers this caller. */
suspend fun ApiClient.getSessionTypes(): List<SessionType> =
    send(ListSerializer(SessionType.serializer()), "/session-types")

/** Every model alias with the effort levels it supports, and what a fast session launches with. */
suspend fun ApiClient.getSessionModels(): SessionModelsResponse =
    send(
        SessionModelsResponse.serializer(),
        "/session-models",
    )

/** The skills of an existing session's environment. */
suspend fun ApiClient.getSkillsOfSession(sessionId: String): SkillListing =
    send(SkillListing.serializer(), "/skills", query = query("session_id" to sessionId))

/** The skills of the environment a new session would launch in. */
suspend fun ApiClient.getSkillsForNewSession(
    sessionType: String,
    workingDir: String? = null,
    agent: String? = null,
): SkillListing =
    send(
        SkillListing.serializer(),
        "/skills",
        query = query("session_type" to sessionType, "working_dir" to workingDir, "agent" to agent),
    )

/** Plan usage as the server last had it reported; every field is absent while unknown. */
suspend fun ApiClient.getUsage(): Usage = send(Usage.serializer(), "/usage")
