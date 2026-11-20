package com.frederikberg.pai.contract

import kotlinx.serialization.Serializable

/*
 * Every enumeration the server sends is a string-backed value, never a Kotlin `enum`: a case the
 * server adds later must not fail the decode of the whole payload. Each class names the values this
 * app knows in `known` (ContractTest holds that set equal to the TypeScript union), and a value
 * outside it is `isKnown == false` with the raw text preserved.
 */

@Serializable
@JvmInline
value class SessionStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Pending = SessionStatus("pending")
        val Active = SessionStatus("active")
        val Completed = SessionStatus("completed")
        val Error = SessionStatus("error")
        val Interrupted = SessionStatus("interrupted")
        val Deleted = SessionStatus("deleted")
        val known: Set<String> = setOf(Pending, Active, Completed, Error, Interrupted, Deleted).map { it.raw }.toSet()
    }
}

/** The readiness axis the session screen is built around, separate from [SessionStatus]. */
@Serializable
@JvmInline
value class SessionState(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Starting = SessionState("starting")
        val Ready = SessionState("ready")
        val Blocked = SessionState("blocked")
        val Attention = SessionState("attention")
        val Closed = SessionState("closed")
        val known: Set<String> = setOf(Starting, Ready, Blocked, Attention, Closed).map { it.raw }.toSet()
    }
}

/** What the conversation claimed about the turn it just finished. */
@Serializable
@JvmInline
value class TurnState(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Working = TurnState("working")
        val Done = TurnState("done")
        val Blocked = TurnState("blocked")
        val known: Set<String> = setOf(Working, Done, Blocked).map { it.raw }.toSet()
    }
}

/** What a session's dot shows; the server computes it once and nothing re-derives it. */
@Serializable
@JvmInline
value class DisplayState(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Starting = DisplayState("starting")
        val Working = DisplayState("working")
        val Done = DisplayState("done")
        val Blocked = DisplayState("blocked")
        val Error = DisplayState("error")
        val Closed = DisplayState("closed")
        val known: Set<String> = setOf(Starting, Working, Done, Blocked, Error, Closed).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class BlockerKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val TrustPrompt = BlockerKind("trust_prompt")
        val TrustPromptConfirmed = BlockerKind("trust_prompt_confirmed")
        val LoginRequired = BlockerKind("login_required")
        val PermissionPrompt = BlockerKind("permission_prompt")
        val ChoicePrompt = BlockerKind("choice_prompt")
        val NotRegistered = BlockerKind("not_registered")
        val Unknown = BlockerKind("unknown")
        val known: Set<String> =
            setOf(
                TrustPrompt,
                TrustPromptConfirmed,
                LoginRequired,
                PermissionPrompt,
                ChoicePrompt,
                NotRegistered,
                Unknown,
            ).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class SessionKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Conversation = SessionKind("conversation")
        val Subagent = SessionKind("subagent")
        val Supervisor = SessionKind("supervisor")
        val Computer = SessionKind("computer")
        val Ultrafast = SessionKind("ultrafast")
        val Mailverdict = SessionKind("mailverdict")
        val known: Set<String> =
            setOf(Conversation, Subagent, Supervisor, Computer, Ultrafast, Mailverdict).map { it.raw }.toSet()
    }
}

/** Who answers a conversation. */
@Serializable
@JvmInline
value class SessionEngine(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Claude = SessionEngine("claude")
        val Hermes = SessionEngine("hermes")
        val known: Set<String> = setOf(Claude, Hermes).map { it.raw }.toSet()
    }
}

/** The `type` of a transcript entry. */
@Serializable
@JvmInline
value class MessageType(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val User = MessageType("user")
        val Assistant = MessageType("assistant")
        val ToolResult = MessageType("tool_result")
        val System = MessageType("system")
        val known: Set<String> = setOf(User, Assistant, ToolResult, System).map { it.raw }.toSet()
    }
}

/** What the signed-in person is: the owner, or a guest who reaches only their own namespaces. */
@Serializable
@JvmInline
value class UserRole(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Owner = UserRole("owner")
        val Guest = UserRole("guest")
        val known: Set<String> = setOf(Owner, Guest).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class NotificationKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Agent = NotificationKind("agent")
        val Alert = NotificationKind("alert")
        val known: Set<String> = setOf(Agent, Alert).map { it.raw }.toSet()
    }
}

