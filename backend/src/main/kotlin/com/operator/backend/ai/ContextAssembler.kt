package com.operator.backend.ai

import com.operator.backend.memory.RetrievalResult
import com.operator.backend.memory.RetrievalTrigger
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel

/**
 * Builds the runtime context block the brief specifies, and nothing more.
 *
 * The brief ends that section with "Do not inject unnecessary information", so every section is
 * omitted when it is empty: an absent transcript is absent, not an empty heading. Memories are
 * rendered as plain statements with their type and confidence, and they are labelled as memory so
 * the model cannot mistake them for something it just looked up (ADR-028).
 */
object ContextAssembler {

    fun build(
        mode: OperatorMode,
        wit: WitLevel,
        retrieval: RetrievalResult?,
        trigger: RetrievalTrigger,
        rollingTranscript: String? = null,
        recentOperatorComments: List<String> = emptyList(),
        availableTools: List<String> = emptyList(),
        /**
         * Things Operator said that the user did not want (Milestone 14). Only the negative ones:
         * a list of approved remarks would read as a licence to say more of them, and the floors
         * are the only thing that decides whether Operator speaks (ADR-045).
         */
        unwantedComments: List<String> = emptyList(),
    ): String = buildString {
        appendLine("CURRENT OPERATOR STATE")
        appendLine()
        appendLine("Mode: ${mode.name}")
        appendLine("Wit: ${wit.name}")
        appendLine("Trigger: ${trigger.name}")

        val memories = retrieval?.memories.orEmpty()
        if (memories.isNotEmpty()) {
            appendLine()
            appendLine("Relevant memories (things the user told you or you stored earlier, NOT live data):")
            memories.forEach { scored ->
                val m = scored.memory
                appendLine("- [${m.memoryType.name}, confidence ${"%.2f".format(m.confidence)}] ${m.content}")
            }
        }

        val people = retrieval?.people.orEmpty()
        if (people.isNotEmpty()) {
            appendLine()
            appendLine("Relevant people:")
            people.forEach { p ->
                val detail = listOfNotNull(p.relationship, p.role).joinToString(", ").ifBlank { null }
                appendLine("- ${p.name}${detail?.let { " ($it)" } ?: ""}")
            }
        }

        if (!rollingTranscript.isNullOrBlank()) {
            appendLine()
            appendLine("Rolling conversation:")
            appendLine(rollingTranscript.trim())
        }

        if (recentOperatorComments.isNotEmpty()) {
            appendLine()
            appendLine("Your recent comments (do not repeat yourself):")
            recentOperatorComments.forEach { appendLine("- $it") }
        }

        if (unwantedComments.isNotEmpty()) {
            appendLine()
            appendLine("The user marked these earlier remarks of yours as unwanted. Do not say things like them:")
            unwantedComments.forEach { appendLine("- $it") }
        }

        if (availableTools.isNotEmpty()) {
            appendLine()
            appendLine("Available tools: ${availableTools.joinToString(", ")}")
        }

        if (memories.isEmpty() && people.isEmpty()) {
            appendLine()
            appendLine("No stored memories are relevant. Do not invent any.")
        }
    }.trim()
}
