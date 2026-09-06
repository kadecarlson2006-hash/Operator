package com.operator.core.decision

import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.FeedbackAdjustment
import com.operator.core.model.OperatorMode

/**
 * The local rules that decide whether Operator is even *allowed* to speak (Milestone 12).
 *
 * Two properties matter more than the logic itself:
 *
 *  - It can only ever say no. The model decides what would be worth saying; this decides whether
 *    saying anything is permitted at all. A decision engine that could be talked into speaking by
 *    a model would not be enforcing "silence is the default", it would be hoping for it.
 *  - It runs *before* the model, and costs nothing. Most ambient moments are refused here, so
 *    they never become a paid call. That is what keeps always-on listening affordable
 *    (docs/RISKS_AND_UNKNOWNS.md item 40).
 *
 * [review] then applies the same scepticism to what the model returned, because a model claiming
 * `shouldSpeak = true` is a suggestion, not an instruction (ADR-009).
 */
class ConversationPolicy(
    private val minCommentIntervalSeconds: Int,
    private val maxCommentsPer5Minutes: Int,
    /**
     * How long to wait between *asking* the model, as opposed to between speaking (Milestone 13).
     *
     * Every other limit here is keyed on when Operator last spoke, which bounds nothing while it
     * stays silent — and silence is the common case by design. Under Milestone 12 the user's
     * finger was the bound; once deciding is automatic, a busy room would buy a model call per
     * utterance forever. These two are the only limits that hold when Operator says nothing.
     */
    private val minDecisionIntervalSeconds: Int = 20,
    private val maxDecisionsPer5Minutes: Int = 12,
    /** A comment below this confidence is not worth interrupting for. */
    private val minConfidence: Float = 0.6f,
    /** Nor is one the model itself judges barely relevant. */
    private val minRelevance: Float = 0.5f,
    /** The brief: one sentence, occasionally two, never more than three. */
    private val maxSentences: Int = 3,
    /**
     * Raises the floors above in response to recent complaints (Milestone 14). It can only ever
     * add, never subtract, so approval cannot talk Operator into speaking more than its configured
     * floors already allow (ADR-045). Absent means no adjustment at all.
     */
    private val feedbackAdjustment: FeedbackAdjustment? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(minCommentIntervalSeconds >= 0) { "minCommentIntervalSeconds must be >= 0" }
        require(maxCommentsPer5Minutes >= 0) { "maxCommentsPer5Minutes must be >= 0" }
        require(minDecisionIntervalSeconds >= 0) { "minDecisionIntervalSeconds must be >= 0" }
        require(maxDecisionsPer5Minutes >= 0) { "maxDecisionsPer5Minutes must be >= 0" }
        require(maxSentences >= 1) { "maxSentences must be >= 1" }
    }

    /** When Operator actually spoke, newest last. Bounded: only the last five minutes matter. */
    private val spokenAt = ArrayDeque<Long>()

    /** When the model was actually consulted, spoken or not. This is what costs money. */
    private val decidedAt = ArrayDeque<Long>()

    /**
     * Recent feedback, newest last, bounded like everything else here.
     *
     * Held in memory rather than read from the store on every decision: the gate runs before the
     * model on every ambient moment, and a database round trip there would put the cheapest part
     * of the pipeline behind the slowest.
     */
    private val feedback = ArrayDeque<CommentFeedback>()

    /** Replaces what the policy knows about recent feedback. Newest last. */
    fun setFeedback(items: List<CommentFeedback>) {
        feedback.clear()
        items.takeLast(MAX_FEEDBACK_REMEMBERED).forEach(feedback::addLast)
    }

    fun recordFeedback(item: CommentFeedback) {
        feedback.addLast(item)
        while (feedback.size > MAX_FEEDBACK_REMEMBERED) feedback.removeFirst()
    }

    /** How much recent complaints have raised the floors, for diagnostics. 0 when nothing applies. */
    fun feedbackPenalty(): Float =
        feedbackAdjustment?.penalty(feedback.toList(), clock()) ?: 0f

    /**
     * Whether the model should be consulted at all. Returns a reason code when it should not —
     * the reason is a short diagnostic label, never model reasoning (ADR-009).
     */
    fun gate(request: DecisionRequest): String? {
        if (request.muted) return "MUTED"
        if (request.mode == OperatorMode.OFF) return "MODE_OFF"

        // An explicit request is the user asking. It still cannot override mute or OFF above,
        // but it is not subject to the rules that exist to stop Operator being a nuisance.
        val invited = request.trigger != DecisionTrigger.AMBIENT
        if (!invited) {
            if (!request.mode.allowsUnsolicitedComments) return "MODE_DOES_NOT_VOLUNTEER"
            if (request.recentTranscript.isBlank()) return "NOTHING_HEARD"

            val now = clock()
            prune(now)
            spokenAt.lastOrNull()?.let { last ->
                if (now - last < minCommentIntervalSeconds * 1_000L) return "RECENTLY_SPOKE"
            }
            if (spokenAt.size >= maxCommentsPer5Minutes) return "RATE_LIMITED"

            // The two limits that still hold when Operator says nothing (Milestone 13).
            decidedAt.lastOrNull()?.let { last ->
                if (now - last < minDecisionIntervalSeconds * 1_000L) return "DECIDED_RECENTLY"
            }
            if (decidedAt.size >= maxDecisionsPer5Minutes) return "DECISION_BUDGET"
        }
        return null
    }

    /**
     * Applies local suppression to what the model returned. A decision that survives this is one
     * Operator may actually speak.
     */
    fun review(decision: ResponseDecision, request: DecisionRequest): ResponseDecision {
        if (!decision.shouldSpeak) return decision
        // Non-blank when shouldSpeak is already guaranteed by ResponseDecision's own init block,
        // so a blank response cannot reach here: it fails at construction. Whatever builds a
        // decision from a model's JSON has to turn "speak, but no text" into silence there.
        val response = decision.response.orEmpty().trim()

        if (decision.category == ResponseCategory.NO_RESPONSE) return decision.suppressed("CATEGORY_NO_RESPONSE")

        // An invited answer is judged on quality, not on whether it was worth interrupting for.
        val invited = request.trigger != DecisionTrigger.AMBIENT
        if (!invited) {
            // Recent complaints raise the bar, and only ever raise it (ADR-045). Applied to the
            // uninvited path alone: the user who just pressed a button is asking, and answering
            // them worse because an unrelated ambient remark annoyed them earlier would be
            // punishing the wrong request.
            val penalty = feedbackPenalty()
            if (decision.confidence < minConfidence + penalty) return decision.suppressed("LOW_CONFIDENCE")
            if (decision.relevance < minRelevance + penalty) return decision.suppressed("LOW_RELEVANCE")
        }

        if (sentenceCount(response) > maxSentences) return decision.suppressed("TOO_LONG")
        if (request.recentComments.any { similar(it, response) }) return decision.suppressed("ALREADY_SAID")

        return decision
    }

    /**
     * Records that the model was consulted. Invited decisions are recorded too — they cost the
     * same — so an automatic decision immediately afterwards does not spend twice; they are simply
     * never *refused* by the budget, because the user asked.
     */
    fun recordDecision(atMillis: Long = clock()) {
        decidedAt.addLast(atMillis)
        prune(atMillis)
    }

    /** Records that Operator spoke, which is what the interval and rate limits are measured from. */
    fun recordSpoken(atMillis: Long = clock()) {
        spokenAt.addLast(atMillis)
        prune(atMillis)
    }

    /** Comments inside the trailing five-minute window. */
    fun recentCommentCount(): Int {
        prune(clock())
        return spokenAt.size
    }

    /** Model calls inside the trailing five-minute window, spoken or not. */
    fun recentDecisionCount(): Int {
        prune(clock())
        return decidedAt.size
    }

    fun reset() {
        spokenAt.clear()
        decidedAt.clear()
    }

    private fun prune(now: Long) {
        val cutoff = now - WINDOW_MILLIS
        while (spokenAt.isNotEmpty() && spokenAt.first() < cutoff) spokenAt.removeFirst()
        while (decidedAt.isNotEmpty() && decidedAt.first() < cutoff) decidedAt.removeFirst()
    }

    private companion object {
        const val WINDOW_MILLIS = 5 * 60 * 1_000L

        /**
         * Feedback kept in memory. Bounded because this is a long-lived object on a phone-facing
         * server, and because a complaint old enough to fall off the end has already decayed to
         * no weight anyway.
         */
        const val MAX_FEEDBACK_REMEMBERED = 50

        /** Terminal punctuation, ignoring a trailing one so "Yes." counts as one sentence. */
        fun sentenceCount(text: String): Int =
            text.trim().trimEnd('.', '!', '?').count { it == '.' || it == '!' || it == '?' } + 1

        /**
         * Near-repetition, not exact: a model asked not to repeat itself will happily reword.
         * Jaccard overlap of lowercased words is crude but catches the case that matters —
         * saying the same thing twice in five minutes.
         */
        fun similar(a: String, b: String): Boolean {
            val wa = a.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }.toSet()
            val wb = b.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }.toSet()
            if (wa.isEmpty() || wb.isEmpty()) return false
            val overlap = wa.intersect(wb).size.toFloat() / minOf(wa.size, wb.size)
            return overlap >= 0.7f
        }
    }
}
