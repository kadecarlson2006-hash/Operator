package com.operator.core.config

import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel

/**
 * Centralized, immutable runtime configuration.
 *
 * Values originate from `local.properties` / environment (via the app's BuildConfig) or a
 * backend-provided config in later milestones. Secrets are deliberately NOT part of this
 * class: provider API keys belong to the backend (see docs/DECISIONS.md ADR-005).
 *
 * Model IDs, voice IDs, and provider names are nullable strings on purpose: nothing is
 * hard-coded, and "not configured" is a first-class state that the UI can display.
 */
data class OperatorConfig(
    val defaultMode: OperatorMode = OperatorMode.STANDBY,
    val defaultWit: WitLevel = WitLevel.NORMAL,

    /** Base URL of the Operator backend (Milestone 4+). */
    val backendUrl: String? = null,

    // Model routing (Milestone 6+). Never hard-code model names.
    val fastModelId: String? = null,
    val deepModelId: String? = null,
    val decisionModelId: String? = null,
    val visionModelId: String? = null,
    /** Embedding model for semantic memory retrieval (Milestone 7). */
    val embeddingModelId: String? = null,
    /**
     * Whether answers may search the web for current information (ADR-052). Off by default:
     * search is billed per call and adds latency, and most questions do not need it.
     */
    val webSearchEnabled: Boolean = false,
    /** How many results to pull in. More context, more cost. */
    val webSearchMaxResults: Int = 3,
    /**
     * Route only to endpoints that do not retain prompts (ADR-053). Off by default because it
     * restricts which providers can serve a model and may make one unavailable - but for a device
     * that transcribes rooms containing other people, it is the setting to want.
     */
    val zeroDataRetention: Boolean = false,
    /** "latency", "throughput" or "price". Null lets OpenRouter choose. */
    val providerSort: String? = null,
    /**
     * Overrides the OpenRouter endpoint. Empty uses the real one. Mirrors
     * `OPERATOR_TRANSCRIPTION_BASE_URL`, and exists for the same reasons: pointing at a
     * self-hosted proxy, and exercising the whole request path against a local server without a
     * live key or a live bill.
     */
    val openRouterBaseUrl: String? = null,

    // Hearing (Milestone 8). The base URL is configurable because the OpenAI-compatible
    // transcription shape is implemented by several vendors and by self-hosted Whisper servers.
    val transcriptionProvider: String? = null,
    val transcriptionModelId: String? = null,
    val transcriptionBaseUrl: String? = null,
    /** Optional language hint; null lets the model detect it. */
    val transcriptionLanguage: String? = null,

    // Voice (Milestone 9).
    val ttsProvider: String? = null,
    val elevenLabsVoiceId: String? = null,
    val elevenLabsModelId: String? = null,

    // Conversation / anti-annoyance (Milestone 11+).
    val rollingContextSeconds: Int = 60,
    val minCommentIntervalSeconds: Int = 45,
    val maxCommentsPer5Minutes: Int = 3,
    /**
     * How often Operator may *ask* whether to speak (Milestone 13). Distinct from the comment
     * limits above, which bound nothing while Operator stays silent — and staying silent is the
     * common case.
     */
    val minDecisionIntervalSeconds: Int = 20,
    val maxDecisionsPer5Minutes: Int = 12,

    // Milestone 1 audio test.
    val recordTestDurationMillis: Long = 4_000,
) {
    init {
        require(rollingContextSeconds > 0) { "rollingContextSeconds must be > 0" }
        require(minCommentIntervalSeconds >= 0) { "minCommentIntervalSeconds must be >= 0" }
        require(maxCommentsPer5Minutes >= 0) { "maxCommentsPer5Minutes must be >= 0" }
        require(minDecisionIntervalSeconds >= 0) { "minDecisionIntervalSeconds must be >= 0" }
        require(maxDecisionsPer5Minutes >= 0) { "maxDecisionsPer5Minutes must be >= 0" }
        require(recordTestDurationMillis in 1_000..30_000) { "recordTestDurationMillis must be 1s..30s" }
    }

    companion object {
        /**
         * Builds a config from a flat key/value map (e.g. BuildConfig fields, a .env file, or
         * system properties). Unknown keys are ignored; malformed numbers fall back to defaults.
         * Keys use the OPERATOR_* naming from the project spec.
         */
        fun fromMap(values: Map<String, String?>): OperatorConfig {
            val defaults = OperatorConfig()
            fun str(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(key: String, default: Int): Int = str(key)?.toIntOrNull() ?: default
            fun long(key: String, default: Long): Long = str(key)?.toLongOrNull() ?: default
            return OperatorConfig(
                defaultMode = str(Keys.DEFAULT_MODE)?.let { v -> OperatorMode.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } } ?: defaults.defaultMode,
                defaultWit = str(Keys.DEFAULT_WIT)?.let { v -> WitLevel.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } } ?: defaults.defaultWit,
                backendUrl = str(Keys.BACKEND_URL),
                fastModelId = str(Keys.FAST_MODEL_ID),
                deepModelId = str(Keys.DEEP_MODEL_ID),
                decisionModelId = str(Keys.DECISION_MODEL_ID),
                visionModelId = str(Keys.VISION_MODEL_ID),
                embeddingModelId = str(Keys.EMBEDDING_MODEL_ID),
                webSearchEnabled = str(Keys.WEB_SEARCH_ENABLED)?.toBoolean() ?: defaults.webSearchEnabled,
                webSearchMaxResults = str(Keys.WEB_SEARCH_MAX_RESULTS)?.toIntOrNull()?.coerceIn(1, 10)
                    ?: defaults.webSearchMaxResults,
                zeroDataRetention = str(Keys.ZERO_DATA_RETENTION)?.toBoolean() ?: defaults.zeroDataRetention,
                providerSort = str(Keys.PROVIDER_SORT)?.lowercase()
                    ?.takeIf { it in setOf("latency", "throughput", "price") },
                openRouterBaseUrl = str(Keys.OPENROUTER_BASE_URL),
                transcriptionProvider = str(Keys.TRANSCRIPTION_PROVIDER),
                transcriptionModelId = str(Keys.TRANSCRIPTION_MODEL_ID),
                transcriptionBaseUrl = str(Keys.TRANSCRIPTION_BASE_URL),
                transcriptionLanguage = str(Keys.TRANSCRIPTION_LANGUAGE),
                ttsProvider = str(Keys.TTS_PROVIDER),
                elevenLabsVoiceId = str(Keys.ELEVENLABS_VOICE_ID),
                elevenLabsModelId = str(Keys.ELEVENLABS_MODEL_ID),
                rollingContextSeconds = int(Keys.ROLLING_CONTEXT_SECONDS, defaults.rollingContextSeconds),
                minCommentIntervalSeconds = int(Keys.MIN_COMMENT_INTERVAL_SECONDS, defaults.minCommentIntervalSeconds),
                maxCommentsPer5Minutes = int(Keys.MAX_COMMENTS_PER_5_MINUTES, defaults.maxCommentsPer5Minutes),
                minDecisionIntervalSeconds = int(Keys.MIN_DECISION_INTERVAL_SECONDS, defaults.minDecisionIntervalSeconds),
                maxDecisionsPer5Minutes = int(Keys.MAX_DECISIONS_PER_5_MINUTES, defaults.maxDecisionsPer5Minutes),
                recordTestDurationMillis = long(Keys.RECORD_TEST_DURATION_MILLIS, defaults.recordTestDurationMillis),
            )
        }
    }

    /** Canonical configuration key names. Shared by local.properties, .env, and BuildConfig. */
    object Keys {
        const val DEFAULT_MODE = "OPERATOR_DEFAULT_MODE"
        const val DEFAULT_WIT = "OPERATOR_DEFAULT_WIT"
        const val BACKEND_URL = "OPERATOR_BACKEND_URL"
        const val FAST_MODEL_ID = "OPERATOR_FAST_MODEL_ID"
        const val DEEP_MODEL_ID = "OPERATOR_DEEP_MODEL_ID"
        const val DECISION_MODEL_ID = "OPERATOR_DECISION_MODEL_ID"
        const val VISION_MODEL_ID = "OPERATOR_VISION_MODEL_ID"
        const val EMBEDDING_MODEL_ID = "OPERATOR_EMBEDDING_MODEL_ID"
        const val WEB_SEARCH_ENABLED = "OPERATOR_WEB_SEARCH"
        const val WEB_SEARCH_MAX_RESULTS = "OPERATOR_WEB_SEARCH_MAX_RESULTS"
        const val ZERO_DATA_RETENTION = "OPERATOR_ZERO_DATA_RETENTION"
        const val PROVIDER_SORT = "OPERATOR_PROVIDER_SORT"
        const val OPENROUTER_BASE_URL = "OPENROUTER_BASE_URL"
        const val TRANSCRIPTION_PROVIDER = "OPERATOR_TRANSCRIPTION_PROVIDER"
        const val TRANSCRIPTION_MODEL_ID = "OPERATOR_TRANSCRIPTION_MODEL_ID"
        const val TRANSCRIPTION_BASE_URL = "OPERATOR_TRANSCRIPTION_BASE_URL"
        const val TRANSCRIPTION_LANGUAGE = "OPERATOR_TRANSCRIPTION_LANGUAGE"
        const val TTS_PROVIDER = "OPERATOR_TTS_PROVIDER"
        const val ELEVENLABS_VOICE_ID = "OPERATOR_ELEVENLABS_VOICE_ID"
        const val ELEVENLABS_MODEL_ID = "OPERATOR_ELEVENLABS_MODEL_ID"
        const val ROLLING_CONTEXT_SECONDS = "ROLLING_CONTEXT_SECONDS"
        const val MIN_COMMENT_INTERVAL_SECONDS = "MIN_COMMENT_INTERVAL_SECONDS"
        const val MAX_COMMENTS_PER_5_MINUTES = "MAX_COMMENTS_PER_5_MINUTES"
        const val MIN_DECISION_INTERVAL_SECONDS = "MIN_DECISION_INTERVAL_SECONDS"
        const val MAX_DECISIONS_PER_5_MINUTES = "MAX_DECISIONS_PER_5_MINUTES"
        const val RECORD_TEST_DURATION_MILLIS = "OPERATOR_RECORD_TEST_DURATION_MILLIS"
    }
}
