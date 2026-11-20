package com.frederikberg.pai.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds every model to the vendored copy of the server's TypeScript API types: the serial names of a
 * model equal the fields of its interface, and the known values of every wire enum equal the
 * literals of its union. A model missing from [models] is unchecked, so a new one is registered here.
 */
class ContractTest {
    private val declarations = TypeScriptDeclarations(resource("types.ts"))

    /** Kotlin model by the interface it mirrors; `Interface.field` names an inline object type. */
    private val models: Map<String, KSerializer<*>> =
        mapOf(
            "ActivityCounts" to ActivityCounts.serializer(),
            "ApiSessionCatalog" to ApiSessionCatalog.serializer(),
            "ApiSessionModelInfo" to ApiSessionModelInfo.serializer(),
            "AnswerBlockerResponse" to AnswerBlockerResponse.serializer(),
            "ArcSseEvent" to ArcSseEvent.serializer(),
            "BashEditDiff" to BashEditDiff.serializer(),
            "BashEditDiffFile" to BashEditDiffFile.serializer(),
            "BashEditDiffHunk" to BashEditDiffHunk.serializer(),
            "Blocker" to Blocker.serializer(),
            "BlockerOption" to BlockerOption.serializer(),
            "CancelResponse" to CancelResponse.serializer(),
            "CloseResponse" to CloseResponse.serializer(),
            "DeleteResponse" to DeleteResponse.serializer(),
            "Draft" to Draft.serializer(),
            "DraftAttachment" to DraftAttachment.serializer(),
            "DraftDiscardResult" to DraftDiscardResult.serializer(),
            "DraftSseEvent" to DraftSseEvent.serializer(),
            "DraftWriteResult" to DraftWriteResult.serializer(),
            "HookSummary" to HookSummary.serializer(),
            "MeResponse" to MeResponse.serializer(),
            "Message" to Message.serializer(),
            "MessageFindResult" to MessageFindResult.serializer(),
            "MoveToBackgroundResponse" to MoveToBackgroundResponse.serializer(),
            "NamespaceSummary" to NamespaceSummary.serializer(),
            "NotificationSummary" to NotificationSummary.serializer(),
            "NotificationsResponse" to NotificationsResponse.serializer(),
            "PaiNotification" to PaiNotification.serializer(),
            "PaiNotification.alert" to NotificationAlert.serializer(),
            "PaiNotification.anchor" to NotificationAnchor.serializer(),
            "PendingSend" to PendingSend.serializer(),
            "PostMessageResponse" to PostMessageResponse.serializer(),
            "PushChannelInfo" to PushChannelInfo.serializer(),
            "PushChannelsResponse" to PushChannelsResponse.serializer(),
            "ResumeResponse" to ResumeResponse.serializer(),
            "ScopedUsageWindow" to ScopedUsageWindow.serializer(),
            "SecretPrompt" to SecretPrompt.serializer(),
            "Session" to Session.serializer(),
            "SessionModelInfo" to SessionModelInfo.serializer(),
            "SessionModelsResponse" to SessionModelsResponse.serializer(),
            // The interface extends Session; only what it adds is the ranking's.
            "SessionSearchResult" to SessionSearchRanking.serializer(),
            "SessionType" to SessionType.serializer(),
            "SignIn" to SignIn.serializer(),
            "SkillEntry" to SkillEntry.serializer(),
            "SkillListing" to SkillListing.serializer(),
            "SseBatchEvent" to SseBatchEvent.serializer(),
            "SseInitEvent" to SseInitEvent.serializer(),
            "SseNotificationEvent" to SseNotificationEvent.serializer(),
            "SseProcessingEvent" to SseProcessingEvent.serializer(),
            "SseStatusEvent" to SseStatusEvent.serializer(),
            "TokenUsage" to TokenUsage.serializer(),
            "ToolCall" to ToolCall.serializer(),
            "ToolResult" to ToolResult.serializer(),
            "Usage" to Usage.serializer(),
            "UsagePace" to UsagePace.serializer(),
            "UsageWindow" to UsageWindow.serializer(),
            "UserSettings" to UserSettings.serializer(),
            "WebPushSubscriptionCreated" to WebPushSubscriptionCreated.serializer(),
            "WebPushSubscriptionSummary" to WebPushSubscriptionSummary.serializer(),
            "WebPushTestResult" to WebPushTestResult.serializer(),
            "WithdrawPendingResponse" to WithdrawPendingResponse.serializer(),
            "WithdrawnSend" to WithdrawnSend.serializer(),
        )

    /** Interfaces whose model carries only what they add to the interface they extend. */
    private val addsToBase = setOf("SessionSearchResult")

