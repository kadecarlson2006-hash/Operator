package com.operator.core.ai

/**
 * Model gateway contract (Milestone 6). First implementation: OpenRouterProvider (backend side).
 * Kept deliberately small; fields will grow with streaming, tools, and usage accounting.
 */
interface AIProvider {
    suspend fun generate(request: AIRequest): AIResponse
}

data class AIRequest(
    val modelId: String,
    val systemPrompt: String,
    val userContent: String,
    val maxOutputTokens: Int? = null,
    /**
     * How hard a reasoning model may think before answering: "minimal", "low", "medium" or
     * "high". Null leaves it to the model. Reasoning tokens count against [maxOutputTokens], so a
     * model left to think freely can spend the whole budget and return nothing.
     */
    val reasoningEffort: String? = null,
)

data class AIResponse(
    val text: String,
    val modelId: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val latencyMillis: Long? = null,
)