/** Whether an alert notification reports the alert being raised or cleared. */
@Serializable
@JvmInline
value class AlertTransition(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Raised = AlertTransition("raised")
        val Cleared = AlertTransition("cleared")
        val known: Set<String> = setOf(Raised, Cleared).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class UsagePaceLevel(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val OnPace = UsagePaceLevel("on_pace")
        val SlightlyOver = UsagePaceLevel("slightly_over")
        val Over = UsagePaceLevel("over")
        val FarOver = UsagePaceLevel("far_over")
        val known: Set<String> = setOf(OnPace, SlightlyOver, Over, FarOver).map { it.raw }.toSet()
    }
}

/** Where an uploaded draft attachment stands; only `stored` and `unclaimed` have bytes to show. */
@Serializable
@JvmInline
value class DraftAttachmentState(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Uploading = DraftAttachmentState("uploading")
        val Stored = DraftAttachmentState("stored")
        val Failed = DraftAttachmentState("failed")
        val Unclaimed = DraftAttachmentState("unclaimed")
        val known: Set<String> = setOf(Uploading, Stored, Failed, Unclaimed).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class VoiceSttMode(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Live = VoiceSttMode("live")
        val Batch = VoiceSttMode("batch")
        val known: Set<String> = setOf(Live, Batch).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class VoiceTakeState(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Open = VoiceTakeState("open")
        val Sealed = VoiceTakeState("sealed")
        val Resolving = VoiceTakeState("resolving")
        val Done = VoiceTakeState("done")
        val Failed = VoiceTakeState("failed")
        val known: Set<String> = setOf(Open, Sealed, Resolving, Done, Failed).map { it.raw }.toSet()
    }
}

/** One signed-in device or machine identity: an app, all of a person's browsers, or an access key. */
@Serializable
@JvmInline
value class SignInKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val App = SignInKind("app")
        val Browser = SignInKind("browser")
        val Machine = SignInKind("machine")
        val known: Set<String> = setOf(App, Browser, Machine).map { it.raw }.toSet()
    }
}

/** What closing a session came to. */
@Serializable
@JvmInline
value class CloseStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Closed = CloseStatus("closed")
        val AlreadyClosed = CloseStatus("already_closed")
        val CloseError = CloseStatus("close_error")
        val known: Set<String> = setOf(Closed, AlreadyClosed, CloseError).map { it.raw }.toSet()
    }
}

/** What resuming a session PAI was not driving came to. */
@Serializable
@JvmInline
value class ResumeStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Resumed = ResumeStatus("resumed")
        val AlreadyRunning = ResumeStatus("already_running")
        val Refused = ResumeStatus("refused")
        val known: Set<String> = setOf(Resumed, AlreadyRunning, Refused).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class DeleteStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Deleted = DeleteStatus("deleted")
        val AlreadyDeleted = DeleteStatus("already_deleted")
        val known: Set<String> = setOf(Deleted, AlreadyDeleted).map { it.raw }.toSet()
    }
}

@Serializable
@JvmInline
value class AnswerBlockerStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Ok = AnswerBlockerStatus("ok")
        val NoBlocker = AnswerBlockerStatus("no_blocker")
        val Error = AnswerBlockerStatus("error")
        val known: Set<String> = setOf(Ok, NoBlocker, Error).map { it.raw }.toSet()
    }
}

/** What moving the running command or subagent to the background came to. */
@Serializable
@JvmInline
value class BackgroundStatus(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val NothingRunning = BackgroundStatus("nothing_running")
        val Moved = BackgroundStatus("moved")
        val Refused = BackgroundStatus("refused")
        val Unavailable = BackgroundStatus("unavailable")
        val NotApplicable = BackgroundStatus("not_applicable")
        val known: Set<String> =
            setOf(
                NothingRunning,
                Moved,
                Refused,
                Unavailable,
                NotApplicable,
            ).map { it.raw }.toSet()
    }
}