    /** The known values of each wire enum by the union that holds them: an alias name, or `Interface.field`. */
    private val enums: Map<String, Set<String>> =
        mapOf(
            "SessionStatus" to SessionStatus.known,
            "SessionState" to SessionState.known,
            "TurnState" to TurnState.known,
            "DisplayState" to DisplayState.known,
            "BlockerKind" to BlockerKind.known,
            "SessionKind" to SessionKind.known,
            "SessionEngine" to SessionEngine.known,
            "UserRole" to UserRole.known,
            "NotificationKind" to NotificationKind.known,
            "UsagePaceLevel" to UsagePaceLevel.known,
            "VoiceSttMode" to VoiceSttMode.known,
            "VoiceTakeState" to VoiceTakeState.known,
            "Message.type" to MessageType.known,
            "PaiNotification.alert.transition" to AlertTransition.known,
            "DraftAttachment.state" to DraftAttachmentState.known,
            "SignIn.kind" to SignInKind.known,
            "CloseResponse.status" to CloseStatus.known,
            "ResumeResponse.status" to ResumeStatus.known,
            "DeleteResponse.status" to DeleteStatus.known,
            "AnswerBlockerResponse.status" to AnswerBlockerStatus.known,
            "MoveToBackgroundResponse.status" to BackgroundStatus.known,
        )

    @Test
    fun everyModelHasExactlyTheFieldsOfItsInterface() {
        models.forEach { (path, serializer) ->
            val expected =
                if (path in addsToBase) interfaceAt(path).fields.keys else fieldsOf(path)
            assertEquals("fields of $path", expected, serializer.descriptor.elementNames.toSet())
        }
    }

    @Test
    fun everyWireEnumKnowsExactlyTheLiteralsOfItsUnion() {
        enums.forEach { (path, known) ->
            val literals =
                if ('.' in path) {
                    val parts = path.split('.')
                    declarations.literalsOfField(parts.first(), parts.drop(1))
                } else {
                    declarations.unions[path]
                }
            assertEquals("values of $path", literals, known)
        }
    }

    @Test
    fun theVendoredFileHoldsExactlyTheDeclarationsTheContractNames() {
        val listed = resource("interfaces.txt").lines().filter { it.isNotBlank() }.toSet()
        val checked = models.keys.filter { '.' !in it }.toSet() + enums.keys.filter { '.' !in it }
        assertEquals("interfaces.txt against the registered models and enums", listed, checked)
        assertEquals("declarations in types.ts against interfaces.txt", listed, declarations.declarationNames)
    }

    @Test
    fun theDecoderToleratesWhatTheServerMayAdd() {
        val session =
            ContractJson.decodeFromString(
                Session.serializer(),
                """{"id":"s1","session_type":"home","status":"a-status-from-the-future","session_tokens":3,
                    "title":null,"a_field_nobody_knows":{"x":1},"display_state":"blinking"}""",
            )

        assertFalse(session.status.isKnown)
        assertEquals("a-status-from-the-future", session.status.raw)
        assertFalse(session.displayState!!.isKnown)
        assertEquals(3L, session.sessionTokens)
    }

    @Test
    fun aRoleThisAppDoesNotKnowStillDecodes() {
        val me =
            ContractJson.decodeFromString(
                MeResponse.serializer(),
                """{"identity":"x","role":"auditor","user_id":null,"display_name":null,
                    "allowed_session_ids":[],"namespaces":[]}""",
            )

        assertFalse(me.role.isKnown)
        assertEquals("auditor", me.role.raw)
    }

    @Test
    fun aTokenUsageWithFurtherCountersKeepsTheOnesItKnows() {
        val usage =
            ContractJson.decodeFromString(
                TokenUsage.serializer(),
                """{"input_tokens":5,"output_tokens":7,"service_tier":"standard","cache_read_input_tokens":2}""",
            )

        assertEquals(5L, usage.inputTokens)
        assertEquals(2L, usage.cacheReadInputTokens)
    }

    @Test
    fun theSnapshotRecordsTheCommitItWasTakenFrom() {
        assertTrue(resource("SYNCED_AT").trim().matches(Regex("[0-9a-f]{40}")))
    }

    private fun fieldsOf(path: String): Set<String> =
        if ('.' in path) interfaceAt(path).fields.keys else declarations.allFields(path)

    private fun interfaceAt(path: String): TsInterface {
        val parts = path.split('.')
        var current = declarations.interfaces.getValue(parts.first())
        parts.drop(1).forEach { current = current.nested.getValue(it) }
        return current
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/api-contract/$name")) { "missing test resource $name" }
            .bufferedReader()
            .use { it.readText() }
}
