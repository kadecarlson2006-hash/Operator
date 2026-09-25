package com.operator.backend.decision

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.ContextAssembler
import com.operator.backend.ai.NeedsCurrentInformation
import com.operator.backend.ai.OpenRouterProvider
import com.operator.backend.ai.WebSearchOptions
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.ai.QueryNeedsRewrite
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
import com.operator.core.wake.WakeWord
import com.operator.core.tts.SpeakableText
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
    /**
     * Supplies the remarks the user has recently marked unwanted (Milestone 14). A function rather
     * than a list because the engine outlives any one decision, and a snapshot taken at
     * construction would go stale the moment somebody gave feedback.
     */
    private val unwantedComments: () -> List<String> = ::emptyList,
    /** Live search for invited questions only (ADR-052). Null disables it entirely. */
    private val webSearch: WebSearchOptions? = null,
    private val promptVersion: String = DEFAULT_PROMPT_VERSION,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where "today" is. The backend runs beside the user, so its zone is theirs. UTC made a
     * weather question asked at 11pm on a Thursday in Kansas come back with Friday's forecast.
     */
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
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

        // Recorded before the call, not after: a call that fails still cost a round trip, and a
        // provider that is timing out is exactly when an unbudgeted retry loop would hurt most.
        policy.recordDecision(startedAt)

        // Timed separately: retrieval embeds the transcript before searching, so it is a whole
        // provider round trip of its own. A single total hides which of the three calls on this
        // path - embed, model, search - is the slow one.
        // Live search when the user actually asked and the question concerns the present
        // (ADR-052). Spoken questions arrive here, not on the answer path, so without this
        // "Operator, what is the weather in Salina today" could never be answered with current
        // information however the search settings were configured. Ambient moments never search:
        // they run on every lull, and the question there is whether to speak, not what is true.
        //
        // Decided before retrieval, because it also decides whether retrieval is worth doing.
        //
        // Judged on the question itself rather than the whole window, for the same reason the
        // question is what goes in the user message: that is the text search builds its query
        // from. Judging the window let a "now" or a "still" from four lines back switch search on
        // for a question that did not need it - seconds of wait and four times the cost, spent
        // looking up something nobody asked.
        val invited = request.trigger != DecisionTrigger.AMBIENT
        val searched = invited && webSearch != null &&
            NeedsCurrentInformation.judge(question(request.recentTranscript).orEmpty())
        // Before search is switched on, so the rewrite itself never searches. Only when it can
        // help: a plain question is already a good query, and the rewrite is a whole round trip
        // spent before the search can start (see QueryNeedsRewrite).
        val rewriteStartedAt = clock()
        val searchQuery = if (searched && config.searchQueryRewrite) {
            question(request.recentTranscript)
                ?.takeIf { QueryNeedsRewrite.judge(it) }
                ?.let { rewriteForSearch(it, modelId) }
        } else {
            null
        }
        val rewriteMillis = clock() - rewriteStartedAt
        (provider as? OpenRouterProvider)?.webSearch = if (searched) webSearch else null

        val retrievalStartedAt = clock()
        // Skipped when live search is about to run. A question about today's weather or this
        // season's roster is about the world, not about anything the user stored, so embedding the
        // transcript to search memory is a whole provider round trip spent on nothing - and it is
        // spent on precisely the questions where latency is most visible, because search is
        // already adding seconds of its own.
        //
        // The trade is real: "what did I say about the Rams game" would want both. Search is the
        // better bet there, since the memory would have to have been stored deliberately.
        val retrieved = retrieval?.takeUnless { searched }?.let {
            runCatching {
                it.retrieve(request.recentTranscript, mode = request.mode, trigger = RetrievalTrigger.AMBIENT)
            }.getOrElse { e ->
                log.warn("Memory retrieval failed during decision, continuing without it: {}", e.message)
                null
            }
        }
        val retrievalMillis = clock() - retrievalStartedAt

        val systemPrompt = listOfNotNull(
            prompts.load(promptVersion),
            ContextAssembler.build(
                mode = request.mode,
                wit = request.wit,
                retrieval = retrieved,
                trigger = RetrievalTrigger.AMBIENT,
                rollingTranscript = request.recentTranscript.ifBlank { null },
                recentOperatorComments = request.recentComments,
                unwantedComments = unwantedComments(),
            ),
        ).joinToString("\n\n")
            // When searching, the framing lives here instead of the user message: search builds its
            // query from the last user turn, and must see only what was asked (see userContentFor).
            .let {
                if (!searched) {
                    it
                } else {
                    // When the query was rewritten the model must still answer what was said, so
                    // that goes here; the user message is then the query, for the search to use.
                    val said = searchQuery?.let { question(request.recentTranscript) }
                    it + "\n\n" + framingFor(request, question = said, inSystemPrompt = true)
                }
            }

        val modelStartedAt = clock()
        val raw = try {
            provider.generate(
                AIRequest(
                    modelId = modelId,
                    systemPrompt = systemPrompt,
                    userContent = userContentFor(request, searched, searchQuery),
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    // With search results in hand the answer is mostly reading them back, and on
                    // a reasoning model the thinking is the largest part of the wait that is ours
                    // to cut. Separately configurable so accuracy can be traded back.
                    reasoningEffort = if (searched) config.searchReasoningEffort else config.decisionReasoningEffort,
                ),
            ).text
        } catch (e: AIProviderException) {
            log.warn("Decision model unavailable, staying silent (searched={}, after {} ms): {}", searched, clock() - modelStartedAt, e.message)
            // Report what was attempted even though it failed. Leaving these at zero made a failed
            // search indistinguishable from a question the gate never searched for, which is the
            // one distinction a live test of search has to be able to see.
            lastOutcome = DecisionOutcome(
                modelCalled = true, modelId = modelId, reasonCode = "MODEL_UNAVAILABLE", latencyMillis = clock() - startedAt,
                searched = searched, retrievalMillis = retrievalMillis, modelMillis = clock() - modelStartedAt,
                rewriteMillis = rewriteMillis,
            )
            return ResponseDecision.silence("MODEL_UNAVAILABLE")
        }

        // Spoken, so never read out a citation or a URL - live search adds them to every answer.
        val parsed = (parse(raw) ?: proseAnswer(raw, request))
            ?.let { d -> d.copy(response = d.response?.let(SpeakableText::clean)) }
        if (parsed == null) {
            lastOutcome = DecisionOutcome(
                modelCalled = true, modelId = modelId, reasonCode = "UNREADABLE_DECISION", latencyMillis = clock() - startedAt,
                searched = searched, retrievalMillis = retrievalMillis, modelMillis = clock() - modelStartedAt,
                rewriteMillis = rewriteMillis,
            )
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
            searched = searched,
            retrievalMillis = retrievalMillis,
            modelMillis = clock() - modelStartedAt,
            rewriteMillis = rewriteMillis,
        )
        // Where the wait went, without anything that was said: the one line to read when asking
        // why an answer was slow.
        log.info(
            "Decided in {} ms: rewrite {} ms, memory {} ms, model {} ms (searched={}, rewritten={})",
            lastOutcome.latencyMillis, rewriteMillis, retrievalMillis, lastOutcome.modelMillis, searched, searchQuery != null,
        )
        return reviewed
    }

    /**
     * Turns what was said into something a search engine can use.
     *
     * People talk to Operator in shorthand. Live, "did you see the rams aaron donald isn't
     * traveling to AUS with the rest of the team" searched twice and found nothing - the second
     * time reading AUS as Austin, the airport - although ESPN had reported exactly that: Donald
     * stayed home from the Melbourne opener. The words that suit a friend do not suit a search box.
     *
     * One extra call to the decision model, with minimal reasoning and no search, before the
     * searched one. Any failure, or an answer that does not look like a query, falls back to the
     * words as spoken: a worse search is better than none. The query is not logged - it is
     * transcript, and transcript logging is minimal by default.
     */
    private suspend fun rewriteForSearch(asked: String, modelId: String): String? {
        (provider as? OpenRouterProvider)?.webSearch = null
        val startedAt = clock()
        // Capped: past this the rewrite has cost more than it can save, and the words as spoken
        // are searched instead of waiting for it.
        val raw = try {
            kotlinx.coroutines.withTimeoutOrNull(SEARCH_QUERY_TIMEOUT_MS) {
                provider.generate(
                    AIRequest(
                        modelId = modelId,
                        systemPrompt = SEARCH_QUERY_PROMPT.replace("{today}", today()),
                        userContent = asked,
                        maxOutputTokens = SEARCH_QUERY_MAX_TOKENS,
                        reasoningEffort = "minimal",
                    ),
                ).text
            }
        } catch (e: AIProviderException) {
            log.warn("Search query rewrite failed after {} ms, searching on the words as spoken: {}", clock() - startedAt, e.message)
            return null
        }
        if (raw == null) {
            log.info("Search query rewrite passed {} ms, searching on the words as spoken", SEARCH_QUERY_TIMEOUT_MS)
            return null
        }
        val query = raw.lineSequence().map { it.trim().trim('"', '\'', '`').trim() }.firstOrNull { it.isNotEmpty() }
            ?.takeIf { it.length in 3..SEARCH_QUERY_MAX_CHARS && !it.startsWith("{") }
        log.info("Search query rewritten in {} ms ({})", clock() - startedAt, if (query != null) "used" else "unusable, using the words as spoken")
        return query
    }

    /** Today's date where the user is, spelled out so "today" and "tonight" resolve correctly. */
    private fun today(): String = localNow("EEEE, MMMM d, yyyy")

    /** The date and the hour: at 11pm "today's weather" means what is left of it, which is tonight. */
    private fun now(): String = localNow("EEEE, MMMM d, yyyy, h:mm a")

    private fun localNow(pattern: String): String =
        java.time.Instant.ofEpochMilli(clock()).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US))

    /**
     * The user message. When searching it is the question and nothing else.
     *
     * OpenRouter's web search builds its query from the last user turn. It used to read
     * "Trigger: DIRECT_ADDRESS / Operator was addressed directly. Answer the question. / What was
     * said to you: ... / Decide now. Reply with the JSON object only." - and live, a forgiving
     * query survived that (the Salina forecast came back right) while a specific news story did
     * not: "did you see the rams aaron donald isn't traveling to AUS" searched and found nothing,
     * although ESPN had reported exactly that. The framing still reaches the model, from the end
     * of the system prompt; it just no longer reaches the search engine.
     */
    private fun userContentFor(request: DecisionRequest, searched: Boolean, searchQuery: String? = null): String {
        val asked = question(request.recentTranscript)
        if (searched && asked != null) return searchQuery ?: asked
        return framingFor(request, question = asked)
    }

    /** Who asked and what to do about it, and - when not searching - what was said. */
    private fun framingFor(request: DecisionRequest, question: String? = null, inSystemPrompt: Boolean = false): String = buildString {
        appendLine("Trigger: ${request.trigger.name}")
        when (request.trigger) {
            // "if you have anything worth saying" read as permission to decline, and the model
            // took it: a live session got silence on a plain question it could certainly answer.
            DecisionTrigger.COMMENT_NOW -> appendLine("The user pressed COMMENT NOW. They have asked to hear from you. Answer them unless you genuinely do not know or it would be harmful.")
            DecisionTrigger.DIRECT_ADDRESS -> appendLine("Operator was addressed directly. Answer the question.")
            DecisionTrigger.AMBIENT -> appendLine("Nobody asked. Say nothing unless it clearly earns its place.")
        }

        // The thing actually said, in the user message rather than only inside the system prompt.
        // Two reasons. The model reads the last user turn as the request, and web search builds
        // its query from it - a user message reading only "Trigger: DIRECT_ADDRESS ... Decide now"
        // gives the search nothing to look for, so search would be enabled and useless.
        question?.let { asked ->
            appendLine()
            appendLine(if (request.trigger == DecisionTrigger.AMBIENT) "Last thing said:" else "What was said to you:")
            appendLine(asked)
        }

        appendLine()
        if (inSystemPrompt) {
            // Search results carry their own dates and the model has no clock; without this it
            // took "today" from whichever forecast day the results happened to lead with. Knowing
            // the hour was not enough on its own: at 11pm Thursday it still called Friday "today".
            appendLine("It is ${now()} where the user is. Name days relative to that: late in the evening, today's weather is tonight's, and the next day is tomorrow.")
            appendLine(
                if (question != null) {
                    "The user message is a web search query Operator wrote from what was said; the user never saw it, so never correct it. Answer what was said, from the search results."
                } else {
                    "The user message is exactly what was said to you."
                },
            )
        }
        appendLine("Decide now. Reply with the JSON object only.")
    }

    /**
     * What was actually asked: the most recent line of the window, without its speaker prefix and
     * without the wake word. One definition, used both to decide whether to search and as the user
     * message - so the gate can never switch search on for text the search will not see.
     */
    private fun question(transcript: String): String? =
        latestLine(transcript)?.let { WakeWord.stripAddress(it) }?.takeIf { it.isNotBlank() }

    /** The most recent line of the rolling window, speaker prefix and all. */
    private fun latestLine(transcript: String): String? =
        transcript.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }

    /**
     * A plain-prose reply to a question the user asked, taken as the answer.
     *
     * With search on, the model sometimes forgets the JSON contract and just answers: live, two
     * Rams runs in eight replied "Yes - ESPN reported on September 8, 2026, that Aaron Donald would
     * not travel with the Rams to Melbourne ..." and were thrown away as unreadable, so a correct
     * answer to a direct question became silence. Invited only - nobody asked for an ambient
     * remark, and there silence is the right reading of a reply that broke the contract. A reply
     * containing a brace is a broken JSON object, never read aloud.
     */
    private fun proseAnswer(raw: String, request: DecisionRequest): ResponseDecision? {
        if (request.trigger == DecisionTrigger.AMBIENT) return null
        val text = raw.trim().takeIf { it.isNotEmpty() && '{' !in it } ?: return null
        return ResponseDecision(
            shouldSpeak = true,
            category = ResponseCategory.DIRECT_REQUEST,
            confidence = 0f,
            urgency = 0f,
            relevance = 0f,
            response = text,
            reasonCode = "PROSE_REPLY",
        )
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
            // Keep what the model reported. These are the only numbers that say how near it came
            // to speaking, which is what makes the floors tunable at all (risk 44, risk 50).
            return ResponseDecision.silence(
                reasonCode = decoded.reasonCode?.takeIf { it.isNotBlank() } ?: "MODEL_CHOSE_SILENCE",
                confidence = decoded.confidence.coerceIn(0f, 1f),
                relevance = decoded.relevance.coerceIn(0f, 1f),
                urgency = decoded.urgency.coerceIn(0f, 1f),
            )
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
        // Room for a reasoning model to think and then answer. 300 was sized for the answer
        // alone - a sentence or two of JSON - and a reasoning model spent all of it thinking on
        // the first live weather question, returning nothing. Output is billed as used, so the
        // headroom costs nothing unless it is needed.
        private const val MAX_OUTPUT_TOKENS = 1_500

        private const val SEARCH_QUERY_MAX_TOKENS = 300
        private const val SEARCH_QUERY_MAX_CHARS = 200
        private const val SEARCH_QUERY_TIMEOUT_MS = 2_000L
        private val SEARCH_QUERY_PROMPT = """
            Turn what someone said into one web search query that would find the answer, or the news
            they are referring to. Today is {today}.
            - Expand shorthand only where it has one meaning. An abbreviation that could be more than one
              thing stays exactly as said: the search engine sees today's news and you do not.
            - Drop conversational filler such as "did you see", "I heard", "apparently", "hey".
            - Keep every name. Add the place when it narrows the search, and for news or an event the
              season or year - which also says what an abbreviation most likely means right now.
            - For current conditions - weather, scores, prices - keep "today", "tonight" or "now" and
              add no date: a dated weather query finds history pages instead of the forecast.
            Reply with the query only, on one line, under fifteen words. No quotes, no explanation.
        """.trimIndent()
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
    /** Whether live search backed this answer, so a current fact is distinguishable from a
     *  remembered one (risk 64). */
    val searched: Boolean = false,
    /** Time spent embedding and searching memory before the model was asked anything. */
    val retrievalMillis: Long = 0,
    /** Time in the model call itself, including any web search it performed. */
    val modelMillis: Long = 0,
    /** Turning what was said into a search query; zero when it was not needed. */
    val rewriteMillis: Long = 0,
)
