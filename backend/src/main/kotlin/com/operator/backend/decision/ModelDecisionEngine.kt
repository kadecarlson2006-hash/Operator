package com.operator.backend.decision

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.ContextAssembler
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.memory.MemoryRetrievalEngine
import com.operator.backend.memory.RetrievalTrigger
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.config.OperatorConfig
import com.operator.core.decision.ConversationPolicy
import com.operator.core.decision.DecisionRequest
import com.operator.core.decision.DecisionTrigger
import com.operator.core.decision.ResponseCategory
import com.operator.core.decision.ResponseDecision
import com.operator.core.decision.ResponseDecisionEngine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** What the decision model is asked to return. Everything is optional: a bad reply becomes silence. */
@Serializable
internal data class DecisionJson(
    @SerialName("shouldSpeak") val shouldSpeak: Boolean = false,
    val category: String? = null,
    val confidence: Float = 0f,
    val urgency: Float = 0f,
    val relevance: Float = 0f,
    val response: String? = null,
    @SerialName("reasonCode") val reasonCode: String? = null,
)

class DecisionModelNotConfiguredException :
    IllegalStateException("no decision model configured; set ${OperatorConfig.Keys.DECISION_MODEL_ID}")

/**
 * The decision stage (Milestone 12): local rules first, then a model, then the local rules again.
 *
 * The ordering is the point. [policy] can refuse before anything is spent, so an ambient moment in
 * a quiet mode, too soon after the last comment, or over the five-minute cap never becomes a paid
 * call. Only what survives that is put to the model, and whatever the model answers is reviewed by
 * the same rules before Operator is allowed to say it.
 *
 * A model that cannot be reached, cannot be parsed, or contradicts itself produces silence. That
 * is the correct failure mode here and needs no special handling: silence is the expected outcome
 * anyway (ADR-009).
 */
class ModelDecisionEngine(
    private val provider: AIProvider,
    private val policy: ConversationPolicy,
    private val prompts: PromptLibrary,
    private val config: OperatorConfig,
    private val retrieval: MemoryRetrievalEngine? = null,
    private val promptVersion: String = DEFAULT_PROMPT_VERSION,
    private val clock: () -> Long = System::currentTimeMillis,
) : ResponseDecisionEngine {

    private val log = LoggerFactory.getLogger("operator-decision")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Diagnostics for the last call, so the app can show what happened without guessing. */
    @Volatile
    var lastOutcome: DecisionOutcome = DecisionOutcome()
        private set

    override suspend fun decide(request: DecisionRequest): ResponseDecision {
        val startedAt = clock()

        policy.gate(request)?.let { reason ->
            lastOutcome = DecisionOutcome(gatedLocally = true, modelCalled = false, reasonCode = reason)
            return ResponseDecision.silence(reason)
        }

        val modelId = config.decisionModelId?.takeIf { it.isNotBlank() }
            ?: config.fastModelId?.takeIf { it.isNotBlank() }
            ?: throw DecisionModelNotConfiguredException()

        val retrieved = retrieval?.let {
            runCatching {
                it.retrieve(request.recentTranscript, mode = request.mode, trigger = RetrievalTrigger.AMBIENT)
            }.getOrElse { e ->
                log.warn("Memory retrieval failed during decision, continuing without it: {}", e.message)
                null
            }
        }

        val systemPrompt = listOfNotNull(
            prompts.load(promptVersion),
            ContextAssembler.build(
                mode = request.mode,
                wit = request.wit,
                retrieval = retrieved,
                trigger = RetrievalTrigger.AMBIENT,
                rollingTranscript = request.recentTranscript.ifBlank { null },
                recentOperatorComments = request.recentComments,
            ),
        ).joinToString("\n\n")

        val raw = try {
            provider.generate(
                AIRequest(
                    modelId = modelId,
                    systemPrompt = systemPrompt,
                    userContent = userContentFor(request),
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                ),
            ).text
        } catch (e: AIProviderException) {
            log.warn("Decision model unavailable, staying silent: {}", e.message)
            lastOutcome = DecisionOutcome(modelCalled = true, modelId = modelId, reasonCode = "MODEL_UNAVAILABLE", latencyMillis = clock() - startedAt)
            return ResponseDecision.silence("MODEL_UNAVAILABLE")
        }

        val parsed = parse(raw)
        if (parsed == null) {
            lastOutcome = DecisionOutcome(modelCalled = true, modelId = modelId, reasonCode = "UNREADABLE_DECISION", latencyMillis = clock() - startedAt)
            return ResponseDecision.silence("UNREADABLE_DECISION")
        }

        val reviewed = policy.review(parsed, request)
        if (reviewed.shouldSpeak) policy.recordSpoken(clock())

        lastOutcome = DecisionOutcome(
            modelCalled = true,
            modelId = modelId,
            reasonCode = reviewed.reasonCode,
            suppressedAfterModel = parsed.shouldSpeak && !reviewed.shouldSpeak,
            latencyMillis = clock() - startedAt,
        )
        return reviewed
    }

    private fun userContentFor(request: DecisionRequest): String = buildString {
        appendLine("Trigger: ${request.trigger.name}")
        when (request.trigger) {
            DecisionTrigger.COMMENT_NOW -> appendLine("The user pressed COMMENT NOW. They want to hear from you if you have anything worth saying.")
            DecisionTrigger.DIRECT_ADDRESS -> appendLine("Operator was addressed directly. Answer.")
            DecisionTrigger.AMBIENT -> appendLine("Nobody asked. Say nothing unless it clearly earns its place.")
        }
        appendLine()
        appendLine("Decide now. Reply with the JSON object only.")
    }

    /**
     * Turns the model's reply into a decision, or null when it cannot be read.
     *
     * A reply claiming `shouldSpeak` with no text is not an error to propagate: it is simply
     * silence, and has to be converted here because [ResponseDecision] refuses to hold that state.
     */
    private fun parse(raw: String): ResponseDecision? {
        val body = extractJsonObject(raw) ?: return null
        val decoded = runCatching { json.decodeFromString(DecisionJson.serializer(), body) }.getOrNull() ?: return null

        val text = decoded.response?.trim()
        val category = ResponseCategory.entries.firstOrNull { it.name.equals(decoded.category, ignoreCase = true) }
            ?: if (decoded.shouldSpeak) ResponseCategory.USEFUL_CONTEXT else ResponseCategory.NO_RESPONSE

        if (!decoded.shouldSpeak || text.isNullOrBlank()) {
            return ResponseDecision.silence(decoded.reasonCode?.takeIf { it.isNotBlank() } ?: "MODEL_CHOSE_SILENCE")
        }

        return ResponseDecision(
            shouldSpeak = true,
            category = category,
            confidence = decoded.confidence.coerceIn(0f, 1f),
            urgency = decoded.urgency.coerceIn(0f, 1f),
            relevance = decoded.relevance.coerceIn(0f, 1f),
            response = text,
            reasonCode = decoded.reasonCode?.takeIf { it.isNotBlank() } ?: category.name,
        )
    }

    /** Models wrap JSON in prose or fences more often than they should. Take the outermost object. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }

    companion object {
        const val DEFAULT_PROMPT_VERSION = "operator-decision-v1"
        private const val MAX_OUTPUT_TOKENS = 300
    }
}

/** What happened on the last decision, for the diagnostics panel. Never carries model reasoning. */
data class DecisionOutcome(
    val gatedLocally: Boolean = false,
    val modelCalled: Boolean = false,
    val modelId: String? = null,
    val reasonCode: String? = null,
    val suppressedAfterModel: Boolean = false,
    val latencyMillis: Long = 0,
)
