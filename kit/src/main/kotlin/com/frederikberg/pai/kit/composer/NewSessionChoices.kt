package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.contract.SessionEngine
import com.frederikberg.pai.contract.SessionModelInfo
import com.frederikberg.pai.contract.SessionModelsResponse
import com.frederikberg.pai.contract.SessionType
import com.frederikberg.pai.kit.drafts.DraftEntry

/** One entry of the model picker; a null [id] is "Default": the flag is left out and the plan picks. */
data class ModelOption(
    val id: String?,
    val label: String,
    val effortLevels: List<String> = emptyList(),
    /** Offered but not selectable, as a Hermes model is for a no-memory session. */
    val disabled: Boolean = false,
)

/** The pickers of the new-session screen, computed once from the server's catalogues and the draft's choices. */
data class NewSessionPickers(
    /** The types to choose between; empty or a single one means there is nothing to choose. */
    val types: List<SessionType>,
    val selectedType: String?,
    val models: List<ModelOption>,
    /** The model that runs with nothing chosen: a fast or API session's own default, else null. */
    val defaultModel: String?,
    val defaultThinking: String?,
    val selectedModel: String?,
    val selectedThinking: String?,
    /** The effort levels of [selectedModel], empty when none is known. */
    val effortLevels: List<String>,
    val noMemory: Boolean,
) {
    /** The model a launch runs on, for the button that opens the picker; null reads as "Default". */
    val resolvedModel: String? get() = selectedModel ?: defaultModel
    val resolvedThinking: String? get() = selectedThinking ?: defaultThinking.takeIf { resolvedModel == defaultModel }
}

object NewSessionRules {
    /** The type a fresh screen preselects: written into the draft rather than merely shown, so what is shown is what is sent. */
    const val PRESELECTED_TYPE = "home"

    private const val FAST = "fast"
    private val API_KINDS = setOf("ultrafast", "mailverdict")

    /** Built into the pod, never something to start as an ordinary session. */
    private val EXCLUDED = setOf("supervisor")

    /** Environments that sink under Custom on the web; a phone without a directory browser offers none of them. */
    private val SUNK = setOf("websearch", "web", "confined", "mailverdict")

    private val EFFORT_LABELS =
        mapOf(
            "none" to "No thinking",
            "low" to "Low",
            "medium" to "Medium",
            "high" to "High",
            "xhigh" to "Extra high",
            "max" to "Max",
        )

    fun effortLabel(level: String): String = EFFORT_LABELS[level] ?: level

    fun isApiKind(type: String?) = type in API_KINDS

    /** Only the home and custom-directory sessions may run on a Hermes model, and a session of an unnamed type. */
    fun allowsHermes(type: String?) = type == null || type == "home" || type == "custom"

    /** The types the picker lists: no internal kinds, and none that need a directory browser to start. */
    fun offeredTypes(types: List<SessionType>): List<SessionType> =
        types.filter { it.id !in EXCLUDED && it.id !in SUNK && it.id != "custom" }

    /** What the draft should hold for the type: its own choice if still offered, else the preselected one, else the first. */
    fun typeToSelect(
        offered: List<SessionType>,
        current: String?,
    ): String? =
        when {
            offered.isEmpty() -> null
            current != null && offered.any { it.id == current } -> current
            else -> (offered.firstOrNull { it.id == PRESELECTED_TYPE } ?: offered.first()).id
        }

    fun pickers(
        draft: DraftEntry,
        types: List<SessionType>,
        models: SessionModelsResponse?,
        noMemory: Boolean,
    ): NewSessionPickers {
        val offered = offeredTypes(types)
        val type = draft.sessionType
        val apiCatalog =
            when (type) {
                "ultrafast" -> models?.ultrafast
                "mailverdict" -> models?.mailverdict
                else -> null
            }
        val options: List<ModelOption> =
            if (apiCatalog != null) {
                apiCatalog.models.map { ModelOption(it.id, apiModelLabel(it.id), it.effortLevels) }
            } else {
                models?.models.orEmpty().filter { it.engine != SessionEngine.Hermes || allowsHermes(type) }.map {
                    it.toOption(noMemory)
                }
            }
        val defaultModel =
            when {
                apiCatalog != null -> apiCatalog.defaultModel
                type == FAST -> models?.fastDefaultModel
                else -> null
            }
        val defaultThinking =
            when {
                apiCatalog != null -> apiCatalog.defaultThinking
                type == FAST -> models?.fastDefaultThinking
                else -> null
            }
        // A choice the list no longer offers would be refused at the create; Default stands in for it.
        val selectedModel = draft.model?.takeIf { id -> options.any { it.id == id && !it.disabled } }
        val active = selectedModel ?: defaultModel
        val levels = options.firstOrNull { it.id == active }?.effortLevels.orEmpty()
        return NewSessionPickers(
            types = offered,
            selectedType = type,
            models = listOf(ModelOption(null, "Default")) + options,
            defaultModel = defaultModel,
            defaultThinking = defaultThinking,
            selectedModel = selectedModel,
            selectedThinking = draft.thinking?.takeIf { it in levels },
            effortLevels = levels,
            noMemory = noMemory,
        )
    }

    private fun SessionModelInfo.toOption(noMemory: Boolean) =
        ModelOption(id, label, effortLevels, disabled = noMemory && engine == SessionEngine.Hermes)

    /** An API session's model as a person reads it: the id without its vendor prefix. */
    fun apiModelLabel(id: String): String = id.substringAfterLast('/')
}
