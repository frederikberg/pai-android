package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.kit.drafts.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * One JSON file per entry under [directory], written to a temporary name and moved into place so a
 * process death leaves either the old entry or the new one. A file that cannot be read is skipped.
 */
class FileOutboxStorage(
    private val directory: File,
) : OutboxStorage {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadAll(): List<OutboxEntry> =
        withContext(Dispatchers.IO) {
            directory
                .listFiles { file -> file.extension == EXTENSION }
                .orEmpty()
                .mapNotNull { file -> runCatching { json.decodeFromString(Stored.serializer(), file.readText()).toEntry() }.getOrNull() }
                .sortedBy { it.createdAtMs }
        }

    override suspend fun put(entry: OutboxEntry) =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val target = fileOf(entry.id)
            val temporary = File(directory, "${entry.id}.tmp")
            temporary.writeText(json.encodeToString(Stored.serializer(), Stored.of(entry)))
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "the outbox entry could not be saved" }
            }
        }

    override suspend fun remove(id: String) {
        withContext(Dispatchers.IO) { fileOf(id).delete() }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { directory.listFiles().orEmpty().forEach { it.delete() } }
    }

    private fun fileOf(id: String) = File(directory, "$id.$EXTENSION")

    @Serializable
    private class StoredFile(
        val filename: String,
        val contentType: String,
        val base64: String,
    )

    @Serializable
    private class Stored(
        val id: String,
        val sessionId: String? = null,
        val sessionType: String? = null,
        val workingDir: String? = null,
        val model: String? = null,
        val thinking: String? = null,
        val noMemory: Boolean = false,
        val text: String,
        val draftAttachmentIds: List<String> = emptyList(),
        val inline: List<StoredFile> = emptyList(),
        val supersededUploadIds: List<String> = emptyList(),
        val state: String,
        val attempts: Int = 0,
        val nextAttemptAtMs: Long = 0,
        val lastError: String? = null,
        val createdAtMs: Long,
        val resultSessionId: String? = null,
        val resultMessageId: Long? = null,
        val resultAtMs: Long = 0,
    ) {
        fun toEntry(): OutboxEntry {
            val target =
                if (sessionId != null) {
                    OutboxTarget.Session(sessionId)
                } else {
                    OutboxTarget.NewSession(sessionType, workingDir, model, thinking, noMemory)
                }
            return OutboxEntry(
                id = id,
                target = target,
                text = text,
                draftAttachmentIds = draftAttachmentIds,
                inline = inline.map { PickedFile(it.filename, it.contentType, Base64.getDecoder().decode(it.base64)) },
                supersededUploadIds = supersededUploadIds,
                state = OutboxState.entries.first { it.name == state },
                attempts = attempts,
                nextAttemptAtMs = nextAttemptAtMs,
                lastError = lastError,
                createdAtMs = createdAtMs,
                result =
                    if (resultSessionId != null && resultMessageId != null) {
                        OutboxResult(resultSessionId, resultMessageId, resultAtMs)
                    } else {
                        null
                    },
            )
        }

        companion object {
            fun of(entry: OutboxEntry): Stored {
                val session = entry.target as? OutboxTarget.Session
                val created = entry.target as? OutboxTarget.NewSession
                return Stored(
                    id = entry.id,
                    sessionId = session?.sessionId,
                    sessionType = created?.sessionType,
                    workingDir = created?.workingDir,
                    model = created?.model,
                    thinking = created?.thinking,
                    noMemory = created?.noMemory ?: false,
                    text = entry.text,
                    draftAttachmentIds = entry.draftAttachmentIds,
                    inline =
                        entry.inline.map {
                            StoredFile(it.filename, it.contentType, Base64.getEncoder().encodeToString(it.bytes))
                        },
                    supersededUploadIds = entry.supersededUploadIds,
                    state = entry.state.name,
                    attempts = entry.attempts,
                    nextAttemptAtMs = entry.nextAttemptAtMs,
                    lastError = entry.lastError,
                    createdAtMs = entry.createdAtMs,
                    resultSessionId = entry.result?.sessionId,
                    resultMessageId = entry.result?.messageId,
                    resultAtMs = entry.result?.atMs ?: 0,
                )
            }
        }
    }

    private companion object {
        const val EXTENSION = "json"
    }
}
