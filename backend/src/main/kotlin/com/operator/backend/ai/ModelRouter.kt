package com.operator.backend.ai

import com.operator.core.config.OperatorConfig

/** Which class of model a request should use. Model IDs are configuration, never constants. */
enum class ModelTier { FAST, DEEP, VISION }

data class RoutingDecision(val tier: ModelTier, val modelId: String, val fallbacks: List<String>, val reason: String)

class ModelNotConfiguredException(tier: ModelTier, key: String) :
    IllegalStateException("no model configured for $tier; set $key")

/**
 * Chooses a [ModelTier] and resolves it to a configured model ID (ADR-022).
 *
 * Milestone 6 wires FAST only in the UI, but the router is complete and testable now because the
 * decision, not the model name, is the part that must not be hard-coded. Heuristics are
 * deliberately simple and explainable: an explicit tier from the caller always wins, an image in
 * the request forces VISION, and a small set of analysis cues promotes a request to DEEP.
 * Anything else is FAST, which is the conversational default.
 */
class ModelRouter(private val config: OperatorConfig) {

    fun route(prompt: String, requested: ModelTier? = null, hasImage: Boolean = false): RoutingDecision {
        val tier = when {
            requested != null -> requested
            hasImage -> ModelTier.VISION
            looksDeep(prompt) -> ModelTier.DEEP
            else -> ModelTier.FAST
        }
        val reason = when {
            requested != null -> "requested explicitly"
            hasImage -> "request carries an image"
            tier == ModelTier.DEEP -> "prompt asks for analysis or a long answer"
            else -> "default conversational tier"
        }
        return RoutingDecision(tier, modelFor(tier), fallbacksFor(tier), reason)
    }

    /** Resolves a tier to its configured model ID, falling back to FAST when a tier is unset. */
    fun modelFor(tier: ModelTier): String {
        val configured = when (tier) {
            ModelTier.FAST -> config.fastModelId
            ModelTier.DEEP -> config.deepModelId ?: config.fastModelId
            ModelTier.VISION -> config.visionModelId ?: config.fastModelId
        }
        return configured?.takeIf { it.isNotBlank() } ?: throw ModelNotConfiguredException(tier, keyFor(tier))
    }

    /** DEEP and VISION fall back to FAST at the provider level when they are distinct models. */
    private fun fallbacksFor(tier: ModelTier): List<String> {
        val fast = config.fastModelId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val chosen = runCatching { modelFor(tier) }.getOrNull()
        return if (chosen != null && chosen != fast) listOf(fast) else emptyList()
    }

    private fun keyFor(tier: ModelTier) = when (tier) {
        ModelTier.FAST -> OperatorConfig.Keys.FAST_MODEL_ID
        ModelTier.DEEP -> OperatorConfig.Keys.DEEP_MODEL_ID
        ModelTier.VISION -> OperatorConfig.Keys.VISION_MODEL_ID
    }

    private fun looksDeep(prompt: String): Boolean {
        val text = prompt.lowercase()
        val cues = listOf("analyse", "analyze", "analysis", "compare", "trade-off", "tradeoff", "pros and cons",
            "should i", "evaluate", "plan for", "write a plan", "strategy", "forecast", "walk me through", "in detail")
        return cues.any { it in text } || prompt.length > DEEP_LENGTH_THRESHOLD
    }

    private companion object {
        /** Long prompts usually carry context that deserves the stronger model. */
        const val DEEP_LENGTH_THRESHOLD = 600
    }
}
